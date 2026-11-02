package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pgvector.PGvector;
import com.project.dao.interfaces.ILibraryDAO;
import com.project.database.DatabaseConnection;
import com.project.util.AIService;
import com.project.util.TextChunker;

/**
 * Materias, temas y documentos (subjects, topics, documents, document_chunks).
 *
 * Los embeddings de los fragmentos se calculan en segundo plano: un solo hilo
 * recorre los fragmentos con embedding null y los completa. Se dispara al
 * arrancar la app (para los fragmentos migrados) y cada vez que se guarda un
 * documento nuevo.
 */
public class LibraryDAOImpl implements ILibraryDAO {

    private static final String DEFAULT_SUBJECT = "Sin clasificar";
    private static final String DEFAULT_TOPIC   = "General";

    // Materia o tema del usuario y no archivado. Se reutiliza en varias consultas.
    private static final String OWNED_TOPIC = """
            SELECT t.id FROM topics t
            JOIN subjects s ON s.id = t.subject_id
            WHERE t.id = ?::uuid AND s.owner_id = ?::uuid
              AND t.archived_at IS NULL AND s.archived_at IS NULL
            """;

    // ═════════════════════════════════════════════════════════════════════
    // MATERIAS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public JsonArray listSubjects(String userId) throws Exception {
        String sql = """
            SELECT s.id AS subject_id, s.name AS subject_name, s.color, s.created_at AS subject_created,
                   t.id AS topic_id, t.name AS topic_name, t.position,
                   (SELECT count(*) FROM generated_content gc
                     WHERE gc.topic_id = t.id AND gc.deleted_at IS NULL) AS content_count,
                   (SELECT count(*) FROM documents d WHERE d.topic_id = t.id) AS document_count
            FROM subjects s
            LEFT JOIN topics t ON t.subject_id = s.id AND t.archived_at IS NULL
            WHERE s.owner_id = ?::uuid AND s.archived_at IS NULL
            ORDER BY s.name, t.position
            """;

