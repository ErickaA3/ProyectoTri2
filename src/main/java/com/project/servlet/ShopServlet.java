package com.project.servlet;

import com.google.gson.Gson;
import com.project.dao.implementation.ShopDAOImpl;
import com.project.dao.interfaces.IShopDAO;
import com.project.model.shop.Product;
import com.project.model.shop.Purchase;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ShopServlet — maneja todas las operaciones de la tienda.
 *
 * Endpoints:
 *   GET  /shop          → catálogo completo + inventario del usuario
 *   POST /shop/buy      → comprar un ítem
 *   POST /shop/equip    → equipar un ítem ya comprado
 *
 * El userId que llega siempre es un UUID en formato String.
 * Ejemplo: "550e8400-e29b-41d4-a716-446655440000"
 * El frontend lo lee de sessionStorage donde Hans lo guardó al hacer login.
 */
@WebServlet("/shop/*")
public class ShopServlet extends HttpServlet {

    private final IShopDAO shopDAO = new ShopDAOImpl();
    private final Gson     gson    = new Gson();

    // ──────────────────────────────────────────────────────────────
    //  GET /shop?userId=<uuid>
    //  Devuelve: catálogo + IDs que el usuario ya tiene + qué tiene equipado
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        setJsonResponse(res);

        try {
            String userId = req.getParameter("userId");
            if (userId == null || userId.isBlank()) {
                sendError(res, 400, "Falta el parámetro userId.");
                return;
            }

            // Catálogo completo
            List<Product> allItems = shopDAO.getAllItems();

            // IDs que el usuario ya compró (para marcar "✓ TUYO" en el frontend)
            List<Integer> ownedIds = shopDAO.getUserInventory(userId);

            // Ítem equipado por tipo (el frontend necesita saber cuál está activo)
            Integer equippedAvatarId     = shopDAO.getEquippedItem(userId, "avatar");
            Integer equippedBackgroundId = shopDAO.getEquippedItem(userId, "background");

            Map<String, Object> response = new HashMap<>();
            response.put("success",              true);
            response.put("items",                allItems);
            response.put("ownedItemIds",         ownedIds);
            response.put("equippedAvatarId",     equippedAvatarId);      // puede ser null
            response.put("equippedBackgroundId", equippedBackgroundId);  // puede ser null

            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            System.err.println("[ShopServlet] Error en doGet: " + e.getMessage());
            sendError(res, 500, "Error interno del servidor.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  POST /shop/buy   → { "userId": "uuid", "itemId": 3 }
    //  POST /shop/equip → { "userId": "uuid", "itemId": 3 }
    // ──────────────────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        setJsonResponse(res);

        String pathInfo = req.getPathInfo();   // "/buy" o "/equip"

        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(res, 400, "Especifica una acción: /shop/buy o /shop/equip");
            return;
        }

        try {
            String body = req.getReader().lines().collect(Collectors.joining());

            switch (pathInfo) {
                case "/buy"   -> handleBuy(body, res);
                case "/equip" -> handleEquip(body, res);
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

    private void handleBuy(String body, HttpServletResponse res) throws IOException {
        ShopRequest data = gson.fromJson(body, ShopRequest.class);

        if (data == null || data.userId == null || data.userId.isBlank() || data.itemId == 0) {
            sendError(res, 400, "Se requieren userId (UUID) e itemId.");
            return;
        }

        Purchase result = shopDAO.buyItem(data.userId, data.itemId);

        // Si la compra falló, responder con 400 para que el JS lo detecte fácil
        if (!result.isSuccess()) {
            res.setStatus(400);
        }

        res.getWriter().write(gson.toJson(result));
    }

    private void handleEquip(String body, HttpServletResponse res) throws IOException {
        ShopRequest data = gson.fromJson(body, ShopRequest.class);

        if (data == null || data.userId == null || data.userId.isBlank() || data.itemId == 0) {
            sendError(res, 400, "Se requieren userId (UUID) e itemId.");
            return;
        }

        boolean equipped = shopDAO.equipItem(data.userId, data.itemId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", equipped);
        response.put("message", equipped
                ? "Ítem equipado correctamente."
                : "No se pudo equipar. Verifica que poseas el ítem.");

        if (!equipped) res.setStatus(400);
        res.getWriter().write(gson.toJson(response));
    }

    // ──────────────────────────────────────────────────────────────
    //  UTILIDADES
    // ──────────────────────────────────────────────────────────────

    private void setJsonResponse(HttpServletResponse res) {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error",   message);
        res.getWriter().write(gson.toJson(error));
    }

    // ──────────────────────────────────────────────────────────────
    //  CLASE INTERNA — body de los POST
    // ──────────────────────────────────────────────────────────────

    /**
     * Gson mapea el JSON del body a esta clase automáticamente.
     * Se usa para tanto /buy como /equip porque los dos reciben lo mismo.
     */
    private static class ShopRequest {
        String userId;   // UUID como String: "550e8400-e29b-41d4-a716-446655440000"
        int    itemId;
    }
}
