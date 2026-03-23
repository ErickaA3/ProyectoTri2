package com.project.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.implementation.DuelDAOImpl;
import com.project.dao.interfaces.IDuelDAO;
import com.project.model.content.Quiz;
import com.project.util.AIService;
import com.project.util.GamificationService;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Servlet de Duelos y Amigos.
 *
 * ═══ AMIGOS ═══
 *   GET  /api/duels/friends          → Lista de amigos
 *   GET  /api/duels/requests         → Solicitudes pendientes
 *   POST /api/duels/friends/add      → Enviar solicitud { "emailOrUsername": "..." }
 *   POST /api/duels/friends/accept   → Aceptar { "friendshipId": "..." }
 *   POST /api/duels/friends/reject   → Rechazar { "friendshipId": "..." }
 *   POST /api/duels/friends/remove   → Eliminar { "friendshipId": "..." }
 *
 * ═══ DUELOS ═══
 *   GET  /api/duels/active           → Duelos activos
 *   GET  /api/duels/history          → Historial de duelos terminados
 *   GET  /api/duels/play?id=UUID     → Obtener preguntas del duelo (valida que no haya jugado)
 *   GET  /api/duels/detail?id=UUID   → Detalle de un duelo
 *   POST /api/duels/create           → Crear duelo (no retorna preguntas, solo metadata)
 *   POST /api/duels/submit           → Enviar resultado + gamificación automática
 *   POST /api/duels/decline          → Declinar/cancelar duelo
 *
 * Todos requieren header X-User-Id.
 */
@WebServlet("/api/duels/*")
@MultipartConfig(maxFileSize = 10485760) // 10MB
public class DuelServlet extends HttpServlet {

    private final IDuelDAO duelDAO = new DuelDAOImpl();
    private final ContentDAOImpl contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    // ─── GET ────────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = req.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) { sendError(res, 401, "Falta X-User-Id."); return; }

        String path = req.getPathInfo() != null ? req.getPathInfo() : "";

