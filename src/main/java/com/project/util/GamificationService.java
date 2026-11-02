package com.project.util;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.dao.implementation.GamificationDAOImpl;
import com.project.dao.interfaces.IGamificationDAO;
import com.project.database.DatabaseConnection;

/**
 * Servicio central de gamificación de Polaris.
 *
 * Desde la V7 nada se guarda como contador: XP, monedas, nivel, racha y
 * progreso de misiones se CALCULAN en la base (v_user_stats,
 * v_user_mission_progress) a partir de los eventos. Este servicio decide
 * cuánto se paga por cada evento y lo guarda en el evento mismo:
 *   · intento sobre un contenido  → attempts.xp_awarded / coins_awarded
 *   · resultado de un duelo       → duel_participants.xp_awarded / coins_awarded
 *   · misión completada           → user_missions.xp_awarded / coins_awarded
 * Los niveles salen de la tabla levels.
 *
 * ═══════════════════════════════════════════════════════════
 *  RECOMPENSAS BASE (calibrado para tienda 300-1500 coins)
 * ═══════════════════════════════════════════════════════════
 *  Estudiar flashcards: +20 XP, +15 coins  (80%+ → ×1.3)
 *  Leer resumen:        +10 XP, +5 coins
 *  Quiz completado:     +30 XP, +20 coins  (80%+ → ×1.3, 90%+ → ×1.5)
 *  Examen experto:      +10..75 XP según puntaje
 *  Abandonar examen:    −25 XP
 *  Duelo ganado:        +40 XP, +40 coins
 *  Duelo perdido:       −10 XP, +8 coins
 *  Duelo empate:        +20 XP, +15 coins
 *  Generar contenido:   0 (se premia aprender, no generar)
 *
 *  Multiplicador de racha (solo sobre lo positivo):
 *  1-2 días ×1.0 · 3-6 días ×1.25 · 7-13 días ×1.5 · 14+ días ×1.75
 *
 *  El XP nunca baja del mínimo del nivel actual: no se pierde nivel.
 */
public class GamificationService {

    private static final IGamificationDAO dao = new GamificationDAOImpl();

    // Costa Rica no tiene horario de verano: el corte de día es la medianoche local.
    private static final ZoneId APP_ZONE = ZoneId.of("America/Costa_Rica");

    // ═════════════════════════════════════════════════════════════════════════
    // PUNTOS DE ENTRADA
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Actividad SIN calificación del servidor: leer un resumen, abandonar un
     * examen o generar. Si trae contentId, queda registrada como intento.
     * Los duelos usan processDuelOutcome.
     */
    public static JsonObject processActivity(String userId, String activityType,
                                              double scorePercent, String contentId,
                                              int timeTakenSecs, double maxScore)
                                              throws Exception {
        return inTransaction(conn -> {
            dao.lockUser(conn, userId);
            String attemptId = null;
            boolean alreadyRewarded = false;

            if (contentId != null && !contentId.isEmpty()) {
                // [SEGURIDAD] No se vuelve a pagar por el mismo contenido. Un
                // abandono cuenta como "ya hubo intento" solo para otro abandono:
                // abandonar no quita la recompensa de terminar el examen después.
                boolean abandon = "abandon_exam".equals(activityType);
                alreadyRewarded = dao.hasPreviousAttempt(conn, userId, contentId, !abandon);
                attemptId = dao.createAttempt(conn, userId, contentId,
                        abandon ? "abandoned" : "completed", timeTakenSecs);
            }

            final String attempt = attemptId;
            JsonObject r = applyRewards(conn, userId, activityType, scorePercent, alreadyRewarded,
                    attempt == null ? null : (xp, coins) -> dao.setAttemptRewards(conn, attempt, xp, coins));
            if (attempt != null) r.addProperty("attemptId", attempt);
            return r;
        });
    }

    /**
     * Actividad CALIFICADA POR EL SERVIDOR: quiz, examen experto o sesión de
     * flashcards. El cliente manda qué respondió (answers) y el servidor
     * calcula el puntaje contra item_options. Se guarda el intento con todas
     * sus respuestas, aunque ya se haya pagado antes.
     *
     * @param answers quiz/examen: [{ "position": 0, "option": 2 }] ("option": null = sin responder)
     *                flashcards:  [{ "position": 0, "correct": true }]
     */
    public static JsonObject processGradedActivity(String userId, String activityType,
                                                   String contentId, int timeTakenSecs,
                                                   JsonArray answers) throws Exception {
        return inTransaction(conn -> {
            dao.lockUser(conn, userId);
            boolean alreadyRewarded = dao.hasPreviousAttempt(conn, userId, contentId, true);
            String attemptId = dao.createAttempt(conn, userId, contentId, "completed", timeTakenSecs);
            int[] graded = dao.saveGradedAnswers(conn, attemptId, contentId, answers,
                                                 "flashcards".equals(activityType));
            int correct = graded[0], total = graded[1];
            double scorePercent = total > 0 ? (correct * 100.0 / total) : 0;

            JsonObject r = applyRewards(conn, userId, activityType, scorePercent, alreadyRewarded,
                    (xp, coins) -> dao.setAttemptRewards(conn, attemptId, xp, coins));
            r.addProperty("attemptId",    attemptId);
            r.addProperty("correctItems", correct);
            r.addProperty("totalItems",   total);
            return r;
        });
    }

    /**
     * Recompensa de un jugador al terminar un duelo. Lo pagado queda en su fila
     * de duel_participants. El intento del duelo (sus respuestas) ya lo guardó
     * IDuelDAO al registrar la partida.
     * @param activityType "duelo_ganado" | "duelo_perdido" | "duelo_empate"
     */
    public static JsonObject processDuelOutcome(String duelId, String userId,
                                                String activityType, double scorePercent)
                                                throws Exception {
        return inTransaction(conn -> {
            dao.lockUser(conn, userId);
            return applyRewards(conn, userId, activityType, scorePercent, false,
                    (xp, coins) -> dao.setDuelRewards(conn, duelId, userId, xp, coins));
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NÚCLEO
    // ═════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface TxWork { JsonObject run(Connection conn) throws Exception; }

    @FunctionalInterface
    private interface RewardSink { void store(int xp, int coins) throws Exception; }

    /**
     * Todo en UNA transacción: el evento, lo pagado y las misiones se guardan
     * juntos o no se guarda nada. La fila del usuario queda bloqueada hasta el
     * commit, así que dos actividades simultáneas no se pisan.
     */
    private static JsonObject inTransaction(TxWork work) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                JsonObject result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Calcula y guarda lo que se paga por un evento ya registrado, completa las
     * misiones que llegaron a su meta y arma la respuesta para el front.
     * @param sink dónde se guarda lo pagado; null si el evento no se registra
     *             (por ejemplo "generar" sin contenido, que no paga nada)
     */
    private static JsonObject applyRewards(Connection conn, String userId, String activityType,
                                           double scorePercent, boolean alreadyRewarded,
                                           RewardSink sink) throws Exception {
        LocalDate today     = today();
        LocalDate weekStart = currentWeekStart();
        int[] levels = levelThresholds();

        // 1. Si ayer no estudió pero anteayer sí, un protector cubre el día de ayer.
        boolean shieldUsed = sink != null && dao.useStreakShieldIfNeeded(conn, userId, today);

        // 2. Stats con el evento de hoy ya incluido (la racha ya cuenta hoy).
        JsonObject before = dao.getStats(conn, userId);
        if (before == null) throw new Exception("Usuario no encontrado: " + userId);
        int xpBefore    = before.get("xp").getAsInt();
        int levelBefore = before.get("level").getAsInt();
        int streak      = before.get("streakCurrent").getAsInt();

        // 3. Recompensa: base × racha. 0 si ya se pagó por este contenido.
        RewardResult base = (alreadyRewarded || sink == null)
                ? new RewardResult(0, 0) : getBaseReward(activityType, scorePercent);
        double multiplier = getStreakMultiplier(streak);
        int xpEarned    = base.xp >= 0 ? (int) Math.round(base.xp * multiplier) : base.xp;
        int coinsEarned = base.coins >= 0 ? (int) Math.round(base.coins * multiplier) : 0;

        // No se pierde nivel: lo negativo nunca baja del mínimo del nivel actual.
        if (xpEarned < 0) {
            int floor = levels[Math.max(0, Math.min(levelBefore, levels.length) - 1)];
            xpEarned = Math.max(xpEarned, floor - xpBefore);
        }
        if (sink != null) sink.store(xpEarned, coinsEarned);

        // 4. Misiones y objetivos: asignar los del período y cerrar los cumplidos.
        dao.ensureMissions(conn, userId, today, weekStart);
        JsonObject missions = dao.completeReachedMissions(conn, userId, today, weekStart);

        // 5. Stats finales (ya con lo pagado y las misiones).
        JsonObject after = dao.getStats(conn, userId);
        int newXp    = after.get("xp").getAsInt();
        int newLevel = after.get("level").getAsInt();

        JsonObject r = new JsonObject();
        r.addProperty("success",          true);
        r.addProperty("alreadyRewarded",  alreadyRewarded);
        r.addProperty("activityType",     activityType);
        r.addProperty("xpEarned",         xpEarned);
        r.addProperty("coinsEarned",      coinsEarned);
        r.addProperty("streakMultiplier", multiplier);
        r.addProperty("scorePercent",     scorePercent);
        r.addProperty("newXp",            newXp);
        r.addProperty("newLevel",         newLevel);
        r.addProperty("newCoins",         after.get("coins").getAsInt());
        r.addProperty("newStreak",        after.get("streakCurrent").getAsInt());
        r.addProperty("streakRecord",     after.get("streakRecord").getAsInt());
        r.addProperty("shieldUsed",       shieldUsed);
        r.addProperty("hasStreakShield",  after.get("hasStreakShield").getAsBoolean());
        r.addProperty("leveledUp",        newLevel > levelBefore);
        r.addProperty("oldLevel",         levelBefore);
        addNextLevel(r, newXp, newLevel, levels);

        r.addProperty("bonusMissionXp",      missions.get("missionXp").getAsInt());
        r.addProperty("bonusMissionCoins",   missions.get("missionCoins").getAsInt());
        r.addProperty("bonusObjectiveXp",    missions.get("objectiveXp").getAsInt());
        r.addProperty("bonusObjectiveCoins", missions.get("objectiveCoins").getAsInt());
        r.add("completedMissions",   missions.get("completedMissions"));
        r.add("completedObjectives", missions.get("completedObjectives"));
        return r;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REGLAS
    // ═════════════════════════════════════════════════════════════════════════

    private static RewardResult getBaseReward(String activityType, double scorePercent) {
        double perfMult = 1.0;
        if (scorePercent >= 90) perfMult = 1.5;
        else if (scorePercent >= 80) perfMult = 1.3;

        return switch (activityType) {
            case "generar"  -> new RewardResult(0, 0);
            case "resumen"  -> new RewardResult(10, 5);

            case "flashcards" -> {
                double fm = scorePercent >= 80 ? 1.3 : 1.0;
                yield new RewardResult((int) Math.round(20 * fm), (int) Math.round(15 * fm));
            }

            case "quiz" -> new RewardResult(
                (int) Math.round(30 * perfMult), (int) Math.round(20 * perfMult));

            case "expert_exam" -> {
                // Mín 10 XP por intentar; sube hasta 30 al 50%; 50 base desde ahí (×1.3 / ×1.5).
                if (scorePercent < 50.0) {
                    yield new RewardResult(
                        Math.max(10, (int) Math.round(10 + (scorePercent / 50.0) * 20)),
                        Math.max(5,  (int) Math.round(5  + (scorePercent / 50.0) * 15)));
                }
                yield new RewardResult((int) Math.round(50 * perfMult), (int) Math.round(35 * perfMult));
            }

            case "abandon_exam"  -> new RewardResult(-25, 0);
            case "duelo_ganado"  -> new RewardResult(40, 40);
            case "duelo_perdido" -> new RewardResult(-10, 8);
            case "duelo_empate"  -> new RewardResult(20, 15);

            default -> new RewardResult(5, 3);
        };
    }

    private static double getStreakMultiplier(int streak) {
        if (streak >= 14) return 1.75;
        if (streak >= 7)  return 1.5;
        if (streak >= 3)  return 1.25;
        return 1.0;
    }

    private record RewardResult(int xp, int coins) {}

    // ═════════════════════════════════════════════════════════════════════════
    // UTILIDADES PÚBLICAS
    // ═════════════════════════════════════════════════════════════════════════

    /** Stats para /api/gamification/stats, con el progreso hacia el siguiente nivel. */
    public static JsonObject getPlayerStats(String userId) throws Exception {
        JsonObject stats = dao.getStats(userId);
        if (stats == null) return null;
        addNextLevel(stats, stats.get("xp").getAsInt(), stats.get("level").getAsInt(), levelThresholds());
        stats.addProperty("streakMultiplier", getStreakMultiplier(stats.get("streakCurrent").getAsInt()));
        return stats;
    }

    private static void addNextLevel(JsonObject o, int xp, int level, int[] levels) {
        int max = levels.length;
        o.addProperty("maxLevel", max);
        if (level < max) {
            int next = levels[level], prev = levels[level - 1];
            o.addProperty("xpToNextLevel",  next - xp);
            o.addProperty("xpNextLevel",    next);
            o.addProperty("xpCurrentLevel", prev);
            o.addProperty("levelProgress",  Math.round(((double) (xp - prev) / (next - prev)) * 100));
        } else {
            o.addProperty("xpToNextLevel",  0);
            o.addProperty("xpNextLevel",    levels[max - 1]);
            o.addProperty("xpCurrentLevel", levels[max - 1]);
            o.addProperty("levelProgress",  100);
        }
    }

    // Los niveles cambian muy rara vez: se leen una vez de la tabla levels.
    private static volatile int[] levelCache;

    private static int[] levelThresholds() throws Exception {
        int[] l = levelCache;
        if (l == null) {
            l = dao.getLevelThresholds();
            if (l.length == 0) throw new Exception("La tabla levels está vacía.");
            levelCache = l;
        }
        return l;
    }

    /**
     * "Hoy" en la zona de la app. Las consultas de misiones y objetivos deben
     * recibir esta fecha como parámetro en vez de usar CURRENT_DATE: la BD
     * corre en UTC y el día cambiaría a las 6pm hora de Costa Rica.
     */
    public static LocalDate today() {
        return LocalDate.now(APP_ZONE);
    }

    /** Lunes de la semana actual en la zona de la app. */
    public static LocalDate currentWeekStart() {
        return today().with(java.time.DayOfWeek.MONDAY);
    }
}
