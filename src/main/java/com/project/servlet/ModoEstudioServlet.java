package com.project.servlet;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.project.dao.implementation.ContentDAOImpl;
import com.project.dao.interfaces.IContentDAO;
import com.project.model.content.Diagram;
import com.project.model.content.EducationalContent;
import com.project.model.content.Flashcard;
import com.project.model.content.Quiz;
import com.project.model.content.Summary;
import com.project.util.AIService;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;



/**
 * Servlet principal del Modo Estudio.
 * URL: /modo-estudio/generar  — POST
 *
 * CORRECCIONES:
 * - [BUG-3] Validación de tipo de archivo PDF antes de parsear
 * - [BUG-3] Mejor manejo de errores cuando el archivo no es PDF válido
 * - [BUG-3] Se leen los bytes UNA sola vez para evitar doble consumo del stream
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
                if (filePart == null || filePart.getSize() == 0) {
                    sendError(res, 400, "No se recibió ningún archivo.");
                    return;
                }

                // ─── [BUG-3 FIX] Validar que el archivo sea PDF ───
                String contentType = filePart.getContentType();
                String fileName    = filePart.getSubmittedFileName();

                boolean isPdf = (contentType != null && contentType.contains("pdf"))
                    || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));

                if (!isPdf) {
                    sendError(res, 400,
                        "Solo se aceptan archivos PDF. Tipo recibido: "
                        + (contentType != null ? contentType : "desconocido"));
                    return;
                }

                // ─── [BUG-3 FIX] Leer bytes UNA sola vez ───
                byte[] fileBytes = filePart.getInputStream().readAllBytes();

                // Validar que los bytes empiecen con %PDF (magic bytes)
                if (fileBytes.length < 5
                    || fileBytes[0] != '%'
                    || fileBytes[1] != 'P'
                    || fileBytes[2] != 'D'
                    || fileBytes[3] != 'F') {
                    sendError(res, 400,
                        "El archivo no es un PDF válido. Asegúrate de subir un archivo .pdf real.");
                    return;
                }

                textoBase = extractTextFromPDF(fileBytes);

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
                // Obtener la config específica de este módulo (si tiene)
                JsonObject moduleConfig = configs.has(option)
                    ? configs.getAsJsonObject(option) : new JsonObject();

                String contentTypeStr = mapOptionToType(option, moduleConfig);
                if (contentTypeStr == null) continue;

                try {
                    JsonObject generated = generateAndSave(
                        userId, contentTypeStr, option, textoBase, sessionId, moduleConfig
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

        // AIService solo tiene case "quiz" para exámenes — expert_exam usa el mismo prompt
        // pero el moduleConfig.tipo le indica a la IA el modo (ver buildPrompt case "quiz")
        String aiType = "expert_exam".equals(contentType) ? "quiz" : contentType;
        String aiResponseJson = AIService.generate(aiType, textoBase, moduleConfig);
        JsonObject aiData = JsonParser.parseString(aiResponseJson).getAsJsonObject();
        String title = aiData.has("title") ? aiData.get("title").getAsString() : "Sin título";

        // Inyectar tipo de módulo en el JSONB para poder recuperarlo después
        // (ej: esquema jerárquico vs timeline, quiz vs expert_exam)
        if (moduleConfig.has("tipo")) {
            aiData.addProperty("schemaType", moduleConfig.get("tipo").getAsString());
        }

        EducationalContent content = buildContentObject(userId, contentType, title, sessionId);
        String savedId = contentDAO.save(content, gson.toJson(aiData), textoBase);

        aiData.addProperty("id", savedId);
        aiData.addProperty("type", contentType);
        aiData.addProperty("sessionId", sessionId);

        return aiData;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXTRACCIÓN DE TEXTO DEL PDF
    // [BUG-3 FIX] Recibe byte[] para evitar doble consumo del InputStream
    // ─────────────────────────────────────────────────────────────────────────

    private String extractTextFromPDF(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                throw new Exception("No se pudo extraer texto del PDF. ¿Es un PDF escaneado sin OCR?");
            }
            return text;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Missing root object")) {
                throw new Exception("El archivo PDF está dañado o no es un PDF válido. Intenta con otro archivo.");
            }
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mapea la opción del frontend al tipo interno de la BD.
     * Para "examenes" lee moduleConfig.tipo para distinguir "quiz" de "expert_exam".
     */
    private String mapOptionToType(String option, JsonObject moduleConfig) {
        return switch (option) {
            case "flashcards" -> "flashcard";
            case "esquemas"   -> "schema";
            case "resumenes"  -> "summary";
            case "examenes"   -> {
                String tipo = (moduleConfig.has("tipo") && !moduleConfig.get("tipo").isJsonNull())
                    ? moduleConfig.get("tipo").getAsString()
                    : "quiz";
                yield tipo;
            }
            default -> null;
        };
    }

    private EducationalContent buildContentObject(String userId, String type,
                                                   String title, String sessionId) {
        return switch (type) {
            case "flashcard"           -> new Flashcard(userId, title, sessionId, null);
            case "schema"              -> new Diagram(userId, title, sessionId, null);
            case "quiz", "expert_exam" -> new Quiz(userId, type, title, sessionId);
            default                    -> new Summary(userId, title, sessionId, null);
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