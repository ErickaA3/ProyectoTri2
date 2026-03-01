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
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servlet principal del Modo Estudio.
 * Recibe texto + tipos seleccionados y genera contenido con IA para cada uno.
 *
 * URL: /modo-estudio/generar
 * Método: POST
 * Body (JSON):
 * {
 *   "userId": "uuid-del-usuario",
 *   "options": ["flashcards", "esquemas", "resumenes", "quizzes"],
 *   "dataType": "text",
 *   "text": "Texto a estudiar..."
 * }
 *
 * Respuesta:
 * {
 *   "success": true,
 *   "sessionId": "uuid-de-la-sesion",
 *   "results": {
 *     "flashcards": { "id": "uuid", "title": "...", "cards": [...] },
 *     "esquemas":   { "id": "uuid", "title": "...", "rootNode": {...} },
 *     "resumenes":  { "id": "uuid", "title": "...", "summaryText": "..." },
 *     "quizzes":    { "id": "uuid", "title": "...", "questions": [...] }
 *   }
 * }
 */
@WebServlet("/modo-estudio/generar")
@MultipartConfig(maxFileSize = 10_485_760) // 10 MB máximo
public class ModoEstudioServlet extends HttpServlet {

    private final IContentDAO contentDAO = new ContentDAOImpl();
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        try {
            // 1. Leer y parsear el body
            String body = req.getReader().lines().collect(Collectors.joining());
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            // userId es UUID — viene como String del frontend (sessionStorage)
            String userId = data.get("userId").getAsString();
            String dataType = data.get("dataType").getAsString();

            // Extraer los tipos de contenido seleccionados
            List<String> options = gson.fromJson(
                data.getAsJsonArray("options"),
                new com.google.gson.reflect.TypeToken<List<String>>(){}.getType()
            );

            // 2. Obtener el texto a estudiar
            String textoBase = extractText(data, dataType);

            if (textoBase == null || textoBase.isBlank()) {
                sendError(res, 400, "No se recibió texto para procesar.");
                return;
            }

            if (options == null || options.isEmpty()) {
                sendError(res, 400, "Selecciona al menos un tipo de contenido.");
                return;
            }

            // 3. Generar un sessionId único para agrupar todo lo de esta sesión
            String sessionId = UUID.randomUUID().toString();

            // 4. Generar contenido para cada tipo seleccionado
            JsonObject results = new JsonObject();

            for (String option : options) {
                String contentType = mapOptionToType(option);
                if (contentType == null) continue;

                try {
                    JsonObject generated = generateAndSave(userId, contentType, textoBase, sessionId);
                    results.add(option, generated);
                } catch (Exception e) {
                    // Si un tipo falla, seguimos con los demás y reportamos el error puntual
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Error generando " + option + ": " + e.getMessage());
                    results.add(option, error);
                }
            }

            // 5. Responder con todos los resultados y el sessionId
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("sessionId", sessionId);
            response.add("results", results);
            res.getWriter().write(gson.toJson(response));

        } catch (Exception e) {
            sendError(res, 500, "Error interno: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // GENERAR Y GUARDAR — llama a la IA y persiste en BD
    // -----------------------------------------------------------------------

    private JsonObject generateAndSave(String userId, String type, String textoBase, String sessionId)
            throws Exception {

        // Llamar a la IA con el prompt del tipo correspondiente
        String aiResponseJson = AIService.generate(type, textoBase);

        // Parsear la respuesta de la IA
        JsonObject aiData = JsonParser.parseString(aiResponseJson).getAsJsonObject();
        String title = aiData.has("title") ? aiData.get("title").getAsString() : "Sin título";

        // Crear el objeto de contenido con userId (UUID String) y sessionId
        EducationalContent content = buildContentObject(userId, type, title, sessionId);

        // Guardar en study_content — pasamos el JSON de la IA y el texto original
        String savedId = contentDAO.save(content, aiResponseJson, textoBase);

        // Agregar el UUID del registro guardado al resultado
        aiData.addProperty("id", savedId);
        aiData.addProperty("type", type);
        aiData.addProperty("sessionId", sessionId);

        return aiData;
    }

    // -----------------------------------------------------------------------
    // MÉTODOS AUXILIARES
    // -----------------------------------------------------------------------

    private String extractText(JsonObject data, String dataType) {
        if ("text".equals(dataType)) {
            return data.has("text") ? data.get("text").getAsString() : null;
        }
        // TODO: Soporte para archivos (PDF parsing — siguiente iteración)
        return null;
    }

    /**
     * Mapea el nombre del frontend al tipo interno de la BD.
     * frontend: "flashcards" → BD check constraint: "flashcard"
     */
    private String mapOptionToType(String option) {
        return switch (option) {
            case "flashcards" -> "flashcard";
            case "esquemas"   -> "schema";
            case "resumenes"  -> "summary";
            case "quizzes"    -> "quiz";
            default           -> null;
        };
    }

    /**
     * Instancia el modelo correcto según el tipo.
     * Nota: para quiz usamos Summary como base temporal
     * hasta que Ericka defina Quiz.java con su estructura.
     */
    private EducationalContent buildContentObject(String userId, String type, String title, String sessionId) {
        return switch (type) {
            case "flashcard" -> new Flashcard(userId, title, sessionId, null);
            case "schema"    -> new Diagram(userId, title, sessionId, null);
            default          -> new Summary(userId, title, sessionId, null); // summary y quiz
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