package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.interfaces.IDuelDAO;
import com.project.database.DatabaseConnection;

public class DuelDAOImpl implements IDuelDAO {

    // ═══════════════════════════════════════════════════════════════
    //  AMIGOS
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String sendFriendRequest(String senderId, String receiverEmailOrUsername) throws Exception {
        String findUser = """
            SELECT id FROM users
            WHERE email = ?::citext OR username = ?::citext
            LIMIT 1
            """;

        String receiverId;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(findUser)) {
            ps.setString(1, receiverEmailOrUsername);
            ps.setString(2, receiverEmailOrUsername);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new Exception("Usuario no encontrado.");
            receiverId = rs.getString("id");
        }

        if (senderId.equals(receiverId)) throw new Exception("No puedes agregarte a ti mismo.");

        String check = """
            SELECT id, status FROM friendships
            WHERE (sender_id = ?::uuid AND receiver_id = ?::uuid)
               OR (sender_id = ?::uuid AND receiver_id = ?::uuid)
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, senderId); ps.setString(2, receiverId);
            ps.setString(3, receiverId); ps.setString(4, senderId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String status = rs.getString("status");
                if ("accepted".equals(status)) throw new Exception("Ya son amigos.");
                if ("pending".equals(status)) throw new Exception("Ya hay una solicitud pendiente.");
            }
        }

        String insert = """
            INSERT INTO friendships (sender_id, receiver_id, status)
            VALUES (?::uuid, ?::uuid, 'pending')
            RETURNING id
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, senderId);
            ps.setString(2, receiverId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("id");
            throw new Exception("No se pudo enviar la solicitud.");
        }
    }

    @Override
    public boolean acceptFriendRequest(String friendshipId, String userId) throws Exception {
        String sql = """
            UPDATE friendships SET status = 'accepted'
            WHERE id = ?::uuid AND receiver_id = ?::uuid AND status = 'pending'
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, friendshipId);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean rejectFriendRequest(String friendshipId, String userId) throws Exception {
        String sql = """
            DELETE FROM friendships
            WHERE id = ?::uuid AND receiver_id = ?::uuid AND status = 'pending'
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, friendshipId);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean removeFriend(String friendshipId, String userId) throws Exception {
        String sql = """
            DELETE FROM friendships
            WHERE id = ?::uuid AND (sender_id = ?::uuid OR receiver_id = ?::uuid)
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, friendshipId);
            ps.setString(2, userId);
            ps.setString(3, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public JsonArray getFriends(String userId) throws Exception {
        String sql = """
            SELECT f.id AS friendship_id,
                   u.id, u.username, u.email,
                   s.level, s.xp, s.streak_current
            FROM friendships f
            JOIN users u ON u.id = CASE
                WHEN f.sender_id = ?::uuid THEN f.receiver_id
                ELSE f.sender_id
            END
            LEFT JOIN v_user_stats s ON s.user_id = u.id
            WHERE (f.sender_id = ?::uuid OR f.receiver_id = ?::uuid)
              AND f.status = 'accepted'
            ORDER BY u.username
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId); ps.setString(2, userId); ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            JsonArray friends = new JsonArray();
            while (rs.next()) {
                JsonObject f = new JsonObject();
                f.addProperty("friendshipId", rs.getString("friendship_id"));
                f.addProperty("id",       rs.getString("id"));
                f.addProperty("username", rs.getString("username"));
                f.addProperty("email",    rs.getString("email"));
                f.addProperty("level",    rs.getInt("level"));
                f.addProperty("xp",       rs.getInt("xp"));
                f.addProperty("streak",   rs.getInt("streak_current"));
                friends.add(f);
            }
            return friends;
        }
    }

    @Override
    public JsonArray getPendingRequests(String userId) throws Exception {
        String sql = """
            SELECT f.id AS friendship_id, u.id, u.username, u.email, f.created_at::text
            FROM friendships f
            JOIN users u ON u.id = f.sender_id
            WHERE f.receiver_id = ?::uuid AND f.status = 'pending'
            ORDER BY f.created_at DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            JsonArray requests = new JsonArray();
            while (rs.next()) {
                JsonObject r = new JsonObject();
                r.addProperty("friendshipId", rs.getString("friendship_id"));
                r.addProperty("id",        rs.getString("id"));
                r.addProperty("username",  rs.getString("username"));
                r.addProperty("email",     rs.getString("email"));
                r.addProperty("createdAt", rs.getString("created_at"));
                requests.add(r);
            }
            return requests;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DUELOS
    //
    //  Desde la V6 cada jugador es una fila de duel_participants y juega un
    //  intento (attempts + attempt_answers) sobre el quiz del duelo. Las
    //  lecturas usan la vista v_duels, que arma challenger/opponent con su
    //  puntaje (respuestas correctas) y su tiempo a partir de esos intentos.
    // ═══════════════════════════════════════════════════════════════

    private final GamificationDAOImpl attemptsDAO = new GamificationDAOImpl();

    @Override
    public String createDuel(String challengerId, String opponentId, String contentId,
                             String topic, int questionCount, int timePerQuestion) throws Exception {
        // questionCount ya no se guarda: es la cantidad de preguntas del quiz.
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String duelId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO duels (content_id, topic, time_per_question, status)
                        VALUES (?::uuid, ?, ?, 'waiting_opponent')
                        RETURNING id
                        """)) {
                    ps.setString(1, contentId);
                    ps.setString(2, topic);
                    ps.setInt(3, timePerQuestion);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new Exception("No se pudo crear el duelo.");
                    duelId = rs.getString("id");
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO duel_participants (duel_id, user_id, role)
                        VALUES (?::uuid, ?::uuid, 'challenger'), (?::uuid, ?::uuid, 'opponent')
                        """)) {
                    ps.setString(1, duelId); ps.setString(2, challengerId);
                    ps.setString(3, duelId); ps.setString(4, opponentId);
                    ps.executeUpdate();
                }
                conn.commit();
                return duelId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public JsonObject getDuel(String duelId, String userId) throws Exception {
        String sql = """
            SELECT d.*,
                   c.username::text AS challenger_name, c_s.level AS challenger_level,
                   o.username::text AS opponent_name, o_s.level AS opponent_level
            FROM v_duels d
            JOIN users c ON c.id = d.challenger_id
            JOIN users o ON o.id = d.opponent_id
            LEFT JOIN v_user_stats c_s ON c_s.user_id = d.challenger_id
            LEFT JOIN v_user_stats o_s ON o_s.user_id = d.opponent_id
            WHERE d.id = ?::uuid
              AND (d.challenger_id = ?::uuid OR d.opponent_id = ?::uuid)
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, duelId); ps.setString(2, userId); ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            JsonObject d = new JsonObject();
            d.addProperty("id",              rs.getString("id"));
            d.addProperty("challengerId",    rs.getString("challenger_id"));
            d.addProperty("challengerName",  rs.getString("challenger_name"));
            d.addProperty("challengerLevel", rs.getInt("challenger_level"));
            d.addProperty("opponentId",      rs.getString("opponent_id"));
            d.addProperty("opponentName",    rs.getString("opponent_name"));
            d.addProperty("opponentLevel",   rs.getInt("opponent_level"));
            d.addProperty("topic",           rs.getString("topic"));
            d.addProperty("questionCount",   rs.getInt("question_count"));
            d.addProperty("status",          rs.getString("status"));
            d.addProperty("challengerScore", scoreOrMinusOne(rs, "challenger_score"));
            d.addProperty("opponentScore",   scoreOrMinusOne(rs, "opponent_score"));
            d.addProperty("challengerTime",  rs.getInt("challenger_time"));
            d.addProperty("opponentTime",    rs.getInt("opponent_time"));
            d.addProperty("winnerId",        rs.getString("winner_id"));
            d.addProperty("createdAt",       rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null);
            d.addProperty("isChallenger", userId.equals(rs.getString("challenger_id")));

            return d;
        }
    }

    /** -1 = ese jugador todavía no jugó (así lo espera el front). */
    private static int scoreOrMinusOne(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? -1 : v;
    }

    @Override
    public JsonArray getActiveDuels(String userId) throws Exception {
        String sql = """
            SELECT d.id, d.topic, d.question_count, d.status, d.created_at::text,
                   d.challenger_id, d.opponent_id,
                   d.challenger_score, d.opponent_score,
                   c.username::text AS challenger_name, c_s.level AS challenger_level,
                   o.username::text AS opponent_name, o_s.level AS opponent_level
            FROM v_duels d
            JOIN users c ON c.id = d.challenger_id
            JOIN users o ON o.id = d.opponent_id
            LEFT JOIN v_user_stats c_s ON c_s.user_id = d.challenger_id
            LEFT JOIN v_user_stats o_s ON o_s.user_id = d.opponent_id
            WHERE (d.challenger_id = ?::uuid OR d.opponent_id = ?::uuid)
              AND d.status IN ('waiting_opponent', 'in_progress')
            ORDER BY d.created_at DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId); ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            JsonArray duels = new JsonArray();
            while (rs.next()) {
                JsonObject d = new JsonObject();
                d.addProperty("id",              rs.getString("id"));
                d.addProperty("topic",           rs.getString("topic"));
                d.addProperty("questionCount",   rs.getInt("question_count"));
                d.addProperty("status",          rs.getString("status"));
                d.addProperty("createdAt",       rs.getString("created_at"));
                d.addProperty("challengerId",    rs.getString("challenger_id"));
                d.addProperty("challengerName",  rs.getString("challenger_name"));
                d.addProperty("challengerLevel", rs.getInt("challenger_level"));
                d.addProperty("opponentId",      rs.getString("opponent_id"));
                d.addProperty("opponentName",    rs.getString("opponent_name"));
                d.addProperty("opponentLevel",   rs.getInt("opponent_level"));
                d.addProperty("isChallenger",    userId.equals(rs.getString("challenger_id")));

                boolean isChallenger = userId.equals(rs.getString("challenger_id"));
                boolean hasPlayed = scoreOrMinusOne(rs, isChallenger ? "challenger_score" : "opponent_score") >= 0;
                d.addProperty("hasPlayed", hasPlayed);

                duels.add(d);
            }
            return duels;
        }
    }

    @Override
    public JsonArray getDuelHistory(String userId) throws Exception {
        String sql = """
            SELECT d.id, d.topic, d.question_count, d.status,
                   d.challenger_score, d.opponent_score,
                   d.challenger_time, d.opponent_time,
                   d.winner_id, d.finished_at::text, d.created_at::text,
                   c.username::text AS challenger_name, o.username::text AS opponent_name,
                   d.challenger_id, d.opponent_id
            FROM v_duels d
            JOIN users c ON c.id = d.challenger_id
            JOIN users o ON o.id = d.opponent_id
            WHERE (d.challenger_id = ?::uuid OR d.opponent_id = ?::uuid)
              AND d.status = 'finished'
            ORDER BY d.finished_at DESC
            LIMIT 20
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId); ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            JsonArray history = new JsonArray();
            while (rs.next()) {
                JsonObject d = new JsonObject();
                d.addProperty("id",              rs.getString("id"));
                d.addProperty("topic",           rs.getString("topic"));
                d.addProperty("challengerName",  rs.getString("challenger_name"));
                d.addProperty("opponentName",    rs.getString("opponent_name"));
                d.addProperty("challengerScore", rs.getInt("challenger_score"));
                d.addProperty("opponentScore",   rs.getInt("opponent_score"));
                d.addProperty("winnerId",        rs.getString("winner_id"));
                d.addProperty("finishedAt",      rs.getString("finished_at"));
                d.addProperty("isChallenger",    userId.equals(rs.getString("challenger_id")));

                String winnerId = rs.getString("winner_id");
                if (winnerId == null) d.addProperty("result", "draw");
                else if (winnerId.equals(userId)) d.addProperty("result", "win");
                else d.addProperty("result", "loss");

                history.add(d);
            }
            return history;
        }
    }

    @Override
    public boolean declineDuel(String duelId, String userId) throws Exception {
        String sql = """
            UPDATE duels SET status = 'declined'
            WHERE id = ?::uuid
              AND EXISTS (SELECT 1 FROM duel_participants p
                          WHERE p.duel_id = duels.id AND p.user_id = ?::uuid)
              AND status IN ('waiting_opponent', 'in_progress')
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, duelId); ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    // ── Helper: arma el UPDATE de cierre de duelo evitando null::uuid ──────────
    private void finishDuelStatement(Connection conn, String duelId, String winnerId) throws Exception {
        // FIX: winner_id = null con cast ?::uuid lanza error en PostgreSQL.
        // Cuando es empate (winnerId == null) guardamos NULL sin cast usando CASE.
        String finish = """
            UPDATE duels
               SET status     = 'finished',
                   winner_id  = CASE WHEN ? THEN NULL ELSE ?::uuid END,
                   finished_at = NOW()
             WHERE id = ?::uuid
            """;
        try (PreparedStatement ps = conn.prepareStatement(finish)) {
            boolean isDraw = (winnerId == null);
            ps.setBoolean(1, isDraw);
            // Cuando es empate el segundo parámetro no se usa (rama ELSE no ejecuta),
            // pero JDBC exige que el bind exista; ponemos un UUID dummy válido.
            ps.setString(2, isDraw ? "00000000-0000-0000-0000-000000000000" : winnerId);
            ps.setString(3, duelId);
            ps.executeUpdate();
        }
    }

    /**
     * Registra la partida de un jugador (modo en tiempo real, desde DuelWebSocket).
     * @param answersJson [{ "position": 0, "option": 2 }, ...] tal como las
     *                    registró DuelRoom (option null = se acabó el tiempo).
     * score y maxScore se ignoran: el servidor califica contra las opciones guardadas.
     */
    @Override
    public JsonObject submitDuelResult(String duelId, String userId, int score,
                                       int maxScore, int timeSecs, String answersJson) throws Exception {
        JsonArray answers = (answersJson == null || answersJson.isBlank())
                ? new JsonArray() : JsonParser.parseString(answersJson).getAsJsonArray();
        return recordPlay(duelId, userId, answers, Math.max(0, timeSecs));
    }

    /**
     * [SEGURIDAD] Versión HTTP: recalcula todo en el servidor contra las
     * preguntas reales, sin confiar en el cliente.
     * @param rawAnswers [{ "questionIndex": 0, "answerGiven": 2, "timeMs": 1200 }, ...]
     */
    @Override
    public JsonObject submitDuelResultGraded(String duelId, String userId,
                                             JsonArray rawAnswers, int timeSecsRaw) throws Exception {
        int questionCount, timePerQuestion;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT question_count, time_per_question FROM v_duels WHERE id = ?::uuid")) {
            ps.setString(1, duelId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new Exception("Duelo no encontrado.");
            questionCount   = rs.getInt(1);
            timePerQuestion = rs.getInt(2);
        }

        // Al formato de saveGradedAnswers; las preguntas que no existen se ignoran.
        JsonArray answers = new JsonArray();
        for (int i = 0; i < rawAnswers.size(); i++) {
            if (!rawAnswers.get(i).isJsonObject()) continue;
            JsonObject a = rawAnswers.get(i).getAsJsonObject();
            int qIndex = a.has("questionIndex") ? safeInt(a.get("questionIndex"), -1) : -1;
            if (qIndex < 0 || qIndex >= questionCount) continue;
            int given = a.has("answerGiven") ? safeInt(a.get("answerGiven"), -1) : -1;
            JsonObject converted = new JsonObject();
            converted.addProperty("position", qIndex);
            if (given >= 0) converted.addProperty("option", given);
            answers.add(converted);
        }

        int maxTime  = Math.max(1, questionCount) * Math.max(5, timePerQuestion);
        int timeSecs = Math.max(0, Math.min(timeSecsRaw, maxTime));
        return recordPlay(duelId, userId, answers, timeSecs);
    }

    /**
     * Guarda la partida de un jugador como intento sobre el quiz del duelo y,
     * si el rival ya había jugado, cierra el duelo con el ganador (más
     * respuestas correctas; si empatan, menos tiempo; si también, empate).
     */
    private JsonObject recordPlay(String duelId, String userId, JsonArray answers, int timeSecs) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Bloquear el duelo: las dos partidas no se pueden cerrar a la vez.
                String status, contentId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status, content_id::text FROM duels WHERE id = ?::uuid FOR UPDATE")) {
                    ps.setString(1, duelId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new Exception("Duelo no encontrado.");
                    status    = rs.getString(1);
                    contentId = rs.getString(2);
                }
                if ("finished".equals(status) || "declined".equals(status)) {
                    throw new Exception("Este duelo ya terminó.");
                }
                if (contentId == null) throw new Exception("El quiz de este duelo ya no existe.");

                // 2. Jugadores y si ya jugaron.
                String rivalId = null;
                boolean iPlayed = false, rivalPlayed = false, isPlayer = false;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT user_id::text,
                               (attempt_id IS NOT NULL OR legacy_score IS NOT NULL) AS played
                        FROM duel_participants WHERE duel_id = ?::uuid
                        """)) {
                    ps.setString(1, duelId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        if (userId.equals(rs.getString(1))) { isPlayer = true; iPlayed = rs.getBoolean(2); }
                        else { rivalId = rs.getString(1); rivalPlayed = rs.getBoolean(2); }
                    }
                }
                if (!isPlayer) throw new Exception("No eres participante de este duelo.");
                if (iPlayed)   throw new Exception("Ya jugaste este duelo.");

                // 3. Intento + respuestas calificadas contra item_options.
                String attemptId = attemptsDAO.createAttempt(conn, userId, contentId, "completed", timeSecs);
                if (!answers.isEmpty()) {
                    attemptsDAO.saveGradedAnswers(conn, attemptId, contentId, answers, false);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE duel_participants SET attempt_id = ?::uuid WHERE duel_id = ?::uuid AND user_id = ?::uuid")) {
                    ps.setString(1, attemptId);
                    ps.setString(2, duelId);
                    ps.setString(3, userId);
                    ps.executeUpdate();
                }

                // 4. Puntajes ya calculados por la vista.
                int myScore, myTime, otherScore, otherTime, maxScore;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT challenger_id::text, challenger_score, challenger_time,
                               opponent_score, opponent_time, question_count
                        FROM v_duels WHERE id = ?::uuid
                        """)) {
                    ps.setString(1, duelId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    boolean isChallenger = userId.equals(rs.getString(1));
                    myScore    = rs.getInt(isChallenger ? 2 : 4);
                    myTime     = rs.getInt(isChallenger ? 3 : 5);
                    otherScore = rs.getInt(isChallenger ? 4 : 2);
                    otherTime  = rs.getInt(isChallenger ? 5 : 3);
                    maxScore   = rs.getInt(6);
                }

                JsonObject result = new JsonObject();
                result.addProperty("success",   true);
                result.addProperty("score",     myScore);
                result.addProperty("maxScore",  maxScore);
                result.addProperty("timeSecs",  myTime);
                result.addProperty("attemptId", attemptId);

                if (rivalPlayed) {
                    String winnerId;
                    String resultType;
                    if (myScore > otherScore || (myScore == otherScore && myTime < otherTime)) {
                        winnerId = userId;  resultType = "win";
                    } else if (myScore < otherScore || myTime > otherTime) {
                        winnerId = rivalId; resultType = "loss";
                    } else {
                        winnerId = null;    resultType = "draw";
                    }
                    finishDuelStatement(conn, duelId, winnerId);

                    result.addProperty("duelFinished", true);
                    result.addProperty("result",       resultType);
                    result.addProperty("winnerId",     winnerId != null ? winnerId : "");
                    result.addProperty("otherScore",   otherScore);
                    result.addProperty("otherTime",    otherTime);
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE duels SET status = 'in_progress' WHERE id = ?::uuid")) {
                        ps.setString(1, duelId);
                        ps.executeUpdate();
                    }
                    result.addProperty("duelFinished", false);
                    result.addProperty("waitingForOpponent", true);
                }

                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Parsea un JsonElement a int de forma defensiva; devuelve fallback si falla. */
    private static int safeInt(com.google.gson.JsonElement el, int fallback) {
        try {
            if (el == null || el.isJsonNull()) return fallback;
            return el.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public JsonObject getDuelQuestions(String duelId, String userId) throws Exception {
        String sql = """
            SELECT d.status, d.challenger_id::text AS challenger_id, d.challenger_score, d.opponent_score,
                   sc.body::text AS content, sc.title, d.question_count, d.topic, d.time_per_question
            FROM v_duels d
            JOIN generated_content sc ON sc.id = d.content_id
            WHERE d.id = ?::uuid
              AND (d.challenger_id = ?::uuid OR d.opponent_id = ?::uuid)
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, duelId); ps.setString(2, userId); ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            String status = rs.getString("status");
            if ("finished".equals(status) || "declined".equals(status)) {
                throw new Exception("Este duelo ya terminó.");
            }
            boolean isChallenger = userId.equals(rs.getString("challenger_id"));
            if (scoreOrMinusOne(rs, isChallenger ? "challenger_score" : "opponent_score") >= 0) {
                throw new Exception("Ya jugaste este duelo.");
            }

            JsonObject result = new JsonObject();
            result.addProperty("title", rs.getString("title"));
            result.addProperty("topic", rs.getString("topic"));
            result.addProperty("questionCount", rs.getInt("question_count"));
            result.addProperty("timePerQuestion", rs.getInt("time_per_question"));

            JsonObject content = JsonParser.parseString(rs.getString("content")).getAsJsonObject();
            result.add("questions", content.has("questions") ? content.getAsJsonArray("questions") : new JsonArray());
            return result;
        }
    }

    @Override
    public boolean finishDuelWithWinner(String duelId, String winnerId) throws Exception {
        String sql = """
            UPDATE duels SET status = 'finished', winner_id = ?::uuid, finished_at = NOW()
            WHERE id = ?::uuid AND status != 'finished'
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, winnerId);
            ps.setString(2, duelId);
            return ps.executeUpdate() > 0;
        }
    }
}
