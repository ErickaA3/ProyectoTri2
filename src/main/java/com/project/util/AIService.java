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
 * Usa patrón System Prompt + User Prompt para resultados consistentes.
 * Cada tipo de contenido tiene prompts optimizados para producción.
 */
public class AIService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL   = "gpt-4o-mini";
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

    // ═══════════════════════════════════════════════════════════════════════════
    // GENERACIÓN DE CONTENIDO EDUCATIVO
    // ═══════════════════════════════════════════════════════════════════════════

    public static String generate(String type, String texto, JsonObject moduleConfig) throws Exception {
        String systemPrompt = buildSystemPrompt(type, moduleConfig);
        String userPrompt   = buildUserPrompt(type, texto, moduleConfig);
        double temperature  = getTemperature(type);
        return callAPIWithSystem(systemPrompt, userPrompt, temperature);
    }

    public static String generate(String type, String texto) throws Exception {
        return generate(type, texto, new JsonObject());
    }

    /** Temperatura óptima por tipo */
    private static double getTemperature(String type) {
        return switch (type) {
            case "flashcard" -> 0.3;
            case "schema"    -> 0.2;
            case "quiz"      -> 0.4;
            case "summary"   -> 0.3;
            default          -> 0.4;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SYSTEM PROMPTS — Define el ROL y las REGLAS de la IA
    // ═══════════════════════════════════════════════════════════════════════════

    private static String buildSystemPrompt(String type, JsonObject config) {
        String base = "Eres un experto en pedagogía y diseño instruccional. "
            + "Tu trabajo es transformar contenido académico en recursos de estudio de alta calidad.\n\n"
            + "REGLAS ABSOLUTAS:\n"
            + "1. Responde ÚNICAMENTE con JSON válido. Sin texto antes ni después.\n"
            + "2. Sin comentarios, sin markdown, sin backticks. Solo el objeto JSON puro.\n"
            + "3. Todos los textos deben estar en español.\n"
            + "4. Sé preciso: extrae solo información que realmente aparece en el texto.\n"
            + "5. NO inventes datos que no estén en el contenido proporcionado.\n"
            + "6. Usa lenguaje claro y académico, evitando jerga innecesaria.\n";

        switch (type) {
            case "flashcard":
                return base + "\nMODO: Generador de Flashcards.\n"
                    + "- Cada tarjeta debe cubrir UN solo concepto, definición, proceso o dato clave.\n"
                    + "- El \"front\" es una pregunta clara o el nombre del concepto.\n"
                    + "- El \"back\" es la respuesta concisa (1-3 oraciones máximo).\n"
                    + "- Varía el tipo de preguntas: definiciones, procesos, comparaciones, ejemplos.\n"
                    + "- Ordena las flashcards de lo más fundamental a lo más específico.\n"
                    + "- NO repitas información entre tarjetas.\n"
                    + "- Genera entre 8 y 15 flashcards dependiendo de la densidad del contenido.\n";

            case "schema": {
                String tipoEsquema = config.has("tipo") ? config.get("tipo").getAsString() : "jerarquico";
                String instrEsquema;
                switch (tipoEsquema) {
                    case "jerarquico":
                        instrEsquema = "TIPO DE ESQUEMA: Jerárquico (árbol de arriba hacia abajo).\n"
                            + "- El rootNode.label es el tema principal.\n"
                            + "- Nivel 1 (children directos): subtemas o categorías principales (3-5 nodos).\n"
                            + "- Nivel 2 (children de nivel 1): puntos clave específicos (2-4 por subtema).\n"
                            + "- Nivel 3 (opcional): detalles o ejemplos si el contenido lo amerita.\n"
                            + "- Cada label debe ser corto: máximo 4-5 palabras.\n"
                            + "- La estructura debe reflejar la jerarquía lógica del contenido.\n";
                        break;
                    case "conceptual":
                        instrEsquema = "TIPO DE ESQUEMA: Mapa Conceptual (nodo central con conexiones radiales).\n"
                            + "- El rootNode.label es el concepto central del tema.\n"
                            + "- Los children directos son los conceptos principales relacionados (4-7 nodos).\n"
                            + "- Cada concepto principal puede tener 1-3 sub-conceptos como children.\n"
                            + "- Los labels deben ser conceptos concretos, no oraciones.\n"
                            + "- Piensa en RELACIONES entre ideas, no solo en jerarquía.\n"
                            + "- Máximo 3-5 palabras por label.\n";
                        break;
                    case "timeline":
                        instrEsquema = "TIPO DE ESQUEMA: Línea del Tiempo (eventos cronológicos).\n"
                            + "- El rootNode.label es el título del período o proceso.\n"
                            + "- Los children directos son los eventos/etapas EN ORDEN CRONOLÓGICO.\n"
                            + "- Cada evento tiene un label descriptivo (incluir fecha/período si aplica).\n"
                            + "- Los sub-children de cada evento son detalles o consecuencias.\n"
                            + "- Genera entre 4 y 8 eventos principales.\n"
                            + "- Si el texto no tiene fechas, usa orden lógico de pasos/fases.\n";
                        break;
                    case "causa-efecto":
                        instrEsquema = "TIPO DE ESQUEMA: Causa y Efecto (diagrama Ishikawa/espina de pescado).\n"
                            + "- El rootNode.label es el EFECTO o problema central.\n"
                            + "- Los children directos son las CAUSAS principales (3-6 causas).\n"
                            + "- Los sub-children de cada causa son sub-causas o factores específicos (1-3 por causa).\n"
                            + "- Distribuye las causas de forma equilibrada.\n"
                            + "- Cada label debe ser conciso: máximo 4-5 palabras.\n"
                            + "- Las causas deben ser categorías distintas, no repeticiones.\n";
                        break;
                    case "ciclico":
                        instrEsquema = "TIPO DE ESQUEMA: Cíclico (proceso que se repite en ciclo).\n"
                            + "- El rootNode.label es el nombre del ciclo/proceso.\n"
                            + "- Los children directos son las FASES del ciclo EN ORDEN.\n"
                            + "- Genera entre 3 y 6 fases.\n"
                            + "- La última fase debe conectar lógicamente con la primera.\n"
                            + "- Cada fase puede tener 1-2 sub-children con detalles.\n"
                            + "- Labels cortos: máximo 4-5 palabras por fase.\n";
                        break;
                    default:
                        instrEsquema = "TIPO DE ESQUEMA: Jerárquico. Organiza de lo general a lo específico.\n";
                }
                return base + "\nMODO: Generador de Esquemas.\n" + instrEsquema;
            }

            case "quiz": {
                String tipo = config.has("tipo") ? config.get("tipo").getAsString() : "quiz";
                String dificultad = config.has("dificultad") ? config.get("dificultad").getAsString() : "medio";
                boolean esExperto = "expert_exam".equals(tipo);

                String instrDif;
                switch (dificultad) {
                    case "facil":
                        instrDif = "DIFICULTAD: Fácil.\n"
                            + "- Preguntas de comprensión directa y memorización.\n"
                            + "- Las opciones incorrectas deben ser claramente diferentes.\n"
                            + "- Enfócate en definiciones, hechos y conceptos básicos.\n";
                        break;
                    case "dificil":
                        instrDif = "DIFICULTAD: Difícil.\n"
                            + "- Preguntas de análisis, aplicación y síntesis.\n"
                            + "- Las opciones incorrectas deben ser plausibles y requerir discriminación fina.\n"
                            + "- Incluye preguntas de \"cuál NO es\", comparaciones y casos hipotéticos.\n";
                        break;
                    default:
                        instrDif = "DIFICULTAD: Media.\n"
                            + "- Mezcla de comprensión directa y aplicación.\n"
                            + "- Las opciones incorrectas deben ser razonables pero distinguibles.\n"
                            + "- Varía entre definiciones, relaciones y aplicaciones prácticas.\n";
                }

                String instrTipo;
                if (esExperto) {
                    instrTipo = "MODO: Examen Experto (evaluación sumativa).\n"
                        + "- Preguntas desafiantes que requieren comprensión profunda.\n"
                        + "- NO incluyas explicaciones (explanation debe ser cadena vacía \"\").\n"
                        + "- Las opciones deben estar balanceadas en longitud.\n";
                } else {
                    instrTipo = "MODO: Quiz formativo (práctica de estudio).\n"
                        + "- Incluye una explicación clara de POR QUÉ la respuesta es correcta.\n"
                        + "- La explicación debe ser educativa, 1-2 oraciones.\n"
                        + "- Ayuda al estudiante a entender, no solo a memorizar.\n";
                }

                return base + "\nMODO: Generador de Evaluaciones.\n" + instrTipo + instrDif;
            }

            case "summary":
                return base + "\nMODO: Generador de Resúmenes Estructurados.\n"
                    + "- Identifica las ideas principales y organízalas en secciones lógicas.\n"
                    + "- Cada sección debe cubrir un aspecto distinto del tema.\n"
                    + "- El body de cada sección: 3-5 oraciones claras y concisas.\n"
                    + "- El highlight es un dato clave, cifra, o concepto crucial de esa sección (o null).\n"
                    + "- Los keywords deben ser términos técnicos o conceptos clave del texto.\n"
                    + "- readingMinutes: estima cuántos minutos toma leer tu resumen (mínimo 2).\n"
                    + "- NO copies oraciones textuales del original. Parafrasea con claridad.\n";

            default:
                return base;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER PROMPTS — El contenido específico a procesar
    // ═══════════════════════════════════════════════════════════════════════════

    private static String buildUserPrompt(String type, String texto, JsonObject config) {
        // Truncar texto si es demasiado largo para no exceder tokens
        String t = texto.length() > 12000
            ? texto.substring(0, 12000) + "\n[...texto truncado...]"
            : texto;

        switch (type) {
            case "flashcard":
                return "Genera flashcards del siguiente contenido.\n\n"
                    + "FORMATO JSON OBLIGATORIO:\n"
                    + "{\n"
                    + "  \"title\": \"Título descriptivo del tema\",\n"
                    + "  \"cards\": [\n"
                    + "    {\"front\": \"Pregunta o concepto\", \"back\": \"Respuesta concisa\"}\n"
                    + "  ]\n"
                    + "}\n\n"
                    + "CONTENIDO:\n" + t;

            case "schema": {
                String tipoEsquema = config.has("tipo") ? config.get("tipo").getAsString() : "jerarquico";
                return "Genera un esquema de tipo \"" + tipoEsquema + "\" del siguiente contenido.\n\n"
                    + "FORMATO JSON OBLIGATORIO:\n"
                    + "{\n"
                    + "  \"title\": \"Título del tema\",\n"
                    + "  \"rootNode\": {\n"
                    + "    \"label\": \"Tema principal\",\n"
                    + "    \"children\": [\n"
                    + "      {\n"
                    + "        \"label\": \"Subtema\",\n"
                    + "        \"children\": [\n"
                    + "          {\"label\": \"Detalle\", \"children\": []}\n"
                    + "        ]\n"
                    + "      }\n"
                    + "    ]\n"
                    + "  }\n"
                    + "}\n\n"
                    + "IMPORTANTE: Todos los nodos DEBEN tener \"label\" (string) y \"children\" (array, puede estar vacío []).\n\n"
                    + "CONTENIDO:\n" + t;
            }

            case "quiz": {
                int numPreguntas = config.has("numPreguntas") ? config.get("numPreguntas").getAsInt() : 10;
                return "Genera exactamente " + numPreguntas + " preguntas del siguiente contenido.\n\n"
                    + "FORMATO JSON OBLIGATORIO:\n"
                    + "{\n"
                    + "  \"title\": \"Título del tema\",\n"
                    + "  \"questions\": [\n"
                    + "    {\n"
                    + "      \"question\": \"Texto de la pregunta\",\n"
                    + "      \"options\": [\"Opción A\", \"Opción B\", \"Opción C\", \"Opción D\"],\n"
                    + "      \"correct\": 0,\n"
                    + "      \"explanation\": \"Por qué es correcta (o vacío en modo experto)\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n"
                    + "IMPORTANTE: \"correct\" es el ÍNDICE numérico (0-3) de la opción correcta. "
                    + "Varía la posición de la respuesta correcta (no siempre en 0). "
                    + "Cada pregunta DEBE tener exactamente 4 opciones.\n\n"
                    + "CONTENIDO:\n" + t;
            }

            case "summary": {
                String subject = config.has("subject") ? config.get("subject").getAsString() : "General";
                return "Genera un resumen estructurado del siguiente contenido. Materia: " + subject + ".\n\n"
                    + "FORMATO JSON OBLIGATORIO:\n"
                    + "{\n"
                    + "  \"title\": \"Título descriptivo\",\n"
                    + "  \"subject\": \"" + subject + "\",\n"
                    + "  \"readingMinutes\": 5,\n"
                    + "  \"sections\": [\n"
                    + "    {\n"
                    + "      \"number\": \"01\",\n"
                    + "      \"heading\": \"Título de la sección\",\n"
                    + "      \"body\": \"Texto explicativo, 3-5 oraciones.\",\n"
                    + "      \"highlight\": \"Dato clave o null\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"keywords\": [\"término1\", \"término2\"]\n"
                    + "}\n\n"
                    + "Genera entre 3 y 6 secciones y entre 6 y 12 keywords relevantes.\n\n"
                    + "CONTENIDO:\n" + t;
            }

            default:
                throw new IllegalArgumentException("Tipo no reconocido: " + type);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT CON HISTORIAL (para el módulo del chatbot de Hans)
    // ═══════════════════════════════════════════════════════════════════════════

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
            "Eres " + nombre + ", un asistente educativo con IA. "
            + "Tu personalidad es " + persona + ". "
            + "Ayudas a estudiantes a entender temas académicos de forma clara y didáctica. "
            + "Responde siempre en español, de forma concisa y con ejemplos cuando sea útil. "
            + "Puedes usar negritas con **texto** para resaltar conceptos importantes."
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

    // ═══════════════════════════════════════════════════════════════════════════
    // LLAMADA HTTP CON SYSTEM + USER PROMPT
    // ═══════════════════════════════════════════════════════════════════════════

    private static String callAPIWithSystem(String systemPrompt, String userPrompt,
                                             double temperature) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new Exception("API Key no configurada en database.properties.");
        }

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);

        JsonArray messages = new JsonArray();
        messages.add(sysMsg);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", temperature);

        // response_format para forzar JSON válido (funciona en gpt-4o-mini y gpt-4o)
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(Duration.ofSeconds(90))
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

        // Limpiar posibles backticks
        aiText = aiText.trim();
        if (aiText.startsWith("```json")) aiText = aiText.substring(7);
        if (aiText.startsWith("```")) aiText = aiText.substring(3);
        if (aiText.endsWith("```")) aiText = aiText.substring(0, aiText.length() - 3);
        aiText = aiText.trim();

        // Validar que sea JSON parseable
        JsonParser.parseString(aiText);
        return aiText;
    }
}