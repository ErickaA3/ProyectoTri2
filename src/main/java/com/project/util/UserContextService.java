package com.project.util;

import com.pgvector.PGvector;
import com.project.database.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * RAG real por búsqueda semántica (pgvector).
 *
 * En vez de mandar TODO el contenido al modelo:
 *   1. Siempre: perfil, stats, misiones, inventario, tienda (poco espacio)
 *   2. Siempre: lista compacta de TODOS los títulos (para que el modelo sepa qué existe)
 *   3. Búsqueda: trae el JSONB completo SOLO de contenidos semánticamente
 *      similares a la pregunta, usando el embedding.
 */
public class UserContextService {

    private static final int MAX_RELEVANT_ITEMS = 4;

    public static String buildContext(UUID userId, String userMessage) {
        StringBuilder ctx = new StringBuilder();

        try (Connection conn = DatabaseConnection.getConnection()) {

            appendUserInfo(conn, userId, ctx);
            appendStats(conn, userId, ctx);

            appendAllTitles(conn, userId, ctx);
            appendRelevantContent(conn, userId, userMessage, ctx);

            appendDailyMissions(conn, userId, ctx);
            appendWeeklyObjectives(conn, userId, ctx);
            appendInventory(conn, userId, ctx);
            appendActivityResults(conn, userId, ctx);
            appendStoreItems(conn, ctx);

        } catch (Exception e) {
            System.err.println("[UserContextService] Error general: " + e.getMessage());
        }

        System.out.println("[RAG] Contexto: " + ctx.length() + " chars (búsqueda: \""
            + truncate(userMessage, 50) + "\")");
        return ctx.toString();
    }

    public static String buildContext(UUID userId) {
        return buildContext(userId, "");
    }

    // ── Perfil ────────────────────────────────────────────────────────────

