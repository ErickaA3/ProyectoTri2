package com.project.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.model.content.EducationalContent;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servlet de Favoritos — mismo patrón que HistorialServlet.
 * Usa ContentDAOImpl (el mismo DAO que historial).
 *
 * GET  /api/favoritos                → lista todos los favoritos
 * GET  /api/favoritos?type=flashcard → filtrado por tipo
 * GET  /api/favoritos/{id}           → contenido completo de un ítem (para "Ver")
 * PUT  /api/favoritos                → toggle favorito
 *
 * Auth: HttpSession → fallback header X-User-Id
 */
@WebServlet("/api/favoritos/*")
public class FavoritesServlet extends HttpServlet {

    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserId(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        // Detectar si viene un ID en el path: /api/favoritos/{id}
        String pathInfo = req.getPathInfo();
        String contentId = extractId(pathInfo);

        try {
            if (contentId != null) {
                // ── GET /api/favoritos/{id} — contenido completo ──
                String json = contentDAO.getContentJson(contentId, userId);
                if (json == null) {
                    sendError(res, 404, "Contenido no encontrado.");
                    return;
                }
                res.getWriter().write(json);

            } else {
                // ── GET /api/favoritos — lista de favoritos ──
                String type = req.getParameter("type");
                List<EducationalContent> favorites = contentDAO.getFavorites(userId);

                if (type != null && !type.isBlank()) {
                    favorites = favorites.stream()
                            .filter(f -> type.equals(f.getType()))
                            .collect(Collectors.toList());
                }

                // Pre-cargar schemaType de todos los esquemas en UNA sola query
                java.util.Map<String, String> schemaTypes = new java.util.HashMap<>();
                List<String> schemaIds = favorites.stream()
                        .filter(f -> "schema".equals(f.getType()))
                        .map(EducationalContent::getId)
                        .collect(Collectors.toList());

                if (!schemaIds.isEmpty()) {
                    try {
                        String placeholders = schemaIds.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
                        String sql = "SELECT id::text, content->>'schemaType' AS schema_type FROM study_content WHERE id IN (" + placeholders + ")";
                        try (java.sql.Connection conn = com.project.database.DatabaseConnection.getConnection();
                             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                            for (int i = 0; i < schemaIds.size(); i++) {
                                ps.setString(i + 1, schemaIds.get(i));
                            }
                            java.sql.ResultSet rs = ps.executeQuery();
                            while (rs.next()) {
                                String st = rs.getString("schema_type");
                                if (st != null) schemaTypes.put(rs.getString("id"), st);
                            }
                        }
                    } catch (Exception ignored) { }
                }

                JsonArray arr = new JsonArray();
                for (EducationalContent item : favorites) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", item.getId());
                    obj.addProperty("type", item.getType());
                    obj.addProperty("title", item.getTitle());
                    obj.addProperty("isFavorite", item.isFavorite());

                    // Agregar schemaType si existe (ya pre-cargado)
                    String st = schemaTypes.get(item.getId());
                    if (st != null) {
                        obj.addProperty("schemaType", st);
                    }

                    arr.add(obj);
                }

                res.getWriter().write(gson.toJson(arr));
            }

        } catch (Exception e) {
            System.err.println("[FavoritesServlet] Error en doGet: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserId(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        try {
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            String contentId = data.has("contentId") ? data.get("contentId").getAsString() : null;
            if (contentId == null || contentId.isBlank()) {
                sendError(res, 400, "Se requiere contentId."); return;
            }

            boolean isFavorite = data.has("isFavorite") && data.get("isFavorite").getAsBoolean();

            boolean success = contentDAO.toggleFavorite(contentId, userId, isFavorite);

            if (success) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("isFavorite", isFavorite);
                res.getWriter().write(gson.toJson(response));
            } else {
                sendError(res, 404, "Contenido no encontrado o no te pertenece.");
            }

        } catch (Exception e) {
            System.err.println("[FavoritesServlet] Error en doPut: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    private String extractId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/")) return null;
        String cleaned = pathInfo.replaceFirst("^/", "");
        int slash = cleaned.indexOf('/');
        String id = (slash == -1) ? cleaned : cleaned.substring(0, slash);
        return id.isBlank() ? null : id;
    }

    private String getUserId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid != null) return uid.toString();
        }
        String header = req.getHeader("X-User-Id");
        return (header != null && !header.isBlank()) ? header : null;
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}