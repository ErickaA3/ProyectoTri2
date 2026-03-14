// ============================================================
// examen-quiz.js
// Lógica del Modo Quiz — formativo, feedback inmediato
// ============================================================

// ===== STARS CANVAS =====
function initStars() {
    const canvas = document.getElementById('starsCanvas');
    const ctx = canvas.getContext('2d');
    function resize() { canvas.width = window.innerWidth; canvas.height = window.innerHeight; }
    resize();
    window.addEventListener('resize', resize);
    const stars = [];
    for (let i = 0; i < 150; i++) {
        stars.push({
            x: Math.random() * canvas.width, y: Math.random() * canvas.height,
            size: Math.random() * 2 + 0.3,
            speedX: (Math.random() - 0.5) * 0.1, speedY: (Math.random() - 0.5) * 0.1,
            opacity: Math.random() * 0.4 + 0.1, opacityChange: (Math.random() - 0.5) * 0.012,
            color: ['#ffffff','#ffffff','#ffe9c4','#d4f1ff','#c4b5fd','#aaddff'][Math.floor(Math.random()*6)]
        });
    }
    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        stars.forEach(s => {
            s.x += s.speedX; s.y += s.speedY;
            s.opacity += s.opacityChange;
            if (s.opacity <= 0.05 || s.opacity >= 0.6) s.opacityChange *= -1;
            if (s.x < 0) s.x = canvas.width; if (s.x > canvas.width) s.x = 0;
            if (s.y < 0) s.y = canvas.height; if (s.y > canvas.height) s.y = 0;
            ctx.beginPath();
            ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
            ctx.fillStyle = s.color;
            ctx.globalAlpha = s.opacity;
            ctx.fill();
            ctx.globalAlpha = 1;
        });
        requestAnimationFrame(animate);
    }
    animate();
}

