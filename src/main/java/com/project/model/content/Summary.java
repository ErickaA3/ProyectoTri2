package com.project.model.content;

/**
 * Representa un resumen generado por IA.
 * Se guarda en study_content con type = "summary".
 * El contenido es texto plano con markdown básico.
 */
public class Summary extends EducationalContent {

    private String summaryText;   // El resumen generado

    public Summary() {}

    public Summary(String userId, String title, String sessionId, String summaryText) {
        super(userId, "summary", title, sessionId);
        this.summaryText = summaryText;
    }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
}

