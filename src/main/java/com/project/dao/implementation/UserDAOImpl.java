package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.project.dao.interfaces.IUserDAO;
import com.project.database.DatabaseConnection;
import com.project.model.users.DailyMission;
import com.project.model.users.Statistics;
import com.project.model.users.User;
import com.project.model.users.WeeklyObjective;

public class UserDAOImpl implements IUserDAO {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Connection conn() throws SQLException {
        return DatabaseConnection.getConnection();
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(UUID.fromString(rs.getString("id")));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setFullName(rs.getString("full_name"));
        u.setLanguage(rs.getString("language"));
        u.setCountry(rs.getString("country"));

        Date bd = rs.getDate("birthdate");
        if (bd != null) u.setBirthdate(bd.toLocalDate());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        return u;
    }

    private Statistics mapStats(ResultSet rs) throws SQLException {
        Statistics s = new Statistics();
        s.setUserId(UUID.fromString(rs.getString("user_id")));
        s.setXp(rs.getInt("xp"));
        s.setLevel(rs.getInt("level"));
        s.setCoins(rs.getInt("coins"));
        s.setStreakCurrent(rs.getInt("streak_current"));
        s.setStreakRecord(rs.getInt("streak_record"));
        s.setHasStreakShield(rs.getBoolean("has_streak_shield"));

        Date last = rs.getDate("streak_last_activity");
        if (last != null) s.setStreakLastActivity(last.toLocalDate());

        return s;
    }

    // ── Implementaciones ───────────────────────────────────────────────────────

