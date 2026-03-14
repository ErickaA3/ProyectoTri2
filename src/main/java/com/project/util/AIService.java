package com.project.util;
 
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
 
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
 
/**
 * Servicio para llamar a OpenAI.
 * Lee la API key desde config/database.properties.
 *
 * CORRECCIONES:
 * - [BUG-1] Prompt de quiz: "correctIndex" → "correct" para coincidir con el JS
 * - [BUG-2] Prompt de summary: se agrega "readingMinutes" al JSON esperado
 */
public class AIService {
 
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL   = "gpt-3.5-turbo";
    private static final String API_KEY = loadApiKey();
 
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
 
    private static final Gson gson = new Gson();
 
    // ── Cargar API key desde properties ──────────────────────────────────────
    private static String loadApiKey() {
        try (InputStream in = AIService.class
                .getClassLoader()
                .getResourceAsStream("config/database.properties")) {
            if (in == null) throw new RuntimeException("No se encontro config/database.properties");
            Properties props = new Properties();
            props.load(in);
            String key = props.getProperty("OPENAI_API_KEY");
            if (key == null || key.isBlank()) throw new RuntimeException("OPENAI_API_KEY no definida");
            return key.trim();
        } catch (Exception e) {
            throw new RuntimeException("Error leyendo API key: " + e.getMessage());
        }
    }
 
    // ── Contenido educativo con config de módulo ──────────────────────────────
    public static String generate(String type, String texto, JsonObject moduleConfig) throws Exception {
        String prompt = buildPrompt(type, texto, moduleConfig);
        return callAPISingle(prompt);
    }
 
    // Sobrecarga sin config (retrocompatibilidad)
    public static String generate(String type, String texto) throws Exception {
        return generate(type, texto, new JsonObject());
    }
 
