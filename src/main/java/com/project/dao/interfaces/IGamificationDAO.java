package com.project.dao.interfaces;

import java.sql.Connection;

import com.google.gson.JsonObject;

/**
 * Contrato para operaciones de gamificación en la BD.
 * Trabaja con user_stats y activity_results.
 *
 * Tablas involucradas:
 *   user_stats          — xp, level, coins, streak_current, streak_record,
 *                          streak_last_activity, has_streak_shield
 *   activity_results    — registro de cada actividad completada
 *   user_daily_missions — progreso de misiones diarias
 *
 * Los métodos que reciben una Connection se usan dentro de la transacción de
 * GamificationService.processActivity(): no abren ni cierran la conexión, ni
 * hacen commit. Así todos los cambios de una actividad se guardan juntos o no
 * se guarda ninguno.
 */
public interface IGamificationDAO {

    /**
     * Obtiene los stats actuales de un usuario (solo lectura, conexión propia).
     * @return JsonObject con xp, level, coins, streakCurrent, streakRecord,
     *         streakLastActivity, hasStreakShield. Null si no existe.
     */
    JsonObject getStats(String userId) throws Exception;

    /**
     * Igual que getStats, pero bloquea la fila de user_stats (SELECT ... FOR UPDATE)
     * hasta que termine la transacción. Evita que dos actividades simultáneas
     * del mismo usuario lean el mismo saldo y una pise a la otra.
     */
    JsonObject getStatsForUpdate(Connection conn, String userId) throws Exception;

    /**
     * Actualiza XP, nivel, monedas y racha de un usuario.
     * @param userId       UUID del usuario
     * @param xp           XP total nuevo (ya calculado)
     * @param level        Nivel nuevo (ya calculado)
     * @param coins        Coins nuevas (ya calculadas)
     * @param streak       Racha actual
     * @param streakRecord Récord de racha
     * @param lastActivity Fecha de última actividad (YYYY-MM-DD)
     * @param shield       Si tiene escudo de racha
     */
    boolean updateStats(Connection conn, String userId, int xp, int level, int coins,
                        int streak, int streakRecord, String lastActivity,
                        boolean shield) throws Exception;

    /**
     * Registra un intento terminado sobre un contenido (attempts). Termina
     * ahora y empezó timeTakenSeconds antes.
     * @param status "completed" o "abandoned"
     * @return UUID del intento
     */
    String createAttempt(Connection conn, String userId, String contentId,
                         String status, int timeTakenSeconds) throws Exception;

    /**
     * ¿Hubo un intento anterior de este usuario sobre este contenido?
     * Se usa para no pagar dos veces por el mismo contenido.
     * @param completedOnly true: solo cuentan los intentos completados
     */
    boolean hasPreviousAttempt(Connection conn, String userId, String contentId,
                               boolean completedOnly) throws Exception;

    /**
     * Califica y guarda las respuestas de un intento contra content_items e
     * item_options (nunca contra lo que diga el cliente sobre si acertó).
     * @param answers     [{position, option}] para preguntas, [{position, correct}] para flashcards
     * @param flashcards  true: cada respuesta es una autoevaluación (recall_rating)
     * @return {aciertos, total de ítems presentados}
     * @throws IllegalArgumentException si una respuesta no corresponde a un ítem u opción del contenido
     */
    int[] saveGradedAnswers(Connection conn, String attemptId, String contentId,
                            com.google.gson.JsonArray answers, boolean flashcards) throws Exception;

    /** Guarda en el intento lo que se pagó por él. */
    void setAttemptRewards(Connection conn, String attemptId, int xp, int coins) throws Exception;

    /**
     * Incrementa el progreso de misiones diarias que coincidan con el tipo de actividad.
     * @param userId       UUID del usuario
     * @param missionType  Tipo de misión (missions.type)
     * @param today        Fecha de "hoy" calculada en Java (con la zona horaria de la app,
     *                     NO se usa CURRENT_DATE de Postgres — esa corre en la zona del
     *                     servidor de BD, que puede no coincidir con la del usuario)
     * @return Número de misiones actualizadas
     */
    int advanceDailyMissions(Connection conn, String userId, String missionType,
                             java.time.LocalDate today) throws Exception;

    /**
     * Incrementa el progreso de objetivos semanales que coincidan.
     * @param userId        UUID del usuario
     * @param objectiveType Tipo (objectives.type)
     * @param weekStart     Lunes de la semana actual, calculado en Java con la zona de la app
     * @return Número de objetivos actualizados
     */
    int advanceWeeklyObjectives(Connection conn, String userId, String objectiveType,
                                java.time.LocalDate weekStart) throws Exception;

    /**
     * Marca como completadas las misiones diarias que llegaron a su meta.
     * @return JsonObject con misiones recién completadas y sus rewards
     */
    JsonObject checkCompletedMissions(Connection conn, String userId,
                                      java.time.LocalDate today) throws Exception;

    /**
     * Marca como completados los objetivos semanales que llegaron a su meta.
     * @return JsonObject con objetivos recién completados y sus rewards
     */
    JsonObject checkCompletedObjectives(Connection conn, String userId,
                                        java.time.LocalDate weekStart) throws Exception;
}
