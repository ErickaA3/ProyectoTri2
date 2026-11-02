package com.project.servlet;

import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.LibraryDAOImpl;
import com.project.dao.interfaces.ILibraryDAO;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Materias y temas del estudiante ("Mis materias").
 *
 *   GET    /api/subjects                 → materias con sus temas
 *   POST   /api/subjects                 → crear materia   { "name": "...", "color": "#3550C9" }
 *   PUT    /api/subjects/{id}            → editar materia  { "name"?: "...", "color"?: "..." }
 *   DELETE /api/subjects/{id}            → archivar materia (no borra contenido)
 *   POST   /api/subjects/{id}/topics     → crear tema      { "name": "..." }
 *   PUT    /api/topics/{id}              → renombrar tema  { "name": "..." }
 *   DELETE /api/topics/{id}              → archivar tema
 *
 * El contrato completo está en docs/api-materias.md.
 * Auth: JWT (JwtFilter cubre /api/*). Todo se valida contra el usuario del token.
 */
@WebServlet(urlPatterns = { "/api/subjects", "/api/subjects/*", "/api/topics/*" })
public class LibraryServlet extends HttpServlet {

    private final ILibraryDAO libraryDAO = new LibraryDAOImpl();
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String userId = userId(req, res);
        if (userId == null) return;
        if (!isSubjects(req) || pathParts(req).length > 0) { sendError(res, 404, "Ruta no encontrada."); return; }

        try {
            JsonObject r = new JsonObject();
            r.addProperty("success", true);
            r.add("subjects", libraryDAO.listSubjects(userId));
            send(res, 200, r);
        } catch (Exception e) {
            sendError(res, 500, "Error al cargar las materias.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String userId = userId(req, res);
        if (userId == null) return;
        String[] parts = pathParts(req);
        JsonObject body = readBody(req);
        if (body == null) { sendError(res, 400, "JSON inválido."); return; }

        try {
            if (isSubjects(req) && parts.length == 0) {
                // POST /api/subjects
                String name = validName(body, res); if (name == null) return;
                String color = optionalColor(body, res); if (color == INVALID) return;
                String id = libraryDAO.createSubject(userId, name, color);
                sendCreated(res, id);

            } else if (isSubjects(req) && parts.length == 2 && "topics".equals(parts[1])) {
                // POST /api/subjects/{id}/topics
                if (!isUuid(parts[0])) { sendError(res, 404, "Materia no encontrada."); return; }
                String name = validName(body, res); if (name == null) return;
                String id = libraryDAO.createTopic(userId, parts[0], name);
                if (id == null) { sendError(res, 404, "Materia no encontrada."); return; }
                sendCreated(res, id);

            } else {
                sendError(res, 404, "Ruta no encontrada.");
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) sendError(res, 409, "Ya existe uno con ese nombre.");
            else sendError(res, 500, "Error al guardar.");
        } catch (Exception e) {
            sendError(res, 500, "Error al guardar.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String userId = userId(req, res);
        if (userId == null) return;
        String[] parts = pathParts(req);
        if (parts.length != 1 || !isUuid(parts[0])) { sendError(res, 404, "Ruta no encontrada."); return; }
        JsonObject body = readBody(req);
        if (body == null) { sendError(res, 400, "JSON inválido."); return; }

        try {
            boolean ok;
            if (isSubjects(req)) {
                String name = body.has("name") ? validName(body, res) : null;
                if (body.has("name") && name == null) return;
                String color = optionalColor(body, res); if (color == INVALID) return;
                ok = libraryDAO.updateSubject(userId, parts[0], name, color);
            } else {
                String name = validName(body, res); if (name == null) return;
                ok = libraryDAO.renameTopic(userId, parts[0], name);
            }
            if (!ok) { sendError(res, 404, "No encontrado."); return; }
            sendOk(res);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) sendError(res, 409, "Ya existe uno con ese nombre.");
            else sendError(res, 500, "Error al guardar.");
        } catch (Exception e) {
            sendError(res, 500, "Error al guardar.");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String userId = userId(req, res);
        if (userId == null) return;
        String[] parts = pathParts(req);
        if (parts.length != 1 || !isUuid(parts[0])) { sendError(res, 404, "Ruta no encontrada."); return; }

        try {
            boolean ok = isSubjects(req)
                    ? libraryDAO.archiveSubject(userId, parts[0])
                    : libraryDAO.archiveTopic(userId, parts[0]);
            if (!ok) { sendError(res, 404, "No encontrado."); return; }
            sendOk(res);
        } catch (Exception e) {
            sendError(res, 500, "Error al archivar.");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Marca de "color inválido" (null significa "no vino color", que es válido). */
    private static final String INVALID = new String("invalid");

    private String userId(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String uid = (String) req.getAttribute("userId");
        if (uid == null) sendError(res, 401, "No autenticado.");
        return uid;
    }

    private boolean isSubjects(HttpServletRequest req) {
        return req.getServletPath().startsWith("/api/subjects");
    }

    private String[] pathParts(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.equals("/")) return new String[0];
        return info.substring(1).split("/");
    }

    private JsonObject readBody(HttpServletRequest req) throws IOException {
        try {
            String raw = req.getReader().lines().collect(Collectors.joining());
            return raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String validName(JsonObject body, HttpServletResponse res) throws IOException {
        String name = body.has("name") && !body.get("name").isJsonNull()
                ? body.get("name").getAsString().trim() : "";
        if (name.isEmpty() || name.length() > 100) {
            sendError(res, 400, "El nombre debe tener entre 1 y 100 caracteres.");
            return null;
        }
        return name;
    }

    private String optionalColor(JsonObject body, HttpServletResponse res) throws IOException {
        if (!body.has("color") || body.get("color").isJsonNull()) return null;
        String color = body.get("color").getAsString().trim();
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            sendError(res, 400, "El color debe tener el formato #RRGGBB.");
            return INVALID;
        }
        return color;
    }

    private static boolean isUuid(String s) {
        try { UUID.fromString(s); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private void sendCreated(HttpServletResponse res, String id) throws IOException {
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("id", id);
        send(res, 201, r);
    }

    private void sendOk(HttpServletResponse res) throws IOException {
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        send(res, 200, r);
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        JsonObject r = new JsonObject();
        r.addProperty("success", false);
        r.addProperty("error", message);
        send(res, status, r);
    }

    private void send(HttpServletResponse res, int status, JsonObject body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(gson.toJson(body));
    }
}
