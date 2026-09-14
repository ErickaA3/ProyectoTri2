package com.project.servlet;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.UserDAOImpl;
import com.project.dao.interfaces.IUserDAO;
import com.project.model.users.Statistics;
import com.project.model.users.User;
import com.project.util.JsonUtil;
import com.project.util.JwtUtil;
import com.project.util.PasswordUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {

    private IUserDAO userDAO;

    @Override
    public void init() {
        userDAO = new UserDAOImpl();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        String body = request.getReader().lines().collect(Collectors.joining());

        // Antes: extractJsonField() a mano, se rompía si el email/contraseña
        // traía comillas o caracteres especiales. Ahora se usa Gson, igual
        // que el resto del proyecto.
        String email;
        String password;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            email    = json.has("email")    && !json.get("email").isJsonNull()    ? json.get("email").getAsString()    : null;
            password = json.has("password") && !json.get("password").isJsonNull() ? json.get("password").getAsString() : null;
        } catch (Exception e) {
            JsonUtil.sendError(response, 400, "Body inválido, se esperaba JSON.");
            return;
        }

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            JsonUtil.sendError(response, 400, "Email y contraseña son obligatorios.");
            return;
        }

        try {
            Optional<User> optUser = userDAO.findByEmail(email.trim().toLowerCase());
            if (optUser.isEmpty()) {
                JsonUtil.sendError(response, 401, "Credenciales incorrectas.");
                return;
            }

            User user = optUser.get();
            if (!PasswordUtil.verify(password, user.getPasswordHash())) {
                JsonUtil.sendError(response, 401, "Credenciales incorrectas.");
                return;
            }

            Optional<Statistics> optStats = userDAO.getStatsByUserId(user.getId());
            Statistics stats = optStats.orElse(null);

            HttpSession session = request.getSession(true);
            session.setAttribute("userId",   user.getId().toString());
            session.setAttribute("username", user.getUsername());
            session.setMaxInactiveInterval(60 * 60 * 8);

            String token = JwtUtil.generarToken(user.getId(), "student");

            String userJson = JsonUtil.buildUserJson(user, stats);
            JsonUtil.setJsonHeaders(response);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"success\":true,\"token\":\"" + token + "\",\"data\":" + userJson + "}");

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
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Allow-Credentials", "true");
    }
}