package com.project.servlet;

import com.google.gson.Gson;
import com.project.dao.implementation.FavoriteDAOImpl;
import com.project.dao.interfaces.IFavoriteDAO;
import com.project.model.favorites.Favorite;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GET  /favorites?userId=xxx               → todos los favoritos del usuario
 * GET  /favorites?userId=xxx&type=flashcard → filtrado por tipo
 * PUT  /favorites  { "contentId": "uuid", "userId": "uuid", "isFavorite": true/false }
 */
@WebServlet("/favorites")
public class FavoritesServlet extends HttpServlet {

    private final IFavoriteDAO favoriteDAO = new FavoriteDAOImpl();
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        setJsonResponse(res);

        try {
            String userIdParam = req.getParameter("userId");
            String type = req.getParameter("type");  // opcional

            if (userIdParam == null || userIdParam.isBlank()) {
                sendError(res, 400, "Falta el parámetro userId.");
                return;
            }

            UUID userId = UUID.fromString(userIdParam);
            List<Favorite> favorites;

            if (type != null && !type.isBlank()) {
                favorites = favoriteDAO.getFavoritesByType(userId, type);
            } else {
                favorites = favoriteDAO.getFavoritesByUser(userId);
            }

            res.getWriter().write(gson.toJson(favorites));

        } catch (IllegalArgumentException e) {
            sendError(res, 400, "userId inválido.");
        } catch (Exception e) {
            System.err.println("[FavoritesServlet] Error en doGet: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        setJsonResponse(res);

        try {
            String body = req.getReader().lines().collect(Collectors.joining());
            FavoriteRequest data = gson.fromJson(body, FavoriteRequest.class);

            if (data == null || data.contentId == null || data.userId == null) {
                sendError(res, 400, "Se requieren contentId y userId.");
                return;
            }

            UUID contentId = UUID.fromString(data.contentId);
            UUID userId    = UUID.fromString(data.userId);

            boolean success = favoriteDAO.setFavorite(contentId, userId, data.isFavorite);

            if (success) {
                String action = data.isFavorite ? "marcado" : "desmarcado";
                res.getWriter().write("{\"success\": true, \"message\": \"Contenido " + action + " como favorito.\"}");
            } else {
                sendError(res, 404, "Contenido no encontrado o no te pertenece.");
            }

        } catch (Exception e) {
            System.err.println("[FavoritesServlet] Error en doPut: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    private void setJsonResponse(HttpServletResponse res) {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.getWriter().write("{\"success\": false, \"error\": \"" + message + "\"}");
    }

    private static class FavoriteRequest {
        String  contentId;
        String  userId;
        boolean isFavorite;
    }
}