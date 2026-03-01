package com.project.model.content;

import java.time.LocalDateTime;

/**
 * Clase abstracta base para todo contenido generado por IA.
 * Flashcard, Diagram y Summary la extienden.
 * Todos van a la tabla study_content, el campo "type" los diferencia.
 *
 * NOTAS SOBRE LA BD:
 * - id y userId son UUID (String) — así está definido en Supabase
 * - sessionId agrupa todos los contenidos generados en una misma sesión
 *   Para que funcione, ejecutar en Supabase:
 *   ALTER TABLE study_content ADD COLUMN session_id UUID;
 *   ALTER TABLE study_content ADD COLUMN source_text TEXT;
 */
public abstract class EducationalContent {

    private String id;          // UUID — generado por Supabase al insertar
    private String userId;      // UUID — viene del sessionStorage del frontend
    private String sessionId;   // UUID — agrupa todo lo generado en una misma sesión
    private String type;        // "flashcard" | "schema" | "summary" | "quiz"
    private String title;
    private boolean isFavorite;
    private LocalDateTime createdAt;

    // Constructor vacío para Gson
    public EducationalContent() {}

    public EducationalContent(String userId, String type, String title, String sessionId) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.sessionId = sessionId;
        this.isFavorite = false;
        this.createdAt = LocalDateTime.now();
    }

    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public boolean isFavorite() { return isFavorite; }
    public void setFavorite(boolean favorite) { isFavorite = favorite; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
