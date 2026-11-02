package com.project.dao.interfaces;

import com.project.model.content.EducationalContent;
import java.util.List;

/**
 * Contrato para operaciones sobre generated_content (antes study_content).
 * ContentDAOImpl implementa esto con el SQL real.
 *
 * Todos los IDs son String (UUID). El dueño de un contenido es quien lo
 * generó (created_by). Lo borrado (deleted_at) no aparece en ningún método.
 */
public interface IContentDAO {

    /**
     * Guarda contenido generado.
     * @param content     Objeto con userId (dueño), type y title
     * @param contentJson JSON generado por la IA (columna body)
     * @param topicId     Tema donde se guarda; null solo para el quiz de un duelo
     * @param documentId  Documento del que salió; sus fragmentos quedan en
     *                    content_sources. null si no viene de un documento.
     * @return            UUID del registro creado, como String
     */
    String save(EducationalContent content, String contentJson,
                String topicId, String documentId) throws Exception;

    /**
     * Contenido de la biblioteca del usuario, con filtro opcional por tipo.
     * @param type "flashcard" | "schema" | "summary" | "quiz" | "expert_exam" | null para todos
     */
    List<EducationalContent> getByUser(String userId, String type) throws Exception;

    /** Favoritos del usuario. */
    List<EducationalContent> getFavorites(String userId) throws Exception;

    /**
     * Marca o desmarca un contenido como favorito del usuario.
     * Verifica que el contenido sea suyo.
     */
    boolean toggleFavorite(String contentId, String userId, boolean isFavorite) throws Exception;

    /**
     * Borra un contenido (borrado lógico: desaparece de la biblioteca, pero sus
     * resultados y los duelos que lo usaron se conservan).
     */
    boolean delete(String contentId, String userId) throws Exception;

    /**
     * JSON completo de un contenido: type, title, isFavorite y content.
     * @return null si no existe o no pertenece al usuario
     */
    String getContentJson(String contentId, String userId) throws Exception;

    /**
     * Solo el tipo de un contenido, verificando que pertenezca al usuario. Se
     * usa para validar en el servidor que un contentId que llega en un request
     * existe, es del usuario y es del tipo que dice ser (ej: no puede reclamar
     * recompensa de "quiz" pasando el id de un resumen).
     * @return el tipo, o null si no existe o no es de ese usuario
     */
    String getContentType(String contentId, String userId) throws Exception;

    /**
     * Datos de ubicación de un contenido: createdAt, topicId, topicName,
     * subjectId, subjectName.
     * @return null si no existe o no pertenece al usuario
     */
    String[] getMetadata(String contentId, String userId) throws Exception;
}
