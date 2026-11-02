package com.project.dao.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.project.dao.interfaces.IWrappedDAO;
import com.project.database.DatabaseConnection;

/**
 * Implementación del resumen semanal ("Wrapped").
 *
 * Nota importante: la base de datos no tiene una columna de "materia" o
 * "tema" explícita en ninguna tabla. Para poder mostrar "tu tema más
 * fuerte" y "tu pregunta más frecuente" se clasifica el texto (títulos de
 * study_content y mensajes de chat_history) por palabras clave, igual que
 * ya hace detectTheme() en chat.js del frontend. Si más adelante se agrega
 * una columna real de materia, esta clasificación por palabras clave se
 * puede reemplazar por una consulta directa.
 */
public class WrappedDAOImpl implements IWrappedDAO {

    // Debe reflejar el mismo mapa de SUBJECT_MAP que hay en chat.js
    private static final Map<String, String[]> SUBJECT_KEYWORDS = new LinkedHashMap<>();
    private static final Map<String, String> SUBJECT_LABELS = new LinkedHashMap<>();
    static {
        SUBJECT_KEYWORDS.put("math", new String[]{
            "matemática","álgebra","cálculo","geometría","ecuación","matriz",
            "función","derivada","integral","estadística","trigonometría","número","fracción"});
        SUBJECT_KEYWORDS.put("history", new String[]{
            "historia","guerra","revolución","imperio","civilización","siglo",
            "rey","presidente","política","sociedad","cultura","antiguo","medieval"});
        SUBJECT_KEYWORDS.put("science", new String[]{
            "física","química","biología","célula","molécula","átomo","energía",
            "fuerza","ecosistema","evolución","genética","laboratorio","experimento"});
        SUBJECT_KEYWORDS.put("code", new String[]{
            "programación","código","función","variable","algoritmo","javascript",
            "java","python","html","css","base de datos","sql","api","software"});

        SUBJECT_LABELS.put("math",    "Matemáticas");
        SUBJECT_LABELS.put("history", "Historia");
        SUBJECT_LABELS.put("science", "Ciencias");
        SUBJECT_LABELS.put("code",    "Programación");
        SUBJECT_LABELS.put("default", "Variado");
    }

