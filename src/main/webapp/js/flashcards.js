// ═══════════════════════════════════════════════════════════════
// ESTADO
// ═══════════════════════════════════════════════════════════════
let cards        = [];
let originalCards = [];   // [FIX] guarda el mazo completo para restaurar en restart
let currentIdx   = 0;
let isFlipped    = false;
let answers      = [];    // { correct: bool } por cada card
let highScore    = 0;
let reviewMode   = false;

// [FIX] id del contenido (study_content) que se está estudiando — se usa
// para el reward de gamificación, el high score y el favorito. Antes no
// se guardaba nada de esto.
let contentId    = null;
let isFavorite   = false;
let sessionStart = 0;      // timestamp para calcular timeTakenSecs del reward
let rewardSent   = false;  // evita mandar el reward más de una vez por sesión

// ═══════════════════════════════════════════════════════════════
// INICIALIZACIÓN
// ═══════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
    // Stars background
    initStars();

    // Cargar datos desde sessionStorage
    const raw = sessionStorage.getItem('studyResults');
    if (!raw) { goBack(); return; }

    try {
        const results = JSON.parse(raw);
        const data = results.flashcards;
        if (!data || !data.cards || data.cards.length === 0) {
            goBack(); return;
        }
        cards = data.cards;
        // [FIX] Guardar copia del mazo original para poder restaurarlo en restart
        originalCards = [...cards];
        document.getElementById('topicLabel').textContent = data.title || 'Flashcards';

        // [FIX] contentId real, para reward/favorito/high score por-contenido
        contentId  = data.id || null;
        isFavorite = !!data.isFavorite;
        updateFavoriteButton();
    } catch (e) {
        console.error('Error parsing flashcards:', e);
        goBack(); return;
    }

    // Cargar high score — [FIX] la clave ahora es por contentId, no por
    // cantidad de tarjetas (dos mazos distintos con el mismo número de
    // tarjetas compartían high score antes de este fix).
    const hsKey = contentId ? ('fc_highscore_' + contentId) : ('fc_highscore_len_' + originalCards.length);
    const savedHS = localStorage.getItem(hsKey);
    if (savedHS) highScore = parseInt(savedHS);
    document.getElementById('highScoreDisplay').textContent = highScore + '%';

    // Inicializar
    answers = new Array(cards.length).fill(null);
    document.getElementById('totalNum').textContent = cards.length;
    sessionStart = Date.now();
    buildDots();
    showCard(0);
});

// ═══════════════════════════════════════════════════════════════
// NAVEGACIÓN DE TARJETAS
// ═══════════════════════════════════════════════════════════════
function showCard(idx) {
    currentIdx = idx;
    isFlipped = false;

    const card = cards[idx];
    document.getElementById('questionText').textContent = card.front;
    document.getElementById('answerText').textContent = card.back;
    document.getElementById('currentNum').textContent = idx + 1;

    // Reset flip
    document.getElementById('flashcard').classList.remove('flipped');

    // Progress
    const pct = ((idx + 1) / cards.length) * 100;
    document.getElementById('progressFill').style.width = pct + '%';

    // Nav buttons
    document.getElementById('prevBtn').disabled = idx === 0;

    // [FIX] Deshabilitar nextBtn en la última tarjeta si quedan sin evaluar.
    // Si ya están todas evaluadas, el click mostrará resultados (nextCard lo maneja).
    const isLastCard  = idx === cards.length - 1;
    const allAnswered = answers.every(a => a !== null);
    document.getElementById('nextBtn').disabled = isLastCard && !allAnswered;

    // Eval buttons: mostrar solo si la tarjeta está volteada y no evaluada
    updateEvalVisibility();

    // Dots
    updateDots();
}

function flipCard() {
    isFlipped = !isFlipped;
    document.getElementById('flashcard').classList.toggle('flipped');
    updateEvalVisibility();
}

function nextCard() {
    if (currentIdx < cards.length - 1) {
        showCard(currentIdx + 1);
    } else {
        // Si todas evaluadas, mostrar resultados
        if (answers.every(a => a !== null)) {
            showResults();
        }
    }
}

function prevCard() {
    if (currentIdx > 0) {
        showCard(currentIdx - 1);
    }
}

// ═══════════════════════════════════════════════════════════════
// EVALUACIÓN
// ═══════════════════════════════════════════════════════════════
function markAnswer(correct) {
    answers[currentIdx] = { correct };
    updateDots();

    // XP toast
    if (correct) showXPToast('+10 XP');

    // Avanzar automáticamente
    setTimeout(() => {
        if (currentIdx < cards.length - 1) {
            nextCard();
        } else if (answers.every(a => a !== null)) {
            showResults();
        }
    }, 400);
}

