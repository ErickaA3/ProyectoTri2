package com.project.dao.implementation;

import com.project.dao.interfaces.IUserDAO;
import com.project.database.DatabaseConnection;
import com.project.model.users.*;
import com.project.util.GamificationService;

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

    // Usuario + perfil + nombre del país. username y email son citext (no
    // distinguen mayúsculas): se comparan siempre contra ?::citext.
    private static final String SELECT_USER = """
            SELECT u.id, u.username::text AS username, u.email::text AS email,
                   u.password_hash, u.created_at,
                   p.full_name, p.country_code, co.name AS country_name,
                   p.language_code, p.birthdate
            FROM users u
            LEFT JOIN user_profiles p ON p.user_id = u.id
            LEFT JOIN countries co    ON co.code = p.country_code
            """;

    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(UUID.fromString(rs.getString("id")));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setFullName(rs.getString("full_name"));
        u.setCountryCode(rs.getString("country_code"));
        u.setCountry(rs.getString("country_name"));
        String lang = rs.getString("language_code");
        u.setLanguage(lang != null ? lang : "es");

        Date bd = rs.getDate("birthdate");
        if (bd != null) u.setBirthdate(bd.toLocalDate());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        return u;
    }

    private Optional<User> findOne(String where, String value) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(SELECT_USER + where)) {
            ps.setString(1, value);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════
    // REGISTRO
    // ═══════════════════════════════════════════════════════

    /**
     * Crea el usuario, su rol "student", su perfil y sus stats en una sola
     * transacción: o se crean todos o ninguno (antes eran tres escrituras
     * sueltas y un fallo a la mitad dejaba un usuario sin perfil o sin stats).
     */
    @Override
    public User register(User user) throws SQLException {
        String language = user.getLanguage() != null ? user.getLanguage() : "es";

        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                UUID id;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO users (username, email, password_hash)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)) {
                    ps.setString(1, user.getUsername());
                    ps.setString(2, user.getEmail());
                    ps.setString(3, user.getPasswordHash());
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new SQLException("No se pudo insertar el usuario.");
                    id = UUID.fromString(rs.getString("id"));
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_roles (user_id, role_code) VALUES (?, 'student')")) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_profiles (user_id, full_name, language_code) VALUES (?, ?, ?)")) {
                    ps.setObject(1, id);
                    ps.setString(2, user.getFullName());
                    ps.setString(3, language);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_stats (user_id) VALUES (?)")) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }

        return findByUsername(user.getUsername())
                .orElseThrow(() -> new SQLException("Usuario creado pero no encontrado."));
    }

    // ═══════════════════════════════════════════════════════
    // BÚSQUEDA
    // ═══════════════════════════════════════════════════════

    @Override
    public Optional<User> findByEmail(String email) throws SQLException {
        return findOne("WHERE u.email = ?::citext", email);
    }

    @Override
    public Optional<User> findById(UUID id) throws SQLException {
        return findOne("WHERE u.id = ?::uuid", id.toString());
    }

    @Override
    public Optional<User> findByUsername(String username) throws SQLException {
        return findOne("WHERE u.username = ?::citext", username);
    }

    @Override
    public List<String> getRoles(UUID userId) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT role_code FROM user_roles WHERE user_id = ? ORDER BY role_code")) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            List<String> roles = new ArrayList<>();
            while (rs.next()) roles.add(rs.getString(1));
            return roles;
        }
    }

    // ═══════════════════════════════════════════════════════
    // CATÁLOGOS DEL PERFIL
    // ═══════════════════════════════════════════════════════

    @Override
    public List<String[]> getCountries() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT code, name FROM countries ORDER BY name")) {
            ResultSet rs = ps.executeQuery();
            List<String[]> list = new ArrayList<>();
            while (rs.next()) list.add(new String[]{ rs.getString("code"), rs.getString("name") });
            return list;
        }
    }

    @Override
    public boolean countryExists(String code) throws SQLException {
        return exists("SELECT 1 FROM countries WHERE code = ?", code);
    }

    @Override
    public boolean languageExists(String code) throws SQLException {
        return exists("SELECT 1 FROM languages WHERE code = ?", code);
    }

    private boolean exists(String sql, String value) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            return ps.executeQuery().next();
        }
    }

    // ═══════════════════════════════════════════════════════
    // ACTUALIZACIÓN DE PERFIL
    // ═══════════════════════════════════════════════════════

    /** Actualiza users y user_profiles juntos (crea el perfil si no existía). */
    @Override
    public void updateUser(User user) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET username = ? WHERE id = ?")) {
                    ps.setString(1, user.getUsername());
                    ps.setObject(2, user.getId());
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO user_profiles (user_id, full_name, country_code, language_code, birthdate)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET
                            full_name     = EXCLUDED.full_name,
                            country_code  = EXCLUDED.country_code,
                            language_code = EXCLUDED.language_code,
                            birthdate     = EXCLUDED.birthdate
                        """)) {
                    ps.setObject(1, user.getId());
                    ps.setString(2, user.getFullName());
                    ps.setString(3, user.getCountryCode());
                    ps.setString(4, user.getLanguage() != null ? user.getLanguage() : "es");
                    ps.setObject(5, user.getBirthdate() != null
                            ? Date.valueOf(user.getBirthdate()) : null);
                    ps.executeUpdate();
                }

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
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
        return exists("SELECT 1 FROM users WHERE email = ?::citext", email);
    }

    @Override
    public boolean usernameExists(String username) throws SQLException {
        return exists("SELECT 1 FROM users WHERE username = ?::citext", username);
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
                WHERE udm.user_id = ?::uuid AND udm.date = ?
                ORDER BY udm.completed ASC, m.id ASC
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ps.setObject(2, GamificationService.today());
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
        // Fecha de Costa Rica, no CURRENT_DATE (la BD corre en UTC).
        java.time.LocalDate today = GamificationService.today();
        String checkSql = """
                SELECT COUNT(*) FROM user_daily_missions
                WHERE user_id = ?::uuid AND date = ?
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ps.setObject(2, today);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        String insertSql = """
                INSERT INTO user_daily_missions (user_id, mission_id, date)
                SELECT ?::uuid, id, ? FROM missions
                ON CONFLICT DO NOTHING
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setString(1, userId.toString());
            ps.setObject(2, today);
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
                  AND week_start = ?
                ORDER BY completed ASC, type ASC
                """;
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId.toString());
            ps.setObject(2, GamificationService.currentWeekStart());
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
        // Verificar si ya tiene objetivos esta semana (lunes en hora de Costa Rica)
        java.time.LocalDate weekStart = GamificationService.currentWeekStart();
        String checkSql = """
                SELECT COUNT(*) FROM user_weekly_objectives
                WHERE user_id = ?::uuid
                AND week_start = ?
                """;
        try (Connection c = conn();
            PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, userId.toString());
            ps.setObject(2, weekStart);
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
                    ?,
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
            ps.setObject(2, weekStart);
            ps.executeUpdate();
        }
    }
}