        Map<String, JsonObject> subjects = new LinkedHashMap<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String sid = rs.getString("subject_id");
                JsonObject subject = subjects.computeIfAbsent(sid, k -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", k);
                    o.add("topics", new JsonArray());
                    return o;
                });
                subject.addProperty("name",  rs.getString("subject_name"));
                subject.addProperty("color", rs.getString("color"));

                if (rs.getString("topic_id") != null) {
                    JsonObject topic = new JsonObject();
                    topic.addProperty("id",            rs.getString("topic_id"));
                    topic.addProperty("name",          rs.getString("topic_name"));
                    topic.addProperty("position",      rs.getInt("position"));
                    topic.addProperty("contentCount",  rs.getInt("content_count"));
                    topic.addProperty("documentCount", rs.getInt("document_count"));
                    subject.getAsJsonArray("topics").add(topic);
                }
            }
        }

        JsonArray result = new JsonArray();
        subjects.values().forEach(result::add);
        return result;
    }

    @Override
    public String createSubject(String userId, String name, String color) throws Exception {
        String sql = "INSERT INTO subjects (owner_id, name, color) VALUES (?::uuid, ?, ?) RETURNING id";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, name);
            ps.setString(3, color);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }

    @Override
    public boolean updateSubject(String userId, String subjectId, String name, String color) throws Exception {
        String sql = """
            UPDATE subjects SET name = coalesce(?, name), color = coalesce(?, color)
            WHERE id = ?::uuid AND owner_id = ?::uuid AND archived_at IS NULL
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, color);
            ps.setString(3, subjectId);
            ps.setString(4, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean archiveSubject(String userId, String subjectId) throws Exception {
        String sql = """
            UPDATE subjects SET archived_at = now()
            WHERE id = ?::uuid AND owner_id = ?::uuid AND archived_at IS NULL
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, subjectId);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // TEMAS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public String createTopic(String userId, String subjectId, String name) throws Exception {
        String sql = """
            INSERT INTO topics (subject_id, name, position)
            SELECT s.id, ?,
                   coalesce((SELECT max(position) + 1 FROM topics WHERE subject_id = s.id), 0)
            FROM subjects s
            WHERE s.id = ?::uuid AND s.owner_id = ?::uuid AND s.archived_at IS NULL
            RETURNING id
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, subjectId);
            ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public boolean renameTopic(String userId, String topicId, String name) throws Exception {
        String sql = """
            UPDATE topics t SET name = ?
            FROM subjects s
            WHERE t.id = ?::uuid AND s.id = t.subject_id AND s.owner_id = ?::uuid
              AND t.archived_at IS NULL
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, topicId);
            ps.setString(3, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean archiveTopic(String userId, String topicId) throws Exception {
        String sql = """
            UPDATE topics t SET archived_at = now()
            FROM subjects s
            WHERE t.id = ?::uuid AND s.id = t.subject_id AND s.owner_id = ?::uuid
              AND t.archived_at IS NULL
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, topicId);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean topicBelongsToUser(String topicId, String userId) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(OWNED_TOPIC)) {
            ps.setString(1, topicId);
            ps.setString(2, userId);
            return ps.executeQuery().next();
        }
    }

    @Override
    public String ensureDefaultTopic(String userId) throws Exception {
        // Si el usuario archivó "Sin clasificar" o "General", se reactivan.
        String upsertSubject = """
            INSERT INTO subjects (owner_id, name) VALUES (?::uuid, ?)
            ON CONFLICT (owner_id, name) DO UPDATE SET archived_at = NULL
            RETURNING id
            """;
        String upsertTopic = """
            INSERT INTO topics (subject_id, name, position)
            VALUES (?::uuid, ?,
                    coalesce((SELECT max(position) + 1 FROM topics WHERE subject_id = ?::uuid), 0))
            ON CONFLICT (subject_id, name) DO UPDATE SET archived_at = NULL
            RETURNING id
            """;

        try (Connection conn = DatabaseConnection.getConnection()) {
            String subjectId;
            try (PreparedStatement ps = conn.prepareStatement(upsertSubject)) {
                ps.setString(1, userId);
                ps.setString(2, DEFAULT_SUBJECT);
                ResultSet rs = ps.executeQuery();
                rs.next();
                subjectId = rs.getString(1);
            }
            try (PreparedStatement ps = conn.prepareStatement(upsertTopic)) {
                ps.setString(1, subjectId);
                ps.setString(2, DEFAULT_TOPIC);
                ps.setString(3, subjectId);
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DOCUMENTOS
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public String createDocument(String topicId, String userId, String fileName,
                                 String mimeType, String text) throws Exception {
        List<String> chunks = TextChunker.split(text);
        if (chunks.isEmpty()) throw new Exception("El documento no tiene texto.");

        String docId;
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO documents (topic_id, uploaded_by, file_name, mime_type)
                        VALUES (?::uuid, ?::uuid, ?, ?)
                        RETURNING id
                        """)) {
                    ps.setString(1, topicId);
                    ps.setString(2, userId);
                    ps.setString(3, fileName);
                    ps.setString(4, mimeType);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    docId = rs.getString(1);
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO document_chunks (document_id, chunk_index, content, token_count)
                        VALUES (?::uuid, ?, ?, ?)
                        """)) {
                    for (int i = 0; i < chunks.size(); i++) {
                        ps.setString(1, docId);
                        ps.setInt(2, i);
                        ps.setString(3, chunks.get(i));
                        ps.setInt(4, TextChunker.estimateTokens(chunks.get(i)));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        scheduleEmbeddingBackfill();
        return docId;
    }

    @Override
    public JsonArray searchChunks(String userId, float[] queryEmbedding, int limit) throws Exception {
        String sql = """
            SELECT s.name AS subject, t.name AS topic, d.file_name AS document, ch.content
            FROM document_chunks ch
            JOIN documents d ON d.id = ch.document_id
            JOIN topics t    ON t.id = d.topic_id   AND t.archived_at IS NULL
            JOIN subjects s  ON s.id = t.subject_id AND s.archived_at IS NULL
            WHERE s.owner_id = ? AND ch.embedding IS NOT NULL
            ORDER BY ch.embedding <=> ?
            LIMIT ?
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.fromString(userId));
            ps.setObject(2, new PGvector(queryEmbedding));
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();

            JsonArray result = new JsonArray();
            while (rs.next()) {
                JsonObject o = new JsonObject();
                o.addProperty("subject",  rs.getString("subject"));
                o.addProperty("topic",    rs.getString("topic"));
                o.addProperty("document", rs.getString("document"));
                o.addProperty("content",  rs.getString("content"));
                result.add(o);
            }
            return result;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EMBEDDINGS EN SEGUNDO PLANO
    // ═════════════════════════════════════════════════════════════════════

    // Un solo hilo dedicado: los fragmentos se procesan de a uno, sin competir
    // con otras tareas de la JVM. Daemon para no impedir que Tomcat cierre.
    private static final ExecutorService EMBEDDING_EXECUTOR =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "embedding-worker");
            t.setDaemon(true);
            return t;
        });

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);
    private static final int BATCH_SIZE = 50;

    /**
     * Pide que se completen los embeddings pendientes. Si ya hay una pasada en
     * curso, se anota que hay trabajo nuevo y esa misma pasada vuelve a revisar
     * al terminar (así nunca corren dos pasadas a la vez).
     */
    public static void scheduleEmbeddingBackfill() {
        PENDING.set(true);
        if (!RUNNING.compareAndSet(false, true)) return;
        EMBEDDING_EXECUTOR.submit(() -> {
            try {
                while (PENDING.getAndSet(false)) {
                    backfillPendingChunks();
                }
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void backfillPendingChunks() {
        int done = 0;
        while (true) {
            List<String[]> batch = new ArrayList<>();
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id::text, content FROM document_chunks WHERE embedding IS NULL LIMIT ?")) {
                ps.setInt(1, BATCH_SIZE);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) batch.add(new String[]{ rs.getString(1), rs.getString(2) });
            } catch (Exception e) {
                System.err.println("[Embeddings] No se pudieron leer los fragmentos pendientes: " + e.getMessage());
                return;
            }
            if (batch.isEmpty()) break;

            for (String[] chunk : batch) {
                try {
                    float[] embedding = AIService.generateEmbedding(chunk[1]);
                    try (Connection conn = DatabaseConnection.getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "UPDATE document_chunks SET embedding = ? WHERE id = ?::uuid")) {
                        ps.setObject(1, new PGvector(embedding));
                        ps.setString(2, chunk[0]);
                        ps.executeUpdate();
                    }
                    done++;
                } catch (Exception e) {
                    // Si falla (sin clave de OpenAI, límite de la API, red), se corta
                    // esta pasada: los pendientes se reintentan en la próxima.
                    System.err.println("[Embeddings] Se detiene la pasada tras " + done
                            + " fragmentos: " + e.getMessage());
                    return;
                }
            }
        }
        if (done > 0) System.out.println("[Embeddings] Fragmentos completados: " + done);
    }

    /** Llamar desde AppShutdownListener al redeploy/shutdown de Tomcat. */
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
}
