package com.project.dao.implementation;

import java.sql.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.project.dao.interfaces.IGamificationDAO;
import com.project.database.DatabaseConnection;

/**
 * Implementación del DAO de gamificación.
 * Habla con user_stats, attempts, attempt_answers, user_daily_missions,
 * user_weekly_objectives en Supabase.
 *
 * Salvo getStats(String), todos los métodos trabajan sobre la Connection que
 * reciben (la transacción de GamificationService.processActivity) y no la cierran.
 */
public class GamificationDAOImpl implements IGamificationDAO {

    private static final String STATS_SQL = """
        SELECT xp, level, coins, streak_current, streak_record,
               streak_last_activity::text, has_streak_shield
        FROM user_stats
        WHERE user_id = ?::uuid
        """;

    // ─── GET STATS ──────────────────────────────────────────────────────────
    @Override
    public JsonObject getStats(String userId) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(STATS_SQL)) {
            stmt.setString(1, userId);
            return mapStats(stmt.executeQuery());
        }
    }

    @Override
    public JsonObject getStatsForUpdate(Connection conn, String userId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(STATS_SQL + " FOR UPDATE")) {
            stmt.setString(1, userId);
            return mapStats(stmt.executeQuery());
        }
    }

    private JsonObject mapStats(ResultSet rs) throws SQLException {
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

    // ─── UPDATE STATS ───────────────────────────────────────────────────────
    @Override
    public boolean updateStats(Connection conn, String userId, int xp, int level, int coins,
                               int streak, int streakRecord, String lastActivity,
                               boolean shield) throws Exception {
        String sql = """
            UPDATE user_stats
            SET xp = ?, level = ?, coins = ?,
                streak_current = ?, streak_record = ?,
                streak_last_activity = ?::date,
                has_streak_shield = ?
            WHERE user_id = ?::uuid
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, xp);
            stmt.setInt(2, level);
            stmt.setInt(3, coins);
            stmt.setInt(4, streak);
            stmt.setInt(5, streakRecord);
            stmt.setString(6, lastActivity);
            stmt.setBoolean(7, shield);
            stmt.setString(8, userId);

            return stmt.executeUpdate() > 0;
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
                if (rating != null) insert.setInt(4, rating); else insert.setNull(4, java.sql.Types.SMALLINT);
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

    // ─── ADVANCE DAILY MISSIONS ─────────────────────────────────────────────
    @Override
    public int advanceDailyMissions(Connection conn, String userId, String missionType,
                                    LocalDate today) throws Exception {
        // Incrementa progress en misiones del día que coincidan con el tipo
        // y que aún no estén completadas.
        // [BUG] Antes usaba CURRENT_DATE (zona horaria del servidor de BD,
        // típicamente UTC) en vez de recibir la fecha ya calculada en la
        // zona horaria de la app — podía no coincidir con la fecha con la
        // que ensureDailyMissions() había insertado la misión de "hoy".
        String sql = """
            UPDATE user_daily_missions udm
            SET progress = progress + 1
            FROM missions m
            WHERE udm.mission_id = m.id
              AND udm.user_id = ?::uuid
              AND udm.date = ?
              AND udm.completed = false
              AND m.type = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setDate(2, java.sql.Date.valueOf(today));
            stmt.setString(3, missionType);

            return stmt.executeUpdate();
        }
    }

    // ─── ADVANCE WEEKLY OBJECTIVES ──────────────────────────────────────────
    @Override
    public int advanceWeeklyObjectives(Connection conn, String userId, String objectiveType,
                                       LocalDate weekStart) throws Exception {
        String sql = """
            UPDATE user_weekly_objectives
            SET progress = progress + 1
            WHERE user_id = ?::uuid
              AND week_start = ?
              AND completed = false
              AND type = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setDate(2, java.sql.Date.valueOf(weekStart));
            stmt.setString(3, objectiveType);

            return stmt.executeUpdate();
        }
    }

    // ─── CHECK COMPLETED MISSIONS ───────────────────────────────────────────
    @Override
    public JsonObject checkCompletedMissions(Connection conn, String userId,
                                             LocalDate today) throws Exception {
        // Marca como completadas las misiones que alcanzaron su required_count
        // y devuelve sus rewards
        String sql = """
            UPDATE user_daily_missions udm
            SET completed = true
            FROM missions m
            WHERE udm.mission_id = m.id
              AND udm.user_id = ?::uuid
              AND udm.date = ?
              AND udm.completed = false
              AND udm.progress >= m.required_count
            RETURNING m.description, m.xp_reward, m.coin_reward
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setDate(2, java.sql.Date.valueOf(today));
            ResultSet rs = stmt.executeQuery();

            JsonObject result = new JsonObject();
            JsonArray completed = new JsonArray();
            int totalXp = 0, totalCoins = 0;

            while (rs.next()) {
                JsonObject mission = new JsonObject();
                mission.addProperty("description", rs.getString("description"));
                mission.addProperty("xpReward",    rs.getInt("xp_reward"));
                mission.addProperty("coinReward",  rs.getInt("coin_reward"));
                completed.add(mission);
                totalXp    += rs.getInt("xp_reward");
                totalCoins += rs.getInt("coin_reward");
            }

            result.add("completedMissions", completed);
            result.addProperty("missionXp",    totalXp);
            result.addProperty("missionCoins", totalCoins);
            return result;
        }
    }

    // ─── CHECK COMPLETED OBJECTIVES ─────────────────────────────────────────
    @Override
    public JsonObject checkCompletedObjectives(Connection conn, String userId,
                                               LocalDate weekStart) throws Exception {
        String sql = """
            UPDATE user_weekly_objectives
            SET completed = true
            WHERE user_id = ?::uuid
              AND week_start = ?
              AND completed = false
              AND progress >= required_count
            RETURNING objective_description, xp_reward, coin_reward
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setDate(2, java.sql.Date.valueOf(weekStart));
            ResultSet rs = stmt.executeQuery();

            JsonObject result = new JsonObject();
            JsonArray completed = new JsonArray();
            int totalXp = 0, totalCoins = 0;

            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("description", rs.getString("objective_description"));
                obj.addProperty("xpReward",    rs.getInt("xp_reward"));
                obj.addProperty("coinReward",  rs.getInt("coin_reward"));
                completed.add(obj);
                totalXp    += rs.getInt("xp_reward");
                totalCoins += rs.getInt("coin_reward");
            }

            result.add("completedObjectives", completed);
            result.addProperty("objectiveXp",    totalXp);
            result.addProperty("objectiveCoins", totalCoins);
            return result;
        }
    }
}
