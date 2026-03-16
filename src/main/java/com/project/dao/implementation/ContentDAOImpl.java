package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.project.dao.interfaces.IContentDAO;
import com.project.database.DatabaseConnection;
import com.project.model.content.EducationalContent;
import com.project.model.content.Summary;

/**
 * Implementación real del IContentDAO.
 * Habla con la tabla study_content en Supabase.
 *
 * CORRECCIONES:
 * - [BUG-4 FIX] Se agrega método getMetadata() para que SummaryServlet
 *   pueda obtener created_at y session_id sin duplicar queries
 */
public class ContentDAOImpl implements IContentDAO {

    @Override
    public String save(EducationalContent content, String contentJson, String sourceText) throws Exception {
        String sql = """
            INSERT INTO study_content
                (user_id, type, title, content, is_favorite, session_id, source_text)
            VALUES
                (?::uuid, ?, ?, ?::jsonb, false, ?::uuid, ?)
            RETURNING id
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, content.getUserId());
            stmt.setString(2, content.getType());
            stmt.setString(3, content.getTitle());
            stmt.setString(4, contentJson);
            stmt.setString(5, content.getSessionId());
            stmt.setString(6, sourceText);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
            throw new Exception("No se pudo guardar el contenido en la BD.");
        }
    }

    @Override
    public List<EducationalContent> getByUser(String userId, String type) throws Exception {
        String sql = (type != null)
            ? """
              SELECT id, user_id, type, title, is_favorite, created_at, session_id
              FROM study_content
              WHERE user_id = ?::uuid AND type = ?
              ORDER BY created_at DESC
              """
            : """
              SELECT id, user_id, type, title, is_favorite, created_at, session_id
              FROM study_content
              WHERE user_id = ?::uuid
              ORDER BY created_at DESC
              """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            if (type != null) stmt.setString(2, type);

            ResultSet rs = stmt.executeQuery();
            List<EducationalContent> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    @Override
    public List<EducationalContent> getFavorites(String userId) throws Exception {
        String sql = """
            SELECT id, user_id, type, title, is_favorite, created_at, session_id
            FROM study_content
            WHERE user_id = ?::uuid AND is_favorite = true
            ORDER BY created_at DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();

            List<EducationalContent> favorites = new ArrayList<>();
            while (rs.next()) {
                favorites.add(mapRow(rs));
            }
            return favorites;
        }
    }

    @Override
    public boolean toggleFavorite(String contentId, String userId, boolean isFavorite) throws Exception {
        String sql = """
            UPDATE study_content
            SET is_favorite = ?
            WHERE id = ?::uuid AND user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, isFavorite);
            stmt.setString(2, contentId);
            stmt.setString(3, userId);

            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        }
    }

    @Override
    public boolean delete(String contentId, String userId) throws Exception {
        String sql = """
            DELETE FROM study_content
            WHERE id = ?::uuid AND user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contentId);
            stmt.setString(2, userId);

            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        }
    }

    private EducationalContent mapRow(ResultSet rs) throws SQLException {
        Summary item = new Summary(
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getString("session_id"),
            null
        );
        item.setId(rs.getString("id"));
        item.setType(rs.getString("type"));
        item.setFavorite(rs.getBoolean("is_favorite"));
        return item;
    }

    @Override
    public String getContentJson(String contentId, String userId) throws Exception {
        String sql = """
            SELECT type, title, content::text, is_favorite
            FROM study_content
            WHERE id = ?::uuid AND user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contentId);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String type    = rs.getString("type");
                String title   = rs.getString("title");
                String content = rs.getString("content");
                boolean isFav  = rs.getBoolean("is_favorite");

                return "{\"type\":\"" + type + "\","
                     + "\"title\":\"" + title.replace("\"", "\\\"") + "\","
                     + "\"isFavorite\":" + isFav + ","
                     + "\"content\":" + content + "}";
            }
            return null;
        }
    }

    // ─── [BUG-4 FIX] Nuevo método para obtener metadatos (created_at, session_id) ───
    /**
     * Devuelve [createdAt, sessionId] como String[], o null si no existe.
     * Usado por SummaryServlet para completar la respuesta JSON.
     */
    public String[] getMetadata(String contentId, String userId) throws Exception {
        String sql = """
            SELECT created_at::text, session_id::text
            FROM study_content
            WHERE id = ?::uuid AND user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contentId);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new String[] {
                    rs.getString(1),  // created_at
                    rs.getString(2)   // session_id
                };
            }
            return null;
        }
    }
}