    private static void appendUserInfo(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = "SELECT username, full_name, country, created_at FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ctx.append("PERFIL: ").append(rs.getString("username"));
                String fn = rs.getString("full_name");
                if (fn != null && !fn.isBlank()) ctx.append(" (").append(fn).append(")");
                String c = rs.getString("country");
                if (c != null && !c.isBlank()) ctx.append(", ").append(c);
                ctx.append("\n\n");
            }
        } catch (SQLException e) { logErr("userInfo", e); }
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    private static void appendStats(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = "SELECT xp, level, coins, streak_current, streak_record, has_streak_shield FROM user_stats WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ctx.append("STATS: XP=").append(rs.getInt("xp"));
                ctx.append(" Nivel=").append(rs.getInt("level"));
                ctx.append(" Monedas=").append(rs.getInt("coins"));
                ctx.append(" Racha=").append(rs.getInt("streak_current")).append("d");
                ctx.append(" Récord=").append(rs.getInt("streak_record")).append("d");
                ctx.append(" Escudo=").append(rs.getBoolean("has_streak_shield") ? "sí" : "no");
                ctx.append("\n\n");
            }
        } catch (SQLException e) { logErr("stats", e); }
    }

    // ── Lista compacta de TODOS los títulos ───────────────────────────────

    private static void appendAllTitles(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = """
            SELECT type, title, is_favorite
            FROM study_content
            WHERE user_id = ?
            ORDER BY created_at DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();

            int total = 0;
            int flashcards = 0, schemas = 0, summaries = 0, quizzes = 0;
            StringBuilder titles = new StringBuilder();

            while (rs.next()) {
                total++;
                String type  = rs.getString("type");
                String title = rs.getString("title");
                boolean fav  = rs.getBoolean("is_favorite");

                switch (type) {
                    case "flashcard" -> flashcards++;
                    case "schema"    -> schemas++;
                    case "summary"   -> summaries++;
                    case "quiz"      -> quizzes++;
                }

                titles.append("  - [").append(mapType(type)).append("] \"");
                titles.append(title != null ? title : "Sin título").append("\"");
                if (fav) titles.append(" ★");
                titles.append("\n");
            }

            if (total > 0) {
                ctx.append("CONTENIDO GENERADO (").append(total).append(" en total):\n");
                ctx.append("  Flashcards: ").append(flashcards);
                ctx.append(", Esquemas: ").append(schemas);
                ctx.append(", Resúmenes: ").append(summaries);
                ctx.append(", Quizzes: ").append(quizzes).append("\n");
                ctx.append("  Lista completa de temas:\n");
                ctx.append(titles);
                ctx.append("\n");
            } else {
                ctx.append("CONTENIDO GENERADO: Ninguno.\n\n");
            }

        } catch (SQLException e) { logErr("allTitles", e); }
    }

    // ── Búsqueda de contenido RELEVANTE — AHORA POR SIMILITUD VECTORIAL ────

    private static void appendRelevantContent(Connection conn, UUID userId,
                                               String userMessage, StringBuilder ctx) {
        if (userMessage == null || userMessage.isBlank()) {
            appendRecentContent(conn, userId, ctx, 3);
            return;
        }

        // 1. Generar el embedding de la pregunta del usuario
        float[] queryEmbedding;
        try {
            queryEmbedding = AIService.generateEmbedding(userMessage);
        } catch (Exception e) {
            System.err.println("[RAG] Error generando embedding de búsqueda: " + e.getMessage());
            appendRecentContent(conn, userId, ctx, 3);
            return;
        }

        // 2. Buscar los contenidos más parecidos por distancia coseno (<=>)
        //    Solo entre filas que YA tienen embedding (embedding IS NOT NULL)
        String sql = """
            SELECT id, type, title, content::text, is_favorite
            FROM study_content
            WHERE user_id = ? AND embedding IS NOT NULL
            ORDER BY embedding <=> ?
            LIMIT ?
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, new PGvector(queryEmbedding));
            ps.setInt(3, MAX_RELEVANT_ITEMS);

            ResultSet rs = ps.executeQuery();
            boolean hasResults = false;

            while (rs.next()) {
                if (!hasResults) {
                    ctx.append("CONTENIDO RELEVANTE A TU PREGUNTA (detalle completo):\n");
                    hasResults = true;
                }

                String id      = rs.getString("id");
                String type    = rs.getString("type");
                String title   = rs.getString("title");
                String content = rs.getString("content");
                boolean fav    = rs.getBoolean("is_favorite");

                ctx.append("\n── ").append(mapType(type)).append(": \"");
                ctx.append(title != null ? title : "Sin título").append("\"");
                if (fav) ctx.append(" ★FAV");
                ctx.append(" ──\n");

                if (content != null && !content.isBlank()) {
                    appendFullContent(ctx, type, content, id);
                }
            }

            if (!hasResults) {
                ctx.append("NOTA: No se encontró contenido que matchee con la pregunta del usuario.\n");
                ctx.append("El estudiante tiene contenido pero sobre otros temas (ver lista arriba).\n\n");
            } else {
                ctx.append("\n");
            }

        } catch (SQLException e) {
            logErr("relevantContent", e);
            appendRecentContent(conn, userId, ctx, 3);
        }
    }

    /** Fallback: traer los N más recientes con detalle */
    private static void appendRecentContent(Connection conn, UUID userId, StringBuilder ctx, int limit) {
        String sql = """
            SELECT id, type, title, content::text, is_favorite
            FROM study_content WHERE user_id = ?
            ORDER BY created_at DESC LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            boolean has = false;

            while (rs.next()) {
                if (!has) {
                    ctx.append("CONTENIDO RECIENTE (detalle):\n");
                    has = true;
                }
                String id      = rs.getString("id");
                String type    = rs.getString("type");
                String title   = rs.getString("title");
                String content = rs.getString("content");

                ctx.append("\n── ").append(mapType(type)).append(": \"");
                ctx.append(title != null ? title : "Sin título").append("\" ──\n");

                if (content != null && !content.isBlank())
                    appendFullContent(ctx, type, content, id);
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("recentContent", e); }
    }

    // ── Volcado completo del JSONB ────────────────────────────────────────

    private static void appendFullContent(StringBuilder ctx, String type, String jsonContent, String id) {
        try {
            var element = com.google.gson.JsonParser.parseString(jsonContent);
            if (!element.isJsonObject()) {
                ctx.append("  ").append(truncate(element.isJsonPrimitive() ? element.getAsString() : jsonContent, 500)).append("\n");
                return;
            }
            var obj = element.getAsJsonObject();

            switch (type) {
                case "flashcard" -> {
                    if (obj.has("cards") && obj.get("cards").isJsonArray()) {
                        var cards = obj.getAsJsonArray("cards");
                        ctx.append("  ").append(cards.size()).append(" tarjetas:\n");
                        for (int i = 0; i < cards.size(); i++) {
                            try {
                                var c = cards.get(i).getAsJsonObject();
                                ctx.append("  ").append(i + 1).append(". P: ").append(safeGet(c, "front")).append("\n");
                                ctx.append("     R: ").append(safeGet(c, "back")).append("\n");
                            } catch (Exception e) { }
                        }
                    }
                }
                case "schema" -> {
                    if (obj.has("rootNode") && obj.get("rootNode").isJsonObject()) {
                        appendSchemaNode(ctx, obj.getAsJsonObject("rootNode"), 0);
                    }
                    if (obj.has("schemaType"))
                        ctx.append("  Tipo de esquema: ").append(obj.get("schemaType").getAsString()).append("\n");
                }
                case "quiz" -> {
                    if (obj.has("questions") && obj.get("questions").isJsonArray()) {
                        var qs = obj.getAsJsonArray("questions");
                        ctx.append("  ").append(qs.size()).append(" preguntas:\n");
                        for (int i = 0; i < qs.size(); i++) {
                            try {
                                var q = qs.get(i).getAsJsonObject();
                                ctx.append("  ").append(i + 1).append(". ").append(safeGet(q, "question")).append("\n");
                                if (q.has("options") && q.get("options").isJsonArray()) {
                                    var opts = q.getAsJsonArray("options");
                                    int correct = q.has("correct") ? q.get("correct").getAsInt() : -1;
                                    for (int j = 0; j < opts.size(); j++) {
                                        ctx.append("     ").append((char)('A' + j)).append(") ");
                                        ctx.append(opts.get(j).getAsString());
                                        if (j == correct) ctx.append(" ✓");
                                        ctx.append("\n");
                                    }
                                }
                                String expl = safeGet(q, "explanation");
                                if (!expl.isBlank())
                                    ctx.append("     Explicación: ").append(expl).append("\n");
                            } catch (Exception e) { }
                        }
                    }
                }
                case "summary" -> {
                    if (obj.has("summaryText")) {
                        ctx.append("  ").append(obj.get("summaryText").getAsString()).append("\n");
                    } else if (obj.has("sections") && obj.get("sections").isJsonArray()) {
                        var secs = obj.getAsJsonArray("sections");
                        for (int i = 0; i < secs.size(); i++) {
                            try {
                                var s = secs.get(i).getAsJsonObject();
                                ctx.append("  ● ").append(safeGet(s, "heading")).append(":\n");
                                ctx.append("    ").append(safeGet(s, "body")).append("\n");
                            } catch (Exception e) { }
                        }
                    }
                    if (obj.has("keywords") && obj.get("keywords").isJsonArray()) {
                        ctx.append("  Palabras clave: ");
                        var kw = obj.getAsJsonArray("keywords");
                        for (int i = 0; i < kw.size(); i++) {
                            if (i > 0) ctx.append(", ");
                            try { ctx.append(kw.get(i).getAsString()); } catch (Exception e) { }
                        }
                        ctx.append("\n");
                    }
                }
                default -> ctx.append("  ").append(truncate(obj.toString(), 500)).append("\n");
            }
        } catch (Exception e) {
            System.err.println("[RAG] Error en contenido completo id=" + id + ": " + e.getMessage());
            ctx.append("  ").append(truncate(jsonContent, 300)).append("\n");
        }
    }

    private static void appendSchemaNode(StringBuilder ctx, com.google.gson.JsonObject node, int depth) {
        String indent = "  " + "  ".repeat(depth);
        ctx.append(indent).append("- ").append(safeGet(node, "label")).append("\n");
        if (node.has("children") && node.get("children").isJsonArray()) {
            var children = node.getAsJsonArray("children");
            for (int i = 0; i < children.size(); i++) {
                try {
                    appendSchemaNode(ctx, children.get(i).getAsJsonObject(), depth + 1);
                } catch (Exception e) { }
            }
        }
    }

    // ── Misiones diarias ──────────────────────────────────────────────────

    private static void appendDailyMissions(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = """
            SELECT m.description, udm.progress, m.required_count, udm.completed, m.xp_reward
            FROM user_daily_missions udm JOIN missions m ON m.id = udm.mission_id
            WHERE udm.user_id = ? AND udm.date = CURRENT_DATE
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            boolean has = false;
            while (rs.next()) {
                if (!has) { ctx.append("MISIONES DIARIAS:\n"); has = true; }
                ctx.append("  ").append(rs.getString("description"));
                ctx.append(" ").append(rs.getInt("progress")).append("/").append(rs.getInt("required_count"));
                ctx.append(rs.getBoolean("completed") ? " ✓" : "");
                ctx.append(" (").append(rs.getInt("xp_reward")).append("XP)\n");
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("dailyMissions", e); }
    }

    // ── Objetivos semanales ───────────────────────────────────────────────

    private static void appendWeeklyObjectives(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = """
            SELECT objective_description, progress, required_count, completed
            FROM user_weekly_objectives WHERE user_id = ?
            ORDER BY week_start DESC LIMIT 5
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            boolean has = false;
            while (rs.next()) {
                if (!has) { ctx.append("OBJETIVOS SEMANALES:\n"); has = true; }
                ctx.append("  ").append(rs.getString("objective_description"));
                ctx.append(" ").append(rs.getInt("progress")).append("/").append(rs.getInt("required_count"));
                ctx.append(rs.getBoolean("completed") ? " ✓" : "").append("\n");
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("weeklyObjectives", e); }
    }

    // ── Inventario ────────────────────────────────────────────────────────

    private static void appendInventory(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = "SELECT si.name, si.type, ui.is_equipped FROM user_inventory ui JOIN store_items si ON si.id = ui.item_id WHERE ui.user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            boolean has = false;
            while (rs.next()) {
                if (!has) { ctx.append("INVENTARIO:\n"); has = true; }
                ctx.append("  ").append(rs.getString("name")).append(" (").append(rs.getString("type")).append(")");
                if (rs.getBoolean("is_equipped")) ctx.append(" [equipado]");
                ctx.append("\n");
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("inventory", e); }
    }

    // ── Resultados ────────────────────────────────────────────────────────

    private static void appendActivityResults(Connection conn, UUID userId, StringBuilder ctx) {
        String sql = "SELECT ar.score, ar.max_score, sc.title FROM activity_results ar JOIN study_content sc ON sc.id = ar.content_id WHERE ar.user_id = ? ORDER BY ar.completed_at DESC LIMIT 10";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ResultSet rs = ps.executeQuery();
            boolean has = false;
            while (rs.next()) {
                if (!has) { ctx.append("RESULTADOS:\n"); has = true; }
                ctx.append("  \"").append(rs.getString("title")).append("\" ").append(rs.getInt("score")).append("/").append(rs.getInt("max_score")).append("\n");
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("activityResults", e); }
    }

    // ── Tienda ────────────────────────────────────────────────────────────

    private static void appendStoreItems(Connection conn, StringBuilder ctx) {
        String sql = "SELECT name, type, cost FROM store_items ORDER BY cost ASC LIMIT 15";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            boolean has = false;
            while (rs.next()) {
                if (!has) { ctx.append("TIENDA:\n"); has = true; }
                ctx.append("  ").append(rs.getString("name")).append(" (").append(rs.getString("type")).append(") ").append(rs.getInt("cost")).append(" monedas\n");
            }
            if (has) ctx.append("\n");
        } catch (SQLException e) { logErr("storeItems", e); }
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private static String mapType(String t) {
        return switch (t) { case "flashcard" -> "Flashcards"; case "schema" -> "Esquema";
            case "summary" -> "Resumen"; case "quiz" -> "Quiz"; default -> t; };
    }

    private static String safeGet(com.google.gson.JsonObject o, String f) {
        if (o == null || !o.has(f) || o.get(f).isJsonNull()) return "";
        try { return o.get(f).getAsString(); } catch (Exception e) { return o.get(f).toString(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", "");
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static void logErr(String section, SQLException e) {
        System.err.println("[UserContext] Error en " + section + ": " + e.getMessage());
    }
}