    private String detectSubject(String text) {
        if (text == null) return "default";
        String t = text.toLowerCase();
        for (Map.Entry<String, String[]> e : SUBJECT_KEYWORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (t.contains(w)) return e.getKey();
            }
        }
        return "default";
    }

    @Override
    public JsonObject getWeeklySummary(String userId) throws Exception {
        LocalDate today          = LocalDate.now();
        LocalDate weekStart      = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart  = weekStart.minusWeeks(1);

        JsonObject result = new JsonObject();

        fillStudyTime(userId, weekStart, lastWeekStart, result);
        fillSubjects(userId, weekStart, result);
        fillFrequentTopic(userId, weekStart, result);
        fillGamification(userId, weekStart, lastWeekStart, result);
        fillSuggestion(result);

        return result;
    }

    // ─── Tiempo de estudio (activity_results.time_taken_seconds) ───────────
    private void fillStudyTime(String userId, LocalDate weekStart, LocalDate lastWeekStart,
                                JsonObject result) throws SQLException {
        String sql = """
            SELECT
              COALESCE(SUM(CASE WHEN finished_at >= ?::date
                                 THEN EXTRACT(EPOCH FROM finished_at - started_at) ELSE 0 END), 0)::bigint AS this_week_secs,
              COALESCE(SUM(CASE WHEN finished_at >= ?::date AND finished_at < ?::date
                                 THEN EXTRACT(EPOCH FROM finished_at - started_at) ELSE 0 END), 0)::bigint AS last_week_secs
            FROM attempts
            WHERE user_id = ?::uuid AND status = 'completed'
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, weekStart.toString());
            stmt.setString(2, lastWeekStart.toString());
            stmt.setString(3, weekStart.toString());
            stmt.setString(4, userId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                double thisWeekHours = rs.getLong("this_week_secs") / 3600.0;
                double lastWeekHours = rs.getLong("last_week_secs") / 3600.0;

                result.addProperty("studyHours", String.format("%.1fh", thisWeekHours));

                String compare;
                if (lastWeekHours <= 0 && thisWeekHours <= 0) {
                    compare = "Aún no hay suficientes datos para comparar.";
                } else if (lastWeekHours <= 0) {
                    compare = "Es tu primera semana con actividad registrada. ¡Buen inicio!";
                } else {
                    double pct = Math.round(((thisWeekHours - lastWeekHours) / lastWeekHours) * 100);
                    if (pct > 0)      compare = "Eso es " + (int) pct + "% más que la semana pasada.";
                    else if (pct < 0) compare = "Eso es " + (int) -pct + "% menos que la semana pasada.";
                    else              compare = "Igual que la semana pasada.";
                }
                result.addProperty("studyCompare", compare);
            }
        }
    }

    // ─── Tema más fuerte / más débil (activity_results + study_content) ────
    private void fillSubjects(String userId, LocalDate weekStart, JsonObject result) throws SQLException {
        String sql = """
            SELECT sc.score_pct AS score, 100 AS max_score, gc.title, s.name AS subject_name
            FROM attempts a
            JOIN v_attempt_scores sc ON sc.attempt_id = a.id
            LEFT JOIN generated_content gc ON gc.id = a.content_id
            LEFT JOIN topics t   ON t.id = gc.topic_id
            LEFT JOIN subjects s ON s.id = t.subject_id
            WHERE a.user_id = ?::uuid AND a.status = 'completed' AND a.finished_at >= ?::date
              AND sc.score_pct IS NOT NULL
            """;

        Map<String, List<Double>> bySubject = new LinkedHashMap<>();
        int questionsCount = 0;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, weekStart.toString());

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                questionsCount++;
                double score    = rs.getDouble("score");
                double maxScore = rs.getDouble("max_score");
                if (maxScore <= 0) continue;

                // La materia ahora es un dato real. Solo para lo que está en
                // "Sin clasificar" se sigue adivinando por palabras clave del título.
                String subjectName = rs.getString("subject_name");
                String subject = (subjectName != null && !"Sin clasificar".equals(subjectName))
                        ? subjectName
                        : detectSubject(rs.getString("title") != null ? rs.getString("title") : "");
                double pct = (score / maxScore) * 100.0;
                bySubject.computeIfAbsent(subject, k -> new ArrayList<>()).add(pct);
            }
        }

        result.addProperty("questionsCount", String.valueOf(questionsCount));

        if (bySubject.isEmpty()) {
            result.addProperty("topSubject", "Sin datos aún");
            result.addProperty("topPct", "");
            result.addProperty("weakSubject", "Sin datos aún");
            return;
        }

        String topKey = null, weakKey = null;
        double topAvg = -1, weakAvg = 101;
        for (Map.Entry<String, List<Double>> e : bySubject.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(d -> d).average().orElse(0);
            if (avg > topAvg)  { topAvg = avg;  topKey = e.getKey(); }
            if (avg < weakAvg) { weakAvg = avg; weakKey = e.getKey(); }
        }

        result.addProperty("topSubject", SUBJECT_LABELS.getOrDefault(topKey, "Variado"));
        result.addProperty("topPct", Math.round(topAvg) + "% de aciertos");

        if (bySubject.size() > 1) {
            result.addProperty("weakSubject", SUBJECT_LABELS.getOrDefault(weakKey, "Variado"));
        } else {
            result.addProperty("weakSubject", "Aún no hay suficientes temas para comparar");
        }
    }

    // ─── Pregunta / tema más frecuente (chat_messages) ──────────────────────
    private void fillFrequentTopic(String userId, LocalDate weekStart, JsonObject result) throws SQLException {
        String sql = """
            SELECT m.body AS message
            FROM chat_messages m JOIN chat_sessions s ON s.id = m.session_id
            WHERE s.user_id = ?::uuid AND m.role = 'user' AND m.created_at >= ?::date
            """;

        Map<String, Integer> counts = new LinkedHashMap<>();

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            stmt.setString(2, weekStart.toString());

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String subject = detectSubject(rs.getString("message"));
                if (subject.equals("default")) continue; // no cuenta como tema identificable
                counts.merge(subject, 1, Integer::sum);
            }
        }

        if (counts.isEmpty()) {
            result.addProperty("freqTopic", "Aún no hay suficientes preguntas esta semana");
            result.addProperty("freqDetail", "Pregúntale más cosas a tu asistente para ver este dato.");
            return;
        }

        String topKey = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();
        int count = counts.get(topKey);
        result.addProperty("freqTopic", SUBJECT_LABELS.getOrDefault(topKey, "Variado"));
        result.addProperty("freqDetail", "Le preguntaste sobre esto " + count + " " +
            (count == 1 ? "vez" : "veces") + " esta semana.");
    }

    // ─── Racha, XP y monedas ────────────────────────────────────────────────
    private void fillGamification(String userId, LocalDate weekStart, LocalDate lastWeekStart,
                                   JsonObject result) throws SQLException {

        // Racha actual (ya es un valor "en vivo", calculado por v_user_stats)
        String streakSql = "SELECT streak_current FROM v_user_stats WHERE user_id = ?::uuid";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(streakSql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            int streak = rs.next() ? rs.getInt("streak_current") : 0;
            result.addProperty("streak", streak + (streak == 1 ? " día" : " días"));
        }

        // XP y monedas ganadas esta semana: suma de lo pagado por cada evento
        // (intentos, duelos y misiones) desde el lunes. Antes se restaban dos
        // fotos del XP guardadas en leaderboard_history; ya no hace falta. El
        // saldo migrado (balance_adjustments) no es algo "ganado" esta semana.
        String sql = """
            SELECT COALESCE(SUM(xp), 0)                         AS xp_earned,
                   COALESCE(SUM(GREATEST(coins, 0)), 0)         AS coins_earned,
                   COUNT(*)                                     AS events
            FROM v_xp_events
            WHERE user_id = ?::uuid AND source <> 'adjustment'
              AND occurred_at >= ?::date
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, weekStart.toString());
            ResultSet rs = stmt.executeQuery();
            rs.next();
            result.addProperty("xp", rs.getInt("events") > 0
                    ? "+" + rs.getInt("xp_earned") + " XP"
                    : "Aún sin datos de esta semana");
            result.addProperty("coins", "+" + rs.getInt("coins_earned") + " monedas");
        }
    }

    // ─── Sugerencia para la próxima semana ──────────────────────────────────
    private void fillSuggestion(JsonObject result) {
        String weak = result.has("weakSubject") ? result.get("weakSubject").getAsString() : null;
        if (weak == null || weak.startsWith("Sin datos") || weak.startsWith("Aún")) {
            result.addProperty("suggestionTitle", "Sigue chateando esta semana");
            result.addProperty("suggestionText",
                "Cuantas más dudas resuelvas, más preciso será tu resumen la próxima semana.");
        } else {
            result.addProperty("suggestionTitle", "Repasa " + weak + " esta semana");
            result.addProperty("suggestionText",
                "Fue el tema donde tuviste el promedio más bajo. Unos minutos de repaso pueden ayudarte bastante.");
        }
    }
}