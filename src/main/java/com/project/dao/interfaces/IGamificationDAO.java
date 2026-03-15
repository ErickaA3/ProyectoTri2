package com.project.dao.interfaces;

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
 */
public interface IGamificationDAO {

    /**
     * Obtiene los stats actuales de un usuario.
     * @return JsonObject con xp, level, coins, streakCurrent, streakRecord,
     *         streakLastActivity, hasStreakShield. Null si no existe.
     */
    JsonObject getStats(String userId) throws Exception;

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
    boolean updateStats(String userId, int xp, int level, int coins,
                        int streak, int streakRecord, String lastActivity,
                        boolean shield) throws Exception;

    /**
     * Registra una actividad completada en activity_results.
     * @return UUID del registro creado
     */
    String saveActivityResult(String userId, String contentId,
                              double score, double maxScore,
                              int timeTakenSeconds) throws Exception;

    /**
     * Incrementa el progreso de misiones diarias que coincidan con el tipo de actividad.
     * @param userId       UUID del usuario
     * @param missionType  Tipo de misión: "evaluacion", "flashcard", "contenido", "duelo", "actividad"
     * @return Número de misiones actualizadas
     */
    int advanceDailyMissions(String userId, String missionType) throws Exception;

    /**
     * Incrementa el progreso de objetivos semanales que coincidan.
     * @param userId       UUID del usuario
     * @param objectiveType Tipo: "racha", "actividades", "duelos", "examen_perfecto"
     * @return Número de objetivos actualizados
     */
    int advanceWeeklyObjectives(String userId, String objectiveType) throws Exception;

    /**
     * Obtiene misiones diarias completadas (para dar rewards).
     * @return JsonObject con misiones recién completadas y sus rewards
     */
    JsonObject checkCompletedMissions(String userId) throws Exception;

    /**
     * Obtiene objetivos semanales completados (para dar rewards).
     * @return JsonObject con objetivos recién completados y sus rewards
     */
    JsonObject checkCompletedObjectives(String userId) throws Exception;
}