function updateEvalVisibility() {
    const evalBtns = document.getElementById('evalButtons');
    // Mostrar si está volteada y no evaluada
    if (isFlipped && answers[currentIdx] === null) {
        evalBtns.style.opacity = '1';
        evalBtns.style.pointerEvents = 'auto';
    } else {
        evalBtns.style.opacity = '0.3';
        evalBtns.style.pointerEvents = 'none';
    }
}

// ═══════════════════════════════════════════════════════════════
// DOTS (indicadores de progreso)
// ═══════════════════════════════════════════════════════════════
function buildDots() {
    const container = document.getElementById('dotsContainer');
    container.innerHTML = cards.map((_, i) =>
        `<div class="fc-dot" data-idx="${i}" onclick="showCard(${i})"></div>`
    ).join('');
}

function updateDots() {
    document.querySelectorAll('.fc-dot').forEach((dot, i) => {
        dot.className = 'fc-dot';
        if (i === currentIdx) dot.classList.add('current');
        if (answers[i] !== null) {
            dot.classList.add(answers[i].correct ? 'correct' : 'incorrect');
        }
    });
}

// ═══════════════════════════════════════════════════════════════
// RESULTADOS
// ═══════════════════════════════════════════════════════════════
function showResults() {
    const correct   = answers.filter(a => a && a.correct).length;
    const incorrect = answers.filter(a => a && !a.correct).length;
    const score     = Math.round((correct / cards.length) * 100);

    // Hide study, show results
    document.getElementById('studyView').classList.add('hidden');
    document.getElementById('resultsView').classList.add('active');

    // Emoji y mensajes según score
    const resultsTitle = document.getElementById('resultsTitle');
    const resultsSub   = document.getElementById('resultsSubtitle');
    if (score >= 90) {
        resultsTitle.textContent = '¡Increíble!';
        resultsSub.textContent = 'Dominas este tema completamente';
    } else if (score >= 70) {
        resultsTitle.textContent = '¡Muy bien!';
        resultsSub.textContent = 'Casi perfecto, sigue así';
    } else if (score >= 50) {
        resultsTitle.textContent = '¡Buen intento!';
        resultsSub.textContent = 'Repasa las que fallaste para mejorar';
    } else {
        resultsTitle.textContent = 'Hay que repasar';
        resultsSub.textContent = 'No te rindas, intenta de nuevo';
    }

    // Stats
    document.getElementById('correctCount').textContent = correct;
    document.getElementById('incorrectCount').textContent = incorrect;
    document.getElementById('scoreNumber').textContent = score + '%';

    // High score (basado en el mazo original para consistencia)
    // [FIX] misma clave por-contentId que en la carga inicial.
    const hsKey = contentId ? ('fc_highscore_' + contentId) : ('fc_highscore_len_' + originalCards.length);
    const isNewHS = score > highScore;
    if (isNewHS) {
        highScore = score;
        localStorage.setItem(hsKey, highScore);
    }
    document.getElementById('highScoreResult').textContent = highScore + '%';
    document.getElementById('highScoreDisplay').textContent = highScore + '%';
    // [FIX] antes solo se ponía display:flex por inline style, pero el
    // centrado/gap de este elemento vive en la clase CSS ".show" — sin
    // agregar la clase, el contenido (estrellas + texto) queda pegado a la
    // izquierda en vez de centrado. Se usa la clase en vez del inline style.
    document.getElementById('newHighscore').classList.toggle('show', isNewHS);

    // Score ring animation
    const ring = document.getElementById('scoreRing');
    const circumference = 2 * Math.PI * 75; // r=75
    const offset = circumference - (score / 100) * circumference;
    setTimeout(() => {
        ring.style.transition = 'stroke-dashoffset 1.5s ease';
        ring.style.strokeDashoffset = offset;
        if (score >= 70) ring.style.stroke = '#22c55e';
        else if (score >= 50) ring.style.stroke = '#fbbf24';
        else ring.style.stroke = '#ef4444';
    }, 200);

    // Answers detail
    const detail = document.getElementById('answersDetail');
    const detailHTML = cards.map((card, i) => {
        const ans = answers[i];
        const isCorrect = ans && ans.correct;
        return `
            <div class="fc-answer-item ${isCorrect ? 'is-correct' : 'is-incorrect'}">
                <div class="fc-answer-status ${isCorrect ? 'green' : 'red'}">
                    <i class="fas ${isCorrect ? 'fa-check' : 'fa-times'}"></i>
                </div>
                <div class="fc-answer-text">
                    <div class="fc-answer-q">${card.front}</div>
                    <div class="fc-answer-a">${card.back}</div>
                </div>
            </div>
        `;
    }).join('');
    detail.innerHTML = `<div class="fc-answers-title"><i class="fas fa-list-check"></i> Detalle de respuestas</div>` + detailHTML;

    // Confetti si score > 70
    if (score >= 70) launchConfetti();

    // [FIX] Registro REAL de XP/monedas en el servidor.
    // Antes esto chequeaba `typeof Gamification !== 'undefined' && Gamification.addXP`,
    // pero ese objeto "Gamification" nunca existió en gamification.js (que solo
    // define funciones sueltas: sendReward, fetchPlayerStats, etc). Esa condición
    // siempre era falsa, así que estudiar flashcards NUNCA llamaba a
    // /api/gamification/reward — el toast de "+25 XP" era puramente visual,
    // no se guardaba xp/monedas/racha/misiones/objetivos en la base de datos.
    if (!rewardSent && typeof sendReward === 'function') {
        rewardSent = true;
        const timeTakenSecs = Math.round((Date.now() - sessionStart) / 1000);
        sendReward('flashcards', score, contentId, timeTakenSecs, cards.length)
            .then(result => {
                if (result && result.success && result.xpEarned) {
                    showXPToast('+' + result.xpEarned + ' XP');
                }
            })
            .catch(err => console.error('[flashcards] Error registrando reward:', err));
    } else {
        // Toast visual (sin backend) — reintentos dentro de la misma sesión
        // ya fueron recompensados una vez, o sendReward no está disponible.
        showXPToast('+25 XP');
    }
}

