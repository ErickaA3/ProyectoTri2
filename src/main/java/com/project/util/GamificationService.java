package com.project.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.dao.implementation.GamificationDAOImpl;
import com.project.dao.interfaces.IGamificationDAO;

/**
 * Servicio central de gamificación de Polaris / Mi ProfesorIA.
 *
 * Responsabilidades:
 *   - Calcular XP y coins por actividad (con bonus de rendimiento)
 *   - Gestionar racha diaria (con escudo)
 *   - Calcular multiplicador de racha
 *   - Calcular nivel a partir de XP
 *   - Registrar actividad y actualizar stats en la BD
 *   - Avanzar misiones diarias y objetivos semanales
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
 *  RECOMPENSAS BASE
 * ═══════════════════════════════════════════════════════════
 *  Generar contenido:   +15 XP, +5 coins
 *  Estudiar flashcards: +20 XP, +8 coins   (80%+ → ×1.3)
 *  Ver resumen:         +10 XP, +3 coins
 *  Quiz completado:     +30 XP, +12 coins  (80%+ → ×1.3, 90%+ → ×1.5)
 *  Examen experto:      +50 XP, +20 coins  (80%+ → ×1.3, 90%+ → ×1.5)
 *  Abandonar examen:    −25 XP
 *  Duelo ganado:        +40 XP, +25 coins
 *  Duelo perdido:       −10 XP, +5 coins
 *  Duelo empate:        +20 XP, +10 coins
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

    // XP acumulado necesario para cada nivel (índice = nivel)
    private static final int[] LEVEL_THRESHOLDS = {
        0,      // Nv.1
        80,     // Nv.2
        200,    // Nv.3
        380,    // Nv.4
        640,    // Nv.5
        1000,   // Nv.6
        1480,   // Nv.7
        2100,   // Nv.8
        2900,   // Nv.9
        3900    // Nv.10 (MAX)
    };

    private static final int MAX_LEVEL = 10;

    // ═════════════════════════════════════════════════════════════════════════
    // MÉTODO PRINCIPAL: Procesar una actividad completada
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Procesa una actividad y devuelve un JSON con todos los rewards obtenidos.
     *
     * @param userId         UUID del usuario
     * @param activityType   "generar" | "flashcards" | "resumen" | "quiz" |
     *                       "expert_exam" | "abandon_exam" | "duelo_ganado" |
     *                       "duelo_perdido" | "duelo_empate"
     * @param scorePercent   Porcentaje de acierto (0-100). Usar 0 si no aplica.
     * @param contentId      UUID del contenido asociado (puede ser null)
     * @param timeTakenSecs  Segundos que tomó la actividad (0 si no aplica)
     * @param maxScore       Puntaje máximo posible (para activity_results)
     *
     * @return JsonObject con:
     *   { success, xpEarned, coinsEarned, streakMultiplier, newStreak,
     *     newXp, newLevel, newCoins, leveledUp, oldLevel,
     *     completedMissions[], completedObjectives[] }
     */
    public static JsonObject processActivity(String userId, String activityType,
                                              double scorePercent, String contentId,
                                              int timeTakenSecs, double maxScore)
                                              throws Exception {

        // 1. Obtener stats actuales
        JsonObject stats = dao.getStats(userId);
        if (stats == null) {
            throw new Exception("Usuario no encontrado en user_stats: " + userId);
        }

        int    currentXp      = stats.get("xp").getAsInt();
        int    currentLevel   = stats.get("level").getAsInt();
        int    currentCoins   = stats.get("coins").getAsInt();
        int    streakCurrent  = stats.get("streakCurrent").getAsInt();
        int    streakRecord   = stats.get("streakRecord").getAsInt();
        String lastActivityStr = stats.get("streakLastActivity").getAsString();
        boolean hasShield     = stats.get("hasStreakShield").getAsBoolean();

        // 2. Calcular racha
        LocalDate today        = LocalDate.now();
        LocalDate lastActivity = (lastActivityStr != null && !lastActivityStr.isEmpty()
                                   && !"null".equals(lastActivityStr))
                                 ? LocalDate.parse(lastActivityStr)
                                 : today.minusDays(2); // Si nunca tuvo actividad

        StreakResult streak = calculateStreak(
            streakCurrent, lastActivity, today, hasShield
        );

        // 3. Calcular rewards base
        RewardResult baseReward = getBaseReward(activityType, scorePercent);

        // 4. Aplicar multiplicador de racha (solo a rewards positivos)
        double multiplier = getStreakMultiplier(streak.newStreak);
        int xpEarned, coinsEarned;

        if (baseReward.xp >= 0) {
            xpEarned    = (int) Math.round(baseReward.xp * multiplier);
            coinsEarned = (int) Math.round(baseReward.coins * multiplier);
        } else {
            // Penalizaciones no se multiplican
            xpEarned    = baseReward.xp;
            coinsEarned = baseReward.coins;
        }

        // 5. Calcular nuevos totales
        int newXp    = Math.max(0, currentXp + xpEarned);
        int newCoins = Math.max(0, currentCoins + coinsEarned);

        // XP no puede bajar del umbral del nivel actual (no pierde nivel)
        int currentLevelThreshold = LEVEL_THRESHOLDS[currentLevel - 1];
        if (newXp < currentLevelThreshold) {
            newXp = currentLevelThreshold;
        }

        // 6. Calcular nuevo nivel
        int newLevel = calculateLevel(newXp);
        boolean leveledUp = newLevel > currentLevel;

        // 7. Actualizar récord de racha
        int newStreakRecord = Math.max(streakRecord, streak.newStreak);

        // 8. Guardar en BD
        dao.updateStats(
            userId, newXp, newLevel, newCoins,
            streak.newStreak, newStreakRecord,
            today.toString(), streak.shieldUsed ? false : hasShield
        );

        // 9. Registrar actividad en activity_results (solo si tiene contentId)
        String resultId = null;
        if (contentId != null && !contentId.isEmpty()) {
            double score = (scorePercent / 100.0) * maxScore;
            resultId = dao.saveActivityResult(
                userId, contentId, score, maxScore, timeTakenSecs
            );
        }

        // 10. Avanzar misiones y objetivos
        String missionType = mapToMissionType(activityType);
        if (missionType != null) {
            dao.advanceDailyMissions(userId, missionType);
            // "actividad" siempre avanza (toda actividad cuenta)
            if (!"actividad".equals(missionType)) {
                dao.advanceDailyMissions(userId, "actividad");
            }
        }

        String objectiveType = mapToObjectiveType(activityType);
        if (objectiveType != null) {
            dao.advanceWeeklyObjectives(userId, objectiveType);
        }
        // Todas las actividades avanzan el objetivo "actividades"
        dao.advanceWeeklyObjectives(userId, "actividades");

        // 11. Verificar misiones/objetivos completados y sumar sus rewards
        JsonObject missionRewards   = dao.checkCompletedMissions(userId);
        JsonObject objectiveRewards = dao.checkCompletedObjectives(userId);

        int bonusXp    = missionRewards.get("missionXp").getAsInt()
                       + objectiveRewards.get("objectiveXp").getAsInt();
        int bonusCoins = missionRewards.get("missionCoins").getAsInt()
                       + objectiveRewards.get("objectiveCoins").getAsInt();

        // Aplicar bonus de misiones/objetivos (si hay)
        if (bonusXp > 0 || bonusCoins > 0) {
            newXp    += bonusXp;
            newCoins += bonusCoins;
            int levelAfterBonus = calculateLevel(newXp);
            if (levelAfterBonus > newLevel) {
                newLevel  = levelAfterBonus;
                leveledUp = true;
            }
            // Actualizar BD con el bonus extra
            dao.updateStats(
                userId, newXp, newLevel, newCoins,
                streak.newStreak, newStreakRecord,
                today.toString(), streak.shieldUsed ? false : hasShield
            );
        }

        // 12. Construir respuesta
        JsonObject response = new JsonObject();
        response.addProperty("success",         true);
        response.addProperty("activityType",    activityType);

        // Rewards de la actividad
        response.addProperty("xpEarned",        xpEarned);
        response.addProperty("coinsEarned",     coinsEarned);
        response.addProperty("streakMultiplier", multiplier);
        response.addProperty("scorePercent",    scorePercent);

        // Stats actualizados
        response.addProperty("newXp",           newXp);
        response.addProperty("newLevel",        newLevel);
        response.addProperty("newCoins",        newCoins);
        response.addProperty("newStreak",       streak.newStreak);
        response.addProperty("streakRecord",    newStreakRecord);

        // Level up info
        response.addProperty("leveledUp",       leveledUp);
        response.addProperty("oldLevel",        currentLevel);
        if (newLevel < MAX_LEVEL) {
            int nextThreshold = LEVEL_THRESHOLDS[newLevel]; // threshold del siguiente
            response.addProperty("xpToNextLevel", nextThreshold - newXp);
            response.addProperty("xpNextLevel",   nextThreshold);
        } else {
            response.addProperty("xpToNextLevel", 0);
            response.addProperty("xpNextLevel",   LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
        }

        // Misiones y objetivos
        response.addProperty("bonusMissionXp",    missionRewards.get("missionXp").getAsInt());
        response.addProperty("bonusMissionCoins",  missionRewards.get("missionCoins").getAsInt());
        response.addProperty("bonusObjectiveXp",   objectiveRewards.get("objectiveXp").getAsInt());
        response.addProperty("bonusObjectiveCoins", objectiveRewards.get("objectiveCoins").getAsInt());
        response.add("completedMissions",    missionRewards.get("completedMissions"));
        response.add("completedObjectives",  objectiveRewards.get("completedObjectives"));

        if (resultId != null) {
            response.addProperty("activityResultId", resultId);
        }

        return response;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CÁLCULOS INTERNOS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Calcula la recompensa base según el tipo de actividad y rendimiento.
     */
    private static RewardResult getBaseReward(String activityType, double scorePercent) {
        double performanceMultiplier = 1.0;
        if (scorePercent >= 90) performanceMultiplier = 1.5;
        else if (scorePercent >= 80) performanceMultiplier = 1.3;

        return switch (activityType) {
            case "generar" -> new RewardResult(15, 5);

            case "flashcards" -> {
                double fm = scorePercent >= 80 ? 1.3 : 1.0;
                yield new RewardResult(
                    (int) Math.round(20 * fm),
                    (int) Math.round(8 * fm)
                );
            }

            case "resumen" -> new RewardResult(10, 3);

            case "quiz" -> new RewardResult(
                (int) Math.round(30 * performanceMultiplier),
                (int) Math.round(12 * performanceMultiplier)
            );

            case "expert_exam" -> new RewardResult(
                (int) Math.round(50 * performanceMultiplier),
                (int) Math.round(20 * performanceMultiplier)
            );

            case "abandon_exam" -> new RewardResult(-25, 0);

            case "duelo_ganado" -> new RewardResult(40, 25);

            case "duelo_perdido" -> new RewardResult(-10, 5);

            case "duelo_empate" -> new RewardResult(20, 10);

            default -> new RewardResult(5, 2); // actividad desconocida: mínimo
        };
    }

    /**
     * Calcula el multiplicador de racha.
     *   1-2 días  → ×1.0
     *   3-6 días  → ×1.25
     *   7-13 días → ×1.5
     *   14+ días  → ×1.75
     */
    private static double getStreakMultiplier(int streak) {
        if (streak >= 14) return 1.75;
        if (streak >= 7)  return 1.5;
        if (streak >= 3)  return 1.25;
        return 1.0;
    }

    /**
     * Calcula la racha actualizada basada en la fecha de última actividad.
     * Si hoy es el día después de la última actividad → incrementa racha.
     * Si es el mismo día → mantiene racha (ya contó hoy).
     * Si pasaron 2+ días → racha se pierde (a menos que tenga escudo).
     */
    private static StreakResult calculateStreak(int currentStreak,
                                                LocalDate lastActivity,
                                                LocalDate today,
                                                boolean hasShield) {
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(lastActivity, today);

        if (daysBetween == 0) {
            // Ya hizo actividad hoy — mantener racha
            return new StreakResult(currentStreak, false);
        }

        if (daysBetween == 1) {
            // Día consecutivo — incrementar racha
            return new StreakResult(currentStreak + 1, false);
        }

        // Pasaron 2+ días sin actividad
        if (hasShield) {
            // El escudo salva la racha (se consume)
            return new StreakResult(currentStreak + 1, true);
        }

        // Racha perdida — empezar de nuevo en 1
        return new StreakResult(1, false);
    }

    /**
     * Calcula el nivel basado en XP total acumulado.
     */
    private static int calculateLevel(int totalXp) {
        for (int i = LEVEL_THRESHOLDS.length - 1; i >= 0; i--) {
            if (totalXp >= LEVEL_THRESHOLDS[i]) {
                return i + 1; // nivel es 1-indexed
            }
        }
        return 1;
    }

    /**
     * Mapea tipo de actividad a tipo de misión diaria.
     */
    private static String mapToMissionType(String activityType) {
        return switch (activityType) {
            case "quiz", "expert_exam" -> "evaluacion";
            case "flashcards"          -> "flashcard";
            case "generar"             -> "contenido";
            case "duelo_ganado", "duelo_perdido", "duelo_empate" -> "duelo";
            case "resumen"             -> "actividad";
            default -> "actividad";
        };
    }

    /**
     * Mapea tipo de actividad a tipo de objetivo semanal.
     */
    private static String mapToObjectiveType(String activityType) {
        return switch (activityType) {
            case "duelo_ganado"  -> "duelos";
            case "expert_exam"   -> "examen_perfecto";
            default -> null; // "actividades" y "racha" se manejan aparte
        };
    }

    // ─── Clases auxiliares internas ──────────────────────────────────────────

    private static class RewardResult {
        int xp;
        int coins;
        RewardResult(int xp, int coins) {
            this.xp = xp;
            this.coins = coins;
        }
    }

    private static class StreakResult {
        int newStreak;
        boolean shieldUsed;
        StreakResult(int newStreak, boolean shieldUsed) {
            this.newStreak = newStreak;
            this.shieldUsed = shieldUsed;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILIDADES PÚBLICAS (para consultar desde otros servlets)
    // ═════════════════════════════════════════════════════════════════════════

    /** Obtiene stats actuales sin modificar nada. */
    public static JsonObject getPlayerStats(String userId) throws Exception {
        JsonObject stats = dao.getStats(userId);
        if (stats == null) return null;

        int xp    = stats.get("xp").getAsInt();
        int level = stats.get("level").getAsInt();

        // Agregar info de progreso al siguiente nivel
        if (level < MAX_LEVEL) {
            int nextThreshold = LEVEL_THRESHOLDS[level];
            int prevThreshold = LEVEL_THRESHOLDS[level - 1];
            stats.addProperty("xpToNextLevel",  nextThreshold - xp);
            stats.addProperty("xpNextLevel",    nextThreshold);
            stats.addProperty("xpCurrentLevel", prevThreshold);
            stats.addProperty("levelProgress",
                Math.round(((double)(xp - prevThreshold) / (nextThreshold - prevThreshold)) * 100));
        } else {
            stats.addProperty("xpToNextLevel",  0);
            stats.addProperty("xpNextLevel",    LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
            stats.addProperty("xpCurrentLevel", LEVEL_THRESHOLDS[MAX_LEVEL - 1]);
            stats.addProperty("levelProgress",  100);
        }

        stats.addProperty("maxLevel",          MAX_LEVEL);
        stats.addProperty("streakMultiplier",   getStreakMultiplier(
            stats.get("streakCurrent").getAsInt()));

        return stats;
    }

    /** XP necesario para un nivel específico. */
    public static int getXpForLevel(int level) {
        if (level < 1) return 0;
        if (level > MAX_LEVEL) return LEVEL_THRESHOLDS[MAX_LEVEL - 1];
        return LEVEL_THRESHOLDS[level - 1];
    }
}