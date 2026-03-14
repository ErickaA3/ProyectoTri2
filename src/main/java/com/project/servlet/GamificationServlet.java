package com.project.servlet;

import java.io.IOException;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * ═══════════════════════════════════════════════════════════
 *  GET /api/gamification/stats
 * ═══════════════════════════════════════════════════════════
 *   Headers:  X-User-Id: UUID
 *   Response: { success, xp, level, coins, streakCurrent, streakRecord,
 *               streakMultiplier, xpToNextLevel, levelProgress, ... }
 *
 * ═══════════════════════════════════════════════════════════
 *  POST /api/gamification/reward
 * ═══════════════════════════════════════════════════════════
 *   Headers: X-User-Id: UUID
 *   Body: {
 *     "activityType": "quiz" | "expert_exam" | "flashcards" | "generar" |
 *                     "resumen" | "abandon_exam" | "duelo_ganado" |
 *                     "duelo_perdido" | "duelo_empate",
 *     "scorePercent": 85,          // 0-100, usar 0 si no aplica
 *     "contentId":    "uuid...",   // opcional
 *     "timeTakenSecs": 180,        // opcional
 *     "maxScore":     10           // opcional, total de preguntas o puntos
 *   }
 *   Response: { success, xpEarned, coinsEarned, streakMultiplier, newStreak,
 *               newXp, newLevel, newCoins, leveledUp, oldLevel,
 *               completedMissions[], completedObjectives[], ... }
 */
@WebServlet({"/api/gamification/stats", "/api/gamification/reward"})
public class GamificationServlet extends HttpServlet {

    private final Gson gson = new Gson();

    // ─── GET: obtener stats ─────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            String userId = req.getHeader("X-User-Id");
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Falta el header X-User-Id.");
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

        // Solo /reward acepta POST
        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        if (!path.endsWith("/reward")) {
            sendError(res, 405, "Usa POST /api/gamification/reward");
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

            // Leer campos del request
            String activityType = data.has("activityType")
                ? data.get("activityType").getAsString()
                : null;

            if (activityType == null || activityType.isBlank()) {
                sendError(res, 400, "Falta activityType en el body.");
                return;
            }

            double scorePercent = data.has("scorePercent")
                ? data.get("scorePercent").getAsDouble()
                : 0;

            String contentId = (data.has("contentId") && !data.get("contentId").isJsonNull())
                ? data.get("contentId").getAsString()
                : null;

            int timeTakenSecs = data.has("timeTakenSecs")
                ? data.get("timeTakenSecs").getAsInt()
                : 0;

            double maxScore = data.has("maxScore")
                ? data.get("maxScore").getAsDouble()
                : 100;

            // Procesar
            JsonObject result = GamificationService.processActivity(
                userId, activityType, scorePercent, contentId, timeTakenSecs, maxScore
            );

            res.getWriter().write(gson.toJson(result));

        } catch (Exception e) {
            sendError(res, 500, "Error procesando reward: " + e.getMessage());
        }
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