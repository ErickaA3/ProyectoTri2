package com.project.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide el texto de un documento en fragmentos para el RAG (document_chunks).
 *
 * Cada fragmento tiene como máximo MAX_CHARS caracteres. Se intenta cortar en
 * un salto de párrafo y, si no hay, al final de una oración, para no partir
 * ideas a la mitad. Los fragmentos se superponen OVERLAP_CHARS caracteres
 * para que una idea que cae justo en el corte quede completa en alguno.
 */
public final class TextChunker {

    public static final int MAX_CHARS     = 2000;
    private static final int MIN_CHARS    = 1000;   // no cortar antes de esto aunque haya un párrafo
    private static final int OVERLAP_CHARS = 200;

    private TextChunker() {}

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) return chunks;

        String t = text.replace("\r\n", "\n").trim();
        int n = t.length();
        int start = 0;

        while (start < n) {
            int end = Math.min(start + MAX_CHARS, n);
            if (end < n) {
                int cut = t.lastIndexOf("\n\n", end);
                if (cut < start + MIN_CHARS) cut = t.lastIndexOf(". ", end);
                if (cut >= start + MIN_CHARS) end = cut + 1;
            }

            String chunk = t.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= n) break;

            // El siguiente fragmento repite el final de este, empezando en una palabra entera.
            int next = Math.max(end - OVERLAP_CHARS, start + 1);
            int space = t.indexOf(' ', next);
            start = (space != -1 && space < end) ? space + 1 : next;
        }
        return chunks;
    }

    /** Estimación de tokens (≈ 4 caracteres por token en español). */
    public static int estimateTokens(String chunk) {
        return Math.max(1, chunk.length() / 4);
    }
}
