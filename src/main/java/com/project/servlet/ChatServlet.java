package com.project.servlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.database.DatabaseConnection;
import com.project.util.AIService;
import com.project.util.JsonUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

// Servlet del chat con el profesor IA: recibe mensajes del usuario, mantiene
// el historial por sesión en BD y delega la generación de respuestas a AIService.
// POST envía un mensaje y obtiene la respuesta; GET consulta el historial o la
// lista de sesiones previas del usuario autenticado.
@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    // POST /api/chat → recibe mensaje, llama a IA, guarda en BD, responde
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        // Verificar sesión
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            JsonUtil.sendError(response, 401, "No autenticado.");
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString((String) session.getAttribute("userId"));
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "ID de usuario inválido.");
            return;
        }

        // Leer body con Gson en vez de buscar comillas a mano — así un mensaje
        // con comillas, saltos de línea o barras invertidas no rompe el parseo.
        String body = request.getReader().lines().collect(Collectors.joining());
        String mensaje;
        String sessionId;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            mensaje   = json.has("mensaje")   && !json.get("mensaje").isJsonNull()   ? json.get("mensaje").getAsString()   : null;
            sessionId = json.has("sessionId") && !json.get("sessionId").isJsonNull() ? json.get("sessionId").getAsString() : null;
        } catch (Exception e) {
            JsonUtil.sendError(response, 400, "Body inválido, se esperaba JSON.");
            return;
        }

        if (mensaje == null || mensaje.isBlank()) {
            JsonUtil.sendError(response, 400, "El mensaje no puede estar vacío.");
            return;
        }

        // Generar sessionId si no viene
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        try (Connection conn = DatabaseConnection.getConnection()) {

            // 1. Cargar historial de esta sesión (últimos 10 mensajes)
            List<AIService.ChatMessage> historial = loadHistory(conn, userId, sessionId);

            // 2. Cargar configuración del profesor
            String[] profesorConfig = loadProfesorConfig(conn, userId);
            String profesorNombre  = profesorConfig[0];
            String personalidad    = profesorConfig[1];

            // 3. Guardar mensaje del usuario en BD
            saveMessage(conn, userId, sessionId, "user", mensaje);

            // 4. Llamar a la IA
            String respuesta = AIService.chat(historial, mensaje, profesorNombre, personalidad);

            // 5. Guardar respuesta del asistente en BD
            saveMessage(conn, userId, sessionId, "assistant", respuesta);

            // 6. Responder al frontend (usando Gson para armar el JSON, evita
            //    errores de escape que tenía la concatenación manual anterior)
            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("reply", respuesta);
            responseJson.addProperty("sessionId", sessionId);
            JsonUtil.sendSuccess(response, responseJson.toString());

        } catch (Exception e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    // GET /api/chat?sessionId=xxx → historial de una sesión
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            JsonUtil.sendError(response, 401, "No autenticado.");
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString((String) session.getAttribute("userId"));
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "ID de usuario inválido.");
            return;
        }

        String sessionId = request.getParameter("sessionId");

        // Antes solo se atrapaba SQLException — un sessionId mal formado
        // (UUID.fromString) lanzaba IllegalArgumentException y se colaba
        // sin control. Ahora se atrapa cualquier Exception.
        try (Connection conn = DatabaseConnection.getConnection()) {

            if (sessionId != null && !sessionId.isBlank()) {
                // Historial de una sesión específica
                List<AIService.ChatMessage> msgs = loadHistory(conn, userId, sessionId);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (AIService.ChatMessage m : msgs) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", m.role);
                    o.addProperty("content", m.content);
                    arr.add(o);
                }
                JsonUtil.sendSuccess(response, arr.toString());
            } else {
                // Lista de sesiones del usuario
                List<String[]> sessions = loadSessions(conn, userId);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String[] s : sessions) {
                    JsonObject o = new JsonObject();
                    o.addProperty("sessionId", s[0]);
                    o.addProperty("firstMessage", s[1]);
                    o.addProperty("createdAt", s[2]);
                    arr.add(o);
                }
                JsonUtil.sendSuccess(response, arr.toString());
            }

        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "sessionId inválido.");
        } catch (Exception e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
        setCorsHeaders(res);
        res.setStatus(HttpServletResponse.SC_OK);
    }

    // ── Helpers BD ────────────────────────────────────────────────────────────

    private List<AIService.ChatMessage> loadHistory(Connection conn, UUID userId, String sessionId)
            throws Exception {
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
                              String role, String message) throws Exception {
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

    private String[] loadProfesorConfig(Connection conn, UUID userId) throws Exception {
        String sql = "SELECT professor_name, personality FROM professor_config WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{ rs.getString("professor_name"), rs.getString("personality") };
            }
        }
        return new String[]{ "Mi ProfesorIA", "amigable y motivador" };
    }

    private List<String[]> loadSessions(Connection conn, UUID userId) throws Exception {
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

    // ── CORS ─────────────────────────────────────────────────────────────────
    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5500");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}