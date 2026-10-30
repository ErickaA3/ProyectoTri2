package com.project.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.DuelDAOImpl;
import com.project.dao.interfaces.IDuelDAO;
import com.project.util.GamificationService;
import com.project.util.JwtUtil;

import io.jsonwebtoken.JwtException;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@ServerEndpoint("/ws/duelo/{duelId}")
public class DuelWebSocket implements DuelRoom.RoomCallback {

    private static final Logger LOG = Logger.getLogger(DuelWebSocket.class.getName());
    private static final Gson GSON = new Gson();

    private static final ConcurrentHashMap<Session, Object> SESSION_LOCKS = new ConcurrentHashMap<>();

    private final IDuelDAO duelDAO = new DuelDAOImpl();

    private String duelId;
    private String userId;
    private Session session;

    @OnOpen
    public void onOpen(Session session, @PathParam("duelId") String duelId) {
        this.session = session;
        this.duelId  = duelId;
        LOG.info("[WS] Conexión abierta — duelId=" + duelId);
    }

    @OnMessage
    public void onMessage(String raw, Session session) {
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = msg.get("type").getAsString();
            switch (type) {
                // RECONNECT es un JOIN más: handleJoin ya distingue sala nueva de partida en curso.
                case "JOIN", "RECONNECT" -> handleJoin(msg);
                case "ANSWER"            -> handleAnswer(msg);
                default                  -> sendError("Tipo de mensaje desconocido: " + type);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[WS] Error procesando mensaje: " + raw, e);
            sendError("Error procesando mensaje: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        LOG.info("[WS] Cierre — userId=" + userId);
        SESSION_LOCKS.remove(session);
        if (userId == null || duelId == null) return;
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        // Se pasa la sesión que se cerró: si el jugador ya entró con otra, no se lo saca.
        if (room != null) room.playerDisconnected(userId, session);
    }

    @OnError
    public void onError(Session session, Throwable t) {
        LOG.log(Level.SEVERE, "[WS] Error en sesión userId=" + userId, t);
    }

    // ── HANDLERS ────────────────────────────────────────────────

    /**
     * Identidad desde el JWT que manda el cliente en JOIN/RECONNECT (el navegador no
     * puede mandar el header Authorization en un WebSocket). El "userId" del mensaje
     * ya no se usa: antes cualquiera podía jugar como otro mandando su id.
     */
    private String authenticate(JsonObject msg) {
        if (!msg.has("token") || msg.get("token").isJsonNull()) return null;
        try {
            return JwtUtil.validarToken(msg.get("token").getAsString()).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Fija el usuario de esta conexión. Una conexión no puede cambiar de usuario. */
    private boolean bindUser(JsonObject msg) throws IOException {
        String tokenUser = authenticate(msg);
        if (tokenUser == null || (userId != null && !userId.equals(tokenUser))) {
            sendError("Sesión no válida. Volvé a iniciar sesión.");
            session.close();
            return false;
        }
        this.userId = tokenUser;
        return true;
    }

    private void handleJoin(JsonObject msg) throws Exception {
        if (!bindUser(msg)) return;

        JsonObject duelInfo = duelDAO.getDuel(duelId, userId);
        if (duelInfo == null) { sendError("No pertenecés a este duelo."); session.close(); return; }

        String status = duelInfo.get("status").getAsString();
        if ("finished".equals(status) || "declined".equals(status)) {
            sendError("Este duelo ya terminó."); session.close(); return;
        }

        DuelRoom room = DuelRoomManager.INSTANCE.getOrCreate(duelId);

        // JOIN repetido a una sala ya en curso (recargó la página o se le cayó la red)
        // → reconexión. NUNCA reinicializar preguntas ni timePerQuestion a mitad de partida.
        if (room.getState() != DuelRoom.State.WAITING) {
            resumePlayer(room);
            return;
        }

        JsonObject questionsData;
        try {
            questionsData = duelDAO.getDuelQuestions(duelId, userId);
        } catch (Exception e) {
            sendError(e.getMessage()); session.close(); return;
        }

        boolean bothReady = room.join(userId, session, this);

        if (!bothReady) { send(buildWaiting("Esperando a tu oponente...")); return; }

        JsonArray questions = questionsData.getAsJsonArray("questions");
        // La IA a veces genera menos preguntas de las pedidas: se juega con las que hay.
        int totalQ   = Math.min(questionsData.get("questionCount").getAsInt(), questions.size());
        int timePerQ = questionsData.has("timePerQuestion")
                       ? questionsData.get("timePerQuestion").getAsInt() : 30;
        if (totalQ == 0) { broadcastToRoom(room, buildError("Este duelo no tiene preguntas.")); return; }

        room.setQuestions(questions);
        if (!room.tryStartGame(totalQ, timePerQ)) {
            // El otro JOIN la arrancó primero.
            resumePlayer(room);
            return;
        }

        broadcastToRoom(room, buildReady(duelInfo, totalQ, timePerQ));
        askQuestion(room, room.getChallengerId(), 0);
        askQuestion(room, room.getOpponentId(), 0);
    }

    /**
     * Vuelve a meter al jugador en un duelo en curso. Si la pregunta actual todavía
     * corre, se la reenvía con el tiempo que le queda (recargar no da tiempo extra).
     * Si el tiempo se le acabó mientras estaba desconectado, recibe la siguiente.
     */
    private void resumePlayer(DuelRoom room) {
        if (room.isClosed()) { sendError("Este duelo ya terminó."); return; }
        if (!room.isPlayer(userId)) { sendError("No pertenecés a este duelo."); return; }

        room.join(userId, session, this);

        JsonObject stateSync = new JsonObject();
        stateSync.addProperty("type",            "STATE_SYNC");
        stateSync.addProperty("currentQuestion", room.getCurrentQuestion(userId));
        stateSync.addProperty("totalQuestions",  room.getTotalQuestions());
        stateSync.addProperty("myScore",         room.getScore(userId));
        send(stateSync);

        String rival = room.getRival(userId);
        if (rival != null) {
            JsonObject back = new JsonObject();
            back.addProperty("type", "OPPONENT_BACK");
            sendTo(room.getSession(rival), back);
        }

        if (room.isFinished(userId)) {
            send(buildWaiting("¡Terminaste! Esperando a tu oponente..."));
            return;
        }

        int idx       = room.getCurrentQuestion(userId);
        int remaining = room.remainingSecs(userId);
        if (remaining > 0) {
            sendQuestionTo(session, room, room.getQuestions(), idx, remaining);
        } else {
            askQuestion(room, userId, idx);
        }
    }

    private void handleAnswer(JsonObject msg) throws Exception {
        if (userId == null) { sendError("Enviá JOIN primero."); return; }

        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null || room.getState() != DuelRoom.State.PLAYING) {
            sendError("El duelo no está en curso."); return;
        }

        int questionIndex = msg.get("questionIndex").getAsInt();
        int answerIdx     = msg.has("answerIdx") ? msg.get("answerIdx").getAsInt() : -1;

        // Atómico: si ya se procesó esta pregunta (por ejemplo, el timeout
        // del servidor ganó la carrera), esto retorna false y no hacemos nada más.
        // El tiempo lo mide el servidor; el "timeSecs" que manda el cliente se ignora.
        boolean applied = room.tryRegisterAnswer(userId, questionIndex, answerIdx, false);
        if (!applied) return;

        JsonObject feedback = new JsonObject();
        feedback.addProperty("type",          "ANSWER_RESULT");
        feedback.addProperty("questionIndex", questionIndex);
        feedback.addProperty("correct",       room.isCorrectAnswer(questionIndex, answerIdx));
        feedback.addProperty("correctIdx",    room.getCorrectIdx(questionIndex));
        feedback.addProperty("yourAnswerIdx", answerIdx);
        send(feedback);

        String rival = room.getRival(userId);
        if (rival != null) {
            JsonObject scoreUpdate = new JsonObject();
            scoreUpdate.addProperty("type",   "SCORE_UPDATE");
            scoreUpdate.addProperty("userId", userId);
            scoreUpdate.addProperty("score",  room.getScore(userId));
            sendTo(room.getSession(rival), scoreUpdate);
        }

        advance(room, userId);
    }

    // ── AVANCE Y CIERRE ──────────────────────────────────────────

    /** Manda la pregunta y arranca su tiempo. Si el jugador no está conectado, espera a que vuelva. */
    private void askQuestion(DuelRoom room, String uid, int index) {
        Session target = room.getSession(uid);
        if (target == null || !target.isOpen()) return;
        int tpq = room.getTimePerQuestion();
        sendQuestionTo(target, room, room.getQuestions(), index, tpq);
        room.startQuestion(uid, index, tpq);
    }

    /** Después de cada respuesta o tiempo agotado: siguiente pregunta, o guardar y esperar al rival. */
    private void advance(DuelRoom room, String uid) {
        if (!room.isFinished(uid)) {
            askQuestion(room, uid, room.getCurrentQuestion(uid));
            return;
        }

        saveResult(room, uid);
        sendTo(room.getSession(uid), buildWaiting("¡Terminaste! Esperando a tu oponente..."));

        if (room.bothSaved()) finishDuel(room);
    }

    /** Guarda el resultado de un jugador una sola vez, con sus respuestas pregunta por pregunta. */
    private void saveResult(DuelRoom room, String uid) {
        if (!room.markSubmitted(uid)) return;
        try {
            duelDAO.submitDuelResult(duelId, uid,
                    room.getScore(uid), room.getTotalQuestions(),
                    room.getTime(uid), room.getAnswersJson(uid));
        } catch (Exception e) {
            LOG.warning("[WS] Error guardando score de " + uid + ": " + e.getMessage());
        } finally {
            room.markSaved(uid);
        }
    }

    private void finishDuel(DuelRoom room) {
        // Los dos pueden terminar casi a la vez: solo el primero cierra el duelo.
        if (!room.tryClose()) return;
        try {
            String cId    = room.getChallengerId();
            String oId    = room.getOpponentId();
            int    cScore = room.getScore(cId);
            int    oScore = room.getScore(oId);
            int    cTime  = room.getTime(cId);
            int    oTime  = room.getTime(oId);
            int    total  = room.getTotalQuestions();

            String winnerId;
            if      (cScore > oScore) winnerId = cId;
            else if (oScore > cScore) winnerId = oId;
            else if (cTime  < oTime)  winnerId = cId;
            else if (oTime  < cTime)  winnerId = oId;
            else                      winnerId = null;

            try {
                duelDAO.finishDuelWithWinner(duelId, winnerId);
            } catch (Exception e) {
                LOG.warning("[WS] Error actualizando winner_id: " + e.getMessage());
            }

            String cActivity = winnerId == null ? "duelo_empate" : cId.equals(winnerId) ? "duelo_ganado" : "duelo_perdido";
            String oActivity = winnerId == null ? "duelo_empate" : oId.equals(winnerId) ? "duelo_ganado" : "duelo_perdido";

            double cPct = total > 0 ? (cScore * 100.0 / total) : 0;
            double oPct = total > 0 ? (oScore * 100.0 / total) : 0;

            JsonObject cReward = GamificationService.processActivity(cId, cActivity, cPct, null, cTime, total);
            JsonObject oReward = GamificationService.processActivity(oId, oActivity, oPct, null, oTime, total);

            sendTo(room.getSession(cId), buildGameOver(winnerId, room, cReward));
            sendTo(room.getSession(oId), buildGameOver(winnerId, room, oReward));

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[WS] Error al finalizar duelo " + duelId, e);
            broadcastToRoom(room, buildError("Error al finalizar: " + e.getMessage()));
        } finally {
            DuelRoomManager.INSTANCE.removeRoom(duelId);
        }
    }

    // ── CALLBACKS ────────────────────────────────────────────────

    @Override
    public void onQuestionTimeout(String duelId, String userId, int questionIndex) {
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null || room.getState() != DuelRoom.State.PLAYING) return;

        // Se registra aunque el jugador esté desconectado: si no, al volver quedaría
        // trabado en esta pregunta sin tiempo corriendo.
        // Atómico: si la respuesta real del cliente ya se procesó para esta
        // pregunta (ganó la carrera), esto retorna false y no hacemos nada.
        boolean applied = room.tryRegisterAnswer(userId, questionIndex, -1, true);
        if (!applied) return;

        JsonObject timeout = new JsonObject();
        timeout.addProperty("type",          "TIMEOUT");
        timeout.addProperty("questionIndex", questionIndex);
        sendTo(room.getSession(userId), timeout);

        try {
            advance(room, userId);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[WS] Error en timeout de " + userId, e);
        }
    }

    @Override
    public void onOpponentLeft(String duelId, String rivalUserId, int graceSecs) {
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null) return;
        sendTo(room.getSession(rivalUserId), buildOpponentLeft(graceSecs));
    }

    /**
     * El jugador no volvió a tiempo. DuelRoom ya cerró la sala (tryClose), así que
     * nadie más da premios ni manda GAME_OVER por este duelo.
     */
    @Override
    public void onAbandon(String duelId, String abandonedUserId) {
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null) return;
        String winner = room.getRival(abandonedUserId);
        try {
            saveResult(room, abandonedUserId);
            if (winner != null) {
                saveResult(room, winner);
                // submitDuelResult elige ganador por puntaje; en un abandono gana el que se quedó.
                duelDAO.forfeitDuel(duelId, winner);

                int total  = room.getTotalQuestions();
                int rScore = room.getScore(winner);
                int aScore = room.getScore(abandonedUserId);
                JsonObject reward = GamificationService.processActivity(winner, "duelo_ganado",
                        total > 0 ? (rScore * 100.0 / total) : 0, null, room.getTime(winner), total);
                GamificationService.processActivity(abandonedUserId, "duelo_perdido",
                        total > 0 ? (aScore * 100.0 / total) : 0, null, room.getTime(abandonedUserId), total);

                JsonObject msg = buildGameOver(winner, room, reward);
                msg.addProperty("reason", "opponent_abandoned");
                sendTo(room.getSession(winner), msg);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[WS] Error procesando abandono en " + duelId, e);
        } finally {
            DuelRoomManager.INSTANCE.removeRoom(duelId);
        }
    }

    // ── BUILDERS ─────────────────────────────────────────────────

    private JsonObject buildWaiting(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "WAITING");
        o.addProperty("message", message);
        return o;
    }

