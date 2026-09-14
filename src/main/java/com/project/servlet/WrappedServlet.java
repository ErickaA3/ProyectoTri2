package com.project.servlet;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.project.dao.implementation.WrappedDAOImpl;
import com.project.dao.interfaces.IWrappedDAO;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet del resumen semanal ("Wrapped").
 *
 * ═══════════════════════════════════════════════════════════
 *  GET /api/wrapped?userId=UUID
 * ═══════════════════════════════════════════════════════════
 *   Response: { success, data: { studyHours, studyCompare,
 *               topSubject, topPct, weakSubject, questionsCount,
 *               freqTopic, freqDetail, streak, xp, coins,
 *               suggestionTitle, suggestionText } }
 *
 * Coincide con lo que ya espera chat.js:
 *   fetch(`${API_BASE}/api/wrapped?userId=${userId}`)
 */
@WebServlet("/api/wrapped")
public class WrappedServlet extends HttpServlet {

    private final Gson gson = new Gson();
    private final IWrappedDAO wrappedDAO = new WrappedDAOImpl();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            String userId = req.getParameter("userId");
            if (userId == null || userId.isBlank()) {
                sendError(res, 401, "Falta el parámetro userId.");
                return;
            }

            JsonObject data = wrappedDAO.getWeeklySummary(userId);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("data", data);
            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            sendError(res, 500, "Error armando el resumen semanal: " + e.getMessage());
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