package com.project.model.content;

import java.util.List;

/**
 * Representa un set de flashcards generado por IA.
 * Se guarda en study_content con type = "flashcard".
 * El campo content_json guarda la lista de tarjetas en formato JSON.
 */
public class Flashcard extends EducationalContent {

    // Cada tarjeta tiene frente y reverso
    private List<FlashcardItem> cards;

    public Flashcard() {}

    public Flashcard(String userId, String title, String sessionId, List<FlashcardItem> cards) {
        super(userId, "flashcard", title, sessionId);
        this.cards = cards;
    }

    public List<FlashcardItem> getCards() { return cards; }
    public void setCards(List<FlashcardItem> cards) { this.cards = cards; }

    // Clase interna: una tarjeta individual
    public static class FlashcardItem {
        private String front;   // Concepto / pregunta
        private String back;    // Definición / respuesta

        public FlashcardItem() {}

        public FlashcardItem(String front, String back) {
            this.front = front;
            this.back = back;
        }

        public String getFront() { return front; }
        public void setFront(String front) { this.front = front; }

        public String getBack() { return back; }
        public void setBack(String back) { this.back = back; }
    }
}
