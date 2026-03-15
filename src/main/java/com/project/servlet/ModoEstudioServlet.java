package com.project.servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

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
 * Soporta archivos: PDF, DOCX, DOC, PPTX, TXT
 * - PDF  → Apache PDFBox
 * - DOCX → Apache POI (XWPF)
 * - DOC  → Apache POI (HWPF)
 * - PPTX → Apache POI (XSLF)
 * - TXT  → lectura directa UTF-8
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
                // ── Multipart: viene con archivo ──────────────────────
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

                String fileName = filePart.getSubmittedFileName();
                String ext = (fileName != null)
                    ? fileName.toLowerCase().substring(fileName.lastIndexOf('.') + 1)
                    : "";

                // Validar extensión soportada
                if (!List.of("pdf", "docx", "doc", "pptx", "txt").contains(ext)) {
                    sendError(res, 400,
                        "Formato no soportado: ." + ext
                        + ". Formatos aceptados: PDF, DOCX, DOC, PPTX, TXT.");
                    return;
                }

                byte[] fileBytes = filePart.getInputStream().readAllBytes();

                // Extraer texto según el formato
                textoBase = switch (ext) {
                    case "pdf"  -> extractTextFromPDF(fileBytes);
                    case "docx" -> extractTextFromDOCX(fileBytes);
                    case "doc"  -> extractTextFromDOC(fileBytes);
                    case "pptx" -> extractTextFromPPTX(fileBytes);
                    case "txt"  -> new String(fileBytes, StandardCharsets.UTF_8);
                    default     -> null;
                };

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
                sendError(res, 400, "No se recibió texto o el archivo estaba vacío."); return;
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
    // EXTRACCIÓN DE TEXTO POR FORMATO
    // ─────────────────────────────────────────────────────────────────────────

    /** PDF → Apache PDFBox */
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
                throw new Exception("El archivo PDF está dañado o no es un PDF válido.");
            }
            throw e;
        }
    }

    /** DOCX → Apache POI XWPF */
    private String extractTextFromDOCX(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String line = p.getText();
                if (line != null && !line.isBlank()) {
                    sb.append(line).append("\n");
                }
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) {
                throw new Exception("No se pudo extraer texto del DOCX. El documento puede estar vacío.");
            }
            return text;
        }
    }

    /** DOC (formato antiguo) → Apache POI HWPF */
    private String extractTextFromDOC(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            WordExtractor extractor = new WordExtractor(doc);
            String text = extractor.getText();
            extractor.close();
            if (text == null || text.isBlank()) {
                throw new Exception("No se pudo extraer texto del DOC. El documento puede estar vacío.");
            }
            return text.trim();
        }
    }

    /** PPTX → Apache POI XSLF */
    private String extractTextFromPPTX(byte[] bytes) throws Exception {
        try (XMLSlideShow pptx = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int slideNum = 1;
            for (XSLFSlide slide : pptx.getSlides()) {
                sb.append("--- Diapositiva ").append(slideNum++).append(" ---\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) {
                throw new Exception("No se pudo extraer texto del PPTX. La presentación puede estar vacía.");
            }
            return text;
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