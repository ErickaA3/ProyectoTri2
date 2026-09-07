/**
 * gamification.js
 * Helper del frontend para el sistema de gamificación.
 *
 * Uso desde cualquier página:
 *   import: <script src="../js/gamification.js"></script>
 *
 *   // Después de completar un quiz:
 *   const result = await sendReward('quiz', 85, examData.id, timeUsed, totalQuestions);
 *   if (result.success) {
 *       showXpToast(result.xpEarned);
 *       updateLocalStats(result);
 *       if (result.leveledUp) showLevelUpModal(result.oldLevel, result.newLevel);
 *   }
 */

const GAMIFICATION_API = (window.API_BASE || '') + '/api/gamification';

// ─── Obtener userId del localStorage ─────────────────────────────────────
function getGamificationUserId() {
    try {
        return JSON.parse(localStorage.getItem('user'))?.id || null;
    } catch (_) {
        return null;
    }
}

// ─── Enviar reward al servidor ───────────────────────────────────────────
/**
 * Registra una actividad completada y obtiene rewards.
 *
 * @param {string} activityType  "quiz"|"expert_exam"|"flashcards"|"generar"|
 *                               "resumen"|"abandon_exam"|"duelo_ganado"|
 *                               "duelo_perdido"|"duelo_empate"
 * @param {number} scorePercent  Nota en porcentaje (0-100). 0 si no aplica.
 * @param {string} contentId     UUID del contenido (null si no aplica)
 * @param {number} timeTakenSecs Segundos usados (0 si no aplica)
 * @param {number} maxScore      Puntaje máximo (ej: número de preguntas)
 *
 * @returns {Object} Respuesta del servidor con xpEarned, coinsEarned,
 *                   newXp, newLevel, newCoins, leveledUp, etc.
 */
async function sendReward(activityType, scorePercent = 0, contentId = null,
                          timeTakenSecs = 0, maxScore = 100) {
    const userId = getGamificationUserId();
    if (!userId) {
        console.warn('[Gamification] No userId found in localStorage');
        return { success: false, error: 'No userId' };
    }

    try {
        const response = await fetch(`${GAMIFICATION_API}/reward`, {
            method:  'POST',
            headers: getAuthHeaders({ 'X-User-Id': userId }),
            body: JSON.stringify({
                activityType,
                scorePercent,
                contentId,
                timeTakenSecs,
                maxScore
            })
        });

        const data = await response.json();

        if (data.success) {
            // Actualizar localStorage automáticamente
            updateLocalStats(data);
        }

        return data;

    } catch (err) {
        console.error('[Gamification] Error:', err);
        return { success: false, error: err.message };
    }
}

// ─── Obtener stats del servidor ──────────────────────────────────────────
async function fetchPlayerStats() {
    const userId = getGamificationUserId();
    if (!userId) return null;

    try {
        const response = await fetch(`${GAMIFICATION_API}/stats`, {
            headers: getAuthHeaders({ 'X-User-Id': userId })
        });
        const data = await response.json();

        if (data.success) {
            updateLocalStats(data);
        }
        return data;

    } catch (err) {
        console.error('[Gamification] Error fetching stats:', err);
        return null;
    }
}

// ─── Actualizar localStorage con los nuevos stats ────────────────────────
/**
 * Sincroniza el objeto 'user' de localStorage con los datos del servidor.
 * El navbar (components.js) lee de aquí, así se actualiza automáticamente.
 */
function updateLocalStats(data) {
    try {
        const user = JSON.parse(localStorage.getItem('user')) || {};

        if (!user.stats) user.stats = {};

        // Solo actualizar si el servidor envió estos campos
        if (data.newXp !== undefined)     user.stats.xp            = data.newXp;
        if (data.newLevel !== undefined)  user.stats.level         = data.newLevel;
        if (data.newCoins !== undefined)  user.stats.coins         = data.newCoins;
        if (data.newStreak !== undefined) user.stats.streakCurrent = data.newStreak;

        // Streak shield: el servidor devuelve hasStreakShield y shieldUsed tras procesar la actividad
        if (data.hasStreakShield !== undefined) user.stats.hasStreakShield = data.hasStreakShield;
        if (data.shieldUsed) {
            user.stats.hasStreakShield = false;
            // Notificar para que el tooltip del navbar refleje el cambio inmediatamente
            console.info('[Gamification] Protector de racha consumido — racha protegida.');
        }

        // También de fetchPlayerStats (campos con nombre diferente)
        if (data.xp !== undefined && data.newXp === undefined)
            user.stats.xp = data.xp;
        if (data.level !== undefined && data.newLevel === undefined)
            user.stats.level = data.level;
        if (data.coins !== undefined && data.newCoins === undefined)
            user.stats.coins = data.coins;
        if (data.streakCurrent !== undefined && data.newStreak === undefined)
            user.stats.streakCurrent = data.streakCurrent;
        if (data.hasStreakShield !== undefined && data.shieldUsed === undefined)
            user.stats.hasStreakShield = data.hasStreakShield;

        localStorage.setItem('user', JSON.stringify(user));

        // Refrescar navbar/sidebar reactivamente (components.js lo escucha)
        if (typeof notifyUserUpdate === 'function') notifyUserUpdate();

        // Si el servidor indicó que ya fue recompensado, el data.xpEarned será 0
        // pero newXp/newCoins siguen siendo los valores actuales correctos — OK.

    } catch (err) {
        console.error('[Gamification] Error updating localStorage:', err);
    }
}