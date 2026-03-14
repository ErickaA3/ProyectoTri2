
package com.project.servlet;
 
import java.io.IOException;
import java.util.stream.Collectors;
 
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
 
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
 
/**
 * Servlet para visualizar resúmenes individuales.
 *
 * [BUG-4 FIX] — Este servlet NO existía. Sin él, resumenes.js hacía
 *               fetch a /api/summaries y recibía 404, por lo que
 *               los resúmenes nunca se mostraban.
 *
 * Endpoints:
 *   GET  /api/summaries?id=UUID       → devuelve el resumen completo
 *   POST /api/summaries/favorite      → toggle favorito
 */
@WebServlet({"/api/summaries", "/api/summaries/favorite"})
public class SummaryServlet extends HttpServlet {
 
    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();
 
    // ─── GET /api/summaries?id=UUID ─────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
 
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
 
        try {
            String userId    = req.getHeader("X-User-Id");
            String contentId = req.getParameter("id");
 
            if (contentId == null || contentId.isBlank()) {
                sendError(res, 400, "Falta el parámetro 'id'.");
                return;
            }
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Falta el header X-User-Id.");
                return;
            }
 
            // Usa el método existente del DAO
            String rawJson = contentDAO.getContentJson(contentId, userId);
 
            if (rawJson == null) {
                sendError(res, 404, "Resumen no encontrado o no pertenece al usuario.");
                return;
            }
 
            // rawJson tiene: { type, title, isFavorite, content:{...} }
            // resumenes.js espera: { success, id, title, isFavorite, createdAt, content:{...} }
            JsonObject data = JsonParser.parseString(rawJson).getAsJsonObject();
 
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("id", contentId);
            response.addProperty("title",
                data.has("title") ? data.get("title").getAsString() : "Sin título");
            response.addProperty("isFavorite",
                data.has("isFavorite") && data.get("isFavorite").getAsBoolean());
 
            // createdAt y sessionId — los obtenemos por consulta separada
            // (getContentJson no los incluye; usamos getFullMetadata)
            String[] meta = ((ContentDAOImpl) contentDAO).getMetadata(contentId, userId);
            if (meta != null) {
                response.addProperty("createdAt", meta[0]);  // timestamp as string
                response.addProperty("sessionId", meta[1]);  // session_id
            }
 
            // content (el JSONB de la IA)
            if (data.has("content") && data.get("content").isJsonObject()) {
                response.add("content", data.getAsJsonObject("content"));
            } else {
                response.add("content", new JsonObject());
            }
 
            res.getWriter().write(gson.toJson(response));
 
        } catch (Exception e) {
            sendError(res, 500, "Error interno: " + e.getMessage());
        }
    }
 
    // ─── POST /api/summaries/favorite ───────────────────────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
 
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
 
        // Solo /favorite responde a POST
        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        if (!path.endsWith("/favorite")) {
            sendError(res, 405, "Método no permitido para esta ruta.");
            return;
        }
 
        try {
            String userId = req.getHeader("X-User-Id");
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Falta el header X-User-Id.");
                return;
            }
 
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
 
            String contentId = data.has("contentId") ? data.get("contentId").getAsString() : null;
            boolean isFavorite = data.has("isFavorite") && data.get("isFavorite").getAsBoolean();
 
            if (contentId == null || contentId.isBlank()) {
                sendError(res, 400, "Falta contentId en el body.");
                return;
            }
 
            boolean updated = contentDAO.toggleFavorite(contentId, userId, isFavorite);
 
            JsonObject response = new JsonObject();
            response.addProperty("success", updated);
            if (!updated) response.addProperty("error", "No se pudo actualizar el favorito.");
            res.getWriter().write(gson.toJson(response));
 
        } catch (Exception e) {
            sendError(res, 500, "Error interno: " + e.getMessage());
        }
    }
 
    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}