    @Override
    public User register(User user) throws SQLException {
        String sqlUser = """
                INSERT INTO users (username, email, password_hash, full_name, language)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, username, email, password_hash, full_name, language, country, birthdate, created_at
                """;

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sqlUser)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getFullName());
            ps.setString(5, user.getLanguage() != null ? user.getLanguage() : "es");

            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new SQLException("No se pudo insertar el usuario.");

            User created = mapUser(rs);

            String sqlStats = "INSERT INTO user_stats (user_id) VALUES (?::uuid)";
            try (PreparedStatement ps2 = c.prepareStatement(sqlStats)) {
                ps2.setString(1, created.getId().toString());
                ps2.executeUpdate();
            }

            return created;
        }
    }

    @Override
    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT * FROM users WHERE email = ?";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?::uuid";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    @Override
    public Optional<Statistics> getStatsByUserId(UUID userId) throws SQLException {
        String sql = "SELECT * FROM user_stats WHERE user_id = ?::uuid";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapStats(rs));
            return Optional.empty();
        }
    }

    @Override
    public void updateStats(Statistics stats) throws SQLException {
        String sql = """
                UPDATE user_stats
                   SET xp = ?, level = ?, coins = ?,
                       streak_current = ?, streak_record = ?,
                       streak_last_activity = ?, has_streak_shield = ?
                 WHERE user_id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, stats.getXp());
            ps.setInt(2, stats.getLevel());
            ps.setInt(3, stats.getCoins());
            ps.setInt(4, stats.getStreakCurrent());
            ps.setInt(5, stats.getStreakRecord());

            LocalDate last = stats.getStreakLastActivity();
            ps.setDate(6, last != null ? Date.valueOf(last) : null);

            ps.setBoolean(7, stats.isHasStreakShield());
            ps.setString(8, stats.getUserId().toString());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean emailExists(String email) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE email = ?";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeQuery().next();
        }
    }

    @Override
    public boolean usernameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeQuery().next();
        }
    }

    @Override
    public void updateUser(User user) throws SQLException {
        String sql = """
                UPDATE users
                   SET full_name = ?, country = ?, language = ?, birthdate = ?
                 WHERE id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.getFullName());
            ps.setString(2, user.getCountry());
            ps.setString(3, user.getLanguage());
            ps.setObject(4, user.getBirthdate() != null ? Date.valueOf(user.getBirthdate()) : null);
            ps.setString(5, user.getId().toString());
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WEEKLY OBJECTIVES — auto-generate 4 if none exist for current week
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void ensureWeeklyObjectives(UUID userId) throws SQLException {
        LocalDate weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY);

        // ¿Ya existen para esta semana?
        String checkSql = "SELECT COUNT(*) FROM user_weekly_objectives WHERE user_id = ?::uuid AND week_start = ?";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ps.setDate(2, Date.valueOf(weekStart));
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) return; // ya hay objetivos esta semana
        }

        // Pool: {type, description, requiredCount, xpReward, coinReward}
        String[][] pool = {
            {"streak_days",        "Mantener racha por 3 d\u00edas",       "3",  "25", "20"},
            {"streak_days",        "Mantener racha por 5 d\u00edas",       "5",  "50", "40"},
            {"activities",         "Completar 5 actividades",         "5",  "30", "25"},
            {"activities",         "Completar 10 actividades",       "10",  "60", "50"},
            {"duels_won",          "Ganar 2 duelos",                  "2",  "40", "30"},
            {"perfect_exam",       "Obtener 100% en un examen",       "1",  "35", "25"},
            {"complete_flashcard", "Estudiar 3 sets de flashcards",   "3",  "30", "20"},
            {"complete_quiz",      "Completar 3 quizzes",             "3",  "30", "25"},
            {"complete_summary",   "Generar 3 res\u00famenes",             "3",  "25", "20"},
            {"win_duel",           "Ganar 3 duelos",                  "3",  "55", "40"},
        };

        // Shuffle y tomar 4
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < pool.length; i++) indices.add(i);
        Collections.shuffle(indices);

        String insertSql = """
                INSERT INTO user_weekly_objectives
                    (user_id, type, week_start, objective_description,
                     required_count, progress, completed, xp_reward, coin_reward)
                VALUES (?::uuid, ?, ?, ?, ?, 0, false, ?, ?)
                """;

        try (Connection c = conn()) {
            for (int i = 0; i < 4; i++) {
                String[] obj = pool[indices.get(i)];
                try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                    ps.setString(1, userId.toString());
                    ps.setString(2, obj[0]);
                    ps.setDate(3, Date.valueOf(weekStart));
                    ps.setString(4, obj[1]);
                    ps.setInt(5, Integer.parseInt(obj[2]));
                    ps.setInt(6, Integer.parseInt(obj[3]));
                    ps.setInt(7, Integer.parseInt(obj[4]));
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public List<WeeklyObjective> getWeeklyObjectives(UUID userId) throws SQLException {
        LocalDate weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        String sql = """
                SELECT user_id, type, week_start, objective_description,
                       required_count, progress, completed, xp_reward, coin_reward
                  FROM user_weekly_objectives
                 WHERE user_id = ?::uuid AND week_start = ?
                 ORDER BY completed ASC
                """;
        List<WeeklyObjective> list = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ps.setDate(2, Date.valueOf(weekStart));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                WeeklyObjective obj = new WeeklyObjective();
                obj.setUserId(UUID.fromString(rs.getString("user_id")));
                obj.setType(rs.getString("type"));
                obj.setWeekStart(rs.getDate("week_start").toLocalDate());
                obj.setObjectiveDescription(rs.getString("objective_description"));
                obj.setRequiredCount(rs.getInt("required_count"));
                obj.setProgress(rs.getInt("progress"));
                obj.setCompleted(rs.getBoolean("completed"));
                obj.setXpReward(rs.getInt("xp_reward"));
                obj.setCoinReward(rs.getInt("coin_reward"));
                list.add(obj);
            }
        }
        return list;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY MISSIONS — auto-generate 3 from missions catalog if none for today
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void ensureDailyMissions(UUID userId) throws SQLException {
        LocalDate today = LocalDate.now();

        // ¿Ya existen para hoy?
        String checkSql = "SELECT COUNT(*) FROM user_daily_missions WHERE user_id = ?::uuid AND date = ?";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ps.setDate(2, Date.valueOf(today));
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) return; // ya hay misiones hoy
        }

        // Tomar 3 misiones aleatorias del catálogo (missions table)
        String selectSql = "SELECT id FROM missions ORDER BY RANDOM() LIMIT 3";
        List<Integer> missionIds = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(selectSql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                missionIds.add(rs.getInt("id"));
            }
        }

        if (missionIds.isEmpty()) {
            System.err.println("[ensureDailyMissions] Tabla missions vacía. No se pueden generar misiones.");
            return;
        }

        // Insertar para el usuario
        String insertSql = """
                INSERT INTO user_daily_missions (user_id, mission_id, date, progress, completed)
                VALUES (?::uuid, ?, ?, 0, false)
                """;
        try (Connection c = conn()) {
            for (int missionId : missionIds) {
                try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                    ps.setString(1, userId.toString());
                    ps.setInt(2, missionId);
                    ps.setDate(3, Date.valueOf(today));
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public List<DailyMission> getDailyMissions(UUID userId) throws SQLException {
        String sql = """
                SELECT udm.user_id, udm.mission_id, udm.date, udm.progress, udm.completed,
                       m.description, m.type, m.required_count, m.xp_reward, m.coin_reward
                  FROM user_daily_missions udm
                  JOIN missions m ON udm.mission_id = m.id
                 WHERE udm.user_id = ?::uuid AND udm.date = ?
                 ORDER BY udm.completed ASC
                """;
        List<DailyMission> list = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ps.setDate(2, Date.valueOf(LocalDate.now()));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                DailyMission dm = new DailyMission();
                dm.setUserId(UUID.fromString(rs.getString("user_id")));
                dm.setMissionId(rs.getInt("mission_id"));
                dm.setDate(rs.getDate("date").toLocalDate());
                dm.setProgress(rs.getInt("progress"));
                dm.setCompleted(rs.getBoolean("completed"));
                dm.setDescription(rs.getString("description"));
                dm.setType(rs.getString("type"));
                dm.setRequiredCount(rs.getInt("required_count"));
                dm.setXpReward(rs.getInt("xp_reward"));
                dm.setCoinReward(rs.getInt("coin_reward"));
                list.add(dm);
            }
        }
        return list;
    }
}