        try {
            switch (path) {
                case "/friends" -> {
                    JsonArray friends = duelDAO.getFriends(userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.add("friends", friends);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/requests" -> {
                    JsonArray requests = duelDAO.getPendingRequests(userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.add("requests", requests);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/active" -> {
                    JsonArray duels = duelDAO.getActiveDuels(userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.add("duels", duels);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/history" -> {
                    JsonArray history = duelDAO.getDuelHistory(userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.add("history", history);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/play" -> {
                    String duelId = req.getParameter("id");
                    if (duelId == null) { sendError(res, 400, "Falta parámetro id."); return; }
                    JsonObject questions = duelDAO.getDuelQuestions(duelId, userId);
                    if (questions == null) { sendError(res, 404, "Duelo no encontrado."); return; }
                    questions.addProperty("success", true);
                    res.getWriter().write(gson.toJson(questions));
                }
                case "/detail" -> {
                    String duelId = req.getParameter("id");
                    if (duelId == null) { sendError(res, 400, "Falta parámetro id."); return; }
                    JsonObject duel = duelDAO.getDuel(duelId, userId);
                    if (duel == null) { sendError(res, 404, "Duelo no encontrado."); return; }
                    duel.addProperty("success", true);
                    res.getWriter().write(gson.toJson(duel));
                }
                default -> sendError(res, 404, "Ruta no encontrada: GET " + path);
            }
        } catch (Exception e) {
            sendError(res, 500, e.getMessage());
        }
    }

    // ─── POST ───────────────────────────────────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String userId = req.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) { sendError(res, 401, "Falta X-User-Id."); return; }

        String path = req.getPathInfo() != null ? req.getPathInfo() : "";

        try {
            // /create puede venir como multipart (con archivo) o JSON (solo texto)
            if ("/create".equals(path)) {
                handleCreate(req, res, userId);
                return;
            }

            // El resto siempre es JSON
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            switch (path) {

                // ── AMIGOS ──
                case "/friends/add" -> {
                    String emailOrUsername = data.get("emailOrUsername").getAsString();
                    String friendshipId = duelDAO.sendFriendRequest(userId, emailOrUsername);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", true);
                    r.addProperty("friendshipId", friendshipId);
                    r.addProperty("message", "Solicitud enviada.");
                    res.getWriter().write(gson.toJson(r));
                }
                case "/friends/accept" -> {
                    String fid = data.get("friendshipId").getAsString();
                    boolean ok = duelDAO.acceptFriendRequest(fid, userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", ok);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/friends/reject" -> {
                    String fid = data.get("friendshipId").getAsString();
                    boolean ok = duelDAO.rejectFriendRequest(fid, userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", ok);
                    res.getWriter().write(gson.toJson(r));
                }
                case "/friends/remove" -> {
                    String fid = data.get("friendshipId").getAsString();
                    boolean ok = duelDAO.removeFriend(fid, userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", ok);
                    res.getWriter().write(gson.toJson(r));
                }

                // ── ENVIAR RESULTADO ──
                case "/submit" -> {
                    String duelId    = data.get("duelId").getAsString();
                    int    score     = data.get("score").getAsInt();
                    int    maxScore  = data.get("maxScore").getAsInt();
                    int    timeSecs  = data.get("timeSecs").getAsInt();
                    String answers   = data.getAsJsonArray("answers").toString();

                    JsonObject result = duelDAO.submitDuelResult(duelId, userId, score, maxScore, timeSecs, answers);

                    // Si el duelo terminó, dar rewards de gamificación a AMBOS jugadores
                    if (result.has("duelFinished") && result.get("duelFinished").getAsBoolean()) {
                        String resultType = result.get("result").getAsString();

                        // Reward para el jugador actual
                        String activityType = switch (resultType) {
                            case "win"  -> "duelo_ganado";
                            case "loss" -> "duelo_perdido";
                            default     -> "duelo_empate";
                        };

                        double pct = maxScore > 0 ? (score * 100.0 / maxScore) : 0;
                        JsonObject reward = GamificationService.processActivity(
                            userId, activityType, pct, null, timeSecs, maxScore
                        );
                        result.add("reward", reward);

                        // Reward para el otro jugador
                        JsonObject duel = duelDAO.getDuel(duelId, userId);
                        String otherUserId = userId.equals(duel.get("challengerId").getAsString())
                            ? duel.get("opponentId").getAsString()
                            : duel.get("challengerId").getAsString();

                        String otherActivityType = switch (resultType) {
                            case "win"  -> "duelo_perdido";  // invertido
                            case "loss" -> "duelo_ganado";   // invertido
                            default     -> "duelo_empate";
                        };

                        int otherScore = result.get("otherScore").getAsInt();
                        double otherPct = maxScore > 0 ? (otherScore * 100.0 / maxScore) : 0;
                        int otherTime = result.get("otherTime").getAsInt();
                        GamificationService.processActivity(
                            otherUserId, otherActivityType, otherPct, null, otherTime, maxScore
                        );
                    }

                    res.getWriter().write(gson.toJson(result));
                }

                // ── DECLINAR DUELO ──
                case "/decline" -> {
                    String duelId = data.get("duelId").getAsString();
                    boolean ok = duelDAO.declineDuel(duelId, userId);
                    JsonObject r = new JsonObject();
                    r.addProperty("success", ok);
                    res.getWriter().write(gson.toJson(r));
                }

                default -> sendError(res, 404, "Ruta no encontrada: POST " + path);
            }
        } catch (Exception e) {
            sendError(res, 500, e.getMessage());
        }
    }

    // ─── CREAR DUELO (JSON o Multipart con archivo) ───────────
    private void handleCreate(HttpServletRequest req, HttpServletResponse res, String userId)
            throws Exception {

        String contentType = req.getContentType();
        String opponentId, topic, text;
        int questionCount, timePerQ;

        if (contentType != null && contentType.contains("multipart/form-data")) {
            // ── Multipart: archivo subido ──
            opponentId    = req.getParameter("opponentId");
            topic         = req.getParameter("topic");
            questionCount = parseInt(req.getParameter("questionCount"), 10);
            timePerQ      = parseInt(req.getParameter("timePerQuestion"), 30);

            Part filePart = req.getPart("file");
            if (filePart == null || filePart.getSize() == 0) {
                sendError(res, 400, "No se recibió archivo."); return;
            }

            text = extractTextFromFile(filePart);
            if (text == null || text.isBlank()) {
                sendError(res, 400, "No se pudo extraer texto del archivo."); return;
            }

        } else {
            // ── JSON: solo texto ──
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            opponentId    = data.get("opponentId").getAsString();
            topic         = data.get("topic").getAsString();
            questionCount = data.has("questionCount") ? data.get("questionCount").getAsInt() : 10;
            timePerQ      = data.has("timePerQuestion") ? data.get("timePerQuestion").getAsInt() : 30;
            text          = data.has("text") && !data.get("text").isJsonNull()
                            ? data.get("text").getAsString() : topic;
        }

        // 1. Generar quiz con la IA
        JsonObject quizConfig = new JsonObject();
        quizConfig.addProperty("tipo", "quiz");
        quizConfig.addProperty("numPreguntas", questionCount);
        quizConfig.addProperty("dificultad", "medio");

        String aiJson = AIService.generate("quiz", text, quizConfig);
        JsonObject aiData = JsonParser.parseString(aiJson).getAsJsonObject();
        String title = aiData.has("title") ? aiData.get("title").getAsString() : topic;

        // 2. Guardar quiz en study_content (type='duel_quiz')
        Quiz quizContent = new Quiz(userId, "duel_quiz", title, null);
        String contentId = contentDAO.save(quizContent, aiJson, text);

        // 3. Crear duelo
        String duelId = duelDAO.createDuel(userId, opponentId, contentId, topic, questionCount, timePerQ);

        // 4. Responder con metadata
        JsonObject r = new JsonObject();
        r.addProperty("success", true);
        r.addProperty("duelId", duelId);
        r.addProperty("contentId", contentId);
        r.addProperty("title", title);
        r.addProperty("questionCount", questionCount);
        r.addProperty("topic", topic);
        res.getWriter().write(gson.toJson(r));
    }

    private int parseInt(String val, int defaultVal) {
        try { return val != null ? Integer.parseInt(val) : defaultVal; }
        catch (NumberFormatException e) { return defaultVal; }
    }

    /** Extrae texto de PDF, DOCX, PPTX o TXT */
    private String extractTextFromFile(Part filePart) {
        String fileName = filePart.getSubmittedFileName().toLowerCase();
        try (InputStream is = filePart.getInputStream()) {
            if (fileName.endsWith(".txt")) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (fileName.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {
                    return new PDFTextStripper().getText(doc);
                }
            }
            if (fileName.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(is)) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append("\n");
                    return sb.toString();
                }
            }
            if (fileName.endsWith(".pptx")) {
                try (XMLSlideShow ppt = new XMLSlideShow(is)) {
                    StringBuilder sb = new StringBuilder();
                    for (XSLFSlide slide : ppt.getSlides()) {
                        for (var shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape ts) {
                                sb.append(ts.getText()).append("\n");
                            }
                        }
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("[DuelServlet] Error extrayendo texto: " + e.getMessage());
        }
        return null;
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}