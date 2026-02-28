package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.project.database.DatabaseConnection;

import com.project.dao.interfaces.IUserDAO;
import com.project.model.users.Statistics;
import com.project.model.users.User;

/**
 * Implementación real de IUserDAO.
 * Todas las operaciones SQL contra Supabase (PostgreSQL) viven aquí.
 */
public class UserDAOImpl implements IUserDAO {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Connection conn() throws SQLException {
    return DatabaseConnection.getConnection();
}

    /** Mapea un ResultSet → User (sin exponer password_hash al exterior). */
    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(UUID.fromString(rs.getString("id")));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));     // solo para login interno
        u.setFullName(rs.getString("full_name"));
        u.setLanguage(rs.getString("language"));
        u.setCountry(rs.getString("country"));

        Date bd = rs.getDate("birthdate");
        if (bd != null) u.setBirthdate(bd.toLocalDate());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        return u;
    }

    /** Mapea un ResultSet → Statistics. */
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
        // 1. Insertar en users y recuperar el UUID generado
        String sqlUser = """
                INSERT INTO users (username, email, password_hash, full_name, language)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, username, email, full_name, language, country, birthdate, created_at
                """;

        try (PreparedStatement ps = conn().prepareStatement(sqlUser)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getFullName());
            ps.setString(5, user.getLanguage() != null ? user.getLanguage() : "es");

            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new SQLException("No se pudo insertar el usuario.");

            User created = mapUser(rs);

            // 2. Crear fila inicial en user_stats
            String sqlStats = "INSERT INTO user_stats (user_id) VALUES (?)";
            try (PreparedStatement ps2 = conn().prepareStatement(sqlStats)) {
                ps2.setObject(1, created.getId());
                ps2.executeUpdate();
            }

            return created;
        }
    }

    @Override
    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT * FROM users WHERE email = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
            return Optional.empty();
        }
    }

    @Override
    public Optional<Statistics> getStatsByUserId(UUID userId) throws SQLException {
        String sql = "SELECT * FROM user_stats WHERE user_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setObject(1, userId);
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
                 WHERE user_id = ?
                """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, stats.getXp());
            ps.setInt(2, stats.getLevel());
            ps.setInt(3, stats.getCoins());
            ps.setInt(4, stats.getStreakCurrent());
            ps.setInt(5, stats.getStreakRecord());

            LocalDate last = stats.getStreakLastActivity();
            ps.setDate(6, last != null ? Date.valueOf(last) : null);

            ps.setBoolean(7, stats.isHasStreakShield());
            ps.setObject(8, stats.getUserId());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean emailExists(String email) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE email = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeQuery().next();
        }
    }

    @Override
    public boolean usernameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeQuery().next();
        }
    }
}