package com.project.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.websocket.Session;
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

    private final ConcurrentHashMap<String, Session> sessions    = new ConcurrentHashMap<>();
    private volatile String challengerId;
    private volatile String opponentId;

    // Progreso individual por jugador
    private final ConcurrentHashMap<String, Integer> currentQuestion = new ConcurrentHashMap<>();
    private volatile int totalQuestions = 0;

    private final ConcurrentHashMap<String, Integer> scores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> times  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> finished = new ConcurrentHashMap<>();

    // Timer individual por jugador
    private final ConcurrentHashMap<String, ScheduledFuture<?>> questionTimers = new ConcurrentHashMap<>();

    private volatile JsonArray questions;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reconnectTimer;

    private RoomCallback callback;

    public DuelRoom(String duelId) {
        this.duelId = duelId;
    }

    public synchronized boolean join(String userId, Session session, RoomCallback cb) {
        this.callback = cb;
        sessions.put(userId, session);
        scores.putIfAbsent(userId, 0);
        times.putIfAbsent(userId, 0);
        currentQuestion.putIfAbsent(userId, 0);
        finished.putIfAbsent(userId, false);

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

    public synchronized void playerDisconnected(String userId) {
        sessions.remove(userId);
        if (state == State.FINISHED) return;

        String rival = getRival(userId);
        if (rival != null && callback != null) {
            callback.onOpponentLeft(duelId, rival, RECONNECT_GRACE_SECS);
        }

        reconnectTimer = scheduler.schedule(() -> {
            synchronized (this) {
                if (!sessions.containsKey(userId) && state != State.FINISHED) {
                    state = State.FINISHED;
                    if (callback != null) callback.onAbandon(duelId, userId);
                }
            }
        }, RECONNECT_GRACE_SECS, TimeUnit.SECONDS);
    }

    public synchronized void startGame(int totalQ, int timeLimitSecs) {
        this.totalQuestions = totalQ;
        this.state = State.PLAYING;
    }

    // Registrar respuesta individual
    public synchronized boolean registerAnswer(String userId, boolean correct, int timeSecs) {
        if (correct) scores.merge(userId, 1, Integer::sum);
        times.merge(userId, timeSecs, Integer::sum);
        cancelQuestionTimer(userId);

        int next = currentQuestion.merge(userId, 1, Integer::sum);
        if (next >= totalQuestions) {
            finished.put(userId, true);
        }

        return finished.getOrDefault(userId, false);
    }

    // Verificar si ambos terminaron
    public synchronized boolean bothFinished() {
        return finished.getOrDefault(challengerId, false)
                && finished.getOrDefault(opponentId, false);
    }

    // Timer individual por pregunta
    public void scheduleQuestionTimer(String userId, int timeLimitSecs) {
        cancelQuestionTimer(userId);
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            synchronized (this) {
                if (state != State.PLAYING) return;
                if (callback != null) {
                    callback.onQuestionTimeout(duelId, userId, currentQuestion.getOrDefault(userId, 0));
                }
            }
        }, timeLimitSecs, TimeUnit.SECONDS);
        questionTimers.put(userId, timer);
    }

    private void cancelQuestionTimer(String userId) {
        ScheduledFuture<?> t = questionTimers.get(userId);
        if (t != null && !t.isDone()) t.cancel(false);
    }

    // Validación de respuesta
    public void setQuestions(JsonArray questions) { this.questions = questions; }
    public JsonArray getQuestions()               { return questions; }

    public boolean isCorrectAnswer(int questionIndex, int answerIdx) {
        if (questions == null || questionIndex >= questions.size()) return false;
        JsonObject q = questions.get(questionIndex).getAsJsonObject();
        if (q.has("correct"))       return q.get("correct").getAsInt() == answerIdx;
        if (q.has("correctAnswer")) return q.get("correctAnswer").getAsInt() == answerIdx;
        return false;
    }

    public int getCorrectIdx(int questionIndex) {
        if (questions == null || questionIndex >= questions.size()) return -1;
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
    public void   setState(State s)              { this.state = s; }
    public int    getTotalQuestions()            { return totalQuestions; }
    public String getChallengerId()              { return challengerId; }
    public String getOpponentId()               { return opponentId; }
    public int    getScore(String uid)           { return scores.getOrDefault(uid, 0); }
    public int    getTime(String uid)            { return times.getOrDefault(uid, 0);  }
    public int    getCurrentQuestion(String uid) { return currentQuestion.getOrDefault(uid, 0); }
    public Session getSession(String uid)        { return sessions.get(uid); }

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