    // ── Chat con historial ────────────────────────────────────────────────────
    /**
     * @param historial      Lista de mensajes previos [{role, content}, ...]
     * @param nuevoMensaje   El mensaje nuevo del usuario
     * @param profesorNombre Nombre del profesor configurado
     * @param personalidad   Personalidad del profesor configurada
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
 
        // Últimos 10 mensajes para no exceder tokens
        int start = Math.max(0, historial.size() - 10);
        for (int i = start; i < historial.size(); i++) {
            ChatMessage m = historial.get(i);
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role);
            msg.addProperty("content", m.content);
            messages.add(msg);
        }
 
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
            throw new Exception("Error en la API de IA. Status: " + response.statusCode()
                + " — " + response.body());
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
    private static String buildPrompt(String type, String texto, JsonObject config) {
        switch (type) {
 
            case "flashcard":
                return "Eres un tutor educativo. Analiza el siguiente texto y genera un set de flashcards.\n"
                    + "Responde UNICAMENTE con un JSON valido, sin texto adicional, con este formato:\n"
                    + "{\n"
                    + "  \"title\": \"Titulo descriptivo del tema\",\n"
                    + "  \"cards\": [\n"
                    + "    { \"front\": \"Concepto o pregunta\", \"back\": \"Definicion o respuesta\" }\n"
                    + "  ]\n"
                    + "}\n"
                    + "Genera entre 8 y 15 flashcards. Se conciso y claro.\n\n"
                    + "TEXTO A ESTUDIAR:\n" + texto;
 
            case "schema": {
                String tipoEsquema = config.has("tipo") ? config.get("tipo").getAsString() : "jerarquico";
                return "Eres un tutor educativo. Analiza el texto y genera un esquema de tipo: " + tipoEsquema + ".\n"
                    + "Responde UNICAMENTE con un JSON valido, sin texto adicional:\n"
                    + "{\n"
                    + "  \"title\": \"Titulo del tema\",\n"
                    + "  \"rootNode\": {\n"
                    + "    \"label\": \"Tema principal\",\n"
                    + "    \"children\": [\n"
                    + "      { \"label\": \"Subtema 1\", \"children\": [\n"
                    + "          { \"label\": \"Punto clave\", \"children\": [] }\n"
                    + "      ]},\n"
                    + "      { \"label\": \"Subtema 2\", \"children\": [] }\n"
                    + "    ]\n"
                    + "  }\n"
                    + "}\n\n"
                    + "TEXTO A ESTUDIAR:\n" + texto;
            }
 
            // ─── [BUG-2 FIX] Se agrega "readingMinutes" al prompt de summary ───
            case "summary": {
                String subject = config.has("subject") ? config.get("subject").getAsString() : "General";
                return "Eres un tutor educativo. Genera un resumen claro y estructurado del texto.\n"
                    + "Responde UNICAMENTE con un JSON valido, sin texto adicional, con este formato exacto:\n"
                    + "{\n"
                    + "  \"title\": \"Titulo descriptivo del tema\",\n"
                    + "  \"subject\": \"" + subject + "\",\n"
                    + "  \"readingMinutes\": 5,\n"
                    + "  \"sections\": [\n"
                    + "    {\n"
                    + "      \"number\": \"01\",\n"
                    + "      \"heading\": \"Titulo de la seccion\",\n"
                    + "      \"body\": \"Texto explicativo de la seccion, entre 3 y 6 oraciones.\",\n"
                    + "      \"highlight\": \"Dato importante opcional, o null si no aplica\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"keywords\": [\"palabra1\", \"palabra2\", \"palabra3\"]\n"
                    + "}\n"
                    + "Genera entre 3 y 6 secciones. El campo highlight debe ser una oracion corta o null.\n"
                    + "Genera entre 6 y 12 keywords relevantes del texto.\n"
                    + "readingMinutes debe ser un estimado de cuantos minutos toma leer el resumen (minimo 2).\n\n"
                    + "TEXTO A ESTUDIAR:\n" + texto;
            }
 
            // ─── [BUG-1 FIX] "correctIndex" → "correct" para coincidir con el JS ───
            case "quiz": {
                int    numPreguntas = config.has("numPreguntas") ? config.get("numPreguntas").getAsInt()  : 10;
                String dificultad   = config.has("dificultad")   ? config.get("dificultad").getAsString() : "medio";
                String tipo         = config.has("tipo")          ? config.get("tipo").getAsString()       : "quiz";
                boolean esExperto   = "expert_exam".equals(tipo);
 
                String instrDificultad;
                switch (dificultad) {
                    case "facil":   instrDificultad = "Preguntas basicas de comprension directa."; break;
                    case "dificil": instrDificultad = "Preguntas de analisis profundo y razonamiento complejo."; break;
                    default:        instrDificultad = "Mezcla de preguntas de comprension y aplicacion."; break;
                }
 
                String instrTipo = esExperto
                    ? "Modo EXPERTO: preguntas desafiantes, sin pistas. Deja explanation vacio."
                    : "Modo QUIZ: incluye una explicacion breve de por que es correcta cada respuesta.";
 
                return "Eres un tutor educativo. Genera un examen de opcion multiple.\n"
                    + instrTipo + "\n"
                    + instrDificultad + "\n"
                    + "Genera exactamente " + numPreguntas + " preguntas.\n"
                    + "Responde UNICAMENTE con un JSON valido, sin texto adicional:\n"
                    + "{\n"
                    + "  \"title\": \"Titulo del tema\",\n"
                    + "  \"questions\": [\n"
                    + "    {\n"
                    + "      \"question\": \"Pregunta aqui\",\n"
                    + "      \"options\": [\"Opcion A\", \"Opcion B\", \"Opcion C\", \"Opcion D\"],\n"
                    + "      \"correct\": 0,\n"                          // ← FIX: era "correctIndex"
                    + "      \"explanation\": \"Por que es correcta\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n"
                    + "TEXTO A ESTUDIAR:\n" + texto;
            }
 
            default:
                throw new IllegalArgumentException("Tipo no reconocido: " + type);
        }
    }
 
    // ── Llamada HTTP simple (para contenido educativo) ────────────────────────
    private static String callAPISingle(String prompt) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new Exception("API Key no configurada en database.properties.");
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
            .timeout(Duration.ofSeconds(60))
            .build();
 
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
 
        if (response.statusCode() != 200) {
            throw new Exception("Error API OpenAI. Status: " + response.statusCode()
                + " — " + response.body());
        }
 
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String aiText = responseJson
            .getAsJsonArray("choices").get(0)
            .getAsJsonObject().getAsJsonObject("message")
            .get("content").getAsString();
 
        JsonParser.parseString(aiText); // Valida que sea JSON
        return aiText;
    }
}