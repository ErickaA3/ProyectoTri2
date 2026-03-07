package com.project.model.content;

/**
 * Modelo para evaluaciones generadas por IA.
 * Cubre ambos tipos: "quiz" (formativo) y "expert_exam" (evaluativo).
 * La diferencia entre ambos es solo de comportamiento en el frontend;
 * la estructura JSON generada por la IA es idéntica.
 *
 * Estructura JSON esperada en study_content.content:
 * {
 *   "title": "...",
 *   "questions": [
 *     {
 *       "question": "...",
 *       "options": ["A", "B", "C", "D"],
 *       "correct": 0,          // índice de la opción correcta
 *       "explanation": "..."   // solo quiz; puede estar vacío en expert_exam
 *     }
 *   ]
 * }
 */
public class Quiz extends EducationalContent {

    /**
     * @param userId    UUID del usuario
     * @param type      "quiz" o "expert_exam"
     * @param title     Título generado por la IA
     * @param sessionId UUID de sesión que agrupa todo el contenido generado
     */
    public Quiz(String userId, String type, String title, String sessionId) {
        super(userId, type, title, sessionId);
    }
}