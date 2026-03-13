package com.project.servlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.database.DatabaseConnection;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet para el visor de Resúmenes.
 *
 * Endpoints:
 *
 *   GET  /api/summaries?id=<UUID>
 *        Header: X-User-Id: <UUID>
 *   → Devuelve el resumen si pertenece al userId.
 *   Respuesta:
 *   {
 *     "success": true,
 *     "id": "uuid",
 *     "title": "...",
 *     "isFavorite": false,
 *     "createdAt": "2025-01-01 12:00:00",
 *     "sessionId": "uuid",
 *     "content": { ... }   <- JSONB original guardado por la IA
 *   }
 *
 *   POST /api/summaries/favorite
 *        Header: X-User-Id: <UUID>
 *        Header: X-HTTP-Method-Override: PATCH   (HttpServlet no tiene doPatch)
 *        Body JSON: { "contentId": "uuid", "isFavorite": true }
 *   -> Marca o desmarca el resumen como favorito.
 *   Respuesta:
 *   { "success": true, "isFavorite": true }
 */
@WebServlet(urlPatterns = { "/api/summaries", "/api/summaries/favorite" })
public class SummaryServlet extends HttpServlet {

    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    // -----------------------------------------------------------------------
    // GET /api/summaries?id=<UUID>
    // userId viene del header X-User-Id (mismo patron que HistorialServlet)
    // -----------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String contentId = req.getParameter("id");
        String userId    = req.getHeader("X-User-Id");

        if (contentId == null || contentId.isBlank()) {
            sendError(res, 400, "Falta el parametro 'id'.");
            return;
        }
        if (userId == null || userId.isBlank()) {
            sendError(res, 401, "No autenticado. Falta el header X-User-Id.");
            return;
        }

        try {
            JsonObject summary = getByIdAndUser(contentId, userId);

            if (summary == null) {
                sendError(res, 404, "Resumen no encontrado o no tienes permiso para verlo.");
                return;
            }

            summary.addProperty("success", true);
            res.getWriter().write(gson.toJson(summary));

        } catch (Exception e) {
            sendError(res, 500, "Error al obtener el resumen: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // POST /api/summaries/favorite
    // Acepta X-HTTP-Method-Override: PATCH porque HttpServlet no tiene doPatch.
    // userId viene del header X-User-Id.
    // Body JSON: { "contentId": "uuid", "isFavorite": true }
    // -----------------------------------------------------------------------
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        // Solo acepta requests a /favorite
        String uri = req.getRequestURI();
        if (!uri.endsWith("/favorite")) {
            sendError(res, 405, "Metodo no permitido en esta ruta.");
            return;
        }

        String userId = req.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            sendError(res, 401, "No autenticado. Falta el header X-User-Id.");
            return;
        }

        try {
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            String  contentId  = data.get("contentId").getAsString();
            boolean isFavorite = data.get("isFavorite").getAsBoolean();

            if (contentId == null || contentId.isBlank()) {
                sendError(res, 400, "Falta el campo 'contentId'.");
                return;
            }

            boolean updated = contentDAO.toggleFavorite(contentId, userId, isFavorite);

            if (!updated) {
                sendError(res, 404, "No se encontro el contenido o no te pertenece.");
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success",    true);
            response.addProperty("isFavorite", isFavorite);
            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            sendError(res, 500, "Error al actualizar favorito: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Consulta directa: obtiene resumen por ID verificando que sea del usuario.
    // No delega a ContentDAOImpl para no depender del codigo de los companeros.
    // -----------------------------------------------------------------------
    private JsonObject getByIdAndUser(String contentId, String userId) throws Exception {
        String sql = """
            SELECT id, user_id, type, title, content::text AS content_json,
                   is_favorite, created_at, session_id
            FROM study_content
            WHERE id      = ?::uuid
              AND user_id = ?::uuid
              AND type    = 'summary'
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, contentId);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return null;

            JsonObject result = new JsonObject();
            result.addProperty("id",         rs.getString("id"));
            result.addProperty("title",      rs.getString("title"));
            result.addProperty("type",       rs.getString("type"));
            result.addProperty("isFavorite", rs.getBoolean("is_favorite"));
            result.addProperty("createdAt",  rs.getTimestamp("created_at").toString());
            result.addProperty("sessionId",  rs.getString("session_id"));

            // content es JSONB — se devuelve como objeto, no como string
            String contentJson = rs.getString("content_json");
            if (contentJson != null && !contentJson.isBlank()) {
                try {
                    JsonElement contentElement = JsonParser.parseString(contentJson);
                    result.add("content", contentElement);
                } catch (JsonSyntaxException e) {
                    result.addProperty("content", contentJson);
                }
            }

            return result;
        }
    }

    // -----------------------------------------------------------------------
    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error",   message);
        res.getWriter().write(gson.toJson(error));
    }
}