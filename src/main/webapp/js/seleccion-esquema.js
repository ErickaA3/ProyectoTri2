// ============================================================
// seleccion-esquema.js
// Lógica del selector de tipo de esquema — Modo Estudio
// ============================================================

let selectedType = null;

// ── Flow Manager ─────────────────────────────────────────────
function getFlow()      { return JSON.parse(sessionStorage.getItem('modoEstudioFlow') || 'null'); }
function saveFlow(flow) { sessionStorage.setItem('modoEstudioFlow', JSON.stringify(flow)); }

function getNextPage(flow) {
    const CONFIG_PAGES = {
        esquemas: '../pages/seleccion-esquema.html',
        examenes: '../pages/config-examen.html'
    };
    // currentConfigIndex ya fue incrementado antes de llamar esta función
    const current = flow.currentConfigIndex;
    if (current < flow.configQueue.length) {
        return CONFIG_PAGES[flow.configQueue[current]];
    }
    return '../pages/sesion-estudio.html';
}

// ── Init ──────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    const flow = getFlow();
    if (!flow) {
        window.location.href = '../pages/modo-estudio.html';
        return;
    }

    const idx   = flow.configQueue.indexOf('esquemas');
    const total = flow.configQueue.length;
    if (idx >= 0) {
        document.getElementById('stepIndicator').innerHTML =
            `<span>Paso ${idx + 1} de ${total}</span>`;
    }

    initStars();
});

// ── Selección ────────────────────────────────────────────────
function selectEsquema(card, type) {
    document.querySelectorAll('.esquema-card').forEach(c => c.classList.remove('selected'));
    card.classList.add('selected');
    selectedType = type;
    document.getElementById('confirmBtn').disabled = false;
    const names = {
        jerarquico: 'Jerárquico',
        conceptual: 'Conceptual',
        timeline: 'Línea del tiempo',
        'causa-efecto': 'Causa y efecto',
        ciclico: 'Cíclico'
    };
    showToast(`Esquema ${names[type]} seleccionado`);
}

function confirmSelection() {
    if (!selectedType) return;

    const flow = getFlow();
    if (!flow) { window.location.href = '../pages/modo-estudio.html'; return; }

    flow.configs.esquemas = { tipo: selectedType };
    flow.currentConfigIndex++;
    saveFlow(flow);

    const btn = document.getElementById('confirmBtn');
    btn.disabled = true;
    document.getElementById('confirmBtnText').innerHTML =
        '<div style="width:20px;height:20px;border:3px solid rgba(45,212,191,0.3);border-top-color:#2dd4bf;border-radius:50%;animation:spin 1s linear infinite;display:inline-block;vertical-align:middle;margin-right:8px"></div> Guardando...';

    setTimeout(() => window.location.href = getNextPage(flow), 600);
}

function goBack(e) {
    e.preventDefault();
    const flow = getFlow();
    if (flow && flow.currentConfigIndex > 0) {
        flow.currentConfigIndex--;
        saveFlow(flow);
    }
    window.location.href = '../pages/modo-estudio.html';
}

// ── Toast ────────────────────────────────────────────────────
function showToast(msg) {
    const t = document.getElementById('toast');
    document.getElementById('toastMessage').textContent = msg;
    t.classList.add('show');
    clearTimeout(t._t);
    t._t = setTimeout(() => t.classList.remove('show'), 3000);
}

// ── Stars Canvas ─────────────────────────────────────────────
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
        color: ['#ffffff', '#ffffff', '#ffe9c4', '#d4f1ff', '#c4b5fd', '#aaddff'][Math.floor(Math.random() * 6)]
    }));

    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        stars.forEach(s => {
            s.x += s.speedX; s.y += s.speedY;
            if (s.x < 0) s.x = canvas.width;
            if (s.x > canvas.width) s.x = 0;
            if (s.y < 0) s.y = canvas.height;
            if (s.y > canvas.height) s.y = 0;
            s.opacity += s.opacityChange;
            if (s.opacity <= 0.05 || s.opacity >= 0.8) s.opacityChange *= -1;
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
    window.addEventListener('resize', resizeCanvas);
}