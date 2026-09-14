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

    boolean finishDuelWithWinner(String duelId, String winnerId) throws Exception;

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
     *
     * [SOLO USO INTERNO — NO EXPONER DIRECTO A UN ENDPOINT HTTP]
     * El score/maxScore/isCorrect de cada respuesta se reciben ya
     * calculados y confiables porque el único llamador es
     * DuelWebSocket, que valida cada respuesta contra las preguntas
     * reales en DuelRoom ANTES de llegar acá (ver DuelRoom.isCorrectAnswer).
     * Para un submit que viene directo de un request HTTP (sin pasar por el
     * WebSocket), usar submitDuelResultGraded(), que recalcula todo en el
     * servidor y no confía en nada del cliente.
     *
     * @param duelId     UUID del duelo
     * @param userId     UUID del jugador que terminó
     * @param score      Puntaje (correctas) — ya validado por el llamador
     * @param maxScore   Total de preguntas
     * @param timeSecs   Tiempo en segundos
     * @param answers    JSON array con las respuestas [{questionIndex, answerGiven, isCorrect, timeMs}]
     * @return true si se guardó; si ambos ya jugaron, declara ganador automáticamente
     */
    JsonObject submitDuelResult(String duelId, String userId, int score,
                                int maxScore, int timeSecs, String answersJson) throws Exception;

    /**
     * Guardar el resultado de un jugador en un duelo, cuando el submit viene
     * DIRECTO de un endpoint HTTP (POST /api/duels/submit) y por lo tanto
     * nada de lo que mande el cliente es confiable.
     *
     * A diferencia de submitDuelResult(), acá el score y la corrección de
     * cada respuesta NO se reciben del cliente: se recalculan en el
     * servidor comparando cada "answerGiven" contra la respuesta correcta
     * real, guardada en study_content para ese duelo. El maxScore también
     * se toma del question_count real del duelo, nunca del body.
     *
     * @param duelId      UUID del duelo
     * @param userId      UUID del jugador que terminó
     * @param rawAnswers  JSON array [{questionIndex, answerGiven}] — solo el
     *                    índice de la opción elegida, nada de "isCorrect"
     * @param timeSecsRaw Tiempo reportado por el cliente (se acota en servidor
     *                    a un rango razonable antes de guardarse)
     * @return mismo shape de respuesta que submitDuelResult()
     */
    JsonObject submitDuelResultGraded(String duelId, String userId,
                                      JsonArray rawAnswers, int timeSecsRaw) throws Exception;

    /** Obtener preguntas del quiz del duelo (desde study_content). */
    JsonObject getDuelQuestions(String duelId, String userId) throws Exception;
}