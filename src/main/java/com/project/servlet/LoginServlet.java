package com.project.servlet;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Collectors;

import com.project.dao.implementation.UserDAOImpl;
import com.project.dao.interfaces.IUserDAO;
import com.project.model.users.Statistics;
import com.project.model.users.User;
import com.project.util.JsonUtil;
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
        String email    = extractJsonField(body, "email");
        String password = extractJsonField(body, "password");

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

            String userJson = JsonUtil.buildUserJson(user, stats);
            JsonUtil.sendSuccess(response, userJson);

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
    String origin = response.getHeader("Origin");
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
    response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    response.setHeader("Access-Control-Allow-Credentials", "false");
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