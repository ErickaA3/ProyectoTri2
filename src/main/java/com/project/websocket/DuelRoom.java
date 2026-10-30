package com.project.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.websocket.Session;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DuelRoom {

    public enum State { WAITING, PLAYING, FINISHED }

    public static final int RECONNECT_GRACE_SECS = 30;

    private final String duelId;
    private volatile State state = State.WAITING;

    // true cuando el duelo ya se cerró (terminado o abandonado). Solo tryClose()
    // lo pasa a true, así el cierre (resultados + XP + GAME_OVER) ocurre una vez.
    private boolean closed = false;

    private final ConcurrentHashMap<String, Session> sessions    = new ConcurrentHashMap<>();
    private volatile String challengerId;
    private volatile String opponentId;

    private final ConcurrentHashMap<String, Integer> currentQuestion = new ConcurrentHashMap<>();
    private volatile int totalQuestions = 0;

    private volatile int timePerQuestion = 30;

    private final ConcurrentHashMap<String, Integer> scores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> times  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> finished = new ConcurrentHashMap<>();

    // Respuestas de cada jugador, en el formato que guarda DuelDAOImpl en duel_answers.
    private final ConcurrentHashMap<String, JsonArray> answers = new ConcurrentHashMap<>();

    // Resultado ya enviado a la BD (submitted) y ya guardado (saved), por jugador.
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();
    private final Set<String> saved     = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, ScheduledFuture<?>> questionTimers = new ConcurrentHashMap<>();
    // Cuándo se mandó la pregunta actual y cuándo vence, por jugador (System.currentTimeMillis).
    private final ConcurrentHashMap<String, Long> questionStartedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> questionDeadline  = new ConcurrentHashMap<>();

    private volatile JsonArray questions;

    // Hilo daemon: si una sala queda colgada, no impide que Tomcat se apague.
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> reconnectTimer;

    private RoomCallback callback;

    public DuelRoom(String duelId) {
        this.duelId = duelId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "duel-room-" + duelId);
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized boolean join(String userId, Session session, RoomCallback cb) {
        this.callback = cb;
        sessions.put(userId, session);
        scores.putIfAbsent(userId, 0);
        times.putIfAbsent(userId, 0);
        currentQuestion.putIfAbsent(userId, 0);
        finished.putIfAbsent(userId, false);
        answers.putIfAbsent(userId, new JsonArray());

        if (challengerId == null) {
            challengerId = userId;
        } else if (!userId.equals(challengerId) && opponentId == null) {
            opponentId = userId;
        }

        if (reconnectTimer != null && !reconnectTimer.isDone()) {
            reconnectTimer.cancel(false);
            reconnectTimer = null;
        }

        return sessions.size() == 2 && challengerId != null && opponentId != null;
    }

    /**
     * Se llama cuando se cierra una conexión. Recibe la sesión que se cerró: si el
     * jugador ya se reconectó con otra, el cierre tardío del socket viejo no lo saca.
     *
     * Solo cuenta como abandono si el duelo está en curso y el jugador no había
     * terminado sus preguntas. Antes de empezar, o si ya terminó y solo está
     * esperando al rival, irse no le hace perder.
     */
    public synchronized void playerDisconnected(String userId, Session session) {
        if (!sessions.remove(userId, session)) return;
        if (state != State.PLAYING || closed || isFinished(userId)) return;

        String rival = getRival(userId);
        if (rival != null && callback != null) {
            callback.onOpponentLeft(duelId, rival, RECONNECT_GRACE_SECS);
        }

        reconnectTimer = scheduler.schedule(() -> {
            synchronized (this) {
                if (!sessions.containsKey(userId) && tryClose()) {
                    if (callback != null) callback.onAbandon(duelId, userId);
                }
            }
        }, RECONNECT_GRACE_SECS, TimeUnit.SECONDS);
    }

    /** Arranca la partida. Devuelve false si ya había arrancado (dos JOIN casi a la vez). */
    public synchronized boolean tryStartGame(int totalQ, int timeLimitSecs) {
        if (state != State.WAITING) return false;
        this.totalQuestions = totalQ;
        this.timePerQuestion = timeLimitSecs;
        this.state = State.PLAYING;
        return true;
    }

    /** Marca el duelo como cerrado. Devuelve true solo la primera vez. */
    public synchronized boolean tryClose() {
        if (closed) return false;
        closed = true;
        state = State.FINISHED;
        return true;
    }

    public synchronized boolean isClosed() { return closed; }

    /**
     * Intenta registrar la respuesta de un jugador PARA UNA PREGUNTA ESPECÍFICA.
     * Es la única puerta de entrada para avanzar el progreso de un jugador —
     * tanto handleAnswer como onQuestionTimeout pasan por acá.
     *
     * Es a prueba de carreras: si el timeout del servidor y la respuesta real
     * del cliente llegan casi al mismo tiempo para la MISMA pregunta, solo el
     * primero que consigue el lock del objeto se aplica; el segundo detecta
     * que currentQuestion ya avanzó y no hace nada (retorna false).
     *
     * El tiempo lo mide el servidor desde que mandó la pregunta. Una pregunta
     * que se queda sin responder cuenta el tiempo completo, no cero.
     *
     * @param answerIdx opción elegida, o -1 si se agotó el tiempo
     * @return true si esta llamada fue la que efectivamente avanzó la partida,
     *         false si ya se había procesado esta pregunta (llamada duplicada/tardía).
     */
    public synchronized boolean tryRegisterAnswer(String userId, int questionIndex, int answerIdx, boolean timedOut) {
        if (isFinished(userId) || questionIndex >= totalQuestions) return false;
        int current = currentQuestion.getOrDefault(userId, 0);
        if (questionIndex != current) {
            // Pregunta obsoleta — ya se procesó (carrera timeout vs respuesta real).
            return false;
        }

        cancelQuestionTimer(userId);
        boolean correct = !timedOut && isCorrectAnswer(questionIndex, answerIdx);
        long elapsedMs  = timedOut ? timePerQuestion * 1000L : elapsedOnQuestion(userId);

        if (correct) scores.merge(userId, 1, Integer::sum);
        times.merge(userId, (int) Math.round(elapsedMs / 1000.0), Integer::sum);

        JsonObject a = new JsonObject();
        a.addProperty("questionIndex", questionIndex);
        if (!timedOut) a.addProperty("answerGiven", String.valueOf(answerIdx));
        a.addProperty("isCorrect", correct);
        a.addProperty("timeMs", elapsedMs);
        answers.computeIfAbsent(userId, k -> new JsonArray()).add(a);

        questionStartedAt.remove(userId);
        questionDeadline.remove(userId);

        int next = currentQuestion.merge(userId, 1, Integer::sum);
        if (next >= totalQuestions) {
            finished.put(userId, true);
        }
        return true;
    }

    private long elapsedOnQuestion(String userId) {
        Long start = questionStartedAt.get(userId);
        long ms = start == null ? 0 : System.currentTimeMillis() - start;
        return Math.max(0, Math.min(ms, timePerQuestion * 1000L));
    }

    public boolean isFinished(String uid) {
        return finished.getOrDefault(uid, false);
    }

    /** Reserva el guardado del resultado de un jugador. Devuelve true solo la primera vez. */
    public boolean markSubmitted(String uid) { return submitted.add(uid); }

    /** El resultado del jugador ya quedó guardado en la BD (o falló y no se reintenta). */
    public void markSaved(String uid) { saved.add(uid); }

    /**
     * Los dos guardaron su resultado. El duelo se cierra recién acá: si se cerrara
     * apenas los dos terminan, el guardado del segundo podría llegar tarde y la BD
     * lo rechazaría porque el duelo ya figura como terminado.
     */
    public synchronized boolean bothSaved() {
        return challengerId != null && opponentId != null
            && saved.contains(challengerId) && saved.contains(opponentId);
    }

    /**
     * Manda a correr el tiempo de una pregunta. El índice de la pregunta
     * se CAPTURA aquí, en el momento de programar el timer — no se relee
     * cuando el timer se dispara. Esto evita que un timer "viejo" reporte
     * un índice ya avanzado si hay una carrera con la respuesta del cliente.
     */
    public synchronized void startQuestion(String userId, int questionIndex, int timeLimitSecs) {
        cancelQuestionTimer(userId);
        long now = System.currentTimeMillis();
        questionStartedAt.put(userId, now);
        questionDeadline.put(userId, now + timeLimitSecs * 1000L);
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            synchronized (this) {
                if (state != State.PLAYING) return;
                if (callback != null) {
                    callback.onQuestionTimeout(duelId, userId, questionIndex);
                }
            }
        }, timeLimitSecs, TimeUnit.SECONDS);
        questionTimers.put(userId, timer);
    }

    /** Segundos que le quedan a la pregunta actual del jugador, o 0 si no hay una corriendo. */
    public synchronized int remainingSecs(String userId) {
        ScheduledFuture<?> t = questionTimers.get(userId);
        Long deadline = questionDeadline.get(userId);
        if (t == null || t.isDone() || deadline == null) return 0;
        long ms = deadline - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 1000.0);
    }

    private void cancelQuestionTimer(String userId) {
        ScheduledFuture<?> t = questionTimers.get(userId);
        if (t != null && !t.isDone()) t.cancel(false);
    }

    public void setQuestions(JsonArray questions) { this.questions = questions; }
    public JsonArray getQuestions()               { return questions; }

    public boolean isCorrectAnswer(int questionIndex, int answerIdx) {
        if (questions == null || questionIndex < 0 || questionIndex >= questions.size()) return false;
        JsonObject q = questions.get(questionIndex).getAsJsonObject();
        if (q.has("correct"))       return q.get("correct").getAsInt() == answerIdx;
        if (q.has("correctAnswer")) return q.get("correctAnswer").getAsInt() == answerIdx;
        return false;
    }

    public int getCorrectIdx(int questionIndex) {
        if (questions == null || questionIndex < 0 || questionIndex >= questions.size()) return -1;
        JsonObject q = questions.get(questionIndex).getAsJsonObject();
        if (q.has("correct"))       return q.get("correct").getAsInt();
        if (q.has("correctAnswer")) return q.get("correctAnswer").getAsInt();
        return -1;
    }

    public void shutdown() {
        questionTimers.values().forEach(t -> { if (!t.isDone()) t.cancel(false); });
        if (reconnectTimer != null) reconnectTimer.cancel(false);
        scheduler.shutdownNow();
    }

    public String getDuelId()                    { return duelId; }
    public State  getState()                     { return state;  }
    public int    getTotalQuestions()            { return totalQuestions; }
    public String getChallengerId()              { return challengerId; }
    public String getOpponentId()               { return opponentId; }
    public int    getScore(String uid)           { return scores.getOrDefault(uid, 0); }
    public int    getTime(String uid)            { return times.getOrDefault(uid, 0);  }
    public int    getCurrentQuestion(String uid) { return currentQuestion.getOrDefault(uid, 0); }
    public Session getSession(String uid)        { return sessions.get(uid); }
    public String getAnswersJson(String uid)     { JsonArray a = answers.get(uid); return a == null ? "[]" : a.toString(); }

    public int  getTimePerQuestion()      { return timePerQuestion; }

    public String getRival(String userId) {
        if (userId.equals(challengerId)) return opponentId;
        if (userId.equals(opponentId))   return challengerId;
        return null;
    }

    public boolean isPlayer(String userId) {
        return userId.equals(challengerId) || userId.equals(opponentId);
    }

    public interface RoomCallback {
        void onQuestionTimeout(String duelId, String userId, int questionIndex);
        void onOpponentLeft(String duelId, String rivalUserId, int graceSecs);
        void onAbandon(String duelId, String abandonedUserId);
    }
}
