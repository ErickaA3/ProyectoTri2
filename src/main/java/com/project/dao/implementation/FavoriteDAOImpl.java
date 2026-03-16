package com.project.dao.implementation;

import com.project.dao.interfaces.IFavoriteDAO;
import com.project.database.DatabaseConnection;
import com.project.model.favorites.Favorite;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FavoriteDAOImpl implements IFavoriteDAO {

    @Override
    public boolean setFavorite(UUID contentId, UUID userId, boolean isFavorite) {
        String sql = "UPDATE study_content SET is_favorite = ? " +
                     "WHERE id = ? AND user_id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, isFavorite);
            stmt.setObject(2, contentId);
            stmt.setObject(3, userId);

            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;

        } catch (SQLException e) {
            System.out.println("[FavoriteDAO] Error en setFavorite: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<Favorite> getFavoritesByUser(UUID userId) {
        String sql = "SELECT id, user_id, type, title, content::text, created_at " +
                     "FROM study_content " +
                     "WHERE user_id = ? AND is_favorite = true " +
                     "ORDER BY created_at DESC";

        return executeQuery(sql, userId, null);
    }

    @Override
    public List<Favorite> getFavoritesByType(UUID userId, String type) {
        String sql = "SELECT id, user_id, type, title, content::text, created_at " +
                     "FROM study_content " +
                     "WHERE user_id = ? AND is_favorite = true AND type = ? " +
                     "ORDER BY created_at DESC";

        return executeQuery(sql, userId, type);
    }

    private List<Favorite> executeQuery(String sql, UUID userId, String type) {
        List<Favorite> favorites = new ArrayList<>();

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, userId);
            if (type != null) {
                stmt.setString(2, type);
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                favorites.add(mapRow(rs));
            }

        } catch (SQLException e) {
            System.out.println("[FavoriteDAO] Error en query: " + e.getMessage());
        }

        return favorites;
    }

    private Favorite mapRow(ResultSet rs) throws SQLException {
        Favorite fav = new Favorite();
        fav.setId(UUID.fromString(rs.getString("id")));
        fav.setUserId(UUID.fromString(rs.getString("user_id")));
        fav.setType(rs.getString("type"));
        fav.setTitle(rs.getString("title"));
        fav.setContent(rs.getString("content"));
        fav.setFavorite(true);
        fav.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return fav;
    }
}