// ═══════════════════════════════════════════════════════════════
// ACCIONES DE RESULTADOS
// ═══════════════════════════════════════════════════════════════
function restartFlashcards() {
    // [FIX] Restaurar el mazo completo original, no el subset de errores
    cards = [...originalCards];
    answers = new Array(cards.length).fill(null);
    reviewMode = false;

    document.getElementById('resultsView').classList.remove('active');
    document.getElementById('studyView').classList.remove('hidden');

    // Actualizar total (puede haber cambiado si veníamos de reviewMistakes)
    document.getElementById('totalNum').textContent = cards.length;

    // Reset score ring
    const ring = document.getElementById('scoreRing');
    ring.style.transition = 'none';
    ring.style.strokeDashoffset = 471.24;

    // [FIX] Reconstruir dots para el mazo correcto
    buildDots();
    showCard(0);
}

function reviewMistakes() {
    const mistakeIndices = answers
        .map((a, i) => (a && !a.correct) ? i : -1)
        .filter(i => i !== -1);

    if (mistakeIndices.length === 0) {
        restartFlashcards();
        return;
    }

    // Crear subset solo con errores (originalCards no se toca)
    cards = mistakeIndices.map(i => originalCards[i]);
    answers = new Array(cards.length).fill(null);
    reviewMode = true;

    document.getElementById('resultsView').classList.remove('active');
    document.getElementById('studyView').classList.remove('hidden');
    document.getElementById('totalNum').textContent = cards.length;

    // Reset ring
    const ring = document.getElementById('scoreRing');
    ring.style.transition = 'none';
    ring.style.strokeDashoffset = 471.24;

    buildDots();
    showCard(0);
}

// ═══════════════════════════════════════════════════════════════
// XP TOAST
// ═══════════════════════════════════════════════════════════════
function showXPToast(text) {
    const toast = document.getElementById('xpToast');
    document.getElementById('xpAmount').textContent = text;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 2000);
}

// ═══════════════════════════════════════════════════════════════
// CONFETTI
// ═══════════════════════════════════════════════════════════════
function launchConfetti() {
    const container = document.getElementById('confettiContainer');
    const colors = ['#2dd4bf', '#8b5cf6', '#fbbf24', '#ec4899', '#3b82f6', '#22c55e'];

    for (let i = 0; i < 60; i++) {
        const piece = document.createElement('div');
        piece.style.cssText = `
            position:fixed; width:${6 + Math.random()*6}px; height:${6 + Math.random()*6}px;
            background:${colors[Math.floor(Math.random()*colors.length)]};
            left:${Math.random()*100}vw; top:-10px;
            border-radius:${Math.random() > 0.5 ? '50%' : '2px'};
            animation:confettiFall ${2 + Math.random()*2}s ease-in forwards;
            animation-delay:${Math.random()*0.5}s;
            z-index:9999; pointer-events:none;
        `;
        container.appendChild(piece);
    }

    // Cleanup
    setTimeout(() => container.innerHTML = '', 5000);
}

