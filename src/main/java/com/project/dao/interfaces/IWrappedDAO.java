package com.project.dao.interfaces;

import com.google.gson.JsonObject;

/**
 * Contrato para el resumen semanal ("Wrapped") del estudiante.
 * Combina datos de activity_results, chat_history, user_stats,
 * user_daily_missions, user_weekly_objectives y leaderboard_history.
 *
 * La semana se calcula de lunes a hoy (date_trunc('week', ...) en Postgres).
 */
public interface IWrappedDAO {

    /**
     * Arma el resumen semanal de un usuario.
     * @return JsonObject con:
     *   studyHours, studyCompare,
     *   topSubject, topPct, weakSubject, questionsCount,
     *   freqTopic, freqDetail,
     *   streak, xp, coins,
     *   suggestionTitle, suggestionText
     */
    JsonObject getWeeklySummary(String userId) throws Exception;
}