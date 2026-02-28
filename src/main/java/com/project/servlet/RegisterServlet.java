package com.project.servlet;

import java.io.IOException;
import java.sql.SQLException;
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

@WebServlet("/api/register")
public class RegisterServlet extends HttpServlet {

    private IUserDAO userDAO;

    @Override
    public void init() {
        userDAO = new UserDAOImpl();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        setCorsHeaders(response);
        String body     = request.getReader().lines().collect(Collectors.joining());
        String username = extractJsonField(body, "username");
        String email    = extractJsonField(body, "email");
        String password = extractJsonField(body, "password");
        String fullName = extractJsonField(body, "fullName");

        if (username == null || username.isBlank()) { JsonUtil.sendError(response, 400, "El nombre de usuario es obligatorio."); return; }
        if (email == null || email.isBlank()) { JsonUtil.sendError(response, 400, "El email es obligatorio."); return; }
        if (!email.contains("@")) { JsonUtil.sendError(response, 400, "El email no tiene un formato válido."); return; }
        if (password == null || password.isBlank()) { JsonUtil.sendError(response, 400, "La contraseña es obligatoria."); return; }
        if (!PasswordUtil.isStrong(password)) { JsonUtil.sendError(response, 400, "La contraseña debe tener al menos 8 caracteres, una letra y un número."); return; }
        if (username.length() < 3 || username.length() > 50) { JsonUtil.sendError(response, 400, "El usuario debe tener entre 3 y 50 caracteres."); return; }

        try {
            if (userDAO.emailExists(email.trim().toLowerCase())) { JsonUtil.sendError(response, 409, "El email ya está registrado."); return; }
            if (userDAO.usernameExists(username.trim())) { JsonUtil.sendError(response, 409, "El nombre de usuario ya está en uso."); return; }

            User newUser = new User();
            newUser.setUsername(username.trim());
            newUser.setEmail(email.trim().toLowerCase());
            newUser.setPasswordHash(PasswordUtil.hash(password));
            newUser.setFullName(fullName);
            newUser.setLanguage("es");

            User created = userDAO.register(newUser);
            Statistics stats = new Statistics(created.getId());

            HttpSession session = request.getSession(true);
            session.setAttribute("userId",   created.getId().toString());
            session.setAttribute("username", created.getUsername());
            session.setMaxInactiveInterval(60 * 60 * 8);

            response.setStatus(HttpServletResponse.SC_CREATED);
            JsonUtil.sendSuccess(response, JsonUtil.buildUserJson(created, stats));

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