// Add confetti animation
const confettiStyle = document.createElement('style');
confettiStyle.textContent = `
    @keyframes confettiFall {
        0% { transform: translateY(0) rotate(0deg); opacity:1; }
        100% { transform: translateY(100vh) rotate(720deg); opacity:0; }
    }
`;
document.head.appendChild(confettiStyle);

// ═══════════════════════════════════════════════════════════════
// FAVORITO
// ═══════════════════════════════════════════════════════════════
// [FIX] Flashcards no tenía botón de favorito — sí lo tiene resumenes.js,
// pero flashcards nunca lo implementó. Usa el endpoint genérico
// /api/favoritos (FavoritesServlet), que funciona para cualquier tipo de
// contenido, no uno propio de resúmenes.
async function toggleFavorite() {
    if (!contentId) return;
    const newValue = !isFavorite;

    try {
        const res = await fetch((window.API_BASE || '') + '/api/favoritos', {
            method:  'PUT',
            headers: getAuthHeaders(),
            body:    JSON.stringify({ contentId, isFavorite: newValue })
        });
        const json = await res.json();
        if (!res.ok || !json.success) throw new Error(json.error || 'Error al actualizar favorito');

        isFavorite = newValue;
        updateFavoriteButton();
        showXPToast(newValue ? 'Añadido a favoritos' : 'Eliminado de favoritos');
    } catch (err) {
        console.error('[flashcards] toggleFavorite:', err);
    }
}

function updateFavoriteButton() {
    const btn  = document.getElementById('favoriteBtn');
    const icon = btn?.querySelector('i');
    if (!btn || !icon) return;
    if (isFavorite) { icon.classList.replace('far', 'fas'); btn.classList.add('active'); }
    else            { icon.classList.replace('fas', 'far'); btn.classList.remove('active'); }
}

// ═══════════════════════════════════════════════════════════════
// NAVEGACIÓN
// ═══════════════════════════════════════════════════════════════
function goBack(e) {
    if (e) e.preventDefault();
    // [FIX] Antes mandaba siempre a sesion-estudio.html, sin importar de
    // dónde venías (Historial, Modo Estudio, etc). Ahora usa el historial
    // real del navegador, igual que resumenes.js y examen-quiz.js — así
    // "volver" te regresa a donde realmente estabas antes.
    if (window.history.length > 1) {
        window.history.back();
    } else {
        window.location.href = '../pages/sesion-estudio.html';
    }
}

// ═══════════════════════════════════════════════════════════════
// TECLADO
// ═══════════════════════════════════════════════════════════════
document.addEventListener('keydown', (e) => {
    switch (e.key) {
        case ' ':
        case 'Enter':
            e.preventDefault();
            flipCard();
            break;
        case 'ArrowRight':
            if (!document.getElementById('nextBtn').disabled) nextCard();
            break;
        case 'ArrowLeft':
            prevCard();
            break;
        case '1':
            if (isFlipped && answers[currentIdx] === null) markAnswer(true);
            break;
        case '2':
            if (isFlipped && answers[currentIdx] === null) markAnswer(false);
            break;
    }
});

// ═══════════════════════════════════════════════════════════════
// STARS BACKGROUND
// ═══════════════════════════════════════════════════════════════
function initStars() {
    const canvas = document.getElementById('starsCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const content = document.getElementById('mainContent');

    function resize() {
        canvas.width = content.offsetWidth;
        canvas.height = Math.max(content.scrollHeight, window.innerHeight);
    }
    resize();

    const stars = Array.from({ length: 100 }, () => ({
        x: Math.random() * canvas.width,
        y: Math.random() * canvas.height,
        size: Math.random() * 1.8 + 0.3,
        speedX: (Math.random() - 0.5) * 0.06,
        speedY: (Math.random() - 0.5) * 0.06,
        opacity: Math.random() * 0.4 + 0.1,
        opacityChange: (Math.random() - 0.5) * 0.008,
        color: ['#ffffff', '#c4b5fd', '#99f6e4'][Math.floor(Math.random() * 3)]
    }));

    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        stars.forEach(s => {
            s.x += s.speedX;
            s.y += s.speedY;
            if (s.x < 0) s.x = canvas.width;
            if (s.x > canvas.width) s.x = 0;
            if (s.y < 0) s.y = canvas.height;
            if (s.y > canvas.height) s.y = 0;
            s.opacity += s.opacityChange;
            if (s.opacity <= 0.05 || s.opacity >= 0.5) s.opacityChange *= -1;
            ctx.beginPath();
            ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
            ctx.fillStyle = s.color;
            ctx.globalAlpha = s.opacity;
            ctx.fill();
        });
        ctx.globalAlpha = 1;
        requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resize);
}