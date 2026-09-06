package com.project.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.DuelDAOImpl;
import com.project.dao.interfaces.IDuelDAO;
import com.project.util.GamificationService;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

@ServerEndpoint("/ws/duelo/{duelId}")
public class DuelWebSocket implements DuelRoom.RoomCallback {

    private static final Logger LOG = Logger.getLogger(DuelWebSocket.class.getName());
    private static final Gson GSON = new Gson();

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
                case "JOIN"      -> handleJoin(msg);
                case "ANSWER"    -> handleAnswer(msg);
                case "RECONNECT" -> handleReconnect(msg);
                default          -> sendError("Tipo de mensaje desconocido: " + type);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[WS] Error procesando mensaje: " + raw, e);
            sendError("Error procesando mensaje: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        LOG.info("[WS] Cierre — userId=" + userId);
        if (userId == null || duelId == null) return;
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room != null && room.getState() != DuelRoom.State.FINISHED) {
            room.playerDisconnected(userId);
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        LOG.log(Level.SEVERE, "[WS] Error en sesión userId=" + userId, t);
    }

    // ── HANDLERS ────────────────────────────────────────────────

    private void handleJoin(JsonObject msg) throws Exception {
        this.userId = msg.get("userId").getAsString();

        JsonObject duelInfo = duelDAO.getDuel(duelId, userId);
        if (duelInfo == null) { sendError("No pertenecés a este duelo."); session.close(); return; }

        String status = duelInfo.get("status").getAsString();
        if ("finished".equals(status) || "declined".equals(status)) {
            sendError("Este duelo ya terminó."); session.close(); return;
        }

        JsonObject questionsData;
        try {
            questionsData = duelDAO.getDuelQuestions(duelId, userId);
        } catch (Exception e) {
            sendError(e.getMessage()); session.close(); return;
        }

        DuelRoom room = DuelRoomManager.INSTANCE.getOrCreate(duelId);
        boolean bothReady = room.join(userId, session, this);

        if (!bothReady) { send(buildWaiting("Esperando a tu oponente...")); return; }

        int totalQ   = questionsData.get("questionCount").getAsInt();
        int timePerQ = questionsData.has("timePerQuestion")
                       ? questionsData.get("timePerQuestion").getAsInt() : 30;

        room.startGame(totalQ, timePerQ);

        JsonArray questions = questionsData.getAsJsonArray("questions");
        room.setQuestions(questions);

        broadcastToRoom(room, buildReady(duelInfo, totalQ, timePerQ));

        // Enviar primera pregunta a cada jugador individualmente
        sendQuestionTo(session, room, questions, 0, timePerQ);
        String rival = room.getRival(userId);
        if (rival != null) {
            Session rivalSession = room.getSession(rival);
            if (rivalSession != null && rivalSession.isOpen()) {
                sendQuestionTo(rivalSession, room, questions, 0, timePerQ);
            }
        }

        // Arrancar timers individuales
        room.scheduleQuestionTimer(userId, timePerQ);
        if (rival != null) room.scheduleQuestionTimer(rival, timePerQ);
    }

    private void handleAnswer(JsonObject msg) throws Exception {
        if (userId == null) { sendError("Enviá JOIN primero."); return; }

        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null || room.getState() != DuelRoom.State.PLAYING) {
            sendError("El duelo no está en curso."); return;
        }

        int questionIndex = msg.get("questionIndex").getAsInt();
        int answerIdx     = msg.has("answerIdx") ? msg.get("answerIdx").getAsInt() : -1;
        int timeSecs      = msg.has("timeSecs")  ? msg.get("timeSecs").getAsInt()  : 0;

        // Validar que sea la pregunta actual de este jugador
        if (questionIndex != room.getCurrentQuestion(userId)) return;

        boolean correct  = room.isCorrectAnswer(questionIndex, answerIdx);
        int correctIdx   = room.getCorrectIdx(questionIndex);

        // Registrar respuesta — avanza pregunta de este jugador
        boolean playerFinished = room.registerAnswer(userId, correct, timeSecs);

