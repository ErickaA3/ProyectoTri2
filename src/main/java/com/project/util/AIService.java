package com.project.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AIService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String MODEL   = "gpt-3.5-turbo";

    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final Gson gson = new Gson();

    // ── Contenido educativo (flashcard, schema, summary, quiz) ────────────────
    public static String generate(String type, String texto) throws Exception {
        String prompt = buildPrompt(type, texto);
        return callAPISingle(prompt);
    }

    // ── Chat con historial ────────────────────────────────────────────────────
    /**
     * @param historial Lista de mensajes previos [{role, content}, ...]
     * @param nuevoMensaje El mensaje nuevo del usuario
     * @param profesorNombre Nombre del profesor configurado
     * @param personalidad Personalidad del profesor configurada
     * @return Texto de respuesta del asistente
     */
    public static String chat(List<ChatMessage> historial, String nuevoMensaje,
                               String profesorNombre, String personalidad) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new Exception("API Key no configurada.");
        }

        String nombre = (profesorNombre != null && !profesorNombre.isBlank())
            ? profesorNombre : "Búho ProfesorIA";
        String persona = (personalidad != null && !personalidad.isBlank())
            ? personalidad : "amigable y motivador";

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content",
            "Eres " + nombre + ", un asistente educativo con IA. " +
            "Tu personalidad es " + persona + ". " +
            "Ayudas a estudiantes a entender temas académicos de forma clara y didáctica. " +
            "Responde siempre en español, de forma concisa y con ejemplos cuando sea útil. " +
            "Puedes usar negritas con **texto** para resaltar conceptos importantes."
        );

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);

        // Agregar historial (últimos 10 mensajes para no exceder tokens)
        int start = Math.max(0, historial.size() - 10);
        for (int i = start; i < historial.size(); i++) {
            ChatMessage m = historial.get(i);
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role);
            msg.addProperty("content", m.content);
            messages.add(msg);
        }

        // Agregar el mensaje nuevo
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", nuevoMensaje);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", 0.8);
        body.addProperty("max_tokens", 800);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Error en la API de IA. Status: " + response.statusCode());
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseJson
            .getAsJsonArray("choices")
            .get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();
    }

    // ── Clase auxiliar para historial ─────────────────────────────────────────
    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role    = role;
            this.content = content;
        }
    }

    // ── Prompts para contenido educativo ──────────────────────────────────────
    private static String buildPrompt(String type, String texto) {
        return switch (type) {

            case "flashcard" -> """
                Eres un tutor educativo. Analiza el siguiente texto y genera un set de flashcards.
                Responde ÚNICAMENTE con un JSON válido, sin texto adicional, con este formato:
                {
                  "title": "Título descriptivo del tema",
                  "cards": [
                    { "front": "Concepto o pregunta", "back": "Definición o respuesta" }
                  ]
                }
                Genera entre 8 y 15 flashcards. Sé conciso y claro.
                TEXTO A ESTUDIAR:
                """ + texto;

            case "schema" -> """
                Eres un tutor educativo. Analiza el siguiente texto y genera un esquema jerárquico.
                Responde ÚNICAMENTE con un JSON válido, sin texto adicional, con este formato:
                {
                  "title": "Título del tema",
                  "rootNode": {
                    "label": "Tema principal",
                    "children": [
                      { "label": "Subtema", "children": [{ "label": "Punto", "children": [] }] }
                    ]
                  }
                }
                TEXTO A ESTUDIAR:
                """ + texto;

            case "summary" -> """
                Eres un tutor educativo. Genera un resumen claro y estructurado del siguiente texto.
                Responde ÚNICAMENTE con un JSON válido, sin texto adicional, con este formato:
                {
                  "title": "Título del tema",
                  "summaryText": "Resumen completo en markdown"
                }
                TEXTO A ESTUDIAR:
                """ + texto;

            case "quiz" -> """
                Eres un tutor educativo. Genera un quiz de opción múltiple sobre el siguiente texto.
                Responde ÚNICAMENTE con un JSON válido, sin texto adicional, con este formato:
                {
                  "title": "Título del tema",
                  "questions": [
                    {
                      "question": "Pregunta aquí",
                      "options": ["A", "B", "C", "D"],
                      "correctIndex": 0,
                      "explanation": "Explicación"
                    }
                  ]
                }
                Genera entre 5 y 10 preguntas.
                TEXTO A ESTUDIAR:
                """ + texto;

            default -> throw new IllegalArgumentException("Tipo no reconocido: " + type);
        };
    }

    // ── Llamada HTTP simple (para contenido educativo) ────────────────────────
    private static String callAPISingle(String prompt) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new Exception("API Key no configurada. Revisa OPENAI_API_KEY.");
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Error en la API. Status: " + response.statusCode());
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String aiText = responseJson
            .getAsJsonArray("choices")
            .get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();

        JsonParser.parseString(aiText);
        return aiText;
    }
}