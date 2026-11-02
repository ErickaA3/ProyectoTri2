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
 * Habla con generated_content (y topics, subjects, favorites) para el historial.
 *
 * Todos los filtros son opcionales — si son null o vacíos no se aplican.
 */
public class HistorialDAOImpl implements IHistorialDAO {

    @Override
    public JsonArray getHistory(String userId, String type, String date, String search)
            throws Exception {

        // Biblioteca del usuario: lo suyo, no borrado, en temas y materias activos
        // (deja fuera el quiz de los duelos, que no tiene tema).
        StringBuilder sql = new StringBuilder("""
            SELECT gc.id, gc.content_type_code AS type, gc.title,
                   (f.content_id IS NOT NULL) AS is_favorite,
                   EXTRACT(EPOCH FROM gc.created_at) * 1000 AS created_at_ms,
                   gc.body->>'schemaType' AS schema_type,
                   t.id AS topic_id, t.name AS topic_name,
                   s.id AS subject_id, s.name AS subject_name
            FROM generated_content gc
            JOIN topics t   ON t.id = gc.topic_id   AND t.archived_at IS NULL
            JOIN subjects s ON s.id = t.subject_id  AND s.archived_at IS NULL
            LEFT JOIN favorites f ON f.content_id = gc.id AND f.user_id = gc.created_by
            WHERE gc.created_by = ?::uuid AND gc.deleted_at IS NULL
            """);

        if (type   != null && !type.isBlank())   sql.append(" AND gc.content_type_code = ?");
        // El día se cuenta en hora de Costa Rica (created_at es timestamptz).
        if (date   != null && !date.isBlank())   sql.append(" AND (gc.created_at AT TIME ZONE 'America/Costa_Rica')::date = ?::date");
        if (search != null && !search.isBlank()) sql.append(" AND LOWER(gc.title) LIKE ?");

        sql.append(" ORDER BY gc.created_at DESC");

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
                item.addProperty("topicId",     rs.getString("topic_id"));
                item.addProperty("topicName",   rs.getString("topic_name"));
                item.addProperty("subjectId",   rs.getString("subject_id"));
                item.addProperty("subjectName", rs.getString("subject_name"));
                items.add(item);
            }

            return items;
        }
    }

    @Override
    public JsonObject getById(String id, String userId) throws Exception {
        String sql = """
            SELECT gc.id, gc.content_type_code AS type, gc.title, gc.body::text AS content_json,
                   EXISTS (SELECT 1 FROM favorites f
                           WHERE f.content_id = gc.id AND f.user_id = gc.created_by) AS is_favorite,
                   EXTRACT(EPOCH FROM gc.created_at) * 1000 AS created_at_ms,
                   gc.topic_id
            FROM generated_content gc
            WHERE gc.id = ?::uuid AND gc.created_by = ?::uuid AND gc.deleted_at IS NULL
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

            String topicId = rs.getString("topic_id");
            if (topicId != null) item.addProperty("topicId", topicId);

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

    @Override
    public int deleteAll(String userId) throws Exception {
        // Borrado lógico: desaparece de la biblioteca, pero los resultados y los
        // duelos que usaron ese contenido (y las victorias del ranking) se conservan.
        // El quiz de los duelos (sin tema) no es parte del historial y no se toca.
        String deleteContent = """
            UPDATE generated_content SET deleted_at = now()
            WHERE created_by = ?::uuid AND deleted_at IS NULL AND topic_id IS NOT NULL
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteContent)) {
            stmt.setString(1, userId);
            return stmt.executeUpdate();
        }
    }
}