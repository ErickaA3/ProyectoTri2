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

import com.project.dao.interfaces.IUserDAO;
import com.project.database.DatabaseConnection;
import com.project.model.users.Statistics;
import com.project.model.users.User;

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
        u.setPasswordHash(rs.getString("password_hash")); // solo para login interno
        u.setFullName(rs.getString("full_name"));

        // language y country pueden ser null en Supabase; se leen de forma segura
        u.setLanguage(rs.getString("language"));
        u.setCountry(rs.getString("country"));

        Date bd = rs.getDate("birthdate");
        if (bd != null) u.setBirthdate(bd.toLocalDate());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        return u;
    }

    /**
     * Mapea un ResultSet parcial proveniente de un RETURNING que no incluye
     * todas las columnas de users (p.ej. el INSERT de register).
     * Solo lee las columnas que sí están presentes.
     */
    private User mapUserFromInsert(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(UUID.fromString(rs.getString("id")));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setFullName(rs.getString("full_name"));
        u.setLanguage(rs.getString("language")); // incluida en el RETURNING del INSERT

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) u.setCreatedAt(ca.toLocalDateTime());

        // country y birthdate no se insertan en el registro inicial; quedan null
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
        // BUG CORREGIDO #1: se agregó 'language' como 5ta columna en el INSERT y en el RETURNING.
        // Antes: INSERT INTO users (username, email, password_hash, full_name) — solo 4 columnas
        // pero se llamaba ps.setString(5, language) → "column index out of range: 5, number of columns: 4"

        // BUG CORREGIDO #2: ambos INSERTs ahora comparten la misma conexión y corren dentro
        // de una transacción explícita. Antes, conn() se llamaba dos veces (dos conexiones
        // distintas), el primer INSERT hacía auto-commit, y si el segundo fallaba el usuario
        // quedaba creado en BD pero el servlet devolvía Error 500 al cliente.
        String sqlUser = """
                INSERT INTO users (username, email, password_hash, full_name, language)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, username, email, password_hash, full_name, language, created_at
                """;
        String sqlStats = "INSERT INTO user_stats (user_id) VALUES (?)";

        Connection c = conn();
        try {
            c.setAutoCommit(false); // inicio de transacción

            User created;
            try (PreparedStatement ps = c.prepareStatement(sqlUser)) {
                ps.setString(1, user.getUsername());
                ps.setString(2, user.getEmail());
                ps.setString(3, user.getPasswordHash());
                ps.setString(4, user.getFullName());
                ps.setString(5, user.getLanguage() != null ? user.getLanguage() : "es");

                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new SQLException("No se pudo insertar el usuario.");
                created = mapUserFromInsert(rs);
            }

            try (PreparedStatement ps2 = c.prepareStatement(sqlStats)) {
                ps2.setObject(1, created.getId());
                ps2.executeUpdate();
            }

            c.commit(); // ambos INSERTs exitosos → confirmar
            return created;

        } catch (SQLException e) {
            c.rollback(); // algo falló → revertir todo (el usuario NO queda huérfano en BD)
            throw e;      // propagar para que RegisterServlet devuelva 500 con el mensaje real
        } finally {
            c.setAutoCommit(true);
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