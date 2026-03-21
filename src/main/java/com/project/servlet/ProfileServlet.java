package com.project.servlet;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.project.dao.implementation.UserDAOImpl;
import com.project.dao.interfaces.IUserDAO;
import com.project.model.users.DailyMission;
import com.project.model.users.Statistics;
import com.project.model.users.User;
import com.project.model.users.WeeklyObjective;
import com.project.util.JsonUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/api/profile")
public class ProfileServlet extends HttpServlet {

    private IUserDAO userDAO;

    @Override
    public void init() {
        userDAO = new UserDAOImpl();
    }

    // GET /api/profile → devuelve datos completos del usuario logueado
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        // Verificar sesión activa
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            JsonUtil.sendError(response, 401, "No autenticado.");
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString((String) session.getAttribute("userId"));
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "ID de usuario inválido.");
            return;
        }

        try {
            Optional<User> optUser = userDAO.findById(userId);
            if (optUser.isEmpty()) {
                JsonUtil.sendError(response, 404, "Usuario no encontrado.");
                return;
            }

            User user                       = optUser.get();
            Optional<Statistics> optStats  = userDAO.getStatsByUserId(userId);
            Statistics stats               = optStats.orElse(null);

            // ── AUTO-CREAR misiones/objetivos si no existen para hoy/esta semana ──
            userDAO.ensureWeeklyObjectives(userId);
            userDAO.ensureDailyMissions(userId);

            // ── Ahora sí leer (ya garantizado que hay filas) ──
            List<WeeklyObjective> weekly   = userDAO.getWeeklyObjectives(userId);
            List<DailyMission> daily       = userDAO.getDailyMissions(userId);

            String json = JsonUtil.buildProfileJson(user, stats, weekly, daily);
            JsonUtil.sendSuccess(response, json);

        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    // PUT /api/profile → actualiza full_name, country, language, birthdate
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            JsonUtil.sendError(response, 401, "No autenticado.");
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString((String) session.getAttribute("userId"));
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(response, 400, "ID de usuario inválido.");
            return;
        }

        String body     = request.getReader().lines().collect(Collectors.joining());
        String fullName = extractJsonField(body, "fullName");
        String country  = extractJsonField(body, "country");
        String language = extractJsonField(body, "language");
        String birthdate = extractJsonField(body, "birthdate");

        try {
            Optional<User> optUser = userDAO.findById(userId);
            if (optUser.isEmpty()) {
                JsonUtil.sendError(response, 404, "Usuario no encontrado.");
                return;
            }

            User user = optUser.get();
            if (fullName  != null && !fullName.isBlank())  user.setFullName(fullName);
            if (country   != null && !country.isBlank())   user.setCountry(country);
            if (language  != null && !language.isBlank())  user.setLanguage(language);
            if (birthdate != null && !birthdate.isBlank()) {
                try {
                    user.setBirthdate(java.time.LocalDate.parse(birthdate));
                } catch (java.time.format.DateTimeParseException ex) {
                    // Formato inválido, ignorar campo
                }
            }

            userDAO.updateUser(user);
            JsonUtil.sendSuccess(response, "{\"message\": \"Perfil actualizado correctamente.\"}");

        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.sendError(response, 500, "Error interno del servidor.");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
        setCorsHeaders(res);
        res.setStatus(HttpServletResponse.SC_OK);
    }

    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5500");
        response.setHeader("Access-Control-Allow-Methods", "GET, PUT, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-User-Id");
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx);
        if (colon == -1) return null;
        int start = json.indexOf("\"", colon) + 1;
        int end   = json.indexOf("\"", start);
        if (start <= 0 || end <= start) return null;
        return json.substring(start, end);
    }
}