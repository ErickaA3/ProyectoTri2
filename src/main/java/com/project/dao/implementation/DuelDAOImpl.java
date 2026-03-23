package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
        // Buscar al usuario por email o username
        String findUser = """
            SELECT id FROM users
            WHERE email = ? OR username = ?
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

        // Verificar que no exista ya
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

        // Crear solicitud
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
            LEFT JOIN user_stats s ON s.user_id = u.id
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
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String createDuel(String challengerId, String opponentId, String contentId,
                             String topic, int questionCount, int timePerQuestion) throws Exception {
        String sql = """
            INSERT INTO duels (challenger_id, opponent_id, content_id, topic, question_count, time_per_question, status)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 'waiting_opponent')
            RETURNING id
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, challengerId);
            ps.setString(2, opponentId);
            ps.setString(3, contentId);
            ps.setString(4, topic);
            ps.setInt(5, questionCount);
            ps.setInt(6, timePerQuestion);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("id");
            throw new Exception("No se pudo crear el duelo.");
        }
    }

    @Override
    public JsonObject getDuel(String duelId, String userId) throws Exception {
        String sql = """
            SELECT d.*,
                   c.username AS challenger_name, c_s.level AS challenger_level,
                   o.username AS opponent_name, o_s.level AS opponent_level
            FROM duels d
            JOIN users c ON c.id = d.challenger_id
            JOIN users o ON o.id = d.opponent_id
            LEFT JOIN user_stats c_s ON c_s.user_id = d.challenger_id
            LEFT JOIN user_stats o_s ON o_s.user_id = d.opponent_id
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
            d.addProperty("challengerScore", rs.getBigDecimal("challenger_score") != null ? rs.getBigDecimal("challenger_score").intValue() : -1);
            d.addProperty("opponentScore",   rs.getBigDecimal("opponent_score") != null ? rs.getBigDecimal("opponent_score").intValue() : -1);
            d.addProperty("challengerTime",  rs.getInt("challenger_time"));
            d.addProperty("opponentTime",    rs.getInt("opponent_time"));
            d.addProperty("winnerId",        rs.getString("winner_id"));
            d.addProperty("createdAt",       rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null);

            // Determinar rol del usuario
            d.addProperty("isChallenger", userId.equals(rs.getString("challenger_id")));

            return d;
        }
    }

    @Override
    public JsonArray getActiveDuels(String userId) throws Exception {
        String sql = """
            SELECT d.id, d.topic, d.question_count, d.status, d.created_at::text,
                   d.challenger_id, d.opponent_id,
                   d.challenger_score, d.opponent_score,
                   c.username AS challenger_name, c_s.level AS challenger_level,
                   o.username AS opponent_name, o_s.level AS opponent_level
            FROM duels d
            JOIN users c ON c.id = d.challenger_id
            JOIN users o ON o.id = d.opponent_id
            LEFT JOIN user_stats c_s ON c_s.user_id = d.challenger_id
            LEFT JOIN user_stats o_s ON o_s.user_id = d.opponent_id
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

                // ¿Este usuario ya jugó?
                boolean isChallenger = userId.equals(rs.getString("challenger_id"));
                boolean hasPlayed = isChallenger
                    ? rs.getBigDecimal("challenger_score") != null
                    : rs.getBigDecimal("opponent_score") != null;
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
                   c.username AS challenger_name, o.username AS opponent_name,
                   d.challenger_id, d.opponent_id
            FROM duels d
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

                // Resultado para este usuario
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
              AND (challenger_id = ?::uuid OR opponent_id = ?::uuid)
              AND status IN ('waiting_opponent', 'in_progress')
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, duelId); ps.setString(2, userId); ps.setString(3, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public JsonObject submitDuelResult(String duelId, String userId, int score,
                                       int maxScore, int timeSecs, String answersJson) throws Exception {
        Connection conn = DatabaseConnection.getConnection();
        try {
            conn.setAutoCommit(false);

            // 1. Obtener info del duelo
            String getDuel = """
                SELECT challenger_id, opponent_id, status, question_count,
                       challenger_score, opponent_score
                FROM duels WHERE id = ?::uuid
                FOR UPDATE
                """;
            String challengerId, opponentId, status;
            int questionCount;
            boolean challengerPlayed, opponentPlayed;

            try (PreparedStatement ps = conn.prepareStatement(getDuel)) {
                ps.setString(1, duelId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { conn.rollback(); throw new Exception("Duelo no encontrado."); }
                challengerId    = rs.getString("challenger_id");
                opponentId      = rs.getString("opponent_id");
                status          = rs.getString("status");
                questionCount   = rs.getInt("question_count");
                challengerPlayed = rs.getBigDecimal("challenger_score") != null;
                opponentPlayed   = rs.getBigDecimal("opponent_score") != null;
            }

            // Validar que el duelo esté activo
            if ("finished".equals(status) || "declined".equals(status)) {
                conn.rollback();
                throw new Exception("Este duelo ya terminó.");
            }

            boolean isChallenger = userId.equals(challengerId);

            // Validar que no haya jugado ya
            if ((isChallenger && challengerPlayed) || (!isChallenger && opponentPlayed)) {
                conn.rollback();
                throw new Exception("Ya jugaste este duelo.");
            }

            // Validar que el usuario sea participante
            if (!userId.equals(challengerId) && !userId.equals(opponentId)) {
                conn.rollback();
                throw new Exception("No eres participante de este duelo.");
            }

            // 2. Guardar respuestas individuales
            JsonArray answers = JsonParser.parseString(answersJson).getAsJsonArray();
            String insertAnswer = """
                INSERT INTO duel_answers (duel_id, user_id, question_index, answer_given, is_correct, time_ms)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(insertAnswer)) {
                for (int i = 0; i < answers.size(); i++) {
                    JsonObject a = answers.get(i).getAsJsonObject();
                    ps.setString(1, duelId);
                    ps.setString(2, userId);
                    ps.setInt(3, a.get("questionIndex").getAsInt());
                    ps.setString(4, a.has("answerGiven") ? a.get("answerGiven").getAsString() : null);
                    ps.setBoolean(5, a.get("isCorrect").getAsBoolean());
                    ps.setInt(6, a.has("timeMs") ? a.get("timeMs").getAsInt() : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // 3. Actualizar score del jugador
            String updateScore = isChallenger
                ? "UPDATE duels SET challenger_score = ?, challenger_time = ? WHERE id = ?::uuid"
                : "UPDATE duels SET opponent_score = ?, opponent_time = ? WHERE id = ?::uuid";

            try (PreparedStatement ps = conn.prepareStatement(updateScore)) {
                ps.setInt(1, score);
                ps.setInt(2, timeSecs);
                ps.setString(3, duelId);
                ps.executeUpdate();
            }

            // 4. ¿Ambos ya jugaron? → declarar ganador. ¿Solo uno? → marcar in_progress
            boolean otherPlayed = isChallenger ? opponentPlayed : challengerPlayed;
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("score", score);
            result.addProperty("maxScore", maxScore);
            result.addProperty("timeSecs", timeSecs);

            if (otherPlayed) {
                // ── AMBOS JUGARON → determinar ganador ──
                String getOther = isChallenger
                    ? "SELECT opponent_score, opponent_time FROM duels WHERE id = ?::uuid"
                    : "SELECT challenger_score, challenger_time FROM duels WHERE id = ?::uuid";

                int otherScore, otherTime;
                try (PreparedStatement ps = conn.prepareStatement(getOther)) {
                    ps.setString(1, duelId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    otherScore = rs.getInt(1);
                    otherTime  = rs.getInt(2);
                }

                // Determinar ganador
                String winnerId = null;
                String resultType;
                if (score > otherScore) {
                    winnerId = userId;
                    resultType = "win";
                } else if (score < otherScore) {
                    winnerId = isChallenger ? opponentId : challengerId;
                    resultType = "loss";
                } else {
                    // Empate en score → gana el más rápido
                    if (timeSecs < otherTime) {
                        winnerId = userId;
                        resultType = "win";
                    } else if (timeSecs > otherTime) {
                        winnerId = isChallenger ? opponentId : challengerId;
                        resultType = "loss";
                    } else {
                        resultType = "draw"; // Empate total
                    }
                }

                // Actualizar duelo como terminado
                String finish = """
                    UPDATE duels SET status = 'finished', winner_id = ?::uuid, finished_at = NOW()
                    WHERE id = ?::uuid
                    """;
                try (PreparedStatement ps = conn.prepareStatement(finish)) {
                    ps.setString(1, winnerId);
                    ps.setString(2, duelId);
                    ps.executeUpdate();
                }

                result.addProperty("duelFinished", true);
                result.addProperty("result", resultType);
                result.addProperty("winnerId", winnerId);
                result.addProperty("otherScore", otherScore);
                result.addProperty("otherTime", otherTime);
            } else {
                // ── PRIMER JUGADOR → actualizar status a in_progress ──
                String updateStatus = "UPDATE duels SET status = 'in_progress' WHERE id = ?::uuid";
                try (PreparedStatement ps = conn.prepareStatement(updateStatus)) {
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
            conn.close();
        }
    }

    @Override
    public JsonObject getDuelQuestions(String duelId, String userId) throws Exception {
        // Primero verificar que el usuario no haya jugado ya
        String checkPlayed = """
            SELECT challenger_id, opponent_id, challenger_score, opponent_score, status
            FROM duels WHERE id = ?::uuid
              AND (challenger_id = ?::uuid OR opponent_id = ?::uuid)
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkPlayed)) {
            ps.setString(1, duelId); ps.setString(2, userId); ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            String status = rs.getString("status");
            if ("finished".equals(status) || "declined".equals(status)) {
                throw new Exception("Este duelo ya terminó.");
            }

            boolean isChallenger = userId.equals(rs.getString("challenger_id"));
            boolean alreadyPlayed = isChallenger
                ? rs.getBigDecimal("challenger_score") != null
                : rs.getBigDecimal("opponent_score") != null;

            if (alreadyPlayed) {
                throw new Exception("Ya jugaste este duelo.");
            }
        }

        // Obtener preguntas
        String sql = """
            SELECT sc.content::text, sc.title, d.question_count, d.topic, d.time_per_question
            FROM duels d
            JOIN study_content sc ON sc.id = d.content_id
            WHERE d.id = ?::uuid
              AND (d.challenger_id = ?::uuid OR d.opponent_id = ?::uuid)
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, duelId); ps.setString(2, userId); ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            JsonObject result = new JsonObject();
            result.addProperty("title", rs.getString("title"));
            result.addProperty("topic", rs.getString("topic"));
            result.addProperty("questionCount", rs.getInt("question_count"));
            result.addProperty("timePerQuestion", rs.getInt("time_per_question"));

            String contentJson = rs.getString("content");
            JsonObject content = JsonParser.parseString(contentJson).getAsJsonObject();
            result.add("questions", content.has("questions") ? content.getAsJsonArray("questions") : new JsonArray());

            return result;
        }
    }
}