        // Decirle al cliente si fue correcto
        JsonObject feedback = new JsonObject();
        feedback.addProperty("type",          "ANSWER_RESULT");
        feedback.addProperty("questionIndex", questionIndex);
        feedback.addProperty("correct",       correct);
        feedback.addProperty("correctIdx",    correctIdx);
        feedback.addProperty("yourAnswerIdx", answerIdx);
        send(feedback);

        // Notificar score al rival
        String rival = room.getRival(userId);
        if (rival != null) {
            Session rivalSession = room.getSession(rival);
            if (rivalSession != null && rivalSession.isOpen()) {
                JsonObject scoreUpdate = new JsonObject();
                scoreUpdate.addProperty("type",   "SCORE_UPDATE");
                scoreUpdate.addProperty("userId", userId);
                scoreUpdate.addProperty("score",  room.getScore(userId));
                sendTo(rivalSession, scoreUpdate);
            }
        }

        if (playerFinished) {
            // Guardar score de este jugador en BD inmediatamente
            try {
                duelDAO.submitDuelResult(duelId, userId,
                        room.getScore(userId), room.getTotalQuestions(),
                        room.getTime(userId), "[]");
            } catch (Exception e) {
                LOG.warning("[WS] Error guardando score de " + userId + ": " + e.getMessage());
            }

            send(buildWaiting("¡Terminaste! Esperando a tu oponente..."));

            if (room.bothFinished()) {
                finishDuel(room);
            }
        } else {
            // Enviar siguiente pregunta solo a este jugador
            int nextIndex = room.getCurrentQuestion(userId);
            JsonObject duelInfo = duelDAO.getDuel(duelId, userId);
            int tpq = duelInfo != null && duelInfo.has("timePerQuestion")
                      ? duelInfo.get("timePerQuestion").getAsInt() : 30;
            sendQuestionTo(session, room, room.getQuestions(), nextIndex, tpq);
            room.scheduleQuestionTimer(userId, tpq);
        }
    }

    private void handleReconnect(JsonObject msg) throws Exception {
        this.userId = msg.get("userId").getAsString();

        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null) { sendError("El duelo ya no está activo."); return; }
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
            Session rivalSession = room.getSession(rival);
            if (rivalSession != null && rivalSession.isOpen()) {
                sendTo(rivalSession, buildInfo("Tu oponente reconectó."));
            }
        }
    }

    // ── FINISH ───────────────────────────────────────────────────

    private void finishDuel(DuelRoom room) {
        try {
            room.setState(DuelRoom.State.FINISHED);

            String cId    = room.getChallengerId();
            String oId    = room.getOpponentId();
            int    cScore = room.getScore(cId);
            int    oScore = room.getScore(oId);
            int    cTime  = room.getTime(cId);
            int    oTime  = room.getTime(oId);
            int    total  = room.getTotalQuestions();

            // Determinar ganador con los scores en memoria (ya guardados en BD individualmente)
            String winnerId;
            if      (cScore > oScore) winnerId = cId;
            else if (oScore > cScore) winnerId = oId;
            else if (cTime  < oTime)  winnerId = cId;  // empate en score → gana el más rápido
            else if (oTime  < cTime)  winnerId = oId;
            else                      winnerId = null;  // empate total

            // Actualizar winner_id en BD
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

            Session cSession = room.getSession(cId);
            if (cSession != null && cSession.isOpen()) sendTo(cSession, buildGameOver(winnerId, room, cReward));

            Session oSession = room.getSession(oId);
            if (oSession != null && oSession.isOpen()) sendTo(oSession, buildGameOver(winnerId, room, oReward));

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

        Session userSession = room.getSession(userId);
        if (userSession == null || !userSession.isOpen()) return;

        JsonObject timeout = new JsonObject();
        timeout.addProperty("type",          "TIMEOUT");
        timeout.addProperty("questionIndex", questionIndex);
        sendTo(userSession, timeout);

        try {
            boolean playerFinished = room.registerAnswer(userId, false, 0);

            if (playerFinished) {
                try {
                    duelDAO.submitDuelResult(duelId, userId,
                            room.getScore(userId), room.getTotalQuestions(),
                            room.getTime(userId), "[]");
                } catch (Exception e) {
                    LOG.warning("[WS] Error guardando score timeout: " + e.getMessage());
                }

                sendTo(userSession, buildWaiting("¡Terminaste! Esperando a tu oponente..."));

                if (room.bothFinished()) finishDuel(room);
            } else {
                int nextIndex = room.getCurrentQuestion(userId);
                JsonObject duelInfo = duelDAO.getDuel(duelId, userId);
                int tpq = duelInfo != null && duelInfo.has("timePerQuestion")
                          ? duelInfo.get("timePerQuestion").getAsInt() : 30;
                sendQuestionTo(userSession, room, room.getQuestions(), nextIndex, tpq);
                room.scheduleQuestionTimer(userId, tpq);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[WS] Error en timeout de " + userId, e);
        }
    }

    @Override
    public void onOpponentLeft(String duelId, String rivalUserId, int graceSecs) {
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null) return;
        Session rivalSession = room.getSession(rivalUserId);
        if (rivalSession != null && rivalSession.isOpen()) sendTo(rivalSession, buildOpponentLeft(graceSecs));
    }

    @Override
    public void onAbandon(String duelId, String abandonedUserId) {
        DuelRoom room = DuelRoomManager.INSTANCE.get(duelId);
        if (room == null) return;
        String winner = room.getRival(abandonedUserId);
        try {
            int aScore = room.getScore(abandonedUserId);
            int rScore = winner != null ? room.getScore(winner) : 0;
            int total  = room.getTotalQuestions();

            duelDAO.submitDuelResult(duelId, abandonedUserId, aScore, total,
                    room.getTime(abandonedUserId), "[]");
            if (winner != null) {
                duelDAO.submitDuelResult(duelId, winner, rScore, total,
                        room.getTime(winner), "[]");
                duelDAO.finishDuelWithWinner(duelId, winner);

                GamificationService.processActivity(winner, "duelo_ganado",
                        total > 0 ? (rScore * 100.0 / total) : 0, null, room.getTime(winner), total);
                GamificationService.processActivity(abandonedUserId, "duelo_perdido",
                        total > 0 ? (aScore * 100.0 / total) : 0, null, room.getTime(abandonedUserId), total);

                Session winnerSession = room.getSession(winner);
                if (winnerSession != null && winnerSession.isOpen()) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type",   "GAME_OVER");
                    msg.addProperty("winner", winner);
                    msg.addProperty("reason", "opponent_abandoned");
                    sendTo(winnerSession, msg);
                }
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

    private JsonObject buildTimeout(int questionIndex) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "TIMEOUT");
        o.addProperty("questionIndex", questionIndex);
        return o;
    }

    private JsonObject buildOpponentLeft(int graceSecs) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "OPPONENT_LEFT");
        o.addProperty("graceSecs", graceSecs);
        return o;
    }

    private JsonObject buildInfo(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "INFO");
        o.addProperty("message", message);
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
        try { target.getBasicRemote().sendText(GSON.toJson(msg)); }
        catch (IOException e) { LOG.log(Level.WARNING, "[WS] Error enviando a sesión " + target.getId(), e); }
    }

    private void broadcastToRoom(DuelRoom room, JsonObject msg) {
        String json = GSON.toJson(msg);
        Session cSession = room.getChallengerId() != null ? room.getSession(room.getChallengerId()) : null;
        Session oSession = room.getOpponentId()   != null ? room.getSession(room.getOpponentId())   : null;
        if (cSession != null && cSession.isOpen()) {
            try { cSession.getBasicRemote().sendText(json); }
            catch (IOException e) { LOG.warning("[WS] Broadcast falló challenger: " + e.getMessage()); }
        }
        if (oSession != null && oSession.isOpen()) {
            try { oSession.getBasicRemote().sendText(json); }
            catch (IOException e) { LOG.warning("[WS] Broadcast falló opponent: " + e.getMessage()); }
        }
    }
}