package com.project.servlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.project.database.DatabaseConnection;
import com.project.util.AIService;
import com.project.util.JsonUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        // Leer body completo primero
        String body = request.getReader().lines().collect(Collectors.joining());

        // Obtener userId: primero de la sesión HTTP, luego del body como fallback
        UUID userId = getUserIdFromSession(request);

        if (userId == null) {
            String userIdStr = extractJsonValue(body, "userId");
            if (userIdStr == null || userIdStr.isBlank()) {
                JsonUtil.sendError(response, 401, "No autenticado. Inicia sesión de nuevo.");
                return;
            }
            try {
                userId = UUID.fromString(userIdStr);
            } catch (IllegalArgumentException e) {
                JsonUtil.sendError(response, 400, "ID de usuario inválido: " + userIdStr);
                return;
            }
        }

        String mensaje   = extractJsonValue(body, "mensaje");
        String sessionId = extractJsonValue(body, "sessionId");

        processChat(response, userId, mensaje, sessionId);
    }

    private void processChat(HttpServletResponse response, UUID userId,
                              String mensaje, String sessionId) throws IOException {

        if (mensaje == null || mensaje.isBlank()) {
            JsonUtil.sendError(response, 400, "El mensaje no puede estar vacío.");
            return;
        }

        if (sessionId == null || sessionId.isBlank() || "null".equals(sessionId)) {
            sessionId = UUID.randomUUID().toString();
        }

        try (Connection conn = DatabaseConnection.getConnection()) {

            List<AIService.ChatMessage> historial = loadHistory(conn, userId, sessionId);

            String[] profesorConfig = loadProfesorConfig(conn, userId);
            String profesorNombre  = profesorConfig[0];
            String personalidad    = profesorConfig[1];

            saveMessage(conn, userId, sessionId, "user", mensaje);

            String respuesta = AIService.chat(historial, mensaje, profesorNombre, personalidad);

            saveMessage(conn, userId, sessionId, "assistant", respuesta);

            String json = "{" +
                "\"reply\":" + toJsonString(respuesta) + "," +
                "\"sessionId\":\"" + sessionId + "\"" +
                "}";
            JsonUtil.sendSuccess(response, json);

        } catch (Exception e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        UUID userId = getUserIdFromSession(request);
        if (userId == null) {
            String userIdStr = request.getParameter("userId");
            if (userIdStr != null && !userIdStr.isBlank()) {
                try {
                    userId = UUID.fromString(userIdStr);
                } catch (IllegalArgumentException e) {
                    JsonUtil.sendError(response, 400, "ID de usuario inválido.");
                    return;
                }
            }
        }

        if (userId == null) {
            JsonUtil.sendError(response, 401, "No autenticado.");
            return;
        }

        String sessionId = request.getParameter("sessionId");

        try (Connection conn = DatabaseConnection.getConnection()) {

            if (sessionId != null && !sessionId.isBlank()) {
                List<AIService.ChatMessage> msgs = loadHistory(conn, userId, sessionId);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < msgs.size(); i++) {
                    AIService.ChatMessage m = msgs.get(i);
                    sb.append("{\"role\":").append(toJsonString(m.role))
                      .append(",\"content\":").append(toJsonString(m.content))
                      .append("}");
                    if (i < msgs.size() - 1) sb.append(",");
                }
                sb.append("]");
                JsonUtil.sendSuccess(response, sb.toString());
            } else {
                List<String[]> sessions = loadSessions(conn, userId);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < sessions.size(); i++) {
                    String[] s = sessions.get(i);
                    sb.append("{\"sessionId\":").append(toJsonString(s[0]))
                      .append(",\"firstMessage\":").append(toJsonString(s[1]))
                      .append(",\"createdAt\":").append(toJsonString(s[2]))
                      .append("}");
                    if (i < sessions.size() - 1) sb.append(",");
                }
                sb.append("]");
                JsonUtil.sendSuccess(response, sb.toString());
            }

        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
        setCorsHeaders(res);
        res.setStatus(HttpServletResponse.SC_OK);
    }

    // ── Helper: obtener userId de la sesión HTTP ──────────────────────────────

    private UUID getUserIdFromSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return null;
        }
        try {
            return UUID.fromString((String) session.getAttribute("userId"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Helpers BD ────────────────────────────────────────────────────────────

    private List<AIService.ChatMessage> loadHistory(Connection conn, UUID userId, String sessionId)
            throws SQLException {
        String sql = """
                SELECT role, message FROM chat_history
                WHERE user_id = ? AND session_id = ?
                ORDER BY created_at ASC
                LIMIT 20
                """;
        List<AIService.ChatMessage> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, UUID.fromString(sessionId));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AIService.ChatMessage(rs.getString("role"), rs.getString("message")));
            }
        }
        return list;
    }

    private void saveMessage(Connection conn, UUID userId, String sessionId,
                              String role, String message) throws SQLException {
        String sql = """
                INSERT INTO chat_history (id, user_id, session_id, role, message, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, UUID.fromString(sessionId));
            ps.setString(4, role);
            ps.setString(5, message);
            ps.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private String[] loadProfesorConfig(Connection conn, UUID userId) throws SQLException {
        String sql = "SELECT professor_name, personality FROM professor_config WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{ rs.getString("professor_name"), rs.getString("personality") };
            }
        }
        return new String[]{ "Búho ProfesorIA", "amigable y motivador" };
    }

    private List<String[]> loadSessions(Connection conn, UUID userId) throws SQLException {
        String sql = """
                SELECT DISTINCT ON (session_id)
                    session_id::text,
                    message,
                    created_at::text
                FROM chat_history
                WHERE user_id = ? AND role = 'user'
                ORDER BY session_id, created_at ASC
                """;
        List<String[]> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                    rs.getString("session_id"),
                    rs.getString("message"),
                    rs.getString("created_at")
                });
            }
        }
        return list;
    }

    // ── Parser JSON robusto ───────────────────────────────────────────────────

    private String extractJsonValue(String json, String field) {
        if (json == null) return null;

        String pattern = "\"" + field + "\"";
        int fieldIdx = json.indexOf(pattern);
        if (fieldIdx == -1) return null;

        int colonIdx = json.indexOf(':', fieldIdx + pattern.length());
        if (colonIdx == -1) return null;

        int pos = colonIdx + 1;
        while (pos < json.length() && json.charAt(pos) == ' ') pos++;

        if (pos >= json.length()) return null;

        // Si el valor es null
        if (json.startsWith("null", pos)) return null;

        // Si el valor es un string entre comillas
        if (json.charAt(pos) == '"') {
            int startVal = pos + 1;
            int endVal = startVal;
            while (endVal < json.length()) {
                if (json.charAt(endVal) == '\\') {
                    endVal += 2;
                    continue;
                }
                if (json.charAt(endVal) == '"') break;
                endVal++;
            }
            if (endVal > startVal) {
                return json.substring(startVal, endVal);
            }
        }

        return null;
    }

    private String toJsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5500");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}