// ===== EXAM DATA — cargado desde sessionStorage =====
function loadExamData() {
    try {
        const results = JSON.parse(sessionStorage.getItem('studyResults') || 'null');
        const flow    = JSON.parse(sessionStorage.getItem('modoEstudioFlow') || 'null');

        if (!results?.examenes?.questions?.length) {
            document.getElementById('examScreen').innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:60vh;gap:1rem;color:var(--text-secondary);text-align:center;padding:2rem">
                    <i class="fas fa-exclamation-triangle" style="font-size:2.5rem;color:var(--accent-orange)"></i>
                    <p style="font-size:1.1rem">No se encontraron preguntas del quiz.</p>
                    <p style="font-size:0.9rem">Vuelve al modo estudio y genera el contenido primero.</p>
                    <button onclick="window.location.href='../pages/modo-estudio.html'"
                        style="margin-top:0.5rem;padding:0.75rem 2rem;border-radius:12px;background:linear-gradient(135deg,var(--accent-cyan),#14b8a6);color:#0f0f23;border:none;cursor:pointer;font-family:inherit;font-size:1rem">
                        Ir al Modo Estudio
                    </button>
                </div>`;
            return null;
        }

        const cfg = flow?.configs?.examenes || {};
        return {
            id:          results.examenes.id,
            title:       results.examenes.title || 'Quiz',
            hasTimer:    cfg.hasTimer ?? false,
            timeMinutes: cfg.timerMinutos ?? 10,
            showExplain: cfg.showExplain ?? true,
            questions:   results.examenes.questions
        };
    } catch(e) {
        console.error('Error cargando datos del quiz:', e);
        return null;
    }
}

const examData = loadExamData();

// ===== STATE =====
let currentQuestion = 0;
let answers         = examData ? new Array(examData.questions.length).fill(null) : [];
let questionLocked  = examData ? new Array(examData.questions.length).fill(false) : [];
let questionResults = examData ? new Array(examData.questions.length).fill(null) : [];
let totalSeconds    = examData ? examData.timeMinutes * 60 : 0;
let timerInterval = null;
let alertShownAt2min = false, alertShownAt1min = false, examFinished = false;
let correctCount = 0, incorrectCount = 0, streak = 0, bestStreak = 0;
const letters = ['A','B','C','D'];

// ===== INIT =====
function initExam() {
    if (!examData) return;
    initStars();
    document.getElementById('examTitleBar').textContent = examData.title;
    document.getElementById('totalQ').textContent = examData.questions.length;
    if (examData.hasTimer) { document.getElementById('timer').classList.remove('timer-hidden'); startTimer(); }
    renderQuestion(); renderDots();
}

// ===== RENDER =====
function renderQuestion() {
    const q = examData.questions[currentQuestion];
    const locked = questionLocked[currentQuestion];
    document.getElementById('currentQ').textContent = currentQuestion + 1;
    document.getElementById('questionNumber').textContent = `Pregunta ${currentQuestion + 1} de ${examData.questions.length}`;
    document.getElementById('questionText').textContent = q.question;
    document.getElementById('progressFill').style.width = ((currentQuestion+1)/examData.questions.length*100)+'%';

    const list = document.getElementById('optionsList');
    list.innerHTML = q.options.map((opt, i) => {
        let cls = 'option-btn', fb = '';
        if (locked) {
            if (i === q.correct) { cls += ' correct'; fb = '<span class="option-feedback"><i class="fas fa-check-circle" style="color:var(--accent-green)"></i></span>'; }
            else if (i === answers[currentQuestion]) { cls += ' incorrect'; fb = '<span class="option-feedback"><i class="fas fa-times-circle" style="color:var(--accent-red)"></i></span>'; }
            else { cls += ' disabled-opt'; }
        } else if (answers[currentQuestion] === i) { cls += ' selected'; }
        return `<button class="${cls}" ${locked ? '' : `onclick="selectOption(${i})"`}><span class="option-letter">${letters[i]}</span><span class="option-text">${opt}</span>${fb}</button>`;
    }).join('');

    const expBox = document.getElementById('explanationBox');
    if (locked && q.explanation && examData.showExplain) {
        document.getElementById('explanationText').textContent = q.explanation;
        expBox.classList.add('show');
    } else {
        expBox.classList.remove('show');
    }

    const btn = document.getElementById('btnSiguiente');
    if (currentQuestion === examData.questions.length - 1 && locked) {
        btn.innerHTML = '<i class="fas fa-chart-bar"></i> Ver resultados';
        btn.className = 'btn-nav btn-entregar';
        btn.onclick = function(){ finishExam(); };
    } else {
        btn.innerHTML = 'Siguiente <i class="fas fa-chevron-right"></i>';
        btn.className = `btn-nav btn-siguiente ${locked ? '' : 'waiting'}`;
        btn.onclick = nextQuestion;
    }
    updateDots();
}

function selectOption(index) {
    if (questionLocked[currentQuestion]) return;
    answers[currentQuestion] = index;
    questionLocked[currentQuestion] = true;
    const q = examData.questions[currentQuestion];
    if (index === q.correct) { correctCount++; streak++; if (streak > bestStreak) bestStreak = streak; questionResults[currentQuestion] = 'correct'; }
    else { incorrectCount++; streak = 0; questionResults[currentQuestion] = 'incorrect'; }
    document.getElementById('scoreCorrect').textContent = correctCount;
    document.getElementById('scoreIncorrect').textContent = incorrectCount;
    document.getElementById('streakCount').textContent = streak;
    renderQuestion();
}

function renderDots() {
    document.getElementById('questionDots').innerHTML = examData.questions.map((_,i) => `<div class="q-dot" data-index="${i}"></div>`).join('');
}

function updateDots() {
    document.querySelectorAll('.q-dot').forEach((dot, i) => {
        dot.className = 'q-dot';
        if (i === currentQuestion) { dot.classList.add('current'); }
        else if (questionResults[i] === 'correct') { dot.classList.add('dot-correct'); }
        else if (questionResults[i] === 'incorrect') { dot.classList.add('dot-incorrect'); }
    });
}

function nextQuestion() {
    if (!questionLocked[currentQuestion]) return;
    if (currentQuestion < examData.questions.length - 1) {
        const card = document.getElementById('questionCard');
        card.classList.add('exit');
        setTimeout(() => { currentQuestion++; renderQuestion(); card.classList.remove('exit'); }, 300);
    }
}

// ===== TIMER =====
function startTimer() { updateTimerDisplay(); timerInterval = setInterval(() => { totalSeconds--; if(totalSeconds<=0){totalSeconds=0;clearInterval(timerInterval);timeUp();} updateTimerDisplay();updateTimerState(); },1000); }
function updateTimerDisplay() { const m=Math.floor(totalSeconds/60),s=totalSeconds%60; document.getElementById('timerValue').textContent=`${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}`; }
function updateTimerState() { const t=document.getElementById('timer');t.classList.remove('warning','danger'); if(totalSeconds<=60){t.classList.add('danger');if(!alertShownAt1min){alertShownAt1min=true;showTimerAlert('Último minuto','Te queda menos de 1 minuto.');}} else if(totalSeconds<=120){t.classList.add('warning');if(!alertShownAt2min){alertShownAt2min=true;showTimerAlert('Queda poco tiempo','Te quedan menos de 2 minutos.');}} }
function timeUp(){showTimerAlert('Se acabó el tiempo','Vamos a ver tus resultados.');const b=document.querySelector('.btn-alert-ok');b.textContent='Ver resultados';b.onclick=function(){closeTimerAlert();finishExam();};}
function showTimerAlert(t,m){document.getElementById('alertTitle').textContent=t;document.getElementById('alertMessage').textContent=m;document.getElementById('timerAlert').classList.add('show');}
function closeTimerAlert(){document.getElementById('timerAlert').classList.remove('show');}

// ===== EXIT =====
function showExitModal(){document.getElementById('exitModal').classList.add('show');}
function closeExitModal(){document.getElementById('exitModal').classList.remove('show');}
function exitExam(){clearInterval(timerInterval);closeExitModal();window.history.back();}

// ===== FINISH =====
function finishExam() {
    if (examFinished) return;
    examFinished = true;
    clearInterval(timerInterval);

    const unanswered = answers.filter(a => a === null).length;
    const total = examData.questions.length;
    const grade = Math.round((correctCount / total) * 100);

    document.getElementById('examScreen').classList.add('hidden');
    document.getElementById('resultsScreen').classList.add('active');

    const emoji    = document.getElementById('resultsEmoji');
    const title    = document.getElementById('resultsMainTitle');
    const subtitle = document.getElementById('resultsMainSubtitle');
    if (grade >= 90)      { emoji.src='giphy.gif';               title.textContent='¡Increíble, eres un crack!'; subtitle.textContent='Dominas este tema a la perfección'; }
    else if (grade >= 70) { emoji.src='giphy.gif';               title.textContent='¡Muy bien hecho!';            subtitle.textContent='Tienes un buen dominio del tema'; }
    else if (grade >= 50) { emoji.src='flexed-biceps_1f4aa.gif'; title.textContent='¡Buen intento!';              subtitle.textContent='Sigue practicando para mejorar'; }
    else                  { emoji.src='WZ6R8rSOyG.gif';          title.textContent='A seguir estudiando';          subtitle.textContent='Repasa el material e inténtalo de nuevo'; }

    animateGrade(grade);

    const msgEl = document.getElementById('gradeMessage');
    if (grade >= 90)      msgEl.innerHTML = '<i class="fas fa-trophy" style="color:var(--accent-green);margin-right:8px"></i><span style="color:var(--accent-green)">Excelente</span> — Dominas este tema';
    else if (grade >= 70) msgEl.innerHTML = '<i class="fas fa-thumbs-up" style="color:var(--accent-cyan);margin-right:8px"></i><span style="color:var(--accent-cyan)">Buen trabajo</span> — Sigue así';
    else if (grade >= 50) msgEl.innerHTML = '<i class="fas fa-book-open" style="color:var(--accent-yellow);margin-right:8px"></i><span style="color:var(--accent-yellow)">Puedes mejorar</span> — Repasa e intenta de nuevo';
    else                  msgEl.innerHTML = '<i class="fas fa-rotate-right" style="color:var(--accent-red);margin-right:8px"></i><span style="color:var(--accent-red)">A seguir practicando</span> — No te desanimes';

    document.getElementById('resultsStats').innerHTML = `
        <div class="stat-card">
            <div class="stat-card-icon" style="background:rgba(34,197,94,0.15);color:var(--accent-green)"><i class="fas fa-check-circle"></i></div>
            <div class="stat-card-value" style="color:var(--accent-green)">${correctCount}</div>
            <div class="stat-card-label">Correctas</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-icon" style="background:rgba(239,68,68,0.15);color:var(--accent-red)"><i class="fas fa-times-circle"></i></div>
            <div class="stat-card-value" style="color:var(--accent-red)">${incorrectCount}</div>
            <div class="stat-card-label">Incorrectas</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-icon" style="background:rgba(160,160,192,0.15);color:var(--text-secondary)"><i class="fas fa-minus-circle"></i></div>
            <div class="stat-card-value">${unanswered}</div>
            <div class="stat-card-label">Sin responder</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-icon" style="background:rgba(251,191,36,0.15);color:var(--accent-yellow)"><i class="fas fa-fire"></i></div>
            <div class="stat-card-value" style="color:var(--accent-yellow)">${bestStreak}</div>
            <div class="stat-card-label">Mejor racha</div>
        </div>`;

    const reviewList = document.getElementById('reviewList');
    reviewList.innerHTML = examData.questions.map((q, i) => {
        let st, ic;
        if (answers[i] === null) { st = 'unanswered'; ic = 'fas fa-minus'; }
        else if (answers[i] === q.correct) { st = 'correct'; ic = 'fas fa-check'; }
        else { st = 'incorrect'; ic = 'fas fa-times'; }
        const ca = q.options[q.correct];
        const ua = answers[i] !== null ? q.options[answers[i]] : null;
        let answerRow = '';
        if (st === 'correct') {
            answerRow = `<div class="review-a-text"><span class="arrow">→</span><span style="color:var(--accent-green)">${ca}</span></div>`;
        } else if (st === 'incorrect') {
            answerRow = `<div class="review-a-text"><span style="color:var(--accent-red)">${ua}</span><span class="arrow" style="margin:0 0.3rem">→</span><span style="color:var(--accent-green)">${ca}</span></div>`;
        } else {
            answerRow = `<div class="review-a-text"><span class="arrow">→</span><span style="color:var(--accent-green)">${ca}</span></div>`;
        }
        return `<div class="review-item ${st}">
            <div class="review-icon"><i class="${ic}"></i></div>
            <div class="review-q">
                <div class="review-q-text"><span style="color:var(--text-secondary);margin-right:0.25rem">${i+1}.</span>${q.question}</div>
                ${answerRow}
            </div>
        </div>`;
    }).join('');

    if (grade >= 70) launchConfetti();

    // ── GAMIFICACIÓN: enviar resultado al servidor ──
    const timeUsed = examData.hasTimer ? (examData.timeMinutes * 60 - totalSeconds) : 0;
    sendReward('quiz', grade, examData.id, timeUsed, examData.questions.length)
        .then(result => {
            if (result.success) {
                setTimeout(() => showXpToast(result.xpEarned), 1500);
            } else {
                setTimeout(() => showXpToast(correctCount * 10), 1500);
            }
        })
        .catch(() => {
            setTimeout(() => showXpToast(correctCount * 10), 1500);
        });
}

// ===== HELPERS =====
function animateGrade(target) {
    const fillEl = document.getElementById('gradeFill');
    const numEl  = document.getElementById('gradeNumber');
    const circumference = 471.24;
    let color;
    if (target >= 80) color = '#22c55e';
    else if (target >= 60) color = '#2dd4bf';
    else if (target >= 40) color = '#fbbf24';
    else color = '#ef4444';
    fillEl.style.stroke = color;
    numEl.style.color = color;
    setTimeout(() => { fillEl.style.strokeDashoffset = circumference - (target/100)*circumference; }, 300);
    let cur = 0;
    const step = Math.max(1, Math.floor(target/40));
    const iv = setInterval(() => { cur += step; if (cur >= target) { cur = target; clearInterval(iv); } numEl.textContent = cur + '%'; }, 30);
}

function launchConfetti() {
    const container = document.getElementById('confettiContainer');
    const colors = ['#2dd4bf','#8b5cf6','#ec4899','#fbbf24','#22c55e','#3b82f6','#f97316'];
    let html = '';
    for (let i = 0; i < 60; i++) {
        const color = colors[Math.floor(Math.random()*colors.length)];
        const left = Math.random()*100, size = 6+Math.random()*8;
        const dur = 1.5+Math.random()*2, delay = Math.random()*0.8;
        const shape = Math.random()>0.5?'50%':'2px';
        html += `<div class="confetti-piece" style="left:${left}%;width:${size}px;height:${size*(Math.random()>0.5?1:1.5)}px;background:${color};border-radius:${shape};animation-duration:${dur}s;animation-delay:${delay}s"></div>`;
    }
    container.innerHTML = html;
    setTimeout(() => container.innerHTML = '', 4000);
}

function showXpToast(amount) {
    const toast = document.getElementById('xpToast');
    document.getElementById('xpToastAmount').textContent = `${amount} XP`;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
}

function retryExam() {
    currentQuestion = 0;
    answers = new Array(examData.questions.length).fill(null);
    questionLocked = new Array(examData.questions.length).fill(false);
    questionResults = new Array(examData.questions.length).fill(null);
    totalSeconds = examData.timeMinutes * 60;
    alertShownAt2min = false; alertShownAt1min = false; examFinished = false;
    correctCount = 0; incorrectCount = 0; streak = 0; bestStreak = 0;
    document.getElementById('scoreCorrect').textContent = '0';
    document.getElementById('scoreIncorrect').textContent = '0';
    document.getElementById('streakCount').textContent = '0';
    document.getElementById('gradeFill').style.strokeDashoffset = '471.24';
    document.getElementById('gradeNumber').textContent = '0%';
    document.getElementById('resultsScreen').classList.remove('active');
    document.getElementById('examScreen').classList.remove('hidden');
    renderQuestion(); renderDots();
    if (examData.hasTimer) startTimer();
}

// ===== KEYBOARD =====
document.getElementById('exitModal').addEventListener('click', function(e){ if(e.target===this) this.classList.remove('show'); });
document.addEventListener('keydown', function(e) {
    if (examFinished) return;
    if (e.key === 'Escape') { closeExitModal(); closeTimerAlert(); }
    if (e.key === 'ArrowRight') nextQuestion();
    const km = {'a':0,'b':1,'c':2,'d':3};
    if (km[e.key.toLowerCase()] !== undefined && !questionLocked[currentQuestion]) {
        const idx = km[e.key.toLowerCase()];
        if (idx < examData.questions[currentQuestion].options.length) selectOption(idx);
    }
});

initExam();