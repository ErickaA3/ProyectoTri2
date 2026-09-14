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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.database.DatabaseConnection;
import com.project.util.AIService;
import com.project.util.JsonUtil;
import com.project.util.UserContextService;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    // POST /api/chat
    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        String body = request.getReader()
                .lines()
                .collect(Collectors.joining());

        UUID userId = resolveUserId(request);

        if (userId == null) {
            JsonUtil.sendError(
                    response,
                    401,
                    "No autenticado. Inicia sesión de nuevo."
            );
            return;
        }

        String mensaje;
        String sessionId;

        try {
            JsonObject json = JsonParser
                    .parseString(body)
                    .getAsJsonObject();

            mensaje = json.has("mensaje")
                    && !json.get("mensaje").isJsonNull()
                    ? json.get("mensaje").getAsString()
                    : null;

            sessionId = json.has("sessionId")
                    && !json.get("sessionId").isJsonNull()
                    ? json.get("sessionId").getAsString()
                    : null;

        } catch (Exception e) {
            JsonUtil.sendError(
                    response,
                    400,
                    "Body inválido, se esperaba JSON."
            );
            return;
        }

        if (mensaje == null || mensaje.isBlank()) {
            JsonUtil.sendError(
                    response,
                    400,
                    "El mensaje no puede estar vacío."
            );
            return;
        }

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        final String finalSessionId = sessionId;

        try {
            List<AIService.ChatMessage> historial;
            String profesorNombre;
            String personalidad;

            // Obtener historial y configuración del profesor
            try (Connection conn = DatabaseConnection.getConnection()) {

                historial = loadHistory(
                        conn,
                        userId,
                        finalSessionId
                );

                String[] profesorConfig =
                        loadProfesorConfig(conn, userId);

                profesorNombre = profesorConfig[0];
                personalidad = profesorConfig[1];

                // Guardar mensaje del usuario
                saveMessage(
                        conn,
                        userId,
                        finalSessionId,
                        "user",
                        mensaje
                );
            }

            // Construir contexto para la IA
            StringBuilder searchContext =
                    new StringBuilder();

            int start =
                    Math.max(0, historial.size() - 6);

            for (int i = start;
                    i < historial.size();
                    i++) {

                searchContext
                        .append(historial.get(i).content)
                        .append(" ");
            }

            searchContext.append(mensaje);

            String contexto =
                    UserContextService.buildContext(
                            userId,
                            searchContext.toString()
                    );

            // Llamar a la IA
            String respuesta = AIService.chat(
                    historial,
                    mensaje,
                    profesorNombre,
                    personalidad,
                    contexto
            );

            // Guardar respuesta del asistente
            try (Connection conn =
                    DatabaseConnection.getConnection()) {

                saveMessage(
                        conn,
                        userId,
                        finalSessionId,
                        "assistant",
                        respuesta
                );
            }

            // Respuesta al frontend
            JsonObject responseJson =
                    new JsonObject();

            responseJson.addProperty(
                    "reply",
                    respuesta
            );

            responseJson.addProperty(
                    "sessionId",
                    finalSessionId
            );

            JsonUtil.sendSuccess(
                    response,
                    responseJson.toString()
            );

        } catch (Exception e) {

            e.printStackTrace();

            JsonUtil.sendError(
                    response,
                    500,
                    "Error interno del servidor."
            );
        }
    }

    // GET /api/chat?sessionId=xxx
    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        UUID userId = resolveUserId(request);

        if (userId == null) {
            JsonUtil.sendError(
                    response,
                    401,
                    "No autenticado."
            );
            return;
        }

        String sessionId =
                request.getParameter("sessionId");

        try (Connection conn =
                DatabaseConnection.getConnection()) {

            if (sessionId != null
                    && !sessionId.isBlank()) {

                // Historial de una sesión
                List<AIService.ChatMessage> msgs =
                        loadHistory(
                                conn,
                                userId,
                                sessionId
                        );

                JsonArray arr = new JsonArray();

                for (AIService.ChatMessage m : msgs) {

                    JsonObject o = new JsonObject();

                    o.addProperty("role", m.role);
                    o.addProperty("content", m.content);

                    arr.add(o);
                }

                JsonUtil.sendSuccess(
                        response,
                        arr.toString()
                );

            } else {

                // Lista de sesiones
                List<String[]> sessions =
                        loadSessions(conn, userId);

                JsonArray arr = new JsonArray();

                for (String[] s : sessions) {

                    JsonObject o = new JsonObject();

                    o.addProperty(
                            "sessionId",
                            s[0]
                    );

                    o.addProperty(
                            "firstMessage",
                            s[1]
                    );

                    o.addProperty(
                            "createdAt",
                            s[2]
                    );

                    arr.add(o);
                }

                JsonUtil.sendSuccess(
                        response,
                        arr.toString()
                );
            }

        } catch (IllegalArgumentException e) {

            JsonUtil.sendError(
                    response,
                    400,
                    "sessionId inválido."
            );

        } catch (Exception e) {

            e.printStackTrace();

            JsonUtil.sendError(
                    response,
                    500,
                    "Error interno del servidor."
            );
        }
    }

    // DELETE /api/chat?sessionId=xxx
    @Override
    protected void doDelete(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        UUID userId = resolveUserId(request);

        if (userId == null) {
            JsonUtil.sendError(
                    response,
                    401,
                    "No autenticado."
            );
            return;
        }

        String sessionId =
                request.getParameter("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            JsonUtil.sendError(
                    response,
                    400,
                    "Se requiere sessionId."
            );
            return;
        }

        try (Connection conn =
                DatabaseConnection.getConnection()) {

            String sql =
                    "DELETE FROM chat_history " +
                    "WHERE user_id = ? AND session_id = ?";

            try (PreparedStatement ps =
                    conn.prepareStatement(sql)) {

                ps.setObject(1, userId);

                ps.setObject(
                        2,
                        UUID.fromString(sessionId)
                );

                int deleted = ps.executeUpdate();

                String json =
                        "{\"deleted\":" + deleted + "}";

                JsonUtil.sendSuccess(
                        response,
                        json
                );
            }

        } catch (IllegalArgumentException e) {

            JsonUtil.sendError(
                    response,
                    400,
                    "sessionId inválido."
            );

        } catch (SQLException e) {

            e.printStackTrace();

            JsonUtil.sendError(
                    response,
                    500,
                    "Error al eliminar el chat."
            );
        }
    }

    // Obtener el ID del usuario autenticado
    private UUID resolveUserId(
            HttpServletRequest request) {

        String uid =
                (String) request.getAttribute("userId");

        if (uid == null) {

            HttpSession session =
                    request.getSession(false);

            if (session != null) {

                Object attr =
                        session.getAttribute("userId");

                if (attr != null) {
                    uid = attr.toString();
                }
            }
        }

        if (uid == null) {
            return null;
        }

        try {

            return UUID.fromString(uid);

        } catch (IllegalArgumentException e) {

            return null;
        }
    }

    // Cargar historial del chat
    private List<AIService.ChatMessage> loadHistory(
            Connection conn,
            UUID userId,
            String sessionId)
            throws Exception {

        String sql = """
                SELECT role, message
                FROM chat_history
                WHERE user_id = ? AND session_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """;

        List<AIService.ChatMessage> list =
                new ArrayList<>();

        try (PreparedStatement ps =
                conn.prepareStatement(sql)) {

            ps.setObject(1, userId);

            ps.setObject(
                    2,
                    UUID.fromString(sessionId)
            );

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {

                list.add(
                        new AIService.ChatMessage(
                                rs.getString("role"),
                                rs.getString("message")
                        )
                );
            }
        }

        // Orden cronológico
        java.util.Collections.reverse(list);

        return list;
    }

    // Guardar mensaje
    private void saveMessage(
            Connection conn,
            UUID userId,
            String sessionId,
            String role,
            String message)
            throws Exception {

        String sql = """
                INSERT INTO chat_history
                (id, user_id, session_id, role, message, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement ps =
                conn.prepareStatement(sql)) {

            ps.setObject(
                    1,
                    UUID.randomUUID()
            );

            ps.setObject(2, userId);

            ps.setObject(
                    3,
                    UUID.fromString(sessionId)
            );

            ps.setString(4, role);
            ps.setString(5, message);

            ps.setTimestamp(
                    6,
                    new Timestamp(
                            System.currentTimeMillis()
                    )
            );

            ps.executeUpdate();
        }
    }

    // Cargar configuración del profesor
    private String[] loadProfesorConfig(
            Connection conn,
            UUID userId)
            throws Exception {

        String sql =
                "SELECT professor_name, personality " +
                "FROM professor_config " +
                "WHERE user_id = ?";

        try (PreparedStatement ps =
                conn.prepareStatement(sql)) {

            ps.setObject(1, userId);

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {

                return new String[]{
                        rs.getString("professor_name"),
                        rs.getString("personality")
                };
            }
        }

        return new String[]{
                "Mi ProfesorIA",
                "amigable y motivador"
        };
    }

    // Cargar sesiones anteriores
    private List<String[]> loadSessions(
            Connection conn,
            UUID userId)
            throws Exception {

        String sql = """
                WITH first_msgs AS (
                    SELECT DISTINCT ON (session_id)
                        session_id,
                        message,
                        created_at
                    FROM chat_history
                    WHERE user_id = ? AND role = 'user'
                    ORDER BY session_id, created_at ASC
                ),
                last_activity AS (
                    SELECT
                        session_id,
                        MAX(created_at) AS last_at
                    FROM chat_history
                    WHERE user_id = ?
                    GROUP BY session_id
                )
                SELECT
                    f.session_id::text,
                    f.message,
                    f.created_at::text
                FROM first_msgs f
                JOIN last_activity l
                    ON l.session_id = f.session_id
                ORDER BY l.last_at DESC
                """;

        List<String[]> list =
                new ArrayList<>();

        try (PreparedStatement ps =
                conn.prepareStatement(sql)) {

            ps.setObject(1, userId);
            ps.setObject(2, userId);

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {

                list.add(
                        new String[]{
                                rs.getString("session_id"),
                                rs.getString("message"),
                                rs.getString("created_at")
                        }
                );
            }
        }

        return list;
    }
}