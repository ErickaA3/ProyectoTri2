package com.project.dao.interfaces;

import com.google.gson.JsonArray;

/**
 * Biblioteca del estudiante: materias (subjects), temas (topics) y el material
 * original (documents + document_chunks).
 *
 * Todas las operaciones verifican que la materia o el tema pertenezcan al
 * usuario. Los métodos que modifican devuelven false (o null) si no existe o
 * no es suyo, para que el servlet responda 404 sin revelar si existe.
 */
public interface ILibraryDAO {

    // ── Materias ────────────────────────────────────────────────────────────

    /** Materias activas del usuario, cada una con sus temas activos y conteos. */
    JsonArray listSubjects(String userId) throws Exception;

    /** @return id de la materia creada. Lanza excepción si ya existe una con ese nombre. */
    String createSubject(String userId, String name, String color) throws Exception;

    boolean updateSubject(String userId, String subjectId, String name, String color) throws Exception;

    /** Archiva la materia (no se borra nada: el contenido se conserva). */
    boolean archiveSubject(String userId, String subjectId) throws Exception;

    // ── Temas ───────────────────────────────────────────────────────────────

    /** @return id del tema creado, o null si la materia no existe o no es del usuario. */
    String createTopic(String userId, String subjectId, String name) throws Exception;

    boolean renameTopic(String userId, String topicId, String name) throws Exception;

    boolean archiveTopic(String userId, String topicId) throws Exception;

    boolean topicBelongsToUser(String topicId, String userId) throws Exception;

    boolean subjectBelongsToUser(String subjectId, String userId) throws Exception;

    /**
     * Tema "General" de la materia "Sin clasificar" del usuario; lo crea si no
     * existe. Es donde va lo que se genera sin elegir tema.
     */
    String ensureDefaultTopic(String userId) throws Exception;

    // ── Documentos (RAG) ────────────────────────────────────────────────────

    /**
     * Guarda el material original en un tema y lo divide en fragmentos.
     * Los embeddings se calculan en segundo plano.
     * @return id del documento
     */
    String createDocument(String topicId, String userId, String fileName,
                          String mimeType, String text) throws Exception;

    /**
     * Fragmentos del material del usuario más parecidos a la consulta
     * (distancia coseno). Cada elemento: chunkId, subject, topic, document, content.
     */
    JsonArray searchChunks(String userId, float[] queryEmbedding, int limit) throws Exception;

    /**
     * Igual, pero limitado a una materia o a un tema (alcance de una sesión de
     * chat). Los dos en null = todo el material del usuario.
     */
    JsonArray searchChunks(String userId, float[] queryEmbedding, int limit,
                           String subjectId, String topicId) throws Exception;
}
