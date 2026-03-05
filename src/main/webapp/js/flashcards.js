// ============================================================
// flashcards.js
// Lógica de Flashcards — lee datos de sessionStorage (Modo Estudio)
// ============================================================

// ── Cargar datos desde sessionStorage ────────────────────────
function loadFlashcardsFromSession() {
    const raw = sessionStorage.getItem('studyResults');
    if (!raw) return null;
    try {
        const results = JSON.parse(raw);
        const fc = results.flashcards;
        if (!fc || !fc.cards || fc.cards.length === 0) return null;
        return {
            title: fc.title || 'Flashcards',
            cards: fc.cards.map(c => ({ question: c.front, answer: c.back }))
        };
    } catch (e) {
        console.error('Error leyendo flashcards del sessionStorage:', e);
        return null;
    }
}

const sessionData = loadFlashcardsFromSession();

// Si no hay datos de la IA, muestra mensaje de error en lugar de datos hardcodeados
if (!sessionData) {
    document.addEventListener('DOMContentLoaded', () => {
        const main = document.getElementById('mainContent');
        if (main) {
            main.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:60vh;gap:1rem;text-align:center;padding:2rem">
                    <i class="fas fa-exclamation-circle" style="font-size:3rem;color:#ef4444;opacity:0.7"></i>
                    <h2 style="color:rgba(255,255,255,0.8)">No hay flashcards disponibles</h2>
                    <p style="color:rgba(255,255,255,0.4);max-width:400px">Genera una sesión de estudio primero desde Modo Estudio.</p>
                    <a href="../pages/modo-estudio.html" style="padding:0.75rem 1.5rem;background:#2dd4bf;color:#0f172a;border-radius:12px;font-weight:600;text-decoration:none">
                        Ir a Modo Estudio
                    </a>
                </div>`;
        }
    });
}

const originalFlashcards = sessionData ? sessionData.cards : [];
const flashcardTopic     = sessionData ? sessionData.title : '';

let flashcardsData = [...originalFlashcards];
let currentIndex   = 0;
let isFlipped      = false;
let answers        = new Array(flashcardsData.length).fill(null);
let highScore      = 0;

// ── Init ──────────────────────────────────────────────────────
function init() {
    if (!sessionData) return;
    document.getElementById('topicLabel').textContent = flashcardTopic;
    document.getElementById('totalNum').textContent   = flashcardsData.length;
    generateDots();
    updateCard();
    updateProgress();
    updateNavButtons();
    initStars();
}

function goBack(e) { e.preventDefault(); window.history.back(); }

// ── Dots ──────────────────────────────────────────────────────
function generateDots() {
    const c = document.getElementById('dotsContainer');
    let h = '';
    for (let i = 0; i < flashcardsData.length; i++) {
        h += `<div class="fc-dot${i === 0 ? ' current' : ''}" id="dot-${i}"></div>`;
    }
    c.innerHTML = h;
}

function updateDots() {
    for (let i = 0; i < flashcardsData.length; i++) {
        const d = document.getElementById(`dot-${i}`);
        if (!d) continue;
        d.className = 'fc-dot';
        if (i === currentIndex)    d.classList.add('current');
        if (answers[i] === true)   d.classList.add('correct');
        if (answers[i] === false)  d.classList.add('incorrect');
    }
}

// ── Card ──────────────────────────────────────────────────────
function flipCard() {
    const card = document.getElementById('flashcard');
    isFlipped = !isFlipped;
    card.classList.toggle('flipped', isFlipped);
    if (isFlipped && answers[currentIndex] === null) {
        document.getElementById('evalButtons').classList.add('visible');
    }
}

function markAnswer(correct) {
    if (answers[currentIndex] !== null) return;
    answers[currentIndex] = correct;
    document.getElementById('evalButtons').classList.remove('visible');
    updateDots();
    updateProgress();
    if (correct) showXpToast(10);

    const allDone = answers.every(a => a !== null);
    if (allDone) {
        setTimeout(showResults, 1000);
    } else {
        setTimeout(() => {
            let next = currentIndex + 1;
            while (next < flashcardsData.length && answers[next] !== null) next++;
            if (next >= flashcardsData.length) {
                next = 0;
                while (next < flashcardsData.length && answers[next] !== null) next++;
            }
            if (next < flashcardsData.length) {
                currentIndex = next;
                resetCardFlip(); updateCard(); updateNavButtons(); updateDots();
            }
        }, 600);
    }
}

function nextCard() {
    if (currentIndex < flashcardsData.length - 1) {
        currentIndex++; resetCardFlip(); updateCard(); updateNavButtons(); updateDots();
    }
}

function prevCard() {
    if (currentIndex > 0) {
        currentIndex--; resetCardFlip(); updateCard(); updateNavButtons(); updateDots();
    }
}

function resetCardFlip() {
    isFlipped = false;
    document.getElementById('flashcard').classList.remove('flipped');
    document.getElementById('evalButtons').classList.remove('visible');
}

function updateCard() {
    const c = flashcardsData[currentIndex];
    document.getElementById('questionText').textContent = c.question;
    document.getElementById('answerText').textContent   = c.answer;
    document.getElementById('currentNum').textContent   = currentIndex + 1;
    if (answers[currentIndex] !== null) {
        document.getElementById('evalButtons').classList.remove('visible');
    }
}

function updateProgress() {
    const a = answers.filter(a => a !== null).length;
    document.getElementById('progressFill').style.width =
        `${Math.max((a / flashcardsData.length) * 100, 3)}%`;
}

function updateNavButtons() {
    document.getElementById('prevBtn').disabled = currentIndex === 0;
    document.getElementById('nextBtn').disabled = currentIndex === flashcardsData.length - 1;
}

// ── Results ───────────────────────────────────────────────────
function showResults() {
    const cc    = answers.filter(a => a === true).length;
    const ic    = answers.filter(a => a === false).length;
    const score = Math.round((cc / flashcardsData.length) * 100);
    const isNew = score > highScore;
    if (isNew) highScore = score;

    document.getElementById('studyView').classList.add('hidden');
    document.getElementById('resultsView').classList.add('active');

    const emoji = document.getElementById('resultsEmoji');
    const title = document.getElementById('resultsTitle');
    const sub   = document.getElementById('resultsSubtitle');

    if (score >= 90)      { emoji.src = '../images/modo-estudio/giphy.gif'; title.textContent = '¡Increíble, eres un crack!'; sub.textContent = 'Dominas este tema a la perfección'; }
    else if (score >= 70) { emoji.src = '../images/modo-estudio/giphy.gif'; title.textContent = '¡Muy bien hecho!'; sub.textContent = 'Tienes un buen dominio del tema'; }
    else if (score >= 50) { emoji.src = '../images/modo-estudio/flexed-biceps_1f4aa.gif'; title.textContent = '¡Buen intento!'; sub.textContent = 'Sigue practicando para mejorar'; }
    else                  { emoji.src = '../images/modo-estudio/WZ6R8rSOyG.gif'; title.textContent = 'A seguir estudiando'; sub.textContent = 'Repasa el material y vuelve a intentar'; }

    document.getElementById('correctCount').textContent    = cc;
    document.getElementById('incorrectCount').textContent  = ic;
    document.getElementById('highScoreResult').textContent = highScore + '%';
    if (isNew) document.getElementById('newHighscore').classList.add('show');

    const ring = document.getElementById('scoreRing');
    const circ = 2 * Math.PI * 75;
    const off  = circ - (score / 100) * circ;
    let rc;
    if (score >= 80)      rc = '#22c55e';
    else if (score >= 60) rc = '#2dd4bf';
    else if (score >= 40) rc = '#fbbf24';
    else                  rc = '#ef4444';
    ring.style.stroke = rc;
    setTimeout(() => { ring.style.strokeDashoffset = off; }, 300);
    animateNumber('scoreNumber', 0, score, 1500, '%');

    // Detalle respuestas
    const dc = document.getElementById('answersDetail');
    let dh = `<div class="fc-answers-title"><i class="fas fa-list-check"></i> Detalle de respuestas</div>`;
    flashcardsData.forEach((c, i) => {
        const ok = answers[i] === true;
        dh += `<div class="fc-answer-item ${ok ? 'is-correct' : 'is-incorrect'}">
            <div class="fc-answer-status ${ok ? 'green' : 'red'}"><i class="fas fa-${ok ? 'check' : 'times'}"></i></div>
            <div class="fc-answer-text">
                <div class="fc-answer-q">${c.question}</div>
                <div class="fc-answer-a"><i class="fas fa-arrow-right" style="font-size:0.7rem;margin-right:0.3rem;opacity:0.5"></i>${c.answer}</div>
            </div>
        </div>`;
    });
    dc.innerHTML = dh;

    if (score >= 70) launchConfetti();
    const xp = cc * 10 + (isNew ? 25 : 0);
    setTimeout(() => showXpToast(xp), 1500);
}

// ── Restart / Review ──────────────────────────────────────────
function restartFlashcards() {
    flashcardsData = [...originalFlashcards];
    currentIndex = 0; isFlipped = false;
    answers = new Array(flashcardsData.length).fill(null);
    document.getElementById('resultsView').classList.remove('active');
    document.getElementById('studyView').classList.remove('hidden');
    document.getElementById('newHighscore').classList.remove('show');
    document.getElementById('highScoreDisplay').textContent = highScore + '%';
    document.getElementById('scoreRing').style.strokeDashoffset = '471.24';
    document.getElementById('topicLabel').textContent = flashcardTopic;
    document.getElementById('totalNum').textContent   = flashcardsData.length;
    resetCardFlip(); generateDots(); updateCard(); updateProgress(); updateNavButtons();
}

function reviewMistakes() {
    const m = [];
    flashcardsData.forEach((c, i) => { if (answers[i] === false) m.push(c); });
    if (m.length === 0) return;
    flashcardsData = [...m];
    currentIndex = 0; isFlipped = false;
    answers = new Array(flashcardsData.length).fill(null);
    document.getElementById('resultsView').classList.remove('active');
    document.getElementById('studyView').classList.remove('hidden');
    document.getElementById('newHighscore').classList.remove('show');
    document.getElementById('totalNum').textContent   = flashcardsData.length;
    document.getElementById('topicLabel').textContent = 'Repaso de errores 🔄';
    document.getElementById('scoreRing').style.strokeDashoffset = '471.24';
    resetCardFlip(); generateDots(); updateCard(); updateProgress(); updateNavButtons();
}

// ── Helpers ───────────────────────────────────────────────────
function animateNumber(id, s, e, d, suf = '') {
    const el = document.getElementById(id);
    const st = performance.now();
    function u(t) {
        const p = Math.min((t - st) / d, 1);
        el.textContent = Math.round(s + (e - s) * (1 - Math.pow(1 - p, 3))) + suf;
        if (p < 1) requestAnimationFrame(u);
    }
    requestAnimationFrame(u);
}

function showXpToast(a) {
    const t = document.getElementById('xpToast');
    document.getElementById('xpAmount').textContent = `+${a} XP`;
    t.classList.add('show');
    setTimeout(() => t.classList.remove('show'), 3000);
}

function launchConfetti() {
    const c    = document.getElementById('confettiContainer');
    const cols = ['#2dd4bf', '#8b5cf6', '#ec4899', '#fbbf24', '#22c55e', '#3b82f6', '#f97316'];
    let h = '';
    for (let i = 0; i < 60; i++) {
        const col = cols[Math.floor(Math.random() * cols.length)];
        h += `<div class="confetti-piece" style="left:${Math.random()*100}%;width:${6+Math.random()*8}px;height:${6+Math.random()*12}px;background:${col};border-radius:${Math.random()>0.5?'50%':'2px'};animation-duration:${1.5+Math.random()*2}s;animation-delay:${Math.random()*0.8}s"></div>`;
    }
    c.innerHTML = h;
    setTimeout(() => c.innerHTML = '', 4000);
}

// ── Stars Canvas ──────────────────────────────────────────────
function initStars() {
    const canvas  = document.getElementById('starsCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.getElementById('mainContent');

    function resizeCanvas() {
        canvas.width  = content.offsetWidth;
        canvas.height = content.scrollHeight;
    }
    resizeCanvas();

    const stars = Array.from({ length: 150 }, () => ({
        x: Math.random() * canvas.width,
        y: Math.random() * canvas.height,
        size: Math.random() * 2 + 0.3,
        speedX: (Math.random() - 0.5) * 0.1,
        speedY: (Math.random() - 0.5) * 0.1,
        opacity: Math.random() * 0.4 + 0.1,
        opacityChange: (Math.random() - 0.5) * 0.012,
        color: ['#ffffff','#ffffff','#ffe9c4','#d4f1ff','#c4b5fd','#aaddff'][Math.floor(Math.random() * 6)]
    }));

    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        stars.forEach(s => {
            s.x += s.speedX; s.y += s.speedY;
            if (s.x < 0) s.x = canvas.width;   if (s.x > canvas.width) s.x = 0;
            if (s.y < 0) s.y = canvas.height;  if (s.y > canvas.height) s.y = 0;
            s.opacity += s.opacityChange;
            if (s.opacity <= 0.05 || s.opacity >= 0.8) s.opacityChange *= -1;
            ctx.beginPath();
            ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
            ctx.fillStyle  = s.color;
            ctx.globalAlpha = s.opacity;
            ctx.fill();
            if (s.size > 1.5) {
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.size * 2.5, 0, Math.PI * 2);
                ctx.fillStyle   = s.color;
                ctx.globalAlpha = s.opacity * 0.2;
                ctx.fill();
            }
        });
        ctx.globalAlpha = 1;
        requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}

// ── Keyboard ──────────────────────────────────────────────────
document.addEventListener('keydown', (e) => {
    if (document.getElementById('resultsView').classList.contains('active')) return;
    switch (e.key) {
        case ' ':
        case 'Enter':      e.preventDefault(); flipCard(); break;
        case 'ArrowRight': if (!document.getElementById('nextBtn').disabled) nextCard(); break;
        case 'ArrowLeft':  if (!document.getElementById('prevBtn').disabled) prevCard(); break;
        case '1': if (isFlipped && answers[currentIndex] === null) markAnswer(true); break;
        case '2': if (isFlipped && answers[currentIndex] === null) markAnswer(false); break;
    }
});

// ── Arrancar ──────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', init);