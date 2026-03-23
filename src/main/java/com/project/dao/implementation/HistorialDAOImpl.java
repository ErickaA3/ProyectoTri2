package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.interfaces.IHistorialDAO;
import com.project.database.DatabaseConnection;

/**
 * Implementación de IHistorialDAO.
 * Habla con study_content en Supabase para las consultas del historial.
 *
 * Todos los filtros son opcionales — si son null o vacíos no se aplican.
 */
public class HistorialDAOImpl implements IHistorialDAO {

    @Override
    public JsonArray getHistory(String userId, String type, String date, String search)
            throws Exception {

        StringBuilder sql = new StringBuilder("""
            SELECT id, type, title, is_favorite,
                   EXTRACT(EPOCH FROM created_at) * 1000 AS created_at_ms,
                   content->>'schemaType' AS schema_type
            FROM study_content
            WHERE user_id = ?::uuid
            """);

        if (type   != null && !type.isBlank())   sql.append(" AND type = ?");
        if (date   != null && !date.isBlank())   sql.append(" AND DATE(created_at) = ?::date");
        if (search != null && !search.isBlank()) sql.append(" AND LOWER(title) LIKE ?");

        sql.append(" ORDER BY created_at DESC");

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int i = 1;
            stmt.setString(i++, userId);
            if (type   != null && !type.isBlank())   stmt.setString(i++, type);
            if (date   != null && !date.isBlank())   stmt.setString(i++, date);
            if (search != null && !search.isBlank()) stmt.setString(i++, "%" + search.toLowerCase() + "%");

            ResultSet rs = stmt.executeQuery();
            JsonArray items = new JsonArray();

            while (rs.next()) {
                JsonObject item = new JsonObject();
                item.addProperty("id",         rs.getString("id"));
                item.addProperty("type",       rs.getString("type"));
                item.addProperty("title",      rs.getString("title"));
                item.addProperty("isFavorite", rs.getBoolean("is_favorite"));
                // Epoch ms extraído directamente por Postgres — evita desfase de timezone del JVM
                item.addProperty("createdAt", rs.getLong("created_at_ms"));
                // Subtipo de esquema (null para otros tipos)
                String schemaType = rs.getString("schema_type");
                if (schemaType != null && !schemaType.isBlank()) {
                    item.addProperty("schemaType", schemaType);
                }
                items.add(item);
            }

            return items;
        }
    }

    @Override
    public JsonObject getById(String id, String userId) throws Exception {
        String sql = """
            SELECT id, type, title, content::text AS content_json,
                   is_favorite,
                   EXTRACT(EPOCH FROM created_at) * 1000 AS created_at_ms,
                   session_id
            FROM study_content
            WHERE id = ?::uuid AND user_id = ?::uuid
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, id);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return null;

            JsonObject item = new JsonObject();
            item.addProperty("id",         rs.getString("id"));
            item.addProperty("type",       rs.getString("type"));
            item.addProperty("title",      rs.getString("title"));
            item.addProperty("isFavorite", rs.getBoolean("is_favorite"));
            item.addProperty("createdAt",  rs.getLong("created_at_ms"));

            String sessionId = rs.getString("session_id");
            if (sessionId != null) item.addProperty("sessionId", sessionId);

            // Parsear el JSONB de content y mezclar sus campos en el objeto raíz
            // Así las páginas reciben la misma estructura que genera ModoEstudioServlet
            String contentJson = rs.getString("content_json");
            if (contentJson != null && !contentJson.isBlank()) {
                try {
                    JsonObject content = JsonParser.parseString(contentJson).getAsJsonObject();
                    for (String key : content.keySet()) {
                        if (!item.has(key)) { // no sobrescribir id/type/title
                            item.add(key, content.get(key));
                        }
                    }
                } catch (Exception ignored) {}
            }

            return item;
        }
    }

    /**
     * Elimina duelos que referencian un contenido específico (evita FK constraint).
     * Se llama antes de eliminar un item de study_content.
     */
    public void deleteDuelsForContent(String contentId) throws Exception {
        String sql = "DELETE FROM duels WHERE content_id = ?::uuid";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.executeUpdate();
        }
    }

    @Override
    public int deleteAll(String userId) throws Exception {
        // Eliminar duelos que referencian contenido del usuario (FK constraint)
        String deleteDuels = """
            DELETE FROM duels
            WHERE content_id IN (SELECT id FROM study_content WHERE user_id = ?::uuid)
            """;
        String deleteContent = "DELETE FROM study_content WHERE user_id = ?::uuid";

        try (Connection conn = DatabaseConnection.getConnection()) {
            // 1. Borrar duelos asociados
            try (PreparedStatement stmt = conn.prepareStatement(deleteDuels)) {
                stmt.setString(1, userId);
                stmt.executeUpdate();
            }
            // 2. Borrar contenido
            try (PreparedStatement stmt = conn.prepareStatement(deleteContent)) {
                stmt.setString(1, userId);
                return stmt.executeUpdate();
            }
        }
    }
}