package com.project.dao.interfaces;

import java.sql.Connection;
import java.time.LocalDate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Contrato para la gamificación.
 *
 * Desde la V7 nada de esto se guarda como contador: XP, monedas, nivel, racha
 * y progreso de misiones se calculan (v_user_stats, v_user_mission_progress)
 * a partir de los eventos. Lo que sí se guarda es lo PAGADO por cada evento:
 * attempts.xp_awarded, duel_participants.xp_awarded, user_missions.xp_awarded.
 *
 * Los métodos que reciben una Connection trabajan dentro de la transacción de
 * GamificationService y no la cierran ni hacen commit.
 */
public interface IGamificationDAO {

    /** Bloquea la fila del usuario hasta el commit: sus actividades se procesan de a una. */
    void lockUser(Connection conn, String userId) throws Exception;

    /**
     * Stats actuales (v_user_stats): xp, level, coins, streakCurrent,
     * streakRecord, streakLastActivity, hasStreakShield. Null si no existe.
     */
    JsonObject getStats(String userId) throws Exception;
    JsonObject getStats(Connection conn, String userId) throws Exception;

    /** XP mínimo de cada nivel (levels), ordenado: índice 0 = nivel 1. */
    int[] getLevelThresholds() throws Exception;

    // ── Intentos ────────────────────────────────────────────────────────────

    /**
     * Registra un intento terminado sobre un contenido (attempts). Termina
     * ahora y empezó timeTakenSeconds antes.
     * @param status "completed" o "abandoned"
     */
    String createAttempt(Connection conn, String userId, String contentId,
                         String status, int timeTakenSeconds) throws Exception;

    /**
     * ¿Hubo un intento anterior de este usuario sobre este contenido?
     * @param completedOnly true: solo cuentan los intentos completados
     */
    boolean hasPreviousAttempt(Connection conn, String userId, String contentId,
                               boolean completedOnly) throws Exception;

    /**
     * Califica y guarda las respuestas de un intento contra content_items e
     * item_options (nunca contra lo que diga el cliente sobre si acertó).
     * @return {aciertos, total de ítems presentados}
     * @throws IllegalArgumentException si una respuesta no corresponde al contenido
     */
    int[] saveGradedAnswers(Connection conn, String attemptId, String contentId,
                            JsonArray answers, boolean flashcards) throws Exception;

    /** Guarda en el intento lo que se pagó por él. */
    void setAttemptRewards(Connection conn, String attemptId, int xp, int coins) throws Exception;

    /** Guarda en duel_participants lo que se pagó a ese jugador por el duelo. */
    void setDuelRewards(Connection conn, String duelId, String userId, int xp, int coins) throws Exception;

    // ── Racha ───────────────────────────────────────────────────────────────

    /**
     * Si ayer no hubo actividad, anteayer sí, y el usuario tiene un protector
     * disponible, lo usa para cubrir el día de ayer (la racha no se corta).
     * @return true si usó un protector
     */
    boolean useStreakShieldIfNeeded(Connection conn, String userId, LocalDate today) throws Exception;

    // ── Misiones ────────────────────────────────────────────────────────────

    /** Asigna al usuario las misiones activas del día y de la semana (si faltan). */
    void ensureMissions(Connection conn, String userId, LocalDate today, LocalDate weekStart) throws Exception;

    /**
     * Marca como completadas las misiones del período actual que ya llegaron
     * a su meta y guarda lo pagado.
     * @return completedMissions / missionXp / missionCoins (diarias) y
     *         completedObjectives / objectiveXp / objectiveCoins (semanales)
     */
    JsonObject completeReachedMissions(Connection conn, String userId,
                                       LocalDate today, LocalDate weekStart) throws Exception;
}
