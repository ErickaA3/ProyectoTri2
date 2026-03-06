package com.project.servlet;

import java.io.IOException;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.implementation.HistorialDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.dao.interfaces.IHistorialDAO;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Servlet para el Historial de contenido generado por el usuario.
 * Lee de la tabla study_content.
 *
 * Endpoints:
 *   GET    /api/historial                → Lista todo el contenido del usuario
 *   GET    /api/historial?type=summary   → Filtra por tipo
 *   GET    /api/historial?date=YYYY-MM-DD → Filtra por fecha
 *   GET    /api/historial?search=texto   → Búsqueda por título
 *   DELETE /api/historial/{id}           → Elimina un registro
 *   DELETE /api/historial                → Elimina todo el historial del usuario
 *   PATCH  /api/historial/{id}/favorite  → Toggle favorito
 *
 * Respuesta lista:
 * {
 *   "success": true,
 *   "data": [
 *     {
 *       "id": "uuid",
 *       "type": "summary",
 *       "title": "...",
 *       "isFavorite": false,
 *       "createdAt": "2026-01-01T12:00:00"
 *     }, ...
 *   ]
 * }
 */
@WebServlet("/api/historial/*")
public class HistorialServlet extends HttpServlet {

    private final IContentDAO   contentDAO   = new ContentDAOImpl();
    private final IHistorialDAO historialDAO = new HistorialDAOImpl();
    private final Gson gson = new Gson();

    // -----------------------------------------------------------------------
    // GET /api/historial  — listar historial con filtros opcionales
    // -----------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserIdFromSession(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        String typeFilter   = req.getParameter("type");    // "summary" | "flashcard" | etc.
        String dateFilter   = req.getParameter("date");    // "YYYY-MM-DD"
        String searchFilter = req.getParameter("search");  // texto libre

        try {
            JsonArray items = historialDAO.getHistory(userId, typeFilter, dateFilter, searchFilter);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("data", items);
            res.getWriter().write(gson.toJson(response));
        } catch (Exception e) {
            sendError(res, 500, "Error al obtener el historial: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // DELETE /api/historial/{id}  — eliminar un elemento
    // DELETE /api/historial       — eliminar todo el historial del usuario
    // -----------------------------------------------------------------------
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserIdFromSession(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        String pathInfo = req.getPathInfo(); // null | "/" | "/{id}"
        String contentId = extractId(pathInfo);

        try {
            if (contentId != null) {
                // Eliminar un solo elemento
                boolean deleted = contentDAO.delete(contentId, userId);
                if (!deleted) { sendError(res, 404, "Elemento no encontrado o no te pertenece."); return; }
                sendSuccess(res, null);
            } else {
                // Eliminar TODO el historial del usuario
                int count = historialDAO.deleteAll(userId);
                JsonObject data = new JsonObject();
                data.addProperty("deletedCount", count);
                sendSuccess(res, data);
            }
        } catch (Exception e) {
            sendError(res, 500, "Error al eliminar: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // POST /api/historial/{id}/favorite con header X-HTTP-Method-Override: PATCH
    // HttpServlet no soporta doPatch nativamente — se intercepta como POST.
    // Body: { "isFavorite": true }
    // -----------------------------------------------------------------------
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        // Solo manejamos POST si viene con el header de override para favorito
        String override  = req.getHeader("X-HTTP-Method-Override");
        String pathInfo  = req.getPathInfo();
        if (!"PATCH".equalsIgnoreCase(override)) {
            sendError(res, 405, "Falta el header X-HTTP-Method-Override: PATCH");
            return;
        }
        if (pathInfo == null || !pathInfo.endsWith("/favorite")) {
            sendError(res, 400, "Ruta inválida. Esperado: /api/historial/{id}/favorite");
            return;
        }

        String userId = getUserIdFromSession(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        String contentId = pathInfo.replace("/favorite", "").replaceFirst("^/", "");
        if (contentId.isBlank()) { sendError(res, 400, "ID no especificado."); return; }

        try {
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            boolean isFavorite = data.get("isFavorite").getAsBoolean();

            boolean updated = contentDAO.toggleFavorite(contentId, userId, isFavorite);
            if (!updated) { sendError(res, 404, "Elemento no encontrado o no te pertenece."); return; }

            JsonObject responseData = new JsonObject();
            responseData.addProperty("isFavorite", isFavorite);
            sendSuccess(res, responseData);

        } catch (Exception e) {
            sendError(res, 500, "Error al actualizar favorito: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    /** Extrae el UUID del primer segmento del pathInfo. */
    private String extractId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/")) return null;
        String cleaned = pathInfo.replaceFirst("^/", "");
        int slash = cleaned.indexOf('/');
        String id = (slash == -1) ? cleaned : cleaned.substring(0, slash);
        return id.isBlank() ? null : id;
    }

    /**
     * Obtiene userId desde la sesión HTTP (establecida por LoginServlet).
     * Si la sesión no tiene el atributo, intenta leerlo del header X-User-Id
     * como fallback para llamadas directas desde el frontend.
     */
    private String getUserIdFromSession(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid != null) return uid.toString();
        }
        // Fallback: header enviado por el frontend
        String header = req.getHeader("X-User-Id");
        return (header != null && !header.isBlank()) ? header : null;
    }

    private void sendSuccess(HttpServletResponse res, JsonObject data) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        if (data != null) response.add("data", data);
        res.getWriter().write(gson.toJson(response));
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}