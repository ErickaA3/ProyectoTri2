package com.project.servlet;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.util.GamificationService;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet de gamificación.
 * Centraliza todas las operaciones de XP, coins, racha y nivel.
 *
 * Endpoints:
 *   GET  /api/gamification/stats     → Stats actuales del jugador
 *   POST /api/gamification/reward    → Registrar actividad y dar rewards
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  [SEGURIDAD] POST /reward es un endpoint público (cualquier usuario
 *  autenticado lo puede llamar directo, sin pasar por la UI). Antes
 *  confiaba ciegamente en "activityType" y "scorePercent" del body, lo que
 *  permitía farmear XP/monedas llamándolo repetidamente sin jugar nada.
 *  Ahora:
 *   1. Los tipos de duelo (duelo_ganado/perdido/empate) están BLOQUEADOS
 *      acá — esos solo los puede otorgar el servidor internamente desde
 *      DuelServlet/DuelWebSocket cuando un duelo realmente termina, nunca
 *      un POST directo del cliente.
 *   2. Los tipos ligados a contenido (quiz, expert_exam, flashcards,
 *      resumen, generar) exigen un contentId real, que se valida contra
 *      study_content: debe existir, ser del usuario autenticado, y ser del
 *      tipo que dice ser. Ya no se puede inventar un UUID o mandar el id
 *      de otro contenido para cobrar la recompensa.
 * ═══════════════════════════════════════════════════════════════════════
 */
@WebServlet({"/api/gamification/stats", "/api/gamification/reward"})
public class GamificationServlet extends HttpServlet {

    private final Gson gson = new Gson();
    private final IContentDAO contentDAO = new ContentDAOImpl();

    // Tipos que SÍ se pueden pedir directo desde el cliente vía este endpoint.
    private static final Set<String> CLIENT_ALLOWED_TYPES = Set.of(
        "quiz", "expert_exam", "flashcards", "resumen", "generar", "abandon_exam"
    );

    // Tipos que exigen contentId real y verificado contra study_content.
    private static final Set<String> CONTENT_REQUIRED_TYPES = Set.of(
        "quiz", "expert_exam", "flashcards", "resumen"
    );

    // activityType del reward → type esperado en study_content.
    private static String expectedContentType(String activityType) {
        return switch (activityType) {
            case "quiz"         -> "quiz";
            case "expert_exam"  -> "expert_exam";
            case "flashcards"   -> "flashcard";
            case "resumen"      -> "summary";
            default             -> null; // "generar" acepta cualquier tipo propio
        };
    }

    // ─── GET: obtener stats ─────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            String userId = getUserId(req);
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Sesión no válida.");
                return;
            }

            JsonObject stats = GamificationService.getPlayerStats(userId);
            if (stats == null) {
                sendError(res, 404, "Usuario no encontrado.");
                return;
            }

            stats.addProperty("success", true);
            res.getWriter().write(gson.toJson(stats));

        } catch (Exception e) {
            sendError(res, 500, "Error obteniendo stats: " + e.getMessage());
        }
    }

    // ─── POST: registrar actividad y dar rewards ────────────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        if (!path.endsWith("/reward")) {
            sendError(res, 405, "Usa POST /api/gamification/reward");
            return;
        }

        try {
            String userId = getUserId(req);
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Sesión no válida.");
                return;
            }

            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            String activityType = data.has("activityType")
                ? data.get("activityType").getAsString()
                : null;

            if (activityType == null || activityType.isBlank()) {
                sendError(res, 400, "Falta activityType en el body.");
                return;
            }

            // [SEGURIDAD] Los rewards de duelo solo los otorga el servidor
            // internamente (DuelServlet/DuelWebSocket → GamificationService
            // directo, sin pasar por este endpoint HTTP). Un cliente nunca
            // puede pedir "duelo_ganado" por su cuenta.
            if (!CLIENT_ALLOWED_TYPES.contains(activityType)) {
                sendError(res, 400, "activityType no permitido en este endpoint: " + activityType);
                return;
            }

            double scorePercent = data.has("scorePercent")
                ? data.get("scorePercent").getAsDouble()
                : 0;
            // Clamp defensivo — nunca confiar en el rango que manda el cliente.
            if (scorePercent < 0)   scorePercent = 0;
            if (scorePercent > 100) scorePercent = 100;

            String contentId = (data.has("contentId") && !data.get("contentId").isJsonNull())
                ? data.get("contentId").getAsString()
                : null;

            int timeTakenSecs = data.has("timeTakenSecs")
                ? data.get("timeTakenSecs").getAsInt()
                : 0;
            if (timeTakenSecs < 0) timeTakenSecs = 0;

            double maxScore = data.has("maxScore")
                ? data.get("maxScore").getAsDouble()
                : 100;
            if (maxScore <= 0) maxScore = 100;

            // [SEGURIDAD] Verificar que el contenido exista, sea del usuario
            // autenticado y sea del tipo que dice ser.
            if (CONTENT_REQUIRED_TYPES.contains(activityType)) {
                if (contentId == null || contentId.isBlank()) {
                    sendError(res, 400, "Esta actividad requiere contentId.");
                    return;
                }
                String realType = contentDAO.getContentType(contentId, userId);
                if (realType == null) {
                    sendError(res, 403, "El contenido no existe o no te pertenece.");
                    return;
                }
                String expected = expectedContentType(activityType);
                if (expected != null && !expected.equals(realType)) {
                    sendError(res, 400, "El contentId no coincide con el tipo de actividad.");
                    return;
                }
            } else if ("generar".equals(activityType) && contentId != null && !contentId.isBlank()) {
                // Si mandan contentId para "generar", igual se valida dueño.
                if (contentDAO.getContentType(contentId, userId) == null) {
                    sendError(res, 403, "El contenido no existe o no te pertenece.");
                    return;
                }
            }

            JsonObject result = GamificationService.processActivity(
                userId, activityType, scorePercent, contentId, timeTakenSecs, maxScore
            );

            res.getWriter().write(gson.toJson(result));

        } catch (Exception e) {
            sendError(res, 500, "Error procesando reward: " + e.getMessage());
        }
    }

    private String getUserId(HttpServletRequest req) {
        Object attr = req.getAttribute("userId");
        if (attr != null) return attr.toString();
        jakarta.servlet.http.HttpSession session = req.getSession(false);
        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid != null) return uid.toString();
        }
        return null;
    }

    private void sendError(HttpServletResponse res, int status, String message)
            throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}