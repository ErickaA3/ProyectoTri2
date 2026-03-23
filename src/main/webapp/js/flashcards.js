// ─────────────────────────────────────────────────────────────
//  flashcards.js — Mi ProfesorIA
//  Estudia flashcards generadas por IA con evaluación y score
// ─────────────────────────────────────────────────────────────

// ═══════════════════════════════════════════════════════════════
// ESTADO
// ═══════════════════════════════════════════════════════════════
let cards       = [];
let currentIdx  = 0;
let isFlipped   = false;
let answers     = [];   // { correct: bool } por cada card
let highScore   = 0;
let reviewMode  = false;

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
        document.getElementById('topicLabel').textContent = data.title || 'Flashcards';
    } catch (e) {
        console.error('Error parsing flashcards:', e);
        goBack(); return;
    }

    // Cargar high score
    const savedHS = localStorage.getItem('fc_highscore_' + cards.length);
    if (savedHS) highScore = parseInt(savedHS);
    document.getElementById('highScoreDisplay').textContent = highScore + '%';

    // Inicializar
    answers = new Array(cards.length).fill(null);
    document.getElementById('totalNum').textContent = cards.length;
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

    // High score
    const isNewHS = score > highScore;
    if (isNewHS) {
        highScore = score;
        localStorage.setItem('fc_highscore_' + cards.length, highScore);
    }
    document.getElementById('highScoreResult').textContent = highScore + '%';
    document.getElementById('highScoreDisplay').textContent = highScore + '%';
    document.getElementById('newHighscore').style.display = isNewHS ? 'flex' : 'none';

    // Score ring animation
    const ring = document.getElementById('scoreRing');
    const circumference = 2 * Math.PI * 75; // r=75
    const offset = circumference - (score / 100) * circumference;
    setTimeout(() => {
        ring.style.transition = 'stroke-dashoffset 1.5s ease';
        ring.style.strokeDashoffset = offset;
        // Color según score
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

    // XP por completar
    showXPToast('+25 XP');

    // Gamification hook (si existe)
    if (typeof Gamification !== 'undefined' && Gamification.addXP) {
        const xp = score >= 90 ? 50 : score >= 70 ? 35 : 25;
        Gamification.addXP(xp);
    }
}

// ═══════════════════════════════════════════════════════════════
// ACCIONES DE RESULTADOS
// ═══════════════════════════════════════════════════════════════
function restartFlashcards() {
    answers = new Array(cards.length).fill(null);
    reviewMode = false;
    document.getElementById('resultsView').classList.remove('active');
    document.getElementById('studyView').classList.remove('hidden');

    // Reset score ring
    const ring = document.getElementById('scoreRing');
    ring.style.transition = 'none';
    ring.style.strokeDashoffset = 471.24;

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

    // Crear subset solo con errores
    const originalCards = [...cards];
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
        100% { transform: translateY(100vh) rotate(${360 + Math.random()*360}deg); opacity:0; }
    }
`;
document.head.appendChild(confettiStyle);

// ═══════════════════════════════════════════════════════════════
// NAVEGACIÓN
// ═══════════════════════════════════════════════════════════════
function goBack(e) {
    if (e) e.preventDefault();
    // Volver a sesion-estudio con los resultados existentes
    window.location.href = '../pages/sesion-estudio.html';
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
            nextCard();
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