package com.project.util;

import java.time.LocalDate;

import com.google.gson.JsonObject;
import com.project.dao.implementation.GamificationDAOImpl;
import com.project.dao.interfaces.IGamificationDAO;

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

    private static final int[] LEVEL_THRESHOLDS = {
        0, 80, 200, 380, 640, 1000, 1480, 2100, 2900, 3900
    };

    private static final int MAX_LEVEL = 10;

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO PRINCIPAL
    // ═════════════════════════════════════════════════════════════════════════

    public static JsonObject processActivity(String userId, String activityType,
                                              double scorePercent, String contentId,
                                              int timeTakenSecs, double maxScore)
                                              throws Exception {

        // 1. Stats actuales
        JsonObject stats = dao.getStats(userId);
        if (stats == null) {
            throw new Exception("Usuario no encontrado en user_stats: " + userId);
        }

        int    currentXp       = stats.has("xp") && !stats.get("xp").isJsonNull() ? stats.get("xp").getAsInt() : 0;
        int    currentLevel    = stats.has("level") && !stats.get("level").isJsonNull() ? stats.get("level").getAsInt() : 1;
        int    currentCoins    = stats.has("coins") && !stats.get("coins").isJsonNull() ? stats.get("coins").getAsInt() : 0;
        int    streakCurrent   = stats.has("streakCurrent") && !stats.get("streakCurrent").isJsonNull() ? stats.get("streakCurrent").getAsInt() : 0;
        int    streakRecord    = stats.has("streakRecord") && !stats.get("streakRecord").isJsonNull() ? stats.get("streakRecord").getAsInt() : 0;
        String lastActivityStr = stats.has("streakLastActivity") && !stats.get("streakLastActivity").isJsonNull() ? stats.get("streakLastActivity").getAsString() : null;
        boolean hasShield      = stats.has("hasStreakShield") && !stats.get("hasStreakShield").isJsonNull() && stats.get("hasStreakShield").getAsBoolean();

        // 2. Racha
        LocalDate today        = LocalDate.now();
        LocalDate lastActivity = (lastActivityStr != null && !lastActivityStr.isEmpty()
                                   && !"null".equals(lastActivityStr))
                                 ? LocalDate.parse(lastActivityStr)
                                 : today.minusDays(2);

        StreakResult streak = calculateStreak(streakCurrent, lastActivity, today, hasShield);

        // 2.5. Guardar resultado + detectar si ya fue recompensado antes
        //      saveActivityResult devuelve null cuando ya existe el registro (mismo user + content)
        String resultId = null;
        boolean alreadyRewarded = false;
        if (contentId != null && !contentId.isEmpty()) {
            double score = (scorePercent / 100.0) * maxScore;
            resultId = dao.saveActivityResult(userId, contentId, score, maxScore, timeTakenSecs);
            if ("expert_exam".equals(activityType) || "quiz".equals(activityType)) {
                alreadyRewarded = (resultId == null);
            }
        }

        // 3. Rewards base — 0 si ya fue recompensado
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
        dao.updateStats(userId, newXp, newLevel, newCoins,
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
            dao.advanceDailyMissions(userId, missionType);
        }

        // Si la racha subió, avanzar misiones de tipo maintain_streak
        if (streak.newStreak > streakCurrent) {
            dao.advanceDailyMissions(userId, "maintain_streak");
        }

        // ══════════════════════════════════════════════════════════════════════
        // 11. OBJETIVOS SEMANALES — tipos alineados con user_weekly_objectives.type
        //     Types: streak_days, activities, duels_won, perfect_exam,
        //            complete_flashcard, complete_quiz, complete_summary, win_duel
        // ══════════════════════════════════════════════════════════════════════
        String objectiveType = mapToObjectiveType(activityType, scorePercent);
        if (objectiveType != null) {
            dao.advanceWeeklyObjectives(userId, objectiveType);
        }

        // "activities" cuenta CUALQUIER actividad positiva (no abandon)
        if (!"abandon_exam".equals(activityType)) {
            dao.advanceWeeklyObjectives(userId, "activities");
        }

        // Si la racha subió, avanzar objetivo de streak_days
        if (streak.newStreak > streakCurrent) {
            dao.advanceWeeklyObjectives(userId, "streak_days");
        }

        // 12. Check completados
        JsonObject missionRewards   = dao.checkCompletedMissions(userId);
        JsonObject objectiveRewards = dao.checkCompletedObjectives(userId);

        int bonusXp    = missionRewards.get("missionXp").getAsInt()
                       + objectiveRewards.get("objectiveXp").getAsInt();
        int bonusCoins = missionRewards.get("missionCoins").getAsInt()
                       + objectiveRewards.get("objectiveCoins").getAsInt();

        if (bonusXp > 0 || bonusCoins > 0) {
            newXp    += bonusXp;
            newCoins += bonusCoins;
            int levelAfterBonus = calculateLevel(newXp);
            if (levelAfterBonus > newLevel) { newLevel = levelAfterBonus; leveledUp = true; }
            dao.updateStats(userId, newXp, newLevel, newCoins,
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
        if (resultId != null) r.addProperty("activityResultId", resultId);

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
        long days = java.time.temporal.ChronoUnit.DAYS.between(ultimaActividad, LocalDate.now());
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
}