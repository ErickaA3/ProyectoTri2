package com.project.util;

import java.io.IOException;

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
        response.getWriter().write("{\"success\":false,\"message\":\"" + escape(message) + "\"}");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String buildUserJson(com.project.model.users.User user, com.project.model.users.Statistics stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(user.getId()).append("\",");
        sb.append("\"username\":\"").append(escape(user.getUsername())).append("\",");
        sb.append("\"email\":\"").append(escape(user.getEmail())).append("\",");
        sb.append("\"fullName\":\"").append(escape(user.getFullName() != null ? user.getFullName() : "")).append("\",");
        sb.append("\"language\":\"").append(escape(user.getLanguage())).append("\"");
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
}