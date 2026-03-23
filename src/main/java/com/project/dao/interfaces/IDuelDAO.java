package com.project.dao.interfaces;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Contrato para operaciones de duelos y amistades.
 * Tablas: friendships, duels, duel_answers, study_content
 */
public interface IDuelDAO {

    // ═══ AMIGOS ═══

    /** Enviar solicitud de amistad por email o username. Retorna el id de la solicitud. */
    String sendFriendRequest(String senderId, String receiverEmailOrUsername) throws Exception;

    /** Aceptar solicitud de amistad. */
    boolean acceptFriendRequest(String friendshipId, String userId) throws Exception;

    /** Rechazar solicitud de amistad. */
    boolean rejectFriendRequest(String friendshipId, String userId) throws Exception;

    /** Eliminar amigo (borrar la amistad aceptada). */
    boolean removeFriend(String friendshipId, String userId) throws Exception;

    /** Lista de amigos aceptados con sus stats (nivel, username, etc). */
    JsonArray getFriends(String userId) throws Exception;

    /** Solicitudes pendientes recibidas. */
    JsonArray getPendingRequests(String userId) throws Exception;

    // ═══ DUELOS ═══

    /**
     * Crear un duelo nuevo.
     * @param challengerId  UUID del retador
     * @param opponentId    UUID del oponente
     * @param contentId     UUID del quiz generado (study_content)
     * @param topic         Tema del duelo
     * @param questionCount Número de preguntas (5, 10 o 15)
     * @return UUID del duelo creado
     */
    String createDuel(String challengerId, String opponentId, String contentId,
                      String topic, int questionCount, int timePerQuestion) throws Exception;

    /** Obtener info completa de un duelo por ID. */
    JsonObject getDuel(String duelId, String userId) throws Exception;

    /** Duelos activos del usuario (donde es challenger u opponent). */
    JsonArray getActiveDuels(String userId) throws Exception;

    /** Historial de duelos terminados. */
    JsonArray getDuelHistory(String userId) throws Exception;

    /** Declinar un duelo pendiente. */
    boolean declineDuel(String duelId, String userId) throws Exception;

    /**
     * Guardar el resultado de un jugador en un duelo.
     * @param duelId     UUID del duelo
     * @param userId     UUID del jugador que terminó
     * @param score      Puntaje (correctas)
     * @param maxScore   Total de preguntas
     * @param timeSecs   Tiempo en segundos
     * @param answers    JSON array con las respuestas [{questionIndex, answerGiven, isCorrect, timeMs}]
     * @return true si se guardó; si ambos ya jugaron, declara ganador automáticamente
     */
    JsonObject submitDuelResult(String duelId, String userId, int score,
                                int maxScore, int timeSecs, String answersJson) throws Exception;

    /** Obtener preguntas del quiz del duelo (desde study_content). */
    JsonObject getDuelQuestions(String duelId, String userId) throws Exception;
}