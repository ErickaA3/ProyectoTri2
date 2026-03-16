// ============================================================
// examen-experto.js
// Lógica del Examen Experto — evaluativo, sin feedback inmediato
// CORREGIDO: Usa configuraciones, XP correcto, repasar errores
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
                    <p style="font-size:1.1rem">No se encontraron preguntas del examen.</p>
                    <p style="font-size:0.9rem">Vuelve al modo estudio y genera el contenido primero.</p>
                    <button onclick="window.location.href='../pages/modo-estudio.html'"
                        style="margin-top:0.5rem;padding:0.75rem 2rem;border-radius:12px;background:linear-gradient(135deg,var(--accent-cyan),#14b8a6);color:#0f0f23;border:none;cursor:pointer;font-family:inherit;font-size:1rem">
                        Ir al Modo Estudio
                    </button>
                </div>`;
            return null;
        }

        const cfg = flow?.configs?.examenes || {};
        
        // ══════════════════════════════════════════════════════════════
        // FIX: Aplicar número de preguntas de la configuración
        // ══════════════════════════════════════════════════════════════
        let allQuestions = results.examenes.questions;
        const numPreguntas = cfg.numPreguntas || allQuestions.length;
        
        // Limitar al número configurado (mezclar y tomar las primeras N)
        if (numPreguntas < allQuestions.length) {
            allQuestions = shuffleArray([...allQuestions]);
            allQuestions = allQuestions.slice(0, numPreguntas);
        }
        
        return {
            id:          results.examenes.id,
            title:       results.examenes.title || 'Examen Experto',
            // ══════════════════════════════════════════════════════════════
            // FIX: Usar timerMinutos de la configuración (siempre tiene timer)
            // ══════════════════════════════════════════════════════════════
            timeMinutes: cfg.timerMinutos ?? 15,
            questions:   allQuestions,
            // Guardar preguntas originales para reinicio completo
            originalQuestions: results.examenes.questions
        };
    } catch(e) {
        console.error('Error cargando datos del examen:', e);
        return null;
    }
}

// Función para mezclar array (Fisher-Yates)
function shuffleArray(array) {
    for (let i = array.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [array[i], array[j]] = [array[j], array[i]];
    }
    return array;
}

let examData = loadExamData();

// ===== STATE =====
let currentQuestion = 0;
let answers         = examData ? new Array(examData.questions.length).fill(null) : [];
let totalSeconds    = examData ? examData.timeMinutes * 60 : 0;
let timerInterval   = null;
let alertShownAt2min = false, alertShownAt1min = false, examFinished = false;
const letters = ['A','B','C','D'];

// Para repasar errores
let isReviewMode = false;
let originalExamData = null;
let correctCount = 0, incorrectCount = 0;

// ===== INIT =====
function initExam() {
    if (!examData) return;
    
    // Guardar datos originales para reinicio completo
    originalExamData = JSON.parse(JSON.stringify(examData));
    
    initStars();
    document.getElementById('examTitleBar').textContent = examData.title;
    document.getElementById('totalQ').textContent = examData.questions.length;
    startTimer();
    renderQuestion();
    renderDots();
}

// ===== RENDER =====
function renderQuestion() {
    const q = examData.questions[currentQuestion];
    document.getElementById('currentQ').textContent = currentQuestion + 1;
    document.getElementById('questionNumber').textContent = `Pregunta ${currentQuestion + 1} de ${examData.questions.length}`;
    document.getElementById('questionText').textContent = q.question;
    document.getElementById('progressFill').style.width = ((currentQuestion+1)/examData.questions.length*100)+'%';

    const list = document.getElementById('optionsList');
    list.innerHTML = q.options.map((opt, i) => {
        let cls = 'option-btn';
        if (answers[currentQuestion] === i) cls += ' selected';
        return `<button class="${cls}" onclick="selectOption(${i})"><span class="option-letter">${letters[i]}</span><span class="option-text">${opt}</span></button>`;
    }).join('');

    // Botón siguiente/entregar
    const btn = document.getElementById('btnSiguiente');
    if (currentQuestion === examData.questions.length - 1) {
        btn.innerHTML = '<i class="fas fa-paper-plane"></i> Entregar examen';
        btn.className = 'btn-nav btn-entregar';
        btn.onclick = function(){ showSubmitModal(); };
    } else {
        btn.innerHTML = 'Siguiente <i class="fas fa-chevron-right"></i>';
        btn.className = 'btn-nav btn-siguiente';
        btn.onclick = nextQuestion;
    }
    updateDots();
}

function selectOption(index) {
    answers[currentQuestion] = index;
    renderQuestion();
}

function renderDots() {
    document.getElementById('questionDots').innerHTML = examData.questions.map((_,i) => `<div class="q-dot" data-index="${i}" onclick="goToQuestion(${i})"></div>`).join('');
}

function updateDots() {
    document.querySelectorAll('.q-dot').forEach((dot, i) => {
        dot.className = 'q-dot';
        if (i === currentQuestion) { dot.classList.add('current'); }
        else if (answers[i] !== null) { dot.classList.add('answered'); }
    });
}

function goToQuestion(index) {
    const card = document.getElementById('questionCard');
    card.classList.add('exit');
    setTimeout(() => { currentQuestion = index; renderQuestion(); card.classList.remove('exit'); }, 200);
}

function nextQuestion() {
    if (currentQuestion < examData.questions.length - 1) {
        const card = document.getElementById('questionCard');
        card.classList.add('exit');
        setTimeout(() => { currentQuestion++; renderQuestion(); card.classList.remove('exit'); }, 300);
    }
}

function prevQuestion() {
    if (currentQuestion > 0) {
        const card = document.getElementById('questionCard');
        card.classList.add('exit');
        setTimeout(() => { currentQuestion--; renderQuestion(); card.classList.remove('exit'); }, 300);
    }
}

// ===== TIMER =====
function startTimer() { 
    updateTimerDisplay(); 
    timerInterval = setInterval(() => { 
        totalSeconds--; 
        if(totalSeconds<=0){
            totalSeconds=0;
            clearInterval(timerInterval);
            timeUp();
        } 
        updateTimerDisplay();
        updateTimerState(); 
    },1000); 
}

function updateTimerDisplay() { 
    const m=Math.floor(totalSeconds/60),s=totalSeconds%60; 
    document.getElementById('timerValue').textContent=`${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}`; 
}

function updateTimerState() { 
    const t=document.getElementById('timer');
    t.classList.remove('warning','danger'); 
    if(totalSeconds<=60){
        t.classList.add('danger');
        if(!alertShownAt1min){
            alertShownAt1min=true;
            showTimerAlert('¡Último minuto!','Te queda menos de 1 minuto para terminar.');
        }
    } else if(totalSeconds<=120){
        t.classList.add('warning');
        if(!alertShownAt2min){
            alertShownAt2min=true;
            showTimerAlert('Queda poco tiempo','Te quedan menos de 2 minutos.');
        }
    } 
}

function timeUp(){
    showTimerAlert('Se acabó el tiempo','Vamos a calificar tu examen.');
    const b=document.querySelector('.btn-alert-ok');
    b.textContent='Ver resultados';
    b.onclick=function(){closeTimerAlert();finishExam();};
}

function showTimerAlert(t,m){
    document.getElementById('alertTitle').textContent=t;
    document.getElementById('alertMessage').textContent=m;
    document.getElementById('timerAlert').classList.add('show');
}

function closeTimerAlert(){
    document.getElementById('timerAlert').classList.remove('show');
}

// ===== EXIT =====
function showExitModal(){document.getElementById('exitModal').classList.add('show');}
function closeExitModal(){document.getElementById('exitModal').classList.remove('show');}
function exitExam(){clearInterval(timerInterval);closeExitModal();window.history.back();}

// ===== SUBMIT MODAL =====
function showSubmitModal() {
    const unanswered = answers.filter(a => a === null).length;
    document.getElementById('submitMessage').textContent = unanswered > 0 
        ? `Tienes ${unanswered} pregunta${unanswered > 1 ? 's' : ''} sin responder.`
        : '¡Has respondido todas las preguntas!';
    document.getElementById('submitModal').classList.add('show');
}
function closeSubmitModal() { document.getElementById('submitModal').classList.remove('show'); }

// ===== FINISH =====
function finishExam() {
    if (examFinished) return;
    examFinished = true;
    clearInterval(timerInterval);
    closeSubmitModal();

    // Calcular resultados
    correctCount = 0;
    incorrectCount = 0;
    examData.questions.forEach((q, i) => {
        if (answers[i] === q.correct) correctCount++;
        else if (answers[i] !== null) incorrectCount++;
    });

    const unanswered = answers.filter(a => a === null).length;
    const total = examData.questions.length;
    const grade = Math.round((correctCount / total) * 100);

    document.getElementById('examScreen').classList.add('hidden');
    document.getElementById('resultsScreen').classList.add('active');

    const emoji    = document.getElementById('resultsEmoji');
    const title    = document.getElementById('resultsMainTitle');
    const subtitle = document.getElementById('resultsMainSubtitle');
    
    // ══════════════════════════════════════════════════════════════
    // FIX: Rutas correctas para imágenes
    // ══════════════════════════════════════════════════════════════
    if (grade >= 90)      { emoji.src='../images/modo-estudio/giphy.gif';               title.textContent='¡Excelente dominio!';        subtitle.textContent='Eres un verdadero experto en este tema'; }
    else if (grade >= 70) { emoji.src='../images/modo-estudio/giphy.gif';               title.textContent='¡Muy buen resultado!';       subtitle.textContent='Tienes un sólido conocimiento del tema'; }
    else if (grade >= 50) { emoji.src='../images/modo-estudio/flexed-biceps_1f4aa.gif'; title.textContent='Resultado aceptable';        subtitle.textContent='Hay espacio para mejorar'; }
    else                  { emoji.src='../images/modo-estudio/WZ6R8rSOyG.gif';          title.textContent='Necesitas más práctica';     subtitle.textContent='Repasa el material e inténtalo de nuevo'; }

    animateGrade(grade);

    const msgEl = document.getElementById('gradeMessage');
    if (grade >= 90)      msgEl.innerHTML = '<i class="fas fa-crown" style="color:var(--accent-green);margin-right:8px"></i><span style="color:var(--accent-green)">Nivel Experto</span> — Dominas este tema';
    else if (grade >= 70) msgEl.innerHTML = '<i class="fas fa-medal" style="color:var(--accent-cyan);margin-right:8px"></i><span style="color:var(--accent-cyan)">Buen nivel</span> — Sigue practicando';
    else if (grade >= 50) msgEl.innerHTML = '<i class="fas fa-book-open" style="color:var(--accent-yellow);margin-right:8px"></i><span style="color:var(--accent-yellow)">En progreso</span> — Repasa los errores';
    else                  msgEl.innerHTML = '<i class="fas fa-rotate-right" style="color:var(--accent-red);margin-right:8px"></i><span style="color:var(--accent-red)">A seguir estudiando</span> — No te desanimes';

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
            <div class="stat-card-icon" style="background:rgba(139,92,246,0.15);color:var(--accent-purple)"><i class="fas fa-clock"></i></div>
            <div class="stat-card-value" style="color:var(--accent-purple)">${formatTime(examData.timeMinutes * 60 - totalSeconds)}</div>
            <div class="stat-card-label">Tiempo usado</div>
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

    // ══════════════════════════════════════════════════════════════
    // FIX: Actualizar botones según modo (repasar errores, etc.)
    // ══════════════════════════════════════════════════════════════
    updateResultButtons();

    if (grade >= 70) launchConfetti();

    // ══════════════════════════════════════════════════════════════
    // FIX: Cálculo correcto de XP para examen experto
    // Examen experto da más XP que quiz normal (multiplicador 1.5x)
    // ══════════════════════════════════════════════════════════════
    const timeUsed = examData.timeMinutes * 60 - totalSeconds;
    const baseXP = correctCount * 15; // 15 XP por correcta (vs 10 en quiz)
    const bonusXP = grade >= 90 ? 50 : (grade >= 70 ? 25 : 0); // Bonus por buen rendimiento
    const totalXP = baseXP + bonusXP;
    
    if (typeof sendReward === 'function') {
        sendReward('expert_exam', grade, examData.id, timeUsed, examData.questions.length)
            .then(result => {
                if (result.success) {
                    setTimeout(() => showXpToast(result.xpEarned), 1500);
                } else {
                    setTimeout(() => showXpToast(totalXP), 1500);
                }
            })
            .catch(() => {
                setTimeout(() => showXpToast(totalXP), 1500);
            });
    } else {
        setTimeout(() => showXpToast(totalXP), 1500);
    }
}

// ══════════════════════════════════════════════════════════════
// FIX: Actualizar botones de resultados según modo
// ══════════════════════════════════════════════════════════════
function updateResultButtons() {
    const actionsContainer = document.querySelector('.results-actions');
    if (!actionsContainer) return;
    
    const hasErrors = examData.questions.some((q, i) => answers[i] !== q.correct);
    
    let buttonsHTML = `
        <button class="btn-result btn-retry" onclick="retryExam()">
            <i class="fas fa-redo"></i> ${isReviewMode ? 'Repasar de nuevo' : 'Intentar de nuevo'}
        </button>
    `;
    
    // Solo mostrar "Repasar errores" si hay errores y NO estamos ya en modo repaso
    if (hasErrors && !isReviewMode) {
        buttonsHTML += `
            <button class="btn-result btn-review" onclick="reviewMistakes()">
                <i class="fas fa-search"></i> Repasar errores (${incorrectCount + answers.filter(a => a === null).length})
            </button>
        `;
    }
    
    // Si estamos en modo repaso, mostrar opción de volver al examen completo
    if (isReviewMode) {
        buttonsHTML += `
            <button class="btn-result btn-full" onclick="restartFullExam()">
                <i class="fas fa-list"></i> Examen completo
            </button>
        `;
    }
    
    buttonsHTML += `
        <button class="btn-result btn-exit" onclick="exitExam()">
            <i class="fas fa-home"></i> Volver al inicio
        </button>
    `;
    
    actionsContainer.innerHTML = buttonsHTML;
}

// ══════════════════════════════════════════════════════════════
// FIX: Función para repasar solo los errores (como flashcards)
// ══════════════════════════════════════════════════════════════
function reviewMistakes() {
    // Filtrar solo las preguntas que se respondieron mal o no se respondieron
    const wrongQuestions = [];
    examData.questions.forEach((q, i) => {
        if (answers[i] === null || answers[i] !== q.correct) {
            wrongQuestions.push(q);
        }
    });
    
    if (wrongQuestions.length === 0) {
        alert('¡No tienes errores que repasar!');
        return;
    }
    
    // Activar modo repaso
    isReviewMode = true;
    
    // Actualizar examData con solo las preguntas incorrectas
    examData.questions = wrongQuestions;
    
    // Resetear estado
    currentQuestion = 0;
    answers = new Array(examData.questions.length).fill(null);
    totalSeconds = examData.timeMinutes * 60;
    alertShownAt2min = false; 
    alertShownAt1min = false; 
    examFinished = false;
    correctCount = 0; 
    incorrectCount = 0;
    
    // Actualizar UI
    document.getElementById('gradeFill').style.strokeDashoffset = '471.24';
    document.getElementById('gradeNumber').textContent = '0%';
    document.getElementById('resultsScreen').classList.remove('active');
    document.getElementById('examScreen').classList.remove('hidden');
    
    // Actualizar título para indicar modo repaso
    document.getElementById('examTitleBar').textContent = `${originalExamData.title} — Repaso de errores 🔄`;
    document.getElementById('totalQ').textContent = examData.questions.length;
    
    renderQuestion(); 
    renderDots();
    startTimer();
}

// ══════════════════════════════════════════════════════════════
// FIX: Función para reiniciar el examen actual (mismo set de preguntas)
// ══════════════════════════════════════════════════════════════
function retryExam() {
    currentQuestion = 0;
    answers = new Array(examData.questions.length).fill(null);
    totalSeconds = examData.timeMinutes * 60;
    alertShownAt2min = false; 
    alertShownAt1min = false; 
    examFinished = false;
    correctCount = 0; 
    incorrectCount = 0;
    
    document.getElementById('gradeFill').style.strokeDashoffset = '471.24';
    document.getElementById('gradeNumber').textContent = '0%';
    document.getElementById('resultsScreen').classList.remove('active');
    document.getElementById('examScreen').classList.remove('hidden');
    
    // Mantener título según modo
    if (isReviewMode) {
        document.getElementById('examTitleBar').textContent = `${originalExamData.title} — Repaso de errores 🔄`;
    } else {
        document.getElementById('examTitleBar').textContent = examData.title;
    }
    
    renderQuestion(); 
    renderDots();
    startTimer();
}

// ══════════════════════════════════════════════════════════════
// FIX: Función para reiniciar examen completo desde modo repaso
// ══════════════════════════════════════════════════════════════
function restartFullExam() {
    // Restaurar datos originales
    isReviewMode = false;
    examData = JSON.parse(JSON.stringify(originalExamData));
    
    currentQuestion = 0;
    answers = new Array(examData.questions.length).fill(null);
    totalSeconds = examData.timeMinutes * 60;
    alertShownAt2min = false; 
    alertShownAt1min = false; 
    examFinished = false;
    correctCount = 0; 
    incorrectCount = 0;
    
    document.getElementById('gradeFill').style.strokeDashoffset = '471.24';
    document.getElementById('gradeNumber').textContent = '0%';
    document.getElementById('resultsScreen').classList.remove('active');
    document.getElementById('examScreen').classList.remove('hidden');
    document.getElementById('examTitleBar').textContent = examData.title;
    document.getElementById('totalQ').textContent = examData.questions.length;
    
    renderQuestion(); 
    renderDots();
    startTimer();
}

// ===== HELPERS =====
function formatTime(seconds) {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
}

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

// ===== KEYBOARD =====
document.getElementById('exitModal').addEventListener('click', function(e){ if(e.target===this) this.classList.remove('show'); });
document.getElementById('submitModal').addEventListener('click', function(e){ if(e.target===this) this.classList.remove('show'); });
document.addEventListener('keydown', function(e) {
    if (examFinished) return;
    if (e.key === 'Escape') { closeExitModal(); closeSubmitModal(); closeTimerAlert(); }
    if (e.key === 'ArrowRight') nextQuestion();
    if (e.key === 'ArrowLeft') prevQuestion();
    const km = {'a':0,'b':1,'c':2,'d':3};
    if (km[e.key.toLowerCase()] !== undefined) {
        const idx = km[e.key.toLowerCase()];
        if (idx < examData.questions[currentQuestion].options.length) selectOption(idx);
    }
});

initExam();