package com.project.dao.implementation;

import com.project.dao.interfaces.IUserDAO;
import com.project.database.DatabaseConnection;
import com.project.model.users.*;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class UserDAOImpl implements IUserDAO {

    private Connection conn() throws SQLException {
        return DatabaseConnection.getConnection();
    }

    // ═══════════════════════════════════════════════════════
    // MAPEO
    // ═══════════════════════════════════════════════════════

    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(UUID.fromString(rs.getString("id")));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));

        try { u.setFullName(rs.getString("full_name")); }
        catch (SQLException ignored) {}
        try { u.setLanguage(rs.getString("language")); }
        catch (SQLException ignored) {}
        try { u.setCountry(rs.getString("country")); }
        catch (SQLException ignored) {}
        try {
            Date bd = rs.getDate("birthdate");
            if (bd != null) u.setBirthdate(bd.toLocalDate());
        } catch (SQLException ignored) {}

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        return u;
    }

    // ═══════════════════════════════════════════════════════
    // REGISTRO
    // ═══════════════════════════════════════════════════════

    @Override
    public User register(User user) throws SQLException {
        String sqlUser = """
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, ?)
                RETURNING id, username, email, password_hash, created_at
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sqlUser)) {

            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());

            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new SQLException("No se pudo insertar el usuario.");
            User created = mapUser(rs);

            // Perfil en clientes
            String sqlCliente = """
                    INSERT INTO clientes (user_id, full_name, language)
                    VALUES (?::uuid, ?, ?)
                    """;
            try (PreparedStatement ps2 = c.prepareStatement(sqlCliente)) {
                ps2.setString(1, created.getId().toString());
                ps2.setString(2, user.getFullName());
                ps2.setString(3, user.getLanguage() != null ? user.getLanguage() : "es");
                ps2.executeUpdate();
            }

            // Stats vacíos
            String sqlStats = "INSERT INTO user_stats (user_id) VALUES (?::uuid)";
            try (PreparedStatement ps3 = c.prepareStatement(sqlStats)) {
                ps3.setString(1, created.getId().toString());
                ps3.executeUpdate();
            }

            created.setFullName(user.getFullName());
            created.setLanguage(user.getLanguage() != null ? user.getLanguage() : "es");
            return created;
        }
    }

    // ═══════════════════════════════════════════════════════
    // BÚSQUEDA
    // ═══════════════════════════════════════════════════════

    @Override
    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = """
                SELECT u.*, c.full_name, c.country, c.language, c.birthdate
                FROM users u
                LEFT JOIN clientes c ON c.user_id = u.id
                WHERE u.email = ?
                """;
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
        String sql = """
                SELECT u.*, c.full_name, c.country, c.language, c.birthdate
                FROM users u
                LEFT JOIN clientes c ON c.user_id = u.id
                WHERE u.id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findByUsername(String username) throws SQLException {
        String sql = """
                SELECT u.*, c.full_name, c.country, c.language, c.birthdate
                FROM users u
                LEFT JOIN clientes c ON c.user_id = u.id
                WHERE u.username = ?
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════
    // ACTUALIZACIÓN DE PERFIL
    // ═══════════════════════════════════════════════════════

    @Override
    public void updateUser(User user) throws SQLException {
        String sqlUser = """
                UPDATE users SET username = ? WHERE id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sqlUser)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getId().toString());
            ps.executeUpdate();
        }

        String sqlCliente = """
                UPDATE clientes
                   SET full_name = ?, country = ?, language = ?, birthdate = ?
                 WHERE user_id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sqlCliente)) {
            ps.setString(1, user.getFullName());
            ps.setString(2, user.getCountry());
            ps.setString(3, user.getLanguage());
            ps.setObject(4, user.getBirthdate() != null
                    ? Date.valueOf(user.getBirthdate()) : null);
            ps.setString(5, user.getId().toString());
            ps.executeUpdate();
        }
    }

    // ═══════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════

    @Override
    public Optional<Statistics> getStatsByUserId(UUID userId) throws SQLException {
        String sql = """
                SELECT user_id, xp, level, coins,
                       streak_current, streak_record,
                       streak_last_activity, has_streak_shield
                FROM user_stats WHERE user_id = ?::uuid
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();

            Statistics s = new Statistics();
            s.setUserId(UUID.fromString(rs.getString("user_id")));
            s.setXp(rs.getInt("xp"));
            s.setLevel(rs.getInt("level"));
            s.setCoins(rs.getInt("coins"));
            s.setStreakCurrent(rs.getInt("streak_current"));
            s.setStreakRecord(rs.getInt("streak_record"));
            s.setHasStreakShield(rs.getBoolean("has_streak_shield"));
            Date lsa = rs.getDate("streak_last_activity");
            if (lsa != null) s.setStreakLastActivity(lsa.toLocalDate());
            return Optional.of(s);
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
            ps.setObject(6, stats.getStreakLastActivity() != null
                    ? Date.valueOf(stats.getStreakLastActivity()) : null);
            ps.setBoolean(7, stats.isHasStreakShield());
            ps.setString(8, stats.getUserId().toString());
            ps.executeUpdate();
        }
    }

    // ═══════════════════════════════════════════════════════
    // EXISTENCIA
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // MISIONES DIARIAS
    // ═══════════════════════════════════════════════════════

    @Override
    public List<DailyMission> getDailyMissions(UUID userId) throws SQLException {
        String sql = """
                SELECT m.id AS mission_id, m.description, m.type, m.required_count,
                       m.xp_reward, m.coin_reward,
                       udm.progress, udm.completed, udm.date
                FROM user_daily_missions udm
                JOIN missions m ON m.id = udm.mission_id
                WHERE udm.user_id = ?::uuid AND udm.date = CURRENT_DATE
                ORDER BY udm.completed ASC, m.id ASC
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            List<DailyMission> list = new ArrayList<>();
            while (rs.next()) {
                DailyMission dm = new DailyMission();
                dm.setMissionId(rs.getInt("mission_id"));
                dm.setDescription(rs.getString("description"));
                dm.setType(rs.getString("type"));
                dm.setRequiredCount(rs.getInt("required_count"));
                dm.setXpReward(rs.getInt("xp_reward"));
                dm.setCoinReward(rs.getInt("coin_reward"));
                dm.setProgress(rs.getInt("progress"));
                dm.setCompleted(rs.getBoolean("completed"));
                Date d = rs.getDate("date");
                if (d != null) dm.setDate(d.toLocalDate());
                list.add(dm);
            }
            return list;
        }
    }

    @Override
    public void ensureDailyMissions(UUID userId) throws SQLException {
        String checkSql = """
                SELECT COUNT(*) FROM user_daily_missions
                WHERE user_id = ?::uuid AND date = CURRENT_DATE
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        String insertSql = """
                INSERT INTO user_daily_missions (user_id, mission_id, date)
                SELECT ?::uuid, id, CURRENT_DATE FROM missions
                ON CONFLICT DO NOTHING
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setString(1, userId.toString());
            ps.executeUpdate();
        }
    }

    // ═══════════════════════════════════════════════════════
    // OBJETIVOS SEMANALES
    // ═══════════════════════════════════════════════════════

    @Override
    public List<WeeklyObjective> getWeeklyObjectives(UUID userId) throws SQLException {
        String sql = """
                SELECT type, week_start, objective_description,
                       required_count, progress, completed,
                       xp_reward, coin_reward
                FROM user_weekly_objectives
                WHERE user_id = ?::uuid
                  AND week_start = date_trunc('week', CURRENT_DATE)::date
                ORDER BY completed ASC, type ASC
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            List<WeeklyObjective> list = new ArrayList<>();
            while (rs.next()) {
                WeeklyObjective wo = new WeeklyObjective();
                wo.setType(rs.getString("type"));
                wo.setObjectiveDescription(rs.getString("objective_description"));
                wo.setRequiredCount(rs.getInt("required_count"));
                wo.setProgress(rs.getInt("progress"));
                wo.setCompleted(rs.getBoolean("completed"));
                wo.setXpReward(rs.getInt("xp_reward"));
                wo.setCoinReward(rs.getInt("coin_reward"));
                Date ws = rs.getDate("week_start");
                if (ws != null) wo.setWeekStart(ws.toLocalDate());
                list.add(wo);
            }
            return list;
        }
    }

    @Override
    public void ensureWeeklyObjectives(UUID userId) throws SQLException {
        // Verificar si ya tiene objetivos esta semana
        String checkSql = """
                SELECT COUNT(*) FROM user_weekly_objectives
                WHERE user_id = ?::uuid
                AND week_start = date_trunc('week', CURRENT_DATE)::date
                """;
        try (Connection c = conn();
            PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        // Insertar desde el catálogo objectives 
        String insertSql = """
                INSERT INTO user_weekly_objectives
                    (user_id, type, week_start, objective_description,
                    required_count, xp_reward, coin_reward)
                SELECT
                    ?::uuid,
                    o.type,
                    date_trunc('week', CURRENT_DATE)::date,
                    o.description,
                    o.required_count,
                    o.xp_reward,
                    o.coin_reward
                FROM objectives o
                ON CONFLICT DO NOTHING
                """;
        try (Connection c = conn();
            PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setString(1, userId.toString());
            ps.executeUpdate();
        }
    }
}