package com.project.dao.implementation;

import com.project.dao.interfaces.IContentDAO;
import com.project.database.DatabaseConnection;
import com.project.model.content.EducationalContent;
import com.project.model.content.Summary;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación real del IContentDAO.
 * Habla con la tabla study_content en Supabase.
 *
 * Estructura real de la tabla (con los ALTER TABLE aplicados):
 * study_content(id UUID, user_id UUID, type, title, content JSONB,
 *               is_favorite, created_at, session_id UUID, source_text TEXT)
 *
 * CORRECCIONES vs versión anterior:
 * - id y user_id son UUID (String), no int
 * - La columna se llama "content", no "content_json"
 * - Se agrega session_id al INSERT
 * - Se agrega source_text al INSERT
 * - getByUser recibe String userId en vez de int
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

            stmt.setString(1, content.getUserId());   // UUID como String
            stmt.setString(2, content.getType());
            stmt.setString(3, content.getTitle());
            stmt.setString(4, contentJson);            // columna "content", tipo JSONB
            stmt.setString(5, content.getSessionId()); // UUID como String
            stmt.setString(6, sourceText);             // texto original del usuario

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id"); // UUID como String
            }
            throw new Exception("No se pudo guardar el contenido en la BD.");
        }
    }

    @Override
    public List<EducationalContent> getByUser(String userId, String type) throws Exception {
        // Si type es null, trae todo; si tiene valor, filtra por tipo
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
        // Verificamos que el contenido pertenezca al usuario antes de actualizar
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

    // Mapea una fila del ResultSet a un objeto base (sin content para listas)
    private EducationalContent mapRow(ResultSet rs) throws SQLException {
        // Usamos Summary como implementación concreta para el mapeo base
        // (solo necesitamos los campos comunes para listas)
        Summary item = new Summary(
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getString("session_id"),
            null // summaryText no se carga en listados
        );
        item.setId(rs.getString("id"));
        item.setType(rs.getString("type")); // sobreescribimos el tipo real
        item.setFavorite(rs.getBoolean("is_favorite"));
        return item;
    }
}