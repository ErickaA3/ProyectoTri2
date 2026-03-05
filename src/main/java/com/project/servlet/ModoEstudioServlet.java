package com.project.servlet;

import com.google.gson.*;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.model.content.*;
import com.project.util.AIService;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;



/**
 * Servlet principal del Modo Estudio.
 * URL: /modo-estudio/generar  — POST
 *
 * Body JSON:
 * {
 *   "userId":   "uuid",
 *   "options":  ["esquemas","flashcards","examenes","resumenes"],
 *   "dataType": "text" | "file",
 *   "text":     "...",          // si dataType = text
 *   "configs":  {               // configuraciones de cada módulo
 *     "examenes": { "tipo": "quiz" | "expert_exam", "numPreguntas": 10, "dificultad": "medio" }
 *     "esquemas": { "tipo": "jerarquico" | ... }
 *   }
 * }
 */
@WebServlet("/modo-estudio/generar")
@MultipartConfig(maxFileSize = 10_485_760) // 10 MB
public class ModoEstudioServlet extends HttpServlet {

    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            String dataType = req.getContentType() != null && req.getContentType().contains("multipart")
                ? "file" : "json";

            String userId;
            List<String> options;
            String textoBase;
            JsonObject configs = new JsonObject();

            if ("file".equals(dataType)) {
                // ── Multipart: viene con archivo PDF ──────────────────────
                userId  = req.getParameter("userId");
                String optionsParam = req.getParameter("options");
                options = gson.fromJson(optionsParam,
                    new com.google.gson.reflect.TypeToken<List<String>>(){}.getType());

                String configsParam = req.getParameter("configs");
                if (configsParam != null && !configsParam.isBlank()) {
                    configs = JsonParser.parseString(configsParam).getAsJsonObject();
                }

                Part filePart = req.getPart("file");
                textoBase = extractTextFromPDF(filePart.getInputStream());

            } else {
                // ── JSON puro: viene con texto ────────────────────────────
                String body = req.getReader().lines().collect(Collectors.joining());
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();

                userId  = data.get("userId").getAsString();
                options = gson.fromJson(data.getAsJsonArray("options"),
                    new com.google.gson.reflect.TypeToken<List<String>>(){}.getType());
                textoBase = (data.has("text") && !data.get("text").isJsonNull()) ? data.get("text").getAsString() : null;

                if (data.has("configs") && data.get("configs").isJsonObject()) {
                    configs = data.getAsJsonObject("configs");
                }
            }

            // ── Validaciones básicas ──────────────────────────────────────
            if (textoBase == null || textoBase.isBlank()) {
                sendError(res, 400, "No se recibió texto o el PDF estaba vacío."); return;
            }
            if (options == null || options.isEmpty()) {
                sendError(res, 400, "Selecciona al menos un tipo de contenido."); return;
            }

            // ── Generar sessionId ─────────────────────────────────────────
            String sessionId = UUID.randomUUID().toString();

            // ── Generar contenido para cada opción seleccionada ───────────
            JsonObject results = new JsonObject();
            for (String option : options) {
                String contentType = mapOptionToType(option);
                if (contentType == null) continue;

                // Obtener la config específica de este módulo (si tiene)
                JsonObject moduleConfig = configs.has(option)
                    ? configs.getAsJsonObject(option) : new JsonObject();

                try {
                    JsonObject generated = generateAndSave(
                        userId, contentType, option, textoBase, sessionId, moduleConfig
                    );
                    results.add(option, generated);
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Error generando " + option + ": " + e.getMessage());
                    results.add(option, error);
                }
            }

            // ── Respuesta ─────────────────────────────────────────────────
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("sessionId", sessionId);
            response.add("results", results);
            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            sendError(res, 500, "Error interno: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERAR Y GUARDAR
    // ─────────────────────────────────────────────────────────────────────────

    private JsonObject generateAndSave(String userId, String contentType, String option,
                                        String textoBase, String sessionId,
                                        JsonObject moduleConfig) throws Exception {

        String aiResponseJson = AIService.generate(contentType, textoBase, moduleConfig);
        JsonObject aiData = JsonParser.parseString(aiResponseJson).getAsJsonObject();
        String title = aiData.has("title") ? aiData.get("title").getAsString() : "Sin título";

        EducationalContent content = buildContentObject(userId, contentType, title, sessionId);
        String savedId = contentDAO.save(content, aiResponseJson, textoBase);

        aiData.addProperty("id", savedId);
        aiData.addProperty("type", contentType);
        aiData.addProperty("sessionId", sessionId);

        return aiData;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXTRACCIÓN DE TEXTO DEL PDF
    // El PDF se descarta — solo guardamos el resultado JSON de la IA
    // ─────────────────────────────────────────────────────────────────────────

        private String extractTextFromPDF(InputStream pdfStream) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                throw new Exception("No se pudo extraer texto del PDF.");
            }
            return text;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mapea la opción del frontend al tipo interno de la BD.
     * frontend → BD (check constraint)
     */
    private String mapOptionToType(String option) {
        return switch (option) {
            case "flashcards" -> "flashcard";
            case "esquemas"   -> "schema";
            case "resumenes"  -> "summary";
            case "examenes"   -> "quiz";   // ← antes era "quizzes", ahora es "examenes"
            default           -> null;
        };
    }

    private EducationalContent buildContentObject(String userId, String type,
                                                   String title, String sessionId) {
        return switch (type) {
            case "flashcard" -> new Flashcard(userId, title, sessionId, null);
            case "schema"    -> new Diagram(userId, title, sessionId, null);
            default          -> new Summary(userId, title, sessionId, null);
        };
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        res.getWriter().write(gson.toJson(error));
    }
}