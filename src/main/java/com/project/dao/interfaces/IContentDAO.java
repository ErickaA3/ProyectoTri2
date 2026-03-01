package com.project.dao.interfaces;

import com.project.model.content.EducationalContent;
import java.util.List;

/**
 * Contrato para operaciones sobre study_content.
 * ContentDAOImpl implementa esto con el SQL real.
 *
 * Todos los IDs son String (UUID) — así está definido en Supabase.
 */
public interface IContentDAO {

    /**
     * Guarda contenido en study_content.
     * @param content     Objeto con userId, type, title, sessionId
     * @param contentJson JSON generado por la IA (va a la columna "content")
     * @param sourceText  Texto original del usuario (columna "source_text")
     * @return            UUID del registro creado, como String
     */
    String save(EducationalContent content, String contentJson, String sourceText) throws Exception;

    /**
     * Obtiene todo el contenido de un usuario, con filtro opcional por tipo.
     * @param userId UUID del usuario como String
     * @param type   "flashcard" | "schema" | "summary" | "quiz" | null para todos
     */
    List<EducationalContent> getByUser(String userId, String type) throws Exception;

    /**
     * Obtiene todos los favoritos de un usuario.
     * @param userId UUID del usuario como String
     */
    List<EducationalContent> getFavorites(String userId) throws Exception;

    /**
     * Marca o desmarca un contenido como favorito.
     * Verifica que el contentId pertenezca al userId antes de actualizar.
     */
    boolean toggleFavorite(String contentId, String userId, boolean isFavorite) throws Exception;

    /**
     * Elimina un contenido por ID.
     * Verifica que pertenezca al usuario antes de borrar.
     */
    boolean delete(String contentId, String userId) throws Exception;
}