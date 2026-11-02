package com.project.dao.implementation;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.project.dao.interfaces.IGamificationDAO;
import com.project.database.DatabaseConnection;

/**
 * Implementación del DAO de gamificación.
 *
 * Lee los stats de v_user_stats y el progreso de v_user_mission_progress
 * (todo calculado); escribe intentos, respuestas, lo pagado por cada evento,
 * el uso de protectores y las misiones completadas.
 */
public class GamificationDAOImpl implements IGamificationDAO {

    private static final String STATS_SQL = """
        SELECT xp, level, coins, streak_current, streak_record,
               streak_last_activity::text, has_streak_shield
        FROM v_user_stats
        WHERE user_id = ?::uuid
        """;

    // ─── STATS ──────────────────────────────────────────────────────────────
    @Override
    public void lockUser(Connection conn, String userId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM users WHERE id = ?::uuid FOR UPDATE")) {
            stmt.setString(1, userId);
            if (!stmt.executeQuery().next()) throw new Exception("Usuario no encontrado: " + userId);
        }
    }

    @Override
    public JsonObject getStats(String userId) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection()) {
            return getStats(conn, userId);
        }
    }

    @Override
    public JsonObject getStats(Connection conn, String userId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(STATS_SQL)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return null;
            JsonObject stats = new JsonObject();
            stats.addProperty("xp",                 rs.getInt("xp"));
            stats.addProperty("level",              rs.getInt("level"));
            stats.addProperty("coins",              rs.getInt("coins"));
            stats.addProperty("streakCurrent",      rs.getInt("streak_current"));
            stats.addProperty("streakRecord",       rs.getInt("streak_record"));
            stats.addProperty("streakLastActivity", rs.getString("streak_last_activity"));
            stats.addProperty("hasStreakShield",    rs.getBoolean("has_streak_shield"));
            return stats;
        }
    }

    @Override
    public int[] getLevelThresholds() throws Exception {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT min_xp FROM levels ORDER BY level")) {
            ResultSet rs = stmt.executeQuery();
            List<Integer> list = new ArrayList<>();
            while (rs.next()) list.add(rs.getInt(1));
            return list.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    // ─── INTENTOS ───────────────────────────────────────────────────────────
    @Override
    public String createAttempt(Connection conn, String userId, String contentId,
                                String status, int timeTakenSeconds) throws Exception {
        String sql = """
            INSERT INTO attempts (user_id, content_id, status, started_at, finished_at)
            VALUES (?::uuid, ?::uuid, ?, now() - make_interval(secs => ?), now())
            RETURNING id
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, contentId);
            stmt.setString(3, status);
            stmt.setInt(4, Math.max(0, timeTakenSeconds));
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getString("id");
        }
    }

    @Override
    public boolean hasPreviousAttempt(Connection conn, String userId, String contentId,
                                      boolean completedOnly) throws Exception {
        String sql = "SELECT 1 FROM attempts WHERE user_id = ?::uuid AND content_id = ?::uuid"
                   + (completedOnly ? " AND status = 'completed'" : "") + " LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, contentId);
            return stmt.executeQuery().next();
        }
    }

    @Override
    public int[] saveGradedAnswers(Connection conn, String attemptId, String contentId,
                                   JsonArray answers, boolean flashcards) throws Exception {
        // Ítems del contenido por posición, y sus opciones por posición.
        Map<Integer, String> itemByPosition = new HashMap<>();
        Map<String, Map<Integer, String[]>> optionsByItem = new HashMap<>();   // {id, is_correct}
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT ci.id::text AS item_id, ci.position AS item_pos,
                       o.id::text AS option_id, o.position AS option_pos, o.is_correct
                FROM content_items ci
                LEFT JOIN item_options o ON o.item_id = ci.id
                WHERE ci.content_id = ?::uuid
                """)) {
            stmt.setString(1, contentId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String itemId = rs.getString("item_id");
                itemByPosition.put(rs.getInt("item_pos"), itemId);
                if (rs.getString("option_id") != null) {
                    optionsByItem.computeIfAbsent(itemId, k -> new HashMap<>())
                        .put(rs.getInt("option_pos"),
                             new String[]{ rs.getString("option_id"), String.valueOf(rs.getBoolean("is_correct")) });
                }
            }
        }
        if (itemByPosition.isEmpty()) {
            throw new IllegalArgumentException("Este contenido no tiene preguntas ni tarjetas para calificar.");
        }

        int correct = 0;
        Set<Integer> seen = new HashSet<>();
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO attempt_answers (attempt_id, item_id, selected_option_id, recall_rating)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?)
                """)) {
            for (JsonElement el : answers) {
                if (!el.isJsonObject()) throw new IllegalArgumentException("Respuesta con formato inválido.");
                JsonObject a = el.getAsJsonObject();
                if (!a.has("position") || a.get("position").isJsonNull())
                    throw new IllegalArgumentException("Falta position en una respuesta.");
                int position = a.get("position").getAsInt();
                String itemId = itemByPosition.get(position);
                if (itemId == null) throw new IllegalArgumentException("No existe la pregunta " + position + ".");
                if (!seen.add(position)) continue;   // la misma pregunta dos veces: cuenta la primera

                String optionId = null;
                Integer rating = null;
                if (flashcards) {
                    if (a.has("correct") && !a.get("correct").isJsonNull()) {
                        boolean knew = a.get("correct").getAsBoolean();
                        rating = knew ? 3 : 1;
                        if (knew) correct++;
                    }
                } else if (a.has("option") && !a.get("option").isJsonNull()) {
                    String[] option = optionsByItem.getOrDefault(itemId, Map.of()).get(a.get("option").getAsInt());
                    if (option == null) throw new IllegalArgumentException("Opción inválida en la pregunta " + position + ".");
                    optionId = option[0];
                    if (Boolean.parseBoolean(option[1])) correct++;
                }

                insert.setString(1, attemptId);
                insert.setString(2, itemId);
                insert.setString(3, optionId);
                if (rating != null) insert.setInt(4, rating); else insert.setNull(4, Types.SMALLINT);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        if (seen.isEmpty()) throw new IllegalArgumentException("No se recibió ninguna respuesta.");
        return new int[]{ correct, seen.size() };
    }

    @Override
    public void setAttemptRewards(Connection conn, String attemptId, int xp, int coins) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE attempts SET xp_awarded = ?, coins_awarded = ? WHERE id = ?::uuid")) {
            stmt.setInt(1, xp);
            stmt.setInt(2, coins);
            stmt.setString(3, attemptId);
            stmt.executeUpdate();
        }
    }

    @Override
    public void setDuelRewards(Connection conn, String duelId, String userId, int xp, int coins) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                UPDATE duel_participants SET xp_awarded = ?, coins_awarded = ?
                WHERE duel_id = ?::uuid AND user_id = ?::uuid
                """)) {
            stmt.setInt(1, xp);
            stmt.setInt(2, coins);
            stmt.setString(3, duelId);
            stmt.setString(4, userId);
            stmt.executeUpdate();
        }
    }

    // ─── RACHA ──────────────────────────────────────────────────────────────
    @Override
    public boolean useStreakShieldIfNeeded(Connection conn, String userId, LocalDate today) throws Exception {
        LocalDate yesterday = today.minusDays(1);
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM v_user_active_days WHERE user_id = ?::uuid AND day = ?) AS yesterday,
                       EXISTS (SELECT 1 FROM v_user_active_days WHERE user_id = ?::uuid AND day = ?) AS before
                """)) {
            stmt.setString(1, userId); stmt.setObject(2, yesterday);
            stmt.setString(3, userId); stmt.setObject(4, today.minusDays(2));
            ResultSet rs = stmt.executeQuery();
            rs.next();
            if (rs.getBoolean("yesterday") || !rs.getBoolean("before")) return false;
        }
        // Se gasta la unidad disponible más antigua.
        try (PreparedStatement stmt = conn.prepareStatement("""
                UPDATE user_consumables SET used_for_date = ?
                WHERE purchase_id = (
                    SELECT c.purchase_id FROM user_consumables c
                    JOIN purchases p ON p.id = c.purchase_id
                    WHERE p.user_id = ?::uuid AND c.used_for_date IS NULL
                    ORDER BY p.purchased_at LIMIT 1)
                """)) {
            stmt.setObject(1, yesterday);
            stmt.setString(2, userId);
            return stmt.executeUpdate() > 0;
        }
    }

    // ─── MISIONES ───────────────────────────────────────────────────────────
    @Override
    public void ensureMissions(Connection conn, String userId, LocalDate today, LocalDate weekStart) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO user_missions (user_id, mission_id, period_start)
                SELECT ?::uuid, id, CASE period WHEN 'daily' THEN ?::date ELSE ?::date END
                FROM missions WHERE is_active
                ON CONFLICT DO NOTHING
                """)) {
            stmt.setString(1, userId);
            stmt.setObject(2, today);
            stmt.setObject(3, weekStart);
            stmt.executeUpdate();
        }
    }

    @Override
    public JsonObject completeReachedMissions(Connection conn, String userId,
                                              LocalDate today, LocalDate weekStart) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray daily = new JsonArray(), weekly = new JsonArray();
        int dailyXp = 0, dailyCoins = 0, weeklyXp = 0, weeklyCoins = 0;

        String sql = """
            UPDATE user_missions um
            SET completed_at = now(), xp_awarded = p.xp_reward, coins_awarded = p.coin_reward
            FROM v_user_mission_progress p
            WHERE p.user_id = um.user_id AND p.mission_id = um.mission_id AND p.period_start = um.period_start
              AND um.user_id = ?::uuid AND um.completed_at IS NULL
              AND p.progress >= p.required_count
              AND ((p.period = 'daily' AND p.period_start = ?) OR (p.period = 'weekly' AND p.period_start = ?))
            RETURNING p.period, p.description, p.xp_reward, p.coin_reward
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setObject(2, today);
            stmt.setObject(3, weekStart);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JsonObject m = new JsonObject();
                m.addProperty("description", rs.getString("description"));
                m.addProperty("xpReward",    rs.getInt("xp_reward"));
                m.addProperty("coinReward",  rs.getInt("coin_reward"));
                if ("daily".equals(rs.getString("period"))) {
                    daily.add(m);  dailyXp += rs.getInt("xp_reward");  dailyCoins += rs.getInt("coin_reward");
                } else {
                    weekly.add(m); weeklyXp += rs.getInt("xp_reward"); weeklyCoins += rs.getInt("coin_reward");
                }
            }
        }

        result.add("completedMissions", daily);
        result.addProperty("missionXp",    dailyXp);
        result.addProperty("missionCoins", dailyCoins);
        result.add("completedObjectives", weekly);
        result.addProperty("objectiveXp",    weeklyXp);
        result.addProperty("objectiveCoins", weeklyCoins);
        return result;
    }
}
