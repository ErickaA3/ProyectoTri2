package com.project.util;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * Servicio para llamar a la API de IA (OpenAI / el que usen en el proyecto).
 * Centraliza todas las llamadas para que Flashcards, Esquemas y Resúmenes
 * usen el mismo método, solo cambiando el prompt.
 *
 * IMPORTANTE: Reemplaza API_KEY y API_URL con los valores reales del proyecto.
 */
public class AIService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY"); // Nunca hardcodear
    private static final String MODEL   = "gpt-3.5-turbo";

    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final Gson gson = new Gson();

    // -----------------------------------------------------------------------
    // MÉTODO PRINCIPAL — recibe el tipo y el texto, retorna JSON string de IA
    // -----------------------------------------------------------------------

    /**
     * Llama a la IA con el prompt correcto según el tipo de contenido.
     * @param type    "flashcard" | "schema" | "summary" | "quiz"
     * @param texto   El texto del usuario para estudiar
     * @return        String JSON con la respuesta estructurada de la IA
     */
    public static String generate(String type, String texto) throws Exception {
        String prompt = buildPrompt(type, texto);
        return callAPI(prompt);
    }

    // -----------------------------------------------------------------------
    // PROMPTS — cada tipo tiene su propio prompt con formato JSON esperado
    // -----------------------------------------------------------------------

    private static String buildPrompt(String type, String texto) {
        return switch (type) {

            case "flashcard" -> """
                Eres un tutor educativo. Analiza el siguiente texto y genera un set de flashcards.
                Responde ÚNICAMENTE con un JSON válido, sin texto adicional, con este formato:
                {
                  "title": "Título descriptivo del tema",
                  "cards": [
                    { "front": "Concepto o pregunta", "back": "Definición o respuesta" },
                    { "front": "...", "back": "..." }
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
                      {
                        "label": "Subtema 1",
                        "children": [
                          { "label": "Punto clave", "children": [] },
                          { "label": "Punto clave", "children": [] }
                        ]
                      },
                      {
                        "label": "Subtema 2",
                        "children": []
                      }
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
                  "summaryText": "Resumen completo usando markdown básico (## para subtítulos, **negrita**, listas con -)"
                }
                El resumen debe ser claro, completo y útil para estudiar.
                
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
                      "options": ["Opción A", "Opción B", "Opción C", "Opción D"],
                      "correctIndex": 0,
                      "explanation": "Por qué esta es la respuesta correcta"
                    }
                  ]
                }
                Genera entre 5 y 10 preguntas de dificultad variada.
                
                TEXTO A ESTUDIAR:
                """ + texto;

            default -> throw new IllegalArgumentException("Tipo de contenido no reconocido: " + type);
        };
    }

    // -----------------------------------------------------------------------
    // LLAMADA HTTP A LA API
    // -----------------------------------------------------------------------

    private static String callAPI(String prompt) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new Exception("API Key no configurada. Revisa la variable de entorno OPENAI_API_KEY.");
        }

        // Construir el body de la petición
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
            throw new Exception("Error en la API de IA. Status: " + response.statusCode());
        }

        // Extraer el texto de la respuesta
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String aiText = responseJson
            .getAsJsonArray("choices")
            .get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();

        // Validar que sea JSON válido antes de retornar
        JsonParser.parseString(aiText); // Lanza excepción si no es JSON válido
        return aiText;
    }
}