    private JsonObject buildReady(JsonObject duelInfo, int totalQ, int timePerQ) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "READY");
        o.addProperty("totalQuestions", totalQ);
        o.addProperty("timePerQuestion", timePerQ);
        JsonArray players = new JsonArray();
        JsonObject p1 = new JsonObject();
        p1.addProperty("userId",   duelInfo.get("challengerId").getAsString());
        p1.addProperty("username", duelInfo.get("challengerName").getAsString());
        p1.addProperty("level",    duelInfo.get("challengerLevel").getAsInt());
        players.add(p1);
        JsonObject p2 = new JsonObject();
        p2.addProperty("userId",   duelInfo.get("opponentId").getAsString());
        p2.addProperty("username", duelInfo.get("opponentName").getAsString());
        p2.addProperty("level",    duelInfo.get("opponentLevel").getAsInt());
        players.add(p2);
        o.add("players", players);
        return o;
    }

    private void sendQuestionTo(Session target, DuelRoom room, JsonArray questions,
                                int index, int timeLimitSecs) {
        if (questions == null || index >= questions.size()) return;
        JsonObject q = questions.get(index).getAsJsonObject();
        JsonObject msg = new JsonObject();
        msg.addProperty("type",      "QUESTION");
        msg.addProperty("index",     index);
        msg.addProperty("timeLimit", timeLimitSecs);
        for (var entry : q.entrySet()) {
            if (!"correct".equals(entry.getKey())
                    && !"correctAnswer".equals(entry.getKey())
                    && !"explanation".equals(entry.getKey())) {
                msg.add(entry.getKey(), entry.getValue());
            }
        }
        sendTo(target, msg);
    }

    private JsonObject buildGameOver(String winnerId, DuelRoom room, JsonObject reward) {
        JsonObject o = new JsonObject();
        o.addProperty("type",   "GAME_OVER");
        o.addProperty("winner", winnerId != null ? winnerId : "draw");
        JsonObject finalScores = new JsonObject();
        if (room.getChallengerId() != null)
            finalScores.addProperty(room.getChallengerId(), room.getScore(room.getChallengerId()));
        if (room.getOpponentId() != null)
            finalScores.addProperty(room.getOpponentId(), room.getScore(room.getOpponentId()));
        o.add("finalScores", finalScores);
        if (reward != null) {
            o.addProperty("xpEarned",    reward.has("xpEarned")    ? reward.get("xpEarned").getAsInt()    : 0);
            o.addProperty("coinsEarned", reward.has("coinsEarned") ? reward.get("coinsEarned").getAsInt() : 0);
            o.addProperty("leveledUp",   reward.has("leveledUp")   && reward.get("leveledUp").getAsBoolean());
            o.addProperty("newLevel",    reward.has("newLevel")     ? reward.get("newLevel").getAsInt()    : 1);
        }
        return o;
    }

    private JsonObject buildOpponentLeft(int graceSecs) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "OPPONENT_LEFT");
        o.addProperty("graceSecs", graceSecs);
        return o;
    }

    private JsonObject buildError(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ERROR");
        o.addProperty("message", message);
        return o;
    }

    // ── ENVÍO ────────────────────────────────────────────────────

    private void send(JsonObject msg) { sendTo(session, msg); }
    private void sendError(String message) { send(buildError(message)); }

    private void sendTo(Session target, JsonObject msg) {
        if (target == null || !target.isOpen()) return;
        Object lock = SESSION_LOCKS.computeIfAbsent(target, k -> new Object());
        synchronized (lock) {
            if (!target.isOpen()) return;
            try {
                target.getBasicRemote().sendText(GSON.toJson(msg));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[WS] Error enviando a sesión " + target.getId(), e);
            } catch (IllegalStateException e) {
                LOG.log(Level.WARNING, "[WS] Sesión en estado inválido para " + target.getId(), e);
            }
        }
    }

    private void broadcastToRoom(DuelRoom room, JsonObject msg) {
        Session cSession = room.getChallengerId() != null ? room.getSession(room.getChallengerId()) : null;
        Session oSession = room.getOpponentId()   != null ? room.getSession(room.getOpponentId())   : null;
        if (cSession != null) sendTo(cSession, msg);
        if (oSession != null) sendTo(oSession, msg);
    }
}
