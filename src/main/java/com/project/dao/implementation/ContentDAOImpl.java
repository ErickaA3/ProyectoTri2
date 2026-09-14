package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.pgvector.PGvector;
import com.project.dao.interfaces.IContentDAO;
import com.project.database.DatabaseConnection;
import com.project.model.content.EducationalContent;
import com.project.model.content.Summary;
import com.project.util.AIService;

/**
 * Implementación real del IContentDAO.
 * Habla con la tabla study_content en Supabase.
 *
 * CORRECCIONES:
 * - [BUG-4 FIX] Se agrega método getMetadata() para que SummaryServlet
 *   pueda obtener created_at y session_id sin duplicar queries
 * - [RAG VECTORIAL] save() ahora guarda el contenido de inmediato y calcula
 *   el embedding EN SEGUNDO PLANO, actualizándolo después con un UPDATE.
 *   Esto evita que el usuario espere la llamada a OpenAI para ver su
 *   contenido guardado. Hay una ventana breve (1-2s) donde el contenido
 *   recién creado aún no es buscable semánticamente por el chatbot.
 * - [SHUTDOWN FIX] shutdownEmbeddingExecutor() se llama desde
 *   AppShutdownListener para evitar que los hilos del pool queden vivos
 *   tras un redeploy en caliente (classloader leak en Tomcat).
 */
public class ContentDAOImpl implements IContentDAO {

    // Pool pequeño y dedicado solo a generar embeddings en background.
    // No usar ForkJoinPool.commonPool() aquí: es compartido por toda la JVM
    // y no queremos que una lentitud de OpenAI compita con otras tareas.
    private static final ExecutorService EMBEDDING_EXECUTOR =
        Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "embedding-worker");
            t.setDaemon(true); // no debe impedir que Tomcat cierre limpio
            return t;
        });

    /**
     * Apaga el pool de embeddings de forma ordenada. Debe llamarse desde
     * un ServletContextListener (contextDestroyed) para evitar el warning
     * de Tomcat "the web application appears to have started a thread...
     * but has failed to stop it" y el classloader leak asociado.
     */
    public static void shutdownEmbeddingExecutor() {
        EMBEDDING_EXECUTOR.shutdown();
        try {
            if (!EMBEDDING_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EMBEDDING_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EMBEDDING_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String save(EducationalContent content, String contentJson, String sourceText) throws Exception {

        String sql = """
            INSERT INTO study_content
                (user_id, type, title, content, is_favorite, session_id, source_text, embedding)
            VALUES
                (?::uuid, ?, ?, ?::jsonb, false, ?::uuid, ?, NULL)
            RETURNING id
            """;

        String newId;
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
                newId = rs.getString("id");
            } else {
                throw new Exception("No se pudo guardar el contenido en la BD.");
            }
        }

        // Contenido ya guardado y visible para el usuario.
        // El embedding se genera aparte, sin bloquear la respuesta.
        scheduleEmbeddingUpdate(newId, content.getType(), content.getTitle(), contentJson);

        return newId;
    }

    /**
     * Genera el embedding en un hilo del pool dedicado y actualiza la fila
     * correspondiente cuando esté listo. Si falla, solo se registra el error:
     * el contenido ya quedó guardado correctamente, y esa fila simplemente
     * no aparecerá en resultados de búsqueda semántica hasta que se reintente
     * (por ejemplo en una futura edición del contenido).
     */
    private void scheduleEmbeddingUpdate(String contentId, String type, String title, String contentJson) {
        EMBEDDING_EXECUTOR.submit(() -> {
            try {
                String textoParaEmbedding = AIService.flattenContentForEmbedding(type, title, contentJson);
                float[] embedding = AIService.generateEmbedding(textoParaEmbedding);
                updateEmbedding(contentId, embedding);
            } catch (Exception e) {
                System.err.println("[ContentDAO] No se pudo generar/guardar embedding para "
                    + contentId + ": " + e.getMessage());
            }
        });
    }

    private void updateEmbedding(String contentId, float[] embedding) throws SQLException {
        String sql = "UPDATE study_content SET embedding = ? WHERE id = ?::uuid";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, new PGvector(embedding));
            stmt.setString(2, contentId);
            stmt.executeUpdate();
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
                    rs.getString(1),
                    rs.getString(2)
                };
            }
            return null;
        }
    }
}