package com.project.util;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;

import com.google.gson.JsonObject;
import com.project.dao.implementation.GamificationDAOImpl;
import com.project.dao.interfaces.IGamificationDAO;
import com.project.database.DatabaseConnection;

/**
 * Servicio central de gamificación de Polaris / Mi ProfesorIA.
 *
 * ═══════════════════════════════════════════════════════════
 *  TABLA DE NIVELES (10 niveles, XP progresivo)
 * ═══════════════════════════════════════════════════════════
 *  Nv.1  →    0 XP  (marco default)
 *  Nv.2  →   80 XP  (Océano)
 *  Nv.3  →  200 XP  (Volcán)
 *  Nv.4  →  380 XP  (Sakura)
 *  Nv.5  →  640 XP  (Amazonas)
 *  Nv.6  → 1000 XP  (Neón)
 *  Nv.7  → 1480 XP  (Espectro RGB)
 *  Nv.8  → 2100 XP  (Dragón)
 *  Nv.9  → 2900 XP  (Galaxia)
 *  Nv.10 → 3900 XP  (Luxury Dorado) — MAX
 *
 * ═══════════════════════════════════════════════════════════
 *  RECOMPENSAS BASE (calibrado para tienda 300-1500 coins)
 * ═══════════════════════════════════════════════════════════
 *  Generar contenido:   +15 XP, +10 coins
 *  Estudiar flashcards: +20 XP, +15 coins  (80%+ → ×1.3)
 *  Ver resumen:         +10 XP, +5 coins
 *  Quiz completado:     +30 XP, +20 coins  (80%+ → ×1.3, 90%+ → ×1.5)
 *  Examen experto:      +50 XP, +35 coins  (80%+ → ×1.3, 90%+ → ×1.5)
 *  Abandonar examen:    −25 XP
 *  Duelo ganado:        +40 XP, +40 coins
 *  Duelo perdido:       −10 XP, +8 coins
 *  Duelo empate:        +20 XP, +15 coins
 *
 *  ~50 coins/día base → ~70 con misiones → ~100 con racha ×1.5
 *  Streak Shield (300)  → ~4 días
 *  Avatar barato (400)  → ~6 días
 *  Avatar caro (1500)   → ~3 semanas con racha
 *
 * ═══════════════════════════════════════════════════════════
 *  MULTIPLICADOR DE RACHA
 * ═══════════════════════════════════════════════════════════
 *  1-2 días:  ×1.0
 *  3-6 días:  ×1.25
 *  7-13 días: ×1.5
 *  14+ días:  ×1.75
 */
public class GamificationService {

    private static final IGamificationDAO dao = new GamificationDAOImpl();

    // [SEGURIDAD/BUG] LocalDate.now() sin zona usa la zona horaria del
    // servidor (en Railway, UTC), no la del usuario. Con Costa Rica en
    // UTC-6, eso hacía que el "día" cambiara a las 6pm hora local en vez
    // de medianoche — la racha y las misiones/objetivos se sentían raras
    // porque el corte de día no coincidía con el día real del usuario.
    // Costa Rica no tiene horario de verano, así que esta zona es estable.
    private static final ZoneId APP_ZONE = ZoneId.of("America/Costa_Rica");

    private static final int[] LEVEL_THRESHOLDS = {
        0, 80, 200, 380, 640, 1000, 1480, 2100, 2900, 3900
    };

    private static final int MAX_LEVEL = 10;

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO PRINCIPAL
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Procesa una actividad completa en UNA transacción: stats, resultado,
     * misiones y objetivos se guardan juntos o no se guarda nada.
     *
     * [BUG] Antes cada paso usaba su propia conexión: si algo fallaba a la
     * mitad quedaban datos a medias, y dos actividades simultáneas del mismo
     * usuario leían el mismo XP/monedas y la segunda pisaba a la primera.
     * Ahora la fila de user_stats se bloquea (FOR UPDATE) al principio, así
     * que las actividades de un mismo usuario se procesan de a una.
     */
    /**
     * Actividad SIN calificación del servidor: leer un resumen, abandonar un
     * examen, generar, o el resultado de un duelo (sin contentId). El
     * scorePercent viene de quien llama.
     * Si trae contentId, queda registrada como intento (attempts).
     */
    public static JsonObject processActivity(String userId, String activityType,
                                              double scorePercent, String contentId,
                                              int timeTakenSecs, double maxScore)
                                              throws Exception {
        return inTransaction(conn -> {
            JsonObject stats = lockStats(conn, userId);
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

            JsonObject r = applyRewards(conn, stats, userId, activityType, scorePercent,
                                        attemptId, alreadyRewarded);
            if (attemptId != null) r.addProperty("attemptId", attemptId);
            return r;
        });
    }

    /**
     * Actividad CALIFICADA POR EL SERVIDOR: quiz, examen experto o sesión de
     * flashcards. El cliente manda qué respondió en cada pregunta o tarjeta
     * (answers) y el servidor calcula el puntaje contra item_options. Se guarda
     * el intento con todas sus respuestas, aunque ya se haya pagado antes.
     *
     * [SEGURIDAD] Antes el porcentaje lo mandaba el navegador y el servidor se
     * lo creía.
     *
     * @param answers Para quiz/examen: [{ "position": 0, "option": 2 }, ...]
     *                ("option": null = no respondió). Para flashcards:
     *                [{ "position": 0, "correct": true }, ...].
     */
    public static JsonObject processGradedActivity(String userId, String activityType,
                                                   String contentId, int timeTakenSecs,
                                                   com.google.gson.JsonArray answers)
                                                   throws Exception {
        return inTransaction(conn -> {
            JsonObject stats = lockStats(conn, userId);
            boolean alreadyRewarded = dao.hasPreviousAttempt(conn, userId, contentId, true);
            String attemptId = dao.createAttempt(conn, userId, contentId, "completed", timeTakenSecs);
            int[] graded = dao.saveGradedAnswers(conn, attemptId, contentId, answers,
                                                 "flashcards".equals(activityType));
            int correct = graded[0], total = graded[1];
            double scorePercent = total > 0 ? (correct * 100.0 / total) : 0;

            JsonObject r = applyRewards(conn, stats, userId, activityType, scorePercent,
                                        attemptId, alreadyRewarded);
            r.addProperty("attemptId",    attemptId);
            r.addProperty("correctItems", correct);
            r.addProperty("totalItems",   total);
            return r;
        });
    }

    @FunctionalInterface
    private interface TxWork { JsonObject run(Connection conn) throws Exception; }

    /**
     * Todo en UNA transacción: intento, respuestas, stats, misiones y objetivos
     * se guardan juntos o no se guarda nada.
     *
     * [BUG] Antes cada paso usaba su propia conexión: si algo fallaba a la
     * mitad quedaban datos a medias, y dos actividades simultáneas del mismo
     * usuario leían el mismo XP/monedas y la segunda pisaba a la primera.
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
     * Lee los stats bloqueando la fila de user_stats (FOR UPDATE) hasta el
     * commit: las actividades de un mismo usuario se procesan de a una.
     */
    private static JsonObject lockStats(Connection conn, String userId) throws Exception {
        JsonObject stats = dao.getStatsForUpdate(conn, userId);
        if (stats == null) {
            throw new Exception("Usuario no encontrado en user_stats: " + userId);
        }
        return stats;
    }

    private static JsonObject applyRewards(Connection conn, JsonObject stats, String userId,
                                           String activityType, double scorePercent,
                                           String attemptId, boolean alreadyRewarded)
                                           throws Exception {

        int    currentXp       = stats.has("xp") && !stats.get("xp").isJsonNull() ? stats.get("xp").getAsInt() : 0;
        int    currentLevel    = stats.has("level") && !stats.get("level").isJsonNull() ? stats.get("level").getAsInt() : 1;
        int    currentCoins    = stats.has("coins") && !stats.get("coins").isJsonNull() ? stats.get("coins").getAsInt() : 0;
        int    streakCurrent   = stats.has("streakCurrent") && !stats.get("streakCurrent").isJsonNull() ? stats.get("streakCurrent").getAsInt() : 0;
        int    streakRecord    = stats.has("streakRecord") && !stats.get("streakRecord").isJsonNull() ? stats.get("streakRecord").getAsInt() : 0;
        String lastActivityStr = stats.has("streakLastActivity") && !stats.get("streakLastActivity").isJsonNull() ? stats.get("streakLastActivity").getAsString() : null;
        boolean hasShield      = stats.has("hasStreakShield") && !stats.get("hasStreakShield").isJsonNull() && stats.get("hasStreakShield").getAsBoolean();

        // 2. Racha
        LocalDate today        = LocalDate.now(APP_ZONE);
        LocalDate lastActivity = (lastActivityStr != null && !lastActivityStr.isEmpty()
                                   && !"null".equals(lastActivityStr))
                                 ? LocalDate.parse(lastActivityStr)
                                 : today.minusDays(2);

        StreakResult streak = calculateStreak(streakCurrent, lastActivity, today, hasShield);

        // 3. Rewards base — 0 si ya fue recompensado (alreadyRewarded lo decide
        //    quien llama, según los intentos anteriores del mismo contenido)
        RewardResult baseReward = alreadyRewarded
            ? new RewardResult(0, 0)
            : getBaseReward(activityType, scorePercent);

        // 4. Multiplicador (solo positivos)
        double multiplier = getStreakMultiplier(streak.newStreak);
        int xpEarned, coinsEarned;

        if (baseReward.xp >= 0) {
            xpEarned    = (int) Math.round(baseReward.xp * multiplier);
            coinsEarned = (int) Math.round(baseReward.coins * multiplier);
        } else {
            xpEarned    = baseReward.xp;
            coinsEarned = baseReward.coins;
        }

        // 5. Nuevos totales
        int newXp    = Math.max(0, currentXp + xpEarned);
        int newCoins = Math.max(0, currentCoins + coinsEarned);

        // No pierde nivel
        int currentLevelThreshold = LEVEL_THRESHOLDS[currentLevel - 1];
        if (newXp < currentLevelThreshold) {
            newXp = currentLevelThreshold;
        }

        // 6. Nuevo nivel
        int newLevel = calculateLevel(newXp);
        boolean leveledUp = newLevel > currentLevel;

        // 7. Récord
        int newStreakRecord = Math.max(streakRecord, streak.newStreak);

        // 8. Guardar
        dao.updateStats(conn, userId, newXp, newLevel, newCoins,
            streak.newStreak, newStreakRecord,
            today.toString(), streak.shieldUsed ? false : hasShield);

        // 9. Activity result ya fue procesado en el paso 2.5

        // ══════════════════════════════════════════════════════════════════════
        // 10. MISIONES DIARIAS — tipos alineados con missions.type en la DB
        //     DB types: complete_flashcard, complete_quiz, complete_summary,
        //               complete_expert_exam, win_duel, maintain_streak
        // ══════════════════════════════════════════════════════════════════════
        String missionType = mapToMissionType(activityType);
        if (missionType != null && !alreadyRewarded) {
            dao.advanceDailyMissions(conn, userId, missionType, today);
        }

        // Si la racha subió, avanzar misiones de tipo maintain_streak
        if (streak.newStreak > streakCurrent) {
            dao.advanceDailyMissions(conn, userId, "maintain_streak", today);
        }

        // ══════════════════════════════════════════════════════════════════════
        // 11. OBJETIVOS SEMANALES — tipos alineados con user_weekly_objectives.type
        //     Types: streak_days, activities, duels_won, perfect_exam,
        //            complete_flashcard, complete_quiz, complete_summary, win_duel
        // ══════════════════════════════════════════════════════════════════════
        LocalDate weekStart = today.with(java.time.DayOfWeek.MONDAY);
        String objectiveType = mapToObjectiveType(activityType, scorePercent);
        if (objectiveType != null) {
            dao.advanceWeeklyObjectives(conn, userId, objectiveType, weekStart);
            // [BUG] El pool de objetivos semanales (UserDAOImpl.ensureWeeklyObjectives)
            // tiene DOS entradas distintas para "ganar duelos": type="duels_won"
            // ("Ganar 2 duelos") y type="win_duel" ("Ganar 3 duelos"). Acá solo se
            // generaba "win_duel" al ganar un duelo, así que a cualquier usuario
            // al que le tocara el objetivo "duels_won" esa semana, nunca se le
            // avanzaba — se quedaba pegado en 0/2 sin importar cuántos duelos
            // ganara. Se avanzan ambos tipos para que ninguno quede huérfano.
            if ("win_duel".equals(objectiveType)) {
                dao.advanceWeeklyObjectives(conn, userId, "duels_won", weekStart);
            }
        }

        // "activities" cuenta CUALQUIER actividad positiva (no abandon)
        if (!"abandon_exam".equals(activityType)) {
            dao.advanceWeeklyObjectives(conn, userId, "activities", weekStart);
        }

        // Si la racha subió, avanzar objetivo de streak_days
        if (streak.newStreak > streakCurrent) {
            dao.advanceWeeklyObjectives(conn, userId, "streak_days", weekStart);
        }

        // 12. Check completados
        JsonObject missionRewards   = dao.checkCompletedMissions(conn, userId, today);
        JsonObject objectiveRewards = dao.checkCompletedObjectives(conn, userId, weekStart);

        int bonusXp    = missionRewards.get("missionXp").getAsInt()
                       + objectiveRewards.get("objectiveXp").getAsInt();
        int bonusCoins = missionRewards.get("missionCoins").getAsInt()
                       + objectiveRewards.get("objectiveCoins").getAsInt();

        if (bonusXp > 0 || bonusCoins > 0) {
            newXp    += bonusXp;
            newCoins += bonusCoins;
            int levelAfterBonus = calculateLevel(newXp);
            if (levelAfterBonus > newLevel) { newLevel = levelAfterBonus; leveledUp = true; }
            dao.updateStats(conn, userId, newXp, newLevel, newCoins,
                streak.newStreak, newStreakRecord,
                today.toString(), streak.shieldUsed ? false : hasShield);
        }

        // 13. Respuesta
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
        r.addProperty("newCoins",         newCoins);
        r.addProperty("newStreak",        streak.newStreak);
        r.addProperty("streakRecord",     newStreakRecord);
        r.addProperty("shieldUsed",       streak.shieldUsed);
        r.addProperty("hasStreakShield",  streak.shieldUsed ? false : hasShield);
        r.addProperty("leveledUp",        leveledUp);
        r.addProperty("oldLevel",         currentLevel);

        if (newLevel < MAX_LEVEL) {
            r.addProperty("xpToNextLevel", LEVEL_THRESHOLDS[newLevel] - newXp);
            r.addProperty("xpNextLevel",   LEVEL_THRESHOLDS[newLevel]);
        } else {
            r.addProperty("xpToNextLevel", 0);
            r.addProperty("xpNextLevel",   LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
        }

        r.addProperty("bonusMissionXp",     missionRewards.get("missionXp").getAsInt());
        r.addProperty("bonusMissionCoins",   missionRewards.get("missionCoins").getAsInt());
        r.addProperty("bonusObjectiveXp",    objectiveRewards.get("objectiveXp").getAsInt());
        r.addProperty("bonusObjectiveCoins", objectiveRewards.get("objectiveCoins").getAsInt());
        r.add("completedMissions",   missionRewards.get("completedMissions"));
        r.add("completedObjectives", objectiveRewards.get("completedObjectives"));
        // Lo pagado por esta actividad queda guardado en su intento (las reglas
        // pueden cambiar después; lo pagado en su momento es un hecho).
        if (attemptId != null) dao.setAttemptRewards(conn, attemptId, xpEarned, Math.max(0, coinsEarned));

        return r;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CÁLCULOS INTERNOS
    // ═════════════════════════════════════════════════════════════════════════

    private static RewardResult getBaseReward(String activityType, double scorePercent) {
        double perfMult = 1.0;
        if (scorePercent >= 90) perfMult = 1.5;
        else if (scorePercent >= 80) perfMult = 1.3;

        return switch (activityType) {
            case "generar"  -> new RewardResult(15, 10);
            case "resumen"  -> new RewardResult(10, 5);

            case "flashcards" -> {
                double fm = scorePercent >= 80 ? 1.3 : 1.0;
                yield new RewardResult((int) Math.round(20 * fm), (int) Math.round(15 * fm));
            }

            case "quiz" -> new RewardResult(
                (int) Math.round(30 * perfMult), (int) Math.round(20 * perfMult));

            case "expert_exam" -> {
                // XP proporcional al score: mín 10 XP por intentar, máx 50 XP al 80%+
                //   0–49%  : escala de 10 a 25 XP  (base × score/100 × 0.5, mín 10)
                //   50–79% : 30 XP base (perfMult = 1.0)
                //   80–89% : 50 × 1.3 = 65 XP
                //   90–100%: 50 × 1.5 = 75 XP
                int baseXp, baseCoins;
                if (scorePercent < 50.0) {
                    // Mínimo 10 XP, sube linealmente hasta 30 al llegar a 50%
                    baseXp    = Math.max(10, (int) Math.round(10 + (scorePercent / 50.0) * 20));
                    baseCoins = Math.max(5,  (int) Math.round(5  + (scorePercent / 50.0) * 15));
                } else {
                    baseXp    = (int) Math.round(50 * perfMult);
                    baseCoins = (int) Math.round(35 * perfMult);
                }
                yield new RewardResult(baseXp, baseCoins);
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

    private static StreakResult calculateStreak(int currentStreak, LocalDate lastActivity,
                                                LocalDate today, boolean hasShield) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(lastActivity, today);

        // Actividad repetida hoy — no cambiar nada
        if (days == 0) return new StreakResult(currentStreak, false);

        // Día consecutivo — racha sube
        if (days == 1) return new StreakResult(currentStreak + 1, false);

        // Se saltó exactamente un día — el escudo lo cubre.
        // FIX Bug A: antes esta condición era solo "if (hasShield)", lo que
        // perdonaba CUALQUIER cantidad de días saltados (10, 30, lo que sea).
        // Ahora el escudo solo aplica cuando days == 2 (un único día saltado
        // entre la última actividad y hoy).
        if (hasShield && days == 2) {
            // La racha se mantiene (no sube — el día faltante queda perdonado).
            // shieldUsed = true → el DAO consumirá el shield (hasStreakShield → false).
            return new StreakResult(currentStreak, true);
        }

        // Sin escudo (o con escudo pero ausencia de más de 1 día): racha rota, empieza desde 1
        return new StreakResult(1, false);
    }

    /**
     * FIX Bug B — Calcula la racha "real" a partir de lo guardado en base de
     * datos, sin esperar a que el usuario haga una actividad nueva para que
     * se refresque (evita que la racha quede "congelada"/inflada en pantalla).
     *
     * Devuelve 0 si la racha ya se rompió:
     *   - más de 1 día de ausencia sin escudo, o
     *   - más de 2 días de ausencia con escudo activo sin usar.
     *
     * Reusable: la llaman tanto getPlayerStats() (endpoint /api/gamification/stats)
     * como ProfileServlet (endpoint /api/profile), que son los dos caminos que
     * hoy muestran la racha al usuario.
     */
    public static int effectiveStreak(int streakActual, LocalDate ultimaActividad, boolean tieneEscudo) {
        if (ultimaActividad == null) {
            return streakActual;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(ultimaActividad, LocalDate.now(APP_ZONE));
        if (days <= 1) {
            return streakActual;
        }
        if (days == 2 && tieneEscudo) {
            return streakActual;
        }
        return 0;
    }

    private static int calculateLevel(int totalXp) {
        for (int i = LEVEL_THRESHOLDS.length - 1; i >= 0; i--) {
            if (totalXp >= LEVEL_THRESHOLDS[i]) return i + 1;
        }
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAPEOS DE TIPO — alineados con los valores reales en la BD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mapea activityType del frontend → missions.type en la BD.
     *
     * Tabla missions tiene:
     *   complete_flashcard, complete_quiz, complete_summary,
     *   complete_expert_exam, win_duel, maintain_streak
     */
    private static String mapToMissionType(String activityType) {
        return switch (activityType) {
            case "quiz"         -> "complete_quiz";
            case "expert_exam"  -> "complete_expert_exam";
            case "flashcards"   -> "complete_flashcard";
            case "resumen"      -> "complete_summary";
            case "generar"      -> "complete_summary";   // generar también cuenta
            case "duelo_ganado" -> "win_duel";
            default -> null;  // abandon_exam, duelo_perdido, duelo_empate → no avanzan misión
        };
    }

    /**
     * Mapea activityType del frontend → user_weekly_objectives.type en la BD.
     *
     * Tabla user_weekly_objectives tiene:
     *   streak_days, activities, duels_won, perfect_exam,
     *   complete_flashcard, complete_quiz, complete_summary, win_duel
     *
     * NOTA: "activities" y "streak_days" se avanzan por separado en processActivity,
     *       no pasan por este mapeo.
     */
    private static String mapToObjectiveType(String activityType, double scorePercent) {
        return switch (activityType) {
            case "duelo_ganado" -> "win_duel";
            case "flashcards"   -> "complete_flashcard";
            case "quiz"         -> "complete_quiz";
            case "resumen"      -> "complete_summary";
            case "generar"      -> "complete_summary";
            // Solo cuenta como perfect_exam si sacó 100%
            case "expert_exam"  -> scorePercent >= 100.0 ? "perfect_exam" : null;
            default -> null;
        };
    }

    private static class RewardResult {
        int xp, coins;
        RewardResult(int xp, int coins) { this.xp = xp; this.coins = coins; }
    }

    private static class StreakResult {
        int newStreak; boolean shieldUsed;
        StreakResult(int ns, boolean su) { this.newStreak = ns; this.shieldUsed = su; }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILIDADES PÚBLICAS
    // ═════════════════════════════════════════════════════════════════════════

    public static JsonObject getPlayerStats(String userId) throws Exception {
        JsonObject stats = dao.getStats(userId);
        if (stats == null) return null;

        int xp = stats.has("xp") && !stats.get("xp").isJsonNull() ? stats.get("xp").getAsInt() : 0;
        int level = stats.has("level") && !stats.get("level").isJsonNull() ? stats.get("level").getAsInt() : 1;
        if (level < MAX_LEVEL) {
            int next = LEVEL_THRESHOLDS[level], prev = LEVEL_THRESHOLDS[level - 1];
            stats.addProperty("xpToNextLevel",  next - xp);
            stats.addProperty("xpNextLevel",    next);
            stats.addProperty("xpCurrentLevel", prev);
            stats.addProperty("levelProgress",  Math.round(((double)(xp - prev) / (next - prev)) * 100));
        } else {
            stats.addProperty("xpToNextLevel",  0);
            stats.addProperty("xpNextLevel",    LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
            stats.addProperty("xpCurrentLevel", LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
            stats.addProperty("levelProgress",  100);
        }
        stats.addProperty("maxLevel",         MAX_LEVEL);

        // FIX Bug B — recalcular la racha real antes de devolverla, en vez de
        // mostrar tal cual lo que quedó guardado la última vez que hubo actividad.
        int streakCurrentRaw = stats.has("streakCurrent") && !stats.get("streakCurrent").isJsonNull()
                ? stats.get("streakCurrent").getAsInt() : 0;
        String lastActivityStr = stats.has("streakLastActivity") && !stats.get("streakLastActivity").isJsonNull()
                ? stats.get("streakLastActivity").getAsString() : null;
        boolean hasShield = stats.has("hasStreakShield") && !stats.get("hasStreakShield").isJsonNull()
                && stats.get("hasStreakShield").getAsBoolean();

        LocalDate lastActivity = (lastActivityStr != null && !lastActivityStr.isEmpty()
                                   && !"null".equals(lastActivityStr))
                                 ? LocalDate.parse(lastActivityStr)
                                 : null;

        int sc = effectiveStreak(streakCurrentRaw, lastActivity, hasShield);
        stats.addProperty("streakCurrent", sc);
        stats.addProperty("streakMultiplier", getStreakMultiplier(sc));
        return stats;
    }

    public static int getXpForLevel(int level) {
        if (level < 1) return 0;
        if (level > MAX_LEVEL) return LEVEL_THRESHOLDS[MAX_LEVEL - 1];
        return LEVEL_THRESHOLDS[level - 1];
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