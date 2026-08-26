package com.project.servlet;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.project.dao.implementation.ShopDAOImpl;
import com.project.dao.interfaces.IShopDAO;
import com.project.model.shop.Product;
import com.project.model.shop.Purchase;
import com.project.util.JwtUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * ShopServlet — maneja todas las operaciones de la tienda.
 *
 * Endpoints:
 *   GET  /shop          → catálogo completo + inventario del usuario
 *   POST /shop/buy      → comprar un ítem  { "itemId": 3 }
 *   POST /shop/equip    → equipar un ítem  { "itemId": 3 }
 *
 * Auth: HttpSession → fallback header X-User-Id
 * (mismo patrón que FavoritesServlet y ModoEstudioServlet)
 */
@WebServlet("/shop/*")
public class ShopServlet extends HttpServlet {

    private final IShopDAO shopDAO = new ShopDAOImpl();
    private final Gson gson = new GsonBuilder()
    .registerTypeAdapter(java.time.LocalDateTime.class,
        (com.google.gson.JsonSerializer<java.time.LocalDateTime>) (src, type, ctx) ->
            new com.google.gson.JsonPrimitive(src.toString()))
    .create();

    // ──────────────────────────────────────────────────────────────
    //  GET /shop
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserId(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        try {
            List<Product> allItems         = shopDAO.getAllItems();
            List<Integer> ownedIds         = shopDAO.getUserInventory(userId);
            Integer       equippedAvatarId = shopDAO.getEquippedItem(userId, "avatar");
            Integer       equippedBgId     = shopDAO.getEquippedItem(userId, "background");
            int           userCoins        = shopDAO.getUserCoins(userId);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("userCoins", userCoins);
            response.add("items",        gson.toJsonTree(allItems));
            response.add("ownedItemIds", gson.toJsonTree(ownedIds));
            if (equippedAvatarId != null) response.addProperty("equippedAvatarId",     equippedAvatarId);
            if (equippedBgId     != null) response.addProperty("equippedBackgroundId", equippedBgId);

            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            System.err.println("[ShopServlet] Error en doGet: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  POST /shop/buy   → { "itemId": 3 }
    //  POST /shop/equip → { "itemId": 3 }
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = getUserId(req);
        if (userId == null) { sendError(res, 401, "Sesión no válida."); return; }

        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(res, 400, "Especifica una acción: /shop/buy o /shop/equip");
            return;
        }

        try {
            String body = req.getReader().lines().collect(Collectors.joining());

            switch (pathInfo) {
                case "/buy"   -> handleBuy(userId, body, res);
                case "/equip" -> handleEquip(userId, body, res);
                default       -> sendError(res, 404, "Ruta no encontrada: " + pathInfo);
            }

        } catch (Exception e) {
            System.err.println("[ShopServlet] Error en doPost: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HANDLERS PRIVADOS
    // ──────────────────────────────────────────────────────────────

    private void handleBuy(String userId, String body, HttpServletResponse res) throws IOException {
        ShopRequest data = gson.fromJson(body, ShopRequest.class);

        if (data == null || data.itemId == 0) {
            sendError(res, 400, "Se requiere itemId.");
            return;
        }

        Purchase result = shopDAO.buyItem(userId, data.itemId);

        if (!result.isSuccess()) res.setStatus(400);
        res.getWriter().write(gson.toJson(result));
    }

    private void handleEquip(String userId, String body, HttpServletResponse res) throws IOException {
        ShopRequest data = gson.fromJson(body, ShopRequest.class);

        if (data == null || data.itemId == 0) {
            sendError(res, 400, "Se requiere itemId.");
            return;
        }

        boolean equipped = shopDAO.equipItem(userId, data.itemId);

        JsonObject response = new JsonObject();
        response.addProperty("success", equipped);
        response.addProperty("message", equipped
                ? "Ítem equipado correctamente."
                : "No se pudo equipar. Verifica que poseas el ítem.");

        if (!equipped) res.setStatus(400);
        res.getWriter().write(gson.toJson(response));
    }

    // ──────────────────────────────────────────────────────────────
    //  HELPERS — mismo patrón que FavoritesServlet
    // ──────────────────────────────────────────────────────────────

    /**
     * Obtiene el userId desde la sesión HTTP.
     * Fallback: header X-User-Id (útil para pruebas con Thunder Client).
     */
    private String getUserId(HttpServletRequest req) {
        // 1. Sesión HTTP
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid != null) return uid.toString();
        }
        // 2. JWT Bearer token
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                String uid = JwtUtil.getUserId(auth.substring(7));
                if (uid != null) return uid;
            } catch (Exception ignored) {}
        }
        // 3. Header X-User-Id (fallback)
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

    // ──────────────────────────────────────────────────────────────
    //  CLASE INTERNA
    // ──────────────────────────────────────────────────────────────

    private static class ShopRequest {
        int itemId;   // userId ya no va aquí, viene de la sesión
    }
}