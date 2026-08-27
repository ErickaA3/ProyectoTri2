package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.database.DatabaseConnection;

/**
 * DAO para la leaderboard de duelos.
 *
 * Soporta tres períodos:
 *   "week"  → últimos 7 días
 *   "month" → últimos 30 días
 *   "all"   → todo el tiempo
 *
 * Devuelve JsonArray directo (mismo estilo que DuelDAOImpl)
 * con los campos: userId, username, wins, xp, streak
 * Ordenado por score compuesto: wins*3 + xp/100 + streak*2
 * Límite: top 10.
 */
public class LeaderboardDAO {

    /**
     * Retorna el ranking global (top 10) para el período indicado.
     * Solo incluye usuarios que han participado en al menos un duelo.
     */
    public JsonArray getLeaderboard(String period) throws Exception {

        // ── Filtro de fecha según período ──────────────────────────
        String dateFilter = switch (period != null ? period : "week") {
            case "month" -> "AND d.finished_at >= NOW() - INTERVAL '30 days'";
            case "all"   -> "";                                   // sin filtro
            default      -> "AND d.finished_at >= NOW() - INTERVAL '7 days'";  // week
        };

        /*
         * ── Query ──────────────────────────────────────────────────
         * Une users + user_stats + duelos terminados.
         * Cuenta las victorias de cada usuario en el período elegido.
         * Ordena por score compuesto descendente.
         */
        String sql = """
            SELECT
                u.id::text                                          AS user_id,
                u.username,
                COUNT(CASE WHEN d.winner_id = u.id THEN 1 END)     AS wins,
                COALESCE(us.xp, 0)                                  AS xp,
                COALESCE(us.streak_current, 0)                      AS streak
            FROM users u
            LEFT JOIN user_stats us ON us.user_id = u.id
            LEFT JOIN duels d
                ON (d.challenger_id = u.id OR d.opponent_id = u.id)
                AND d.status = 'finished'
                """ + dateFilter + """
            GROUP BY u.id, u.username, us.xp, us.streak_current
            HAVING COUNT(CASE WHEN d.winner_id = u.id THEN 1 END) > 0
                OR COALESCE(us.xp, 0) > 0
            ORDER BY (
                COUNT(CASE WHEN d.winner_id = u.id THEN 1 END) * 3
                + COALESCE(us.xp, 0) / 100
                + COALESCE(us.streak_current, 0) * 2
            ) DESC
            LIMIT 10
            """;

        JsonArray result = new JsonArray();

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            int rank = 1;
            while (rs.next()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("userId",   rs.getString("user_id"));
                entry.addProperty("username", rs.getString("username"));
                entry.addProperty("wins",     rs.getInt("wins"));
                entry.addProperty("xp",       rs.getInt("xp"));
                entry.addProperty("streak",   rs.getInt("streak"));
                entry.addProperty("rank",     rank++);
                result.add(entry);
            }
        }

        return result;
    }

    /**
     * Retorna el ranking filtrado por amigos del usuario actual.
     * Incluye al usuario actual + sus amigos aceptados.
     */
    public JsonArray getLeaderboardFriends(String userId, String period) throws Exception {

        String dateFilter = switch (period != null ? period : "week") {
            case "month" -> "AND d.finished_at >= NOW() - INTERVAL '30 days'";
            case "all"   -> "";
            default      -> "AND d.finished_at >= NOW() - INTERVAL '7 days'";
        };

        /*
         * Subquery de amigos aceptados del usuario actual
         * (sender o receiver) + el propio usuario.
         */
        String sql = """
            WITH friend_ids AS (
                SELECT receiver_id AS friend_id
                FROM friendships
                WHERE sender_id = ?::uuid AND status = 'accepted'
                UNION
                SELECT sender_id AS friend_id
                FROM friendships
                WHERE receiver_id = ?::uuid AND status = 'accepted'
                UNION
                SELECT ?::uuid AS friend_id
            )
            SELECT
                u.id::text                                          AS user_id,
                u.username,
                COUNT(CASE WHEN d.winner_id = u.id THEN 1 END)     AS wins,
                COALESCE(us.xp, 0)                                  AS xp,
                COALESCE(us.streak_current, 0)                      AS streak
            FROM users u
            INNER JOIN friend_ids fi ON fi.friend_id = u.id
            LEFT JOIN user_stats us ON us.user_id = u.id
            LEFT JOIN duels d
                ON (d.challenger_id = u.id OR d.opponent_id = u.id)
                AND d.status = 'finished'
                """ + dateFilter + """
            GROUP BY u.id, u.username, us.xp, us.streak_current
            ORDER BY (
                COUNT(CASE WHEN d.winner_id = u.id THEN 1 END) * 3
                + COALESCE(us.xp, 0) / 100
                + COALESCE(us.streak_current, 0) * 2
            ) DESC
            LIMIT 10
            """;

        JsonArray result = new JsonArray();

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, userId);
            stmt.setString(3, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("userId",   rs.getString("user_id"));
                    entry.addProperty("username", rs.getString("username"));
                    entry.addProperty("wins",     rs.getInt("wins"));
                    entry.addProperty("xp",       rs.getInt("xp"));
                    entry.addProperty("streak",   rs.getInt("streak"));
                    entry.addProperty("rank",     rank++);
                    result.add(entry);
                }
            }
        }

        return result;
    }
}