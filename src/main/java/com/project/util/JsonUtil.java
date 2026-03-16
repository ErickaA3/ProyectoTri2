package com.project.util;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

public class JsonUtil {

    private JsonUtil() {}

    public static void setJsonHeaders(HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
    }

    public static void sendSuccess(HttpServletResponse response, String jsonData) throws IOException {
        setJsonHeaders(response);
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write("{\"success\":true,\"data\":" + jsonData + "}");
    }

    public static void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        setJsonHeaders(response);
        response.setStatus(statusCode);
        response.getWriter().write("{\"success\":false,\"error\":\"" + escape(message) + "\"}");
    }

    // ── Login / Register ───────────────────────────────────────────────────────

    public static String buildUserJson(com.project.model.users.User user,
                                       com.project.model.users.Statistics stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(user.getId()).append("\",");
        sb.append("\"username\":\"").append(escape(user.getUsername())).append("\",");
        sb.append("\"email\":\"").append(escape(user.getEmail())).append("\",");
        sb.append("\"fullName\":\"").append(escape(user.getFullName() != null ? user.getFullName() : "")).append("\",");
        sb.append("\"language\":\"").append(escape(user.getLanguage() != null ? user.getLanguage() : "")).append("\"");

        if (stats != null) {
            sb.append(",\"stats\":{");
            sb.append("\"xp\":").append(stats.getXp()).append(",");
            sb.append("\"level\":").append(stats.getLevel()).append(",");
            sb.append("\"coins\":").append(stats.getCoins()).append(",");
            sb.append("\"streakCurrent\":").append(stats.getStreakCurrent()).append(",");
            sb.append("\"streakRecord\":").append(stats.getStreakRecord()).append(",");
            sb.append("\"hasStreakShield\":").append(stats.isHasStreakShield());
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }

    // ── Perfil completo ────────────────────────────────────────────────────────

    public static String buildProfileJson(
            com.project.model.users.User user,
            com.project.model.users.Statistics stats,
            List<com.project.model.users.WeeklyObjective> weekly,
            List<com.project.model.users.DailyMission> daily) {

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Datos del usuario
        sb.append("\"id\":\"").append(user.getId()).append("\",");
        sb.append("\"username\":\"").append(escape(user.getUsername())).append("\",");
        sb.append("\"email\":\"").append(escape(user.getEmail())).append("\",");
        sb.append("\"fullName\":\"").append(escape(user.getFullName() != null ? user.getFullName() : "")).append("\",");
        sb.append("\"country\":\"").append(escape(user.getCountry() != null ? user.getCountry() : "")).append("\",");
        sb.append("\"language\":\"").append(escape(user.getLanguage() != null ? user.getLanguage() : "")).append("\",");
        sb.append("\"birthdate\":\"").append(user.getBirthdate() != null ? user.getBirthdate().toString() : "").append("\",");
        sb.append("\"createdAt\":\"").append(user.getCreatedAt() != null ? user.getCreatedAt().toString() : "").append("\",");

        // Stats
        if (stats != null) {
            sb.append("\"stats\":{");
            sb.append("\"xp\":").append(stats.getXp()).append(",");
            sb.append("\"level\":").append(stats.getLevel()).append(",");
            sb.append("\"coins\":").append(stats.getCoins()).append(",");
            sb.append("\"streakCurrent\":").append(stats.getStreakCurrent()).append(",");
            sb.append("\"streakRecord\":").append(stats.getStreakRecord()).append(",");
            sb.append("\"hasStreakShield\":").append(stats.isHasStreakShield());
            sb.append("},");
        } else {
            sb.append("\"stats\":null,");
        }

        // Objetivos semanales
        sb.append("\"weeklyObjectives\":[");
        for (int i = 0; i < weekly.size(); i++) {
            com.project.model.users.WeeklyObjective obj = weekly.get(i);
            sb.append("{");
            sb.append("\"description\":\"").append(escape(obj.getObjectiveDescription())).append("\",");
            sb.append("\"requiredCount\":").append(obj.getRequiredCount()).append(",");
            sb.append("\"progress\":").append(obj.getProgress()).append(",");
            sb.append("\"completed\":").append(obj.isCompleted()).append(",");
            sb.append("\"xpReward\":").append(obj.getXpReward()).append(",");
            sb.append("\"coinReward\":").append(obj.getCoinReward());
            sb.append("}");
            if (i < weekly.size() - 1) sb.append(",");
        }
        sb.append("],");

        // Misiones diarias
        sb.append("\"dailyMissions\":[");
        for (int i = 0; i < daily.size(); i++) {
            com.project.model.users.DailyMission dm = daily.get(i);
            sb.append("{");
            sb.append("\"description\":\"").append(escape(dm.getDescription())).append("\",");
            sb.append("\"requiredCount\":").append(dm.getRequiredCount()).append(",");
            sb.append("\"progress\":").append(dm.getProgress()).append(",");
            sb.append("\"completed\":").append(dm.isCompleted()).append(",");
            sb.append("\"xpReward\":").append(dm.getXpReward()).append(",");
            sb.append("\"coinReward\":").append(dm.getCoinReward());
            sb.append("}");
            if (i < daily.size() - 1) sb.append(",");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}