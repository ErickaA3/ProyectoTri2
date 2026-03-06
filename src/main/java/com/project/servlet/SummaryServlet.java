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
 *   GET  /resumen?id=<UUID>&userId=<UUID>
 *   → Devuelve los datos del resumen si pertenece al userId.
 *   Respuesta:
 *   {
 *     "success": true,
 *     "id": "uuid",
 *     "title": "Título del resumen",
 *     "isFavorite": false,
 *     "createdAt": "2025-01-01T12:00:00",
 *     "sessionId": "uuid",
 *     "content": { ... }   <-- JSON original guardado por la IA
 *   }
 *
 *   POST /resumen/favorite
 *   Body JSON: { "contentId": "uuid", "userId": "uuid", "isFavorite": true }
 *   → Marca o desmarca el resumen como favorito.
 *   Respuesta:
 *   { "success": true }
 */
@WebServlet(urlPatterns = { "/resumen", "/resumen/favorite" })
public class SummaryServlet extends HttpServlet {

    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    // -----------------------------------------------------------------------
    // GET /resumen?id=<UUID>&userId=<UUID>
    // -----------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String contentId = req.getParameter("id");
        String userId    = req.getParameter("userId");

        if (contentId == null || contentId.isBlank()) {
            sendError(res, 400, "Falta el parámetro 'id'.");
            return;
        }
        if (userId == null || userId.isBlank()) {
            sendError(res, 400, "Falta el parámetro 'userId'.");
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
    // POST /resumen/favorite
    // -----------------------------------------------------------------------
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            String  contentId  = data.get("contentId").getAsString();
            String  userId     = data.get("userId").getAsString();
            boolean isFavorite = data.get("isFavorite").getAsBoolean();

            if (contentId == null || contentId.isBlank() || userId == null || userId.isBlank()) {
                sendError(res, 400, "Faltan parámetros requeridos.");
                return;
            }

            boolean updated = contentDAO.toggleFavorite(contentId, userId, isFavorite);

            if (!updated) {
                sendError(res, 404, "No se encontró el contenido o no te pertenece.");
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("isFavorite", isFavorite);
            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            sendError(res, 500, "Error al actualizar favorito: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // CONSULTA DIRECTA: obtiene resumen por ID verificando que sea del usuario
    // No se modifica ContentDAOImpl para no tocar el código del compañero.
    // -----------------------------------------------------------------------
    private JsonObject getByIdAndUser(String contentId, String userId) throws Exception {
        String sql = """
            SELECT id, user_id, type, title, content::text AS content_json,
                   is_favorite, created_at, session_id
            FROM study_content
            WHERE id = ?::uuid
              AND user_id = ?::uuid
              AND type = 'summary'
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

            // El content es JSONB — lo parseamos para devolverlo como objeto
            String contentJson = rs.getString("content_json");
            if (contentJson != null && !contentJson.isBlank()) {
                try {
                    JsonElement contentElement = JsonParser.parseString(contentJson);
                    result.add("content", contentElement);
                } catch (JsonSyntaxException e) {
                    // Si por alguna razón no es JSON válido, lo mandamos como string
                    result.addProperty("content", contentJson);
                }
            }

            return result;
        }
    }

    // -----------------------------------------------------------------------
    // HELPER
    // -----------------------------------------------------------------------
    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}