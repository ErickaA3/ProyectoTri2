package com.project.dao.implementation;

import java.sql.*;
import java.time.LocalDate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.dao.interfaces.IGamificationDAO;
import com.project.database.DatabaseConnection;

/**
 * Implementación del DAO de gamificación.
 * Habla con user_stats, activity_results, user_daily_missions,
 * user_weekly_objectives en Supabase.
 */
public class GamificationDAOImpl implements IGamificationDAO {

    // ─── GET STATS ──────────────────────────────────────────────────────────
    @Override
    public JsonObject getStats(String userId) throws Exception {
        String sql = """
            SELECT xp, level, coins, streak_current, streak_record,
                   streak_last_activity::text, has_streak_shield
            FROM user_stats
            WHERE user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                JsonObject stats = new JsonObject();
                stats.addProperty("xp",                rs.getInt("xp"));
                stats.addProperty("level",             rs.getInt("level"));
                stats.addProperty("coins",             rs.getInt("coins"));
                stats.addProperty("streakCurrent",     rs.getInt("streak_current"));
                stats.addProperty("streakRecord",      rs.getInt("streak_record"));
                stats.addProperty("streakLastActivity", rs.getString("streak_last_activity"));
                stats.addProperty("hasStreakShield",    rs.getBoolean("has_streak_shield"));
                return stats;
            }
            return null;
        }
    }

    // ─── UPDATE STATS ───────────────────────────────────────────────────────
    @Override
    public boolean updateStats(String userId, int xp, int level, int coins,
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

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

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

    // ─── SAVE ACTIVITY RESULT ───────────────────────────────────────────────
    @Override
    public String saveActivityResult(String userId, String contentId,
                                     double score, double maxScore,
                                     int timeTakenSeconds) throws Exception {
        String sql = """
            INSERT INTO activity_results
                (user_id, content_id, score, max_score, time_taken_seconds)
            VALUES
                (?::uuid, ?::uuid, ?, ?, ?)
            RETURNING id
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, contentId);
            stmt.setDouble(3, score);
            stmt.setDouble(4, maxScore);
            stmt.setInt(5, timeTakenSeconds);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
            throw new Exception("No se pudo guardar el resultado de actividad.");
        }
    }

    // ─── ADVANCE DAILY MISSIONS ─────────────────────────────────────────────
    @Override
    public int advanceDailyMissions(String userId, String missionType) throws Exception {
        // Incrementa progress en misiones del día que coincidan con el tipo
        // y que aún no estén completadas
        String sql = """
            UPDATE user_daily_missions udm
            SET progress = progress + 1
            FROM missions m
            WHERE udm.mission_id = m.id
              AND udm.user_id = ?::uuid
              AND udm.date = CURRENT_DATE
              AND udm.completed = false
              AND m.type = ?
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, missionType);

            return stmt.executeUpdate();
        }
    }

    // ─── ADVANCE WEEKLY OBJECTIVES ──────────────────────────────────────────
    @Override
    public int advanceWeeklyObjectives(String userId, String objectiveType) throws Exception {
        String sql = """
            UPDATE user_weekly_objectives
            SET progress = progress + 1
            WHERE user_id = ?::uuid
              AND week_start = date_trunc('week', CURRENT_DATE)::date
              AND completed = false
              AND type = ?
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, objectiveType);

            return stmt.executeUpdate();
        }
    }

    // ─── CHECK COMPLETED MISSIONS ───────────────────────────────────────────
    @Override
    public JsonObject checkCompletedMissions(String userId) throws Exception {
        // Marca como completadas las misiones que alcanzaron su required_count
        // y devuelve sus rewards
        String sql = """
            UPDATE user_daily_missions udm
            SET completed = true
            FROM missions m
            WHERE udm.mission_id = m.id
              AND udm.user_id = ?::uuid
              AND udm.date = CURRENT_DATE
              AND udm.completed = false
              AND udm.progress >= m.required_count
            RETURNING m.description, m.xp_reward, m.coin_reward
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
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
    public JsonObject checkCompletedObjectives(String userId) throws Exception {
        String sql = """
            UPDATE user_weekly_objectives
            SET completed = true
            WHERE user_id = ?::uuid
              AND week_start = date_trunc('week', CURRENT_DATE)::date
              AND completed = false
              AND progress >= required_count
            RETURNING objective_description, xp_reward, coin_reward
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
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