package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.interfaces.IContentDAO;
import com.project.database.DatabaseConnection;
import com.project.model.content.EducationalContent;
import com.project.model.content.Summary;

/**
 * Implementación real del IContentDAO sobre generated_content.
 *
 * - El favorito vive en la tabla favorites (por usuario), no en el contenido.
 * - Borrar es lógico (deleted_at): resultados y duelos que lo usaron se conservan.
 * - El embedding ya no se calcula sobre lo generado sino sobre el material
 *   original (document_chunks, ver LibraryDAOImpl).
 */
public class ContentDAOImpl implements IContentDAO {

    // Contenido visible en la biblioteca del usuario: suyo, no borrado, y en
    // un tema y una materia que no estén archivados. Deja fuera el quiz de los
    // duelos, que no tiene tema.
    private static final String LIBRARY_FROM = """
        FROM generated_content gc
        JOIN topics t   ON t.id = gc.topic_id   AND t.archived_at IS NULL
        JOIN subjects s ON s.id = t.subject_id  AND s.archived_at IS NULL
        LEFT JOIN favorites f ON f.content_id = gc.id AND f.user_id = gc.created_by
        WHERE gc.created_by = ?::uuid AND gc.deleted_at IS NULL
        """;

    private static final String LIBRARY_COLUMNS = """
        SELECT gc.id, gc.created_by, gc.content_type_code, gc.title,
               (f.content_id IS NOT NULL) AS is_favorite
        """;

    @Override
    public String save(EducationalContent content, String contentJson,
                       String topicId, String documentId) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String newId;
                try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO generated_content (topic_id, created_by, content_type_code, title, body)
                        VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb)
                        RETURNING id
                        """)) {
                    stmt.setString(1, topicId);
                    stmt.setString(2, content.getUserId());
                    stmt.setString(3, content.getType());
                    stmt.setString(4, safeTitle(content.getTitle()));
                    stmt.setString(5, contentJson);
                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) throw new Exception("No se pudo guardar el contenido en la BD.");
                    newId = rs.getString("id");
                }

                insertItems(conn, newId);

                if (documentId != null) {
                    try (PreparedStatement stmt = conn.prepareStatement("""
                            INSERT INTO content_sources (content_id, chunk_id)
                            SELECT ?::uuid, id FROM document_chunks WHERE document_id = ?::uuid
                            """)) {
                        stmt.setString(1, newId);
                        stmt.setString(2, documentId);
                        stmt.executeUpdate();
                    }
                }

                conn.commit();
                return newId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Crea las filas de content_items (e item_options) a partir del JSON recién
     * guardado: las preguntas de un quiz/examen o las tarjetas de un mazo. Son
     * las que se usan para calificar en el servidor. Mismo SQL que la V5.
     */
    private void insertItems(Connection conn, String contentId) throws SQLException {
        String[] statements = {
            """
            INSERT INTO content_items (content_id, position, prompt, answer)
            SELECT gc.id, q.ord - 1, coalesce(q.value ->> 'question', ''), q.value ->> 'explanation'
            FROM generated_content gc
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(gc.body -> 'questions') = 'array' THEN gc.body -> 'questions' ELSE '[]'::jsonb END
            ) WITH ORDINALITY AS q(value, ord)
            WHERE gc.id = ?::uuid AND gc.content_type_code IN ('quiz', 'expert_exam', 'duel_quiz')
            """,
            """
            INSERT INTO item_options (item_id, position, text, is_correct)
            SELECT ci.id, o.ord - 1, o.value #>> '{}',
                   coalesce((o.ord - 1) = CASE WHEN (q.value ->> 'correct') ~ '^\\d+$'
                                               THEN (q.value ->> 'correct')::int END, false)
            FROM generated_content gc
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(gc.body -> 'questions') = 'array' THEN gc.body -> 'questions' ELSE '[]'::jsonb END
            ) WITH ORDINALITY AS q(value, ord)
            JOIN content_items ci ON ci.content_id = gc.id AND ci.position = q.ord - 1
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(q.value -> 'options') = 'array' THEN q.value -> 'options' ELSE '[]'::jsonb END
            ) WITH ORDINALITY AS o(value, ord)
            WHERE gc.id = ?::uuid AND gc.content_type_code IN ('quiz', 'expert_exam', 'duel_quiz')
            """,
            """
            INSERT INTO content_items (content_id, position, prompt, answer)
            SELECT gc.id, c.ord - 1, coalesce(c.value ->> 'front', ''), c.value ->> 'back'
            FROM generated_content gc
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(gc.body -> 'cards') = 'array' THEN gc.body -> 'cards' ELSE '[]'::jsonb END
            ) WITH ORDINALITY AS c(value, ord)
            WHERE gc.id = ?::uuid AND gc.content_type_code = 'flashcard'
            """
        };
        for (String sql : statements) {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, contentId);
                stmt.executeUpdate();
            }
        }
    }

    /** title es NOT NULL y de 1 a 200 caracteres en la BD. */
    private static String safeTitle(String title) {
        if (title == null || title.isBlank()) return "Sin título";
        String t = title.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    @Override
    public List<EducationalContent> getByUser(String userId, String type) throws Exception {
        String sql = LIBRARY_COLUMNS + LIBRARY_FROM
                   + (type != null ? " AND gc.content_type_code = ?" : "")
                   + " ORDER BY gc.created_at DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            if (type != null) stmt.setString(2, type);
            return mapRows(stmt.executeQuery());
        }
    }

    @Override
    public List<EducationalContent> getFavorites(String userId) throws Exception {
        String sql = LIBRARY_COLUMNS + LIBRARY_FROM
                   + " AND f.content_id IS NOT NULL ORDER BY f.created_at DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            return mapRows(stmt.executeQuery());
        }
    }

    @Override
    public boolean toggleFavorite(String contentId, String userId, boolean isFavorite) throws Exception {
        // Solo sobre contenido propio y no borrado.
        String sql = isFavorite
            ? """
              INSERT INTO favorites (user_id, content_id)
              SELECT created_by, id FROM generated_content
              WHERE id = ?::uuid AND created_by = ?::uuid AND deleted_at IS NULL
              ON CONFLICT DO NOTHING
              """
            : """
              DELETE FROM favorites f
              USING generated_content gc
              WHERE f.content_id = gc.id AND f.user_id = gc.created_by
                AND gc.id = ?::uuid AND gc.created_by = ?::uuid AND gc.deleted_at IS NULL
              """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.setString(2, userId);
            stmt.executeUpdate();
            // Marcar algo que ya era favorito (o quitar algo que no lo era) no es
            // un error: se responde true si el contenido existe y es del usuario.
            return getContentType(contentId, userId) != null;
        }
    }

    @Override
    public boolean delete(String contentId, String userId) throws Exception {
        String sql = """
            UPDATE generated_content SET deleted_at = now()
            WHERE id = ?::uuid AND created_by = ?::uuid AND deleted_at IS NULL
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.setString(2, userId);
            return stmt.executeUpdate() > 0;
        }
    }

    private List<EducationalContent> mapRows(ResultSet rs) throws SQLException {
        List<EducationalContent> results = new ArrayList<>();
        while (rs.next()) {
            Summary item = new Summary(rs.getString("created_by"), rs.getString("title"), null, null);
            item.setId(rs.getString("id"));
            item.setType(rs.getString("content_type_code"));
            item.setFavorite(rs.getBoolean("is_favorite"));
            results.add(item);
        }
        return results;
    }

    @Override
    public String getContentJson(String contentId, String userId) throws Exception {
        String sql = """
            SELECT gc.content_type_code, gc.title, gc.body::text AS body,
                   EXISTS (SELECT 1 FROM favorites f
                           WHERE f.content_id = gc.id AND f.user_id = gc.created_by) AS is_favorite
            FROM generated_content gc
            WHERE gc.id = ?::uuid AND gc.created_by = ?::uuid AND gc.deleted_at IS NULL
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return null;

            JsonObject o = new JsonObject();
            o.addProperty("type",       rs.getString("content_type_code"));
            o.addProperty("title",      rs.getString("title"));
            o.addProperty("isFavorite", rs.getBoolean("is_favorite"));
            o.add("content", JsonParser.parseString(rs.getString("body")));
            return o.toString();
        }
    }

    @Override
    public String getContentType(String contentId, String userId) throws Exception {
        String sql = """
            SELECT content_type_code FROM generated_content
            WHERE id = ?::uuid AND created_by = ?::uuid AND deleted_at IS NULL
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.setString(2, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public String[] getMetadata(String contentId, String userId) throws Exception {
        String sql = """
            SELECT gc.created_at::text, t.id::text, t.name, s.id::text, s.name
            FROM generated_content gc
            LEFT JOIN topics t   ON t.id = gc.topic_id
            LEFT JOIN subjects s ON s.id = t.subject_id
            WHERE gc.id = ?::uuid AND gc.created_by = ?::uuid AND gc.deleted_at IS NULL
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, contentId);
            stmt.setString(2, userId);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return null;
            return new String[] {
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)
            };
        }
    }
}
