package com.project.servlet;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.dao.implementation.LeaderboardDAO;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Servlet de Leaderboard de Duelos.
 *
 * ═══ ENDPOINTS ═══
 *   GET /api/leaderboard/duels
 *       ?period=week|month|all     (default: week)
 *       ?scope=global|friends      (default: friends)
 *
 * Autenticación: Authorization: Bearer <JWT>. La identidad la resuelve JwtFilter
 * y queda en req.getAttribute("userId"); ya no se acepta el header X-User-Id.
 *
 * Respuesta:
 * {
 *   "success": true,
 *   "leaderboard": [
 *     { "userId": "...", "username": "...", "wins": 12, "xp": 4800, "streak": 5, "rank": 1 },
 *     ...
 *   ]
 * }
 */
@WebServlet("/api/leaderboard/*")
public class LeaderboardServlet extends HttpServlet {

    private final LeaderboardDAO leaderboardDAO = new LeaderboardDAO();
    private final Gson gson = new Gson();

    // ─── GET ─────────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserId(req);
        if (userId == null || userId.isBlank()) {
            sendError(res, 401, "No autenticado.");
            return;
        }

        String path = req.getPathInfo() != null ? req.getPathInfo() : "";

        try {
            switch (path) {

                case "/duels" -> {
                    String period = req.getParameter("period");   // week | month | all
                    String scope  = req.getParameter("scope");    // global | friends

                    JsonArray ranking;
                    if ("global".equalsIgnoreCase(scope)) {
                        ranking = leaderboardDAO.getLeaderboard(period);
                    } else {
                        // Por defecto: solo amigos (incluye al usuario actual)
                        ranking = leaderboardDAO.getLeaderboardFriends(userId, period);
                    }

                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.add("leaderboard", ranking);
                    res.getWriter().write(gson.toJson(r));
                }

                default -> sendError(res, 404, "Ruta no encontrada: GET /api/leaderboard" + path);
            }

        } catch (Exception e) {
            System.err.println("[LeaderboardServlet] Error: " + e.getMessage());
            sendError(res, 500, e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    /**
     * Obtiene el userId desde el JWT validado por JwtFilter (req.getAttribute),
     * con fallback a HttpSession. Ya no se acepta el header X-User-Id (spoofable).
     */
    private String getUserId(HttpServletRequest req) {
        Object attr = req.getAttribute("userId");
        if (attr != null) return attr.toString();
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid != null) return uid.toString();
        }
        return null;
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}