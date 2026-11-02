package com.project.servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

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

    private static final com.project.dao.interfaces.ILibraryDAO LIBRARY =
            new com.project.dao.implementation.LibraryDAOImpl();

    // Límite de texto extraído del PDF que se pasa a la IA (chars)
    private static final int PDF_TEXT_LIMIT = 8000;

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
            JsonUtil.sendError(response, 401, "No autenticado. Inicia sesión de nuevo.");
            return;
        }

        String mensaje;
        String sessionId;
        String pdfBase64;
        String pdfName;
        String topicId;
        String subjectId;

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            mensaje = json.has("mensaje") && !json.get("mensaje").isJsonNull()
                    ? json.get("mensaje").getAsString() : null;

            sessionId = json.has("sessionId") && !json.get("sessionId").isJsonNull()
                    ? json.get("sessionId").getAsString() : null;

            // PDF adjunto (opcional)
            pdfBase64 = json.has("pdfBase64") && !json.get("pdfBase64").isJsonNull()
                    ? json.get("pdfBase64").getAsString() : null;

            pdfName = json.has("pdfName") && !json.get("pdfName").isJsonNull()
                    ? json.get("pdfName").getAsString() : "documento.pdf";

            // Alcance de una sesión NUEVA (opcional): un tema o una materia.
            topicId = json.has("topicId") && !json.get("topicId").isJsonNull()
                    ? json.get("topicId").getAsString() : null;
            subjectId = json.has("subjectId") && !json.get("subjectId").isJsonNull()
                    ? json.get("subjectId").getAsString() : null;

        } catch (Exception e) {
            JsonUtil.sendError(response, 400, "Body inválido, se esperaba JSON.");
            return;
        }

        if (mensaje == null || mensaje.isBlank()) {
            JsonUtil.sendError(response, 400, "El mensaje no puede estar vacío.");
            return;
        }

        // Extraer texto del PDF si viene adjunto
        String pdfTexto = null;
        if (pdfBase64 != null && !pdfBase64.isBlank()) {
            pdfTexto = extractTextFromBase64Pdf(pdfBase64, pdfName);
        }

        try {
            List<AIService.ChatMessage> historial;
            String profesorNombre;
            String personalidad;
            ChatSession session;

            try (Connection conn = DatabaseConnection.getConnection()) {
                session = resolveSession(conn, userId, sessionId, subjectId, topicId, mensaje);
                if (session == null) {
                    JsonUtil.sendError(response, 404, "Conversación, tema o materia no encontrados.");
                    return;
                }
                historial = loadHistory(conn, userId, session.id());

                String[] profesorConfig = loadProfesorConfig(conn, userId);
                profesorNombre = profesorConfig[0];
                personalidad   = profesorConfig[1];

                // Guardar mensaje del usuario (con nota del PDF si aplica)
                String mensajeGuardado = (pdfTexto != null)
                        ? mensaje + "\n[PDF adjunto: " + pdfName + "]"
                        : mensaje;
                saveMessage(conn, session.id(), "user", mensajeGuardado);
            }

            // Contexto RAG, limitado al alcance de la sesión
            StringBuilder searchContext = new StringBuilder();
            int start = Math.max(0, historial.size() - 6);
            for (int i = start; i < historial.size(); i++) {
                searchContext.append(historial.get(i).content).append(" ");
            }
            searchContext.append(mensaje);

            JsonArray sources = new JsonArray();
            String contexto = UserContextService.buildContext(userId, searchContext.toString(),
                    session.subjectId(), session.topicId(), sources);

            // Llamar a la IA (con o sin PDF)
            String respuesta = AIService.chat(
                    historial,
                    mensaje,
                    profesorNombre,
                    personalidad,
                    contexto,
                    pdfTexto,
                    pdfName
            );

            // Guardar respuesta y de qué fragmentos del material salió
            try (Connection conn = DatabaseConnection.getConnection()) {
                String messageId = saveMessage(conn, session.id(), "assistant", respuesta);
                saveSources(conn, messageId, sources);
            }

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("reply",     respuesta);
            responseJson.addProperty("sessionId", session.id().toString());
            if (session.topicId() != null)   responseJson.addProperty("topicId",   session.topicId());
            if (session.subjectId() != null) responseJson.addProperty("subjectId", session.subjectId());
            // Fuentes para mostrar al estudiante: materia, tema y documento (sin ids internos)
            JsonArray publicSources = new JsonArray();
            for (var el : sources) {
                JsonObject src = el.getAsJsonObject().deepCopy();
                src.remove("chunkId");
                publicSources.add(src);
            }
            responseJson.add("sources", publicSources);

            JsonUtil.sendSuccess(response, responseJson.toString());

        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "Identificador inválido.");
        } catch (Exception e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    /**
     * Extrae texto de un PDF codificado en base64.
     * Retorna null si falla (el chat sigue funcionando sin el PDF).
     */
    private String extractTextFromBase64Pdf(String base64, String nombre) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(new ByteArrayInputStream(bytes)))) {
                String texto = new PDFTextStripper().getText(doc).trim();
                if (texto.isBlank()) {
                    System.err.println("[ChatServlet] PDF sin texto extraíble: " + nombre);
                    return null;
                }
                // Truncar si es muy largo
                if (texto.length() > PDF_TEXT_LIMIT) {
                    texto = texto.substring(0, PDF_TEXT_LIMIT) + "\n[...PDF truncado por longitud...]";
                }
                return texto;
            }
        } catch (Exception e) {
            System.err.println("[ChatServlet] Error extrayendo PDF '" + nombre + "': " + e.getMessage());
            return null;
        }
    }

    // GET /api/chat?sessionId=xxx
    // GET /api/chat?userId=xxx                → lista de sesiones
    // GET /api/chat?userId=xxx&search=palabra → busca por palabra clave en TODO el contenido de los chats
    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        UUID userId = resolveUserId(request);
        if (userId == null) { JsonUtil.sendError(response, 401, "No autenticado."); return; }

        String sessionId = request.getParameter("sessionId");
        String search     = request.getParameter("search");

        try (Connection conn = DatabaseConnection.getConnection()) {

            if (sessionId != null && !sessionId.isBlank()) {
                List<AIService.ChatMessage> msgs = loadHistory(conn, userId, sessionId);
                JsonArray arr = new JsonArray();
                for (AIService.ChatMessage m : msgs) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role",    m.role);
                    o.addProperty("content", m.content);
                    arr.add(o);
                }
                JsonUtil.sendSuccess(response, arr.toString());

            } else if (search != null && !search.isBlank()) {
                // [FEATURE] Búsqueda por palabra clave — recorre TODO el contenido
                // de los mensajes guardados en chat_messages, no solo el primer
                // mensaje de cada sesión. Así "cuéntame de spiderman" encuentra
                // un chat aunque esa palabra haya salido a mitad de conversación.
                List<String[]> results = searchSessions(conn, userId, search.trim());
                JsonArray arr = new JsonArray();
                for (String[] s : results) {
                    JsonObject o = new JsonObject();
                    o.addProperty("sessionId",    s[0]);
                    o.addProperty("firstMessage", s[1]);
                    o.addProperty("createdAt",    s[2]);
                    o.addProperty("snippet",      s[3]);
                    arr.add(o);
                }
                JsonUtil.sendSuccess(response, arr.toString());

            } else {
                List<String[]> sessions = loadSessions(conn, userId);
                JsonArray arr = new JsonArray();
                for (String[] s : sessions) {
                    JsonObject o = new JsonObject();
                    o.addProperty("sessionId",    s[0]);
                    o.addProperty("firstMessage", s[1]);
                    o.addProperty("createdAt",    s[2]);
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

    // DELETE /api/chat?sessionId=xxx
    @Override
    protected void doDelete(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        UUID userId = resolveUserId(request);
        if (userId == null) { JsonUtil.sendError(response, 401, "No autenticado."); return; }

        String sessionId = request.getParameter("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            JsonUtil.sendError(response, 400, "Se requiere sessionId."); return;
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            // Borra la sesión; sus mensajes y citas se borran en cascada.
            String sql = "DELETE FROM chat_sessions WHERE user_id = ? AND id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, userId);
                ps.setObject(2, UUID.fromString(sessionId));
                int deleted = ps.executeUpdate();
                JsonUtil.sendSuccess(response, "{\"deleted\":" + deleted + "}");
            }
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "sessionId inválido.");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error al eliminar el chat.");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private UUID resolveUserId(HttpServletRequest request) {
        String uid = (String) request.getAttribute("userId");
        if (uid == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object attr = session.getAttribute("userId");
                if (attr != null) uid = attr.toString();
            }
        }
        if (uid == null) return null;
        try { return UUID.fromString(uid); } catch (IllegalArgumentException e) { return null; }
    }

    /** Sesión de chat con su alcance (subjectId y topicId null = todo el material). */
    private record ChatSession(UUID id, String subjectId, String topicId) {}

    /**
     * Devuelve la sesión del usuario, o la crea si no existe (con el alcance
     * pedido y el primer mensaje como título). Null si la sesión es de otro
     * usuario o si el tema/materia no existe o no es suyo.
     */
    private ChatSession resolveSession(Connection conn, UUID userId, String sessionId,
                                       String subjectId, String topicId, String firstMessage)
            throws Exception {
        UUID id = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID() : UUID.fromString(sessionId);

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, subject_id::text, topic_id::text FROM chat_sessions WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                if (!userId.equals(rs.getObject(1, UUID.class))) return null;
                return new ChatSession(id, rs.getString(2), rs.getString(3));
            }
        }

        // Nueva: un tema ya implica su materia, así que si viene tema se ignora la materia.
        if (topicId != null && !topicId.isBlank()) {
            if (!LIBRARY.topicBelongsToUser(topicId, userId.toString())) return null;
            subjectId = null;
        } else if (subjectId != null && !subjectId.isBlank()) {
            if (!LIBRARY.subjectBelongsToUser(subjectId, userId.toString())) return null;
            topicId = null;
        } else {
            subjectId = null;
            topicId = null;
        }

        String title = firstMessage.trim();
        if (title.length() > 100) title = title.substring(0, 100);
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO chat_sessions (id, user_id, subject_id, topic_id, title)
                VALUES (?, ?, ?::uuid, ?::uuid, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, subjectId);
            ps.setString(4, topicId);
            ps.setString(5, title.isEmpty() ? "Conversación" : title);
            ps.executeUpdate();
        }
        return new ChatSession(id, subjectId, topicId);
    }

    private List<AIService.ChatMessage> loadHistory(Connection conn, UUID userId, String sessionId)
            throws Exception {
        return loadHistory(conn, userId, UUID.fromString(sessionId));
    }

    private List<AIService.ChatMessage> loadHistory(Connection conn, UUID userId, UUID sessionId)
            throws Exception {
        String sql = """
                SELECT m.role, m.body
                FROM chat_messages m
                JOIN chat_sessions s ON s.id = m.session_id
                WHERE s.user_id = ? AND s.id = ?
                ORDER BY m.created_at DESC
                LIMIT 20
                """;
        List<AIService.ChatMessage> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AIService.ChatMessage(rs.getString("role"), rs.getString("body")));
            }
        }
        java.util.Collections.reverse(list);
        return list;
    }

    /** @return id del mensaje guardado */
    private String saveMessage(Connection conn, UUID sessionId, String role, String message) throws Exception {
        String sql = "INSERT INTO chat_messages (session_id, role, body) VALUES (?, ?, ?) RETURNING id::text";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, message);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }

    /** Guarda de qué fragmentos del material salió una respuesta (citas). */
    private void saveSources(Connection conn, String messageId, JsonArray sources) throws Exception {
        if (sources.isEmpty()) return;
        String sql = """
                INSERT INTO chat_message_sources (message_id, chunk_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (var el : sources) {
                ps.setString(1, messageId);
                ps.setString(2, el.getAsJsonObject().get("chunkId").getAsString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private String[] loadProfesorConfig(Connection conn, UUID userId) throws Exception {
        String sql = "SELECT name, personality FROM assistant_settings WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{ rs.getString("name"), rs.getString("personality") };
            }
        }
        return new String[]{ "Mi ProfesorIA", "amigable y motivador" };
    }

    private List<String[]> loadSessions(Connection conn, UUID userId) throws Exception {
        // El título de la sesión es el "firstMessage" que muestra el sidebar.
        String sql = """
                SELECT s.id::text, s.title, s.created_at::text
                FROM chat_sessions s
                LEFT JOIN LATERAL (SELECT max(created_at) AS last_at
                                   FROM chat_messages WHERE session_id = s.id) l ON true
                WHERE s.user_id = ?
                ORDER BY coalesce(l.last_at, s.created_at) DESC
                """;
        List<String[]> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{ rs.getString(1), rs.getString(2), rs.getString(3) });
            }
        }
        return list;
    }

    /**
     * [FEATURE] Busca sesiones cuyo contenido (en CUALQUIER mensaje, no solo el
     * primero) contenga la palabra clave. Devuelve, por cada sesión que matchea:
     * el sessionId, el título, la fecha del mensaje y un snippet de ese mensaje.
     */
    private List<String[]> searchSessions(Connection conn, UUID userId, String keyword) throws Exception {
        String sql = """
                SELECT DISTINCT ON (s.id)
                       s.id::text, s.title, m.created_at::text, m.body
                FROM chat_messages m
                JOIN chat_sessions s ON s.id = m.session_id
                WHERE s.user_id = ? AND m.body ILIKE ?
                ORDER BY s.id, m.created_at DESC
                """;
        List<String[]> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, "%" + keyword + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    truncateSnippet(rs.getString(4), keyword)
                });
            }
        }
        // Más recientes primero, como máximo 30
        list.sort((x, y) -> y[2].compareTo(x[2]));
        return list.size() > 30 ? new ArrayList<>(list.subList(0, 30)) : list;
    }

    /** Recorta el snippet alrededor de la palabra clave para no devolver mensajes larguísimos. */
    private String truncateSnippet(String text, String keyword) {
        if (text == null) return "";
        if (text.length() <= 120) return text;

        int idx = text.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) return text.substring(0, 120) + "...";

        int start = Math.max(0, idx - 40);
        int end   = Math.min(text.length(), idx + keyword.length() + 60);
        String snippet = text.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < text.length()) snippet = snippet + "...";
        return snippet;
    }
}