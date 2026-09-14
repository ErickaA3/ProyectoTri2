/* ===== CHAT.JS - Mi ProfesorIA — Rediseño Cósmico ===== */

const API_BASE = window.API_BASE || '';

let chatHistory    = [];
let currentSession = null;
let attachedPdf    = null;  // { name, base64 } cuando hay PDF adjunto
let busy           = false;
let msgCount       = 0;

const BOT_AV = `<div class="msg-av bot-av"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#2dd4bf" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg></div>`;

const SUBJECT_MAP = {
    math:    ['matemática','álgebra','cálculo','geometría','ecuación','matriz','función','derivada','integral','estadística','trigonometría','número','fracción'],
    history: ['historia','guerra','revolución','imperio','civilización','siglo','rey','presidente','política','sociedad','cultura','antiguo','medieval'],
    science: ['física','química','biología','célula','molécula','átomo','energía','fuerza','ecosistema','evolución','genética','laboratorio','experimento'],
    code:    ['programación','código','función','variable','algoritmo','javascript','java','python','html','css','base de datos','sql','api','software'],
};
function detectTheme(text) {
    const t = text.toLowerCase();
    for (const [theme, words] of Object.entries(SUBJECT_MAP)) {
        if (words.some(w => t.includes(w))) return theme;
    }
    return 'default';
}
function applyTheme(theme) {
    const main = document.getElementById('chatMain');
    if (!main) return;
    main.classList.remove('theme-math','theme-history','theme-science','theme-code','theme-default');
    main.classList.add('theme-' + theme);
}

function initStars() {
    const main = document.getElementById('chatMain');
    if (!main) return;
    for (let i = 0; i < 80; i++) {
        const s = document.createElement('div');
        const sz = Math.random() * 2.5 + 1;
        s.className = 'chat-star';
        s.style.cssText = `width:${sz}px;height:${sz}px;top:${Math.random()*100}%;left:${Math.random()*100}%;--d:${2+Math.random()*4}s;--delay:${-Math.random()*4}s;`;
        main.appendChild(s);
    }
}

const SUGGESTIONS = {
    math:    ['¿Puedes dar un ejemplo?','¿Cómo se resuelve paso a paso?','¿Para qué sirve en la vida real?'],
    history: ['¿Cuál fue el impacto principal?','¿Quiénes fueron los protagonistas?','¿Qué pasó después?'],
    science: ['¿Puedes explicarlo más simple?','Dame un experimento práctico','¿Cómo se relaciona con la vida diaria?'],
    code:    ['¿Me muestras el código?','¿Cuáles son los errores comunes?','¿Qué alternativas existen?'],
    default: ['Explícame más','Dame un ejemplo','¿Hay algo que deba saber antes?'],
};
function showSuggestions(theme) {
    const bar = document.getElementById('suggestionsBar');
    if (!bar) return;
    const chips = SUGGESTIONS[theme] || SUGGESTIONS.default;
    bar.innerHTML = chips.map((c, i) =>
        `<div class="sug-chip" style="animation-delay:${i*0.08}s" onclick="sendSuggestion('${c}')">${c}</div>`
    ).join('');
}
function hideSuggestions() {
    const bar = document.getElementById('suggestionsBar');
    if (bar) bar.innerHTML = '';
}
function sendSuggestion(text) {
    hideSuggestions();
    document.getElementById('userInput').value = text;
    send();
}

function shootConfetti() {
    const wrap = document.getElementById('confettiWrap');
    if (!wrap) return;
    const colors = ['#8b5cf6','#2dd4bf','#f59e0b','#ec4899','#22c55e'];
    for (let i = 0; i < 28; i++) {
        const c = document.createElement('div');
        c.className = 'conf-piece';
        c.style.cssText = `left:${20+Math.random()*60}%;background:${colors[i%5]};--dur:${0.9+Math.random()*1.1}s;--delay:${Math.random()*0.3}s;`;
        wrap.appendChild(c);
        setTimeout(() => c.remove(), 2200);
    }
}

function showXpPopup(amount) {
    const main = document.getElementById('chatMain');
    if (!main) return;
    const el = document.createElement('div');
    el.className = 'xp-popup';
    el.textContent = `+${amount} XP ✦`;
    el.style.cssText = `right:${60+Math.random()*40}px;bottom:${100+Math.random()*60}px;`;
    main.appendChild(el);
    setTimeout(() => el.remove(), 1700);
}

function addCompRow(msgDiv) {
    const comp = document.createElement('div');
    comp.className = 'comp-row';
    comp.innerHTML = `
        <button class="comp-btn" onclick="comprendo('si', this)">✅ Lo entendí</button>
        <button class="comp-btn" onclick="comprendo('mas', this)">🤔 Más o menos</button>
        <button class="comp-btn" onclick="comprendo('no', this)">❌ No entendí</button>`;
    msgDiv.querySelector('.msg-body').appendChild(comp);
}
function comprendo(r, btn) {
    btn.closest('.comp-row').querySelectorAll('.comp-btn').forEach(b => b.disabled = true);
    btn.classList.add('picked');
    const res = {
        si:  '¡Genial! Sigamos avanzando 🚀',
        mas: 'Sin problema — lo reformulo desde otro ángulo.',
        no:  'Vamos de cero, paso a paso. Sin apuro 💪',
    };
    setTimeout(() => addBotMsg(res[r]), 400);
    if (r === 'si') { shootConfetti(); showXpPopup(10); }
}

function getUserId() {
    try { return JSON.parse(localStorage.getItem('user') || '{}').id || null; }
    catch(e) { return null; }
}

// ── JWT + JSON headers (de develop) ────────────────────────
function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    try {
        const token = localStorage.getItem('token');
        if (token) headers['Authorization'] = `Bearer ${token}`;
    } catch(e) {}
    return headers;
}

document.addEventListener('DOMContentLoaded', () => {
    try {
        const user = JSON.parse(localStorage.getItem('user') || '{}');
        const initials = (user.username || 'U').substring(0, 2).toUpperCase();
        document.getElementById('chatAvatar').textContent      = initials;
        document.getElementById('chatProfileName').textContent = user.fullName || user.username || 'Usuario';
    } catch(e) {}

    document.getElementById('initTime').textContent = fmt(new Date());
    initStars();
    applyTheme('default');

    if (!getUserId()) {
        PolarisLoading.hide('chatLoading');
        addBotMsg('No se detectó sesión activa. <a href="../index.html" style="color:#2dd4bf;">Inicia sesión</a> para usar el chat.');
        document.getElementById('sendBtn').disabled = true;
        return;
    }

    const _ct = PolarisLoading.rotateMessages('chatLoadingSub',
        ['Iniciando chat...', 'Cargando historial...', 'Conectando...']);
    Promise.allSettled([loadSessions(), loadEquippedBackground()])
        .finally(() => { clearInterval(_ct); PolarisLoading.hide('chatLoading'); });

    setTimeout(() => showSuggestions('default'), 1200);
});

async function send() {
    const inp = document.getElementById('userInput');
    const txt = inp.value.trim();
    if (!txt || busy) return;

    const userId = getUserId();
    if (!userId) {
        addBotMsg('Tu sesión expiró. <a href="../index.html" style="color:#2dd4bf;">Inicia sesión de nuevo</a>.');
        return;
    }

    hideSuggestions();
    addMsg('user', txt);
    inp.value = '';
    inp.style.height = 'auto';
    setTyping(true);

    const theme = detectTheme(txt);
    if (theme !== 'default') applyTheme(theme);
    msgCount++;

    try {
        const res = await fetch(`${API_BASE}/api/chat`, {
            method: 'POST',
            headers: getAuthHeaders(),
            credentials: 'include',
            body: JSON.stringify(Object.assign({ mensaje: txt, sessionId: currentSession, userId }, attachedPdf ? { pdfBase64: attachedPdf.base64, pdfName: attachedPdf.name } : {}))
        });

        const data = await res.json();
        setTyping(false);

        if (!data.success) {
            addBotMsg('Hubo un error: ' + (data.message || 'Intenta de nuevo.'));
            return;
        }

        // Limpiar PDF adjunto después de enviar
        removePdf();

        if (!currentSession) {
            currentSession = data.data.sessionId;
            addSessionToSidebar(txt, currentSession, true, new Date().toISOString());
        }

        const reply = formatReply(data.data.reply);
        const msgDiv = addBotMsg(reply);

        if (msgCount % 2 === 0 && msgDiv) addCompRow(msgDiv);
        setTimeout(() => showSuggestions(theme), 600);
        showXpPopup(5);

    } catch(e) {
        setTyping(false);
        addBotMsg('Error de conexión. Verifica que el servidor esté activo.');
        console.error(e);
    }
}

function newChat() {
    currentSession = null;
    chatHistory    = [];
    msgCount       = 0;

    const area   = document.getElementById('msgsArea');
    const typing = document.getElementById('typingRow');
    [...area.children].forEach(c => { if (c !== typing) c.remove(); });

    applyTheme('default');
    addBotMsg('¡Nuevo chat iniciado! ¿En qué tema te puedo ayudar?');
    document.querySelectorAll('.chat-item').forEach(c => c.classList.remove('active'));
    setTimeout(() => showSuggestions('default'), 400);
}

async function loadSessions() {
    const userId = getUserId();
    if (!userId) return;
    try {
        const res = await fetch(`${API_BASE}/api/chat?userId=${userId}`, {
            headers: getAuthHeaders(),
            credentials: 'include'
        });
        if (!res.ok) return;
        const data = await res.json();
        if (!data.success) return;

        const sessions  = data.data;
        const container = document.querySelector('.recents');
        container.querySelectorAll('.chat-item').forEach(el => el.remove());

        if (!sessions || sessions.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-sessions';
            empty.textContent = 'No hay chats recientes.';
            empty.style.cssText = 'color:var(--text-secondary);font-size:0.8rem;padding:1rem 0;text-align:center;';
            container.appendChild(empty);
            return;
        }
        sessions.forEach(s => addSessionToSidebar(s.firstMessage, s.sessionId, false, s.createdAt));
    } catch(e) { console.error('Error cargando sesiones:', e); }
}

// ── Fecha relativa (de develop) ─────────────────────────────
function formatSessionDate(isoString) {
    if (!isoString) return '';
    const fecha = new Date(isoString);
    if (isNaN(fecha.getTime())) return '';
    const hoy  = new Date();
    const same = (a, b) => a.getFullYear()===b.getFullYear() && a.getMonth()===b.getMonth() && a.getDate()===b.getDate();
    if (same(fecha, hoy)) return 'Hoy';
    const ayer = new Date(hoy); ayer.setDate(hoy.getDate()-1);
    if (same(fecha, ayer)) return 'Ayer';
    return fecha.toLocaleDateString('es', { day:'2-digit', month:'short' });
}

function addSessionToSidebar(firstMessage, sessionId, prepend = false, createdAt = null) {
    const container = document.querySelector('.recents');
    const empty = container.querySelector('.empty-sessions');
    if (empty) empty.remove();

    const theme = detectTheme(firstMessage || '');
    const iconColors = { math:'#3b82f6', history:'#f59e0b', science:'#10b981', code:'#2dd4bf', default:'#8b5cf6' };
    const iconColor = iconColors[theme] || iconColors.default;

    const item = document.createElement('div');
    item.className = 'chat-item';
    item.dataset.sessionId = sessionId;
    item.style.setProperty('--dot-color', iconColor);
    item.innerHTML = `
        <div class="chat-item-meta">
            <div class="chat-item-label">${esc(firstMessage ? firstMessage.substring(0,35) : 'Chat')}${firstMessage && firstMessage.length>35?'...':''}</div>
            <div class="chat-item-date">${formatSessionDate(createdAt)}</div>
        </div>
        <button class="chat-item-delete" title="Eliminar chat">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
        </button>`;
    item.addEventListener('click', (e) => {
        if (e.target.closest('.chat-item-delete')) return;
        selectSession(item, sessionId);
    });
    item.querySelector('.chat-item-delete').addEventListener('click', (e) => {
        e.stopPropagation();
        showDeleteConfirm(sessionId, item);
    });

    if (prepend) {
        const label = container.querySelector('.section-label');
        if (label && label.nextSibling) container.insertBefore(item, label.nextSibling);
        else container.appendChild(item);
    } else {
        container.appendChild(item);
    }
}

let pendingDeleteSessionId = null;
let pendingDeleteElement   = null;

function showDeleteConfirm(sessionId, el) {
    pendingDeleteSessionId = sessionId;
    pendingDeleteElement   = el;
    const label = el.querySelector('.chat-item-label').textContent;
    const modal = document.getElementById('deleteConfirmModal');
    modal.querySelector('.delete-chat-name').textContent = label;
    modal.classList.add('show');
}
function closeDeleteModal() {
    document.getElementById('deleteConfirmModal').classList.remove('show');
    pendingDeleteSessionId = null;
    pendingDeleteElement   = null;
}
async function confirmDeleteChat() {
    if (!pendingDeleteSessionId) return;
    const userId = getUserId();
    if (!userId) return;
    try {
        const res = await fetch(`${API_BASE}/api/chat?sessionId=${pendingDeleteSessionId}&userId=${userId}`, {
            method: 'DELETE',
            headers: getAuthHeaders(),
            credentials: 'include'
        });
        const data = await res.json();
        if (data.success) {
            if (pendingDeleteElement) pendingDeleteElement.remove();
            if (currentSession === pendingDeleteSessionId) newChat();
            if (document.querySelectorAll('.chat-item').length === 0) {
                const container = document.querySelector('.recents');
                const empty = document.createElement('div');
                empty.className = 'empty-sessions';
                empty.textContent = 'No hay chats recientes.';
                empty.style.cssText = 'color:var(--text-secondary);font-size:0.8rem;padding:1rem 0;text-align:center;';
                container.appendChild(empty);
            }
        }
    } catch(e) { console.error('Error eliminando chat:', e); }
    closeDeleteModal();
}

async function selectSession(el, sessionId) {
    document.querySelectorAll('.chat-item').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    currentSession = sessionId;
    const userId = getUserId();
    if (!userId) return;
    try {
        const res = await fetch(`${API_BASE}/api/chat?sessionId=${sessionId}&userId=${userId}`, {
            headers: getAuthHeaders(),
            credentials: 'include'
        });
        const data = await res.json();
        if (!data.success) return;

        const area   = document.getElementById('msgsArea');
        const typing = document.getElementById('typingRow');
        [...area.children].forEach(c => { if (c !== typing) c.remove(); });

        const firstUserMsg = data.data.find(m => m.role === 'user');
        if (firstUserMsg) applyTheme(detectTheme(firstUserMsg.message || firstUserMsg.content || ''));

        data.data.forEach(msg => {
            const texto = msg.message || msg.content || '';
            if (msg.role === 'user') addMsg('user', texto);
            else addBotMsg(formatReply(texto));
        });
    } catch(e) { console.error('Error cargando historial:', e); }
}

function addBotMsg(txt) { return addMsg('bot', txt); }

function addMsg(role, txt) {
    const area   = document.getElementById('msgsArea');
    const typing = document.getElementById('typingRow');
    const div    = document.createElement('div');
    div.className = `msg ${role}`;
    const initials = document.getElementById('chatAvatar').textContent || 'U';
    if (role === 'bot') {
        div.innerHTML = `${BOT_AV}<div class="msg-body"><div class="msg-who">Mi ProfesorIA</div><div class="bubble bot-bubble">${txt}</div><div class="msg-time">${fmt(new Date())}</div></div>`;
    } else {
        div.innerHTML = `<div class="msg-av user-av">${initials}</div><div class="msg-body"><div class="msg-who" style="text-align:right">Tú</div><div class="bubble">${esc(txt)}</div><div class="msg-time">${fmt(new Date())}</div></div>`;
    }
    area.insertBefore(div, typing);
    scroll();
    return div;
}

function setTyping(on) {
    busy = on;
    const tr = document.getElementById('typingRow');
    document.getElementById('sendBtn').disabled = on;
    if (on) tr.classList.add('show');
    else    tr.classList.remove('show');
    scroll();
}

function scroll() {
    const a = document.getElementById('msgsArea');
    setTimeout(() => a.scrollTop = a.scrollHeight, 60);
}

function fmt(d) { return d.toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' }); }

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
}

function handleKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
}

// ── Fix de seguridad (de develop): escapa HTML antes del formato ──
function formatReply(txt) {
    if (!txt) return '';
    let safe = txt.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    return safe
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>');
}

function esc(t) {
    if (!t) return '';
    return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>');
}

const BG_CLASS_MAP = {
    'Noche Oscura':'bg-default','Galaxia':'bg-galaxy','Volcán':'bg-volcano',
    'Océano':'bg-ocean','Amazonas':'bg-forest','Cielo Nocturno':'bg-sky',
    'Lluvia Digital':'bg-rain','Aurora Boreal':'bg-aurora'
};
async function loadEquippedBackground() {
    const userId = getUserId();
    if (!userId) return;
    try {
        const res = await fetch(`${API_BASE}/shop`, { headers: getAuthHeaders(), credentials: 'include' });
        if (!res.ok) return;
        const data = await res.json();
        if (!data.success || !data.equippedBackgroundId) return;
        const item = (data.items || []).find(i => i.id === data.equippedBackgroundId);
        if (!item) return;
        const bgClass = BG_CLASS_MAP[item.name];
        if (!bgClass) return;
        const content = document.querySelector('.content');
        if (content) {
            content.classList.remove('bg-galaxy','bg-volcano','bg-ocean','bg-forest','bg-aurora','bg-sky','bg-rain');
            if (bgClass !== 'bg-default') content.classList.add(bgClass);
        }
    } catch(e) { console.error('[Chat] Error cargando fondo equipado:', e); }
}

document.addEventListener('click', function(e) {
    if (e.target.id === 'deleteConfirmModal') closeDeleteModal();
    if (e.target.id === 'wrappedModal') closeWrapped();
});

// ── Resumen semanal (Wrapped) ────────────────────────────────
const WRAPPED_SLIDES = ['ws1','ws2','ws3','wsfreq','ws4','ws5'];
let wrappedIdx = 0;
let wrappedDataLoaded = false;

function buildWrappedNav() {
    const nav = document.getElementById('wrappedNav');
    if (!nav || nav.children.length) return;
    WRAPPED_SLIDES.forEach((_, i) => {
        const d = document.createElement('div');
        d.className = 'wdot' + (i === 0 ? ' active' : '');
        nav.appendChild(d);
    });
}
function renderWrappedSlide() {
    WRAPPED_SLIDES.forEach((id, i) => {
        const el = document.getElementById(id);
        if (el) el.classList.toggle('show', i === wrappedIdx);
    });
    const nav = document.getElementById('wrappedNav');
    if (nav) [...nav.children].forEach((d, i) => d.classList.toggle('active', i === wrappedIdx));
}
function nextWrappedSlide() { wrappedIdx = Math.min(wrappedIdx + 1, WRAPPED_SLIDES.length - 1); renderWrappedSlide(); }
function prevWrappedSlide() { wrappedIdx = Math.max(wrappedIdx - 1, 0); renderWrappedSlide(); }

async function loadWrappedData() {
    const userId = getUserId();
    let data = {
        studyHours: '--', studyCompare: 'Aún no hay suficientes datos.',
        topSubject: 'Sin datos', topPct: '', weakSubject: 'Sin datos', questionsCount: '0',
        freqTopic: 'Sin datos aún', freqDetail: 'Pregúntale más cosas a tu asistente esta semana.',
        streak: '0 días', xp: '+0 XP', coins: '+0 monedas',
        suggestionTitle: 'Sigue chateando esta semana',
        suggestionText: 'Cuantas más dudas resuelvas, más preciso será tu resumen la próxima semana.'
    };
    try {
        if (userId) {
            const res = await fetch(`${API_BASE}/api/wrapped?userId=${userId}`, {
                headers: getAuthHeaders(), credentials: 'include'
            });
            if (res.ok) {
                const json = await res.json();
                if (json.success && json.data) data = { ...data, ...json.data };
            }
        }
    } catch(e) { console.error('[Wrapped] usando datos de respaldo:', e); }

    document.getElementById('wStudyTime').textContent       = data.studyHours;
    document.getElementById('wStudyCompare').textContent    = data.studyCompare;
    document.getElementById('wTopSubject').textContent      = data.topSubject;
    document.getElementById('wTopPct').textContent          = data.topPct;
    document.getElementById('wWeakSubject').textContent     = data.weakSubject;
    document.getElementById('wQuestionsCount').textContent  = data.questionsCount;
    document.getElementById('wFreqTopic').textContent       = data.freqTopic;
    document.getElementById('wFreqDetail').textContent      = data.freqDetail;
    document.getElementById('wStreak').textContent          = data.streak;
    document.getElementById('wXp').textContent              = data.xp;
    document.getElementById('wCoins').textContent           = data.coins;
    document.getElementById('wSuggestionTitle').textContent = data.suggestionTitle;
    document.getElementById('wSuggestionText').textContent  = data.suggestionText;
    wrappedDataLoaded = true;
}

function openWrapped() {
    buildWrappedNav();
    wrappedIdx = 0;
    renderWrappedSlide();
    document.getElementById('wrappedModal').classList.add('show');
    if (!wrappedDataLoaded) loadWrappedData();
}
function closeWrapped() {
    document.getElementById('wrappedModal').classList.remove('show');
}

async function shareWrappedCard(e) {
    e.stopPropagation();
    const status = document.getElementById('wrappedShareStatus');
    if (typeof html2canvas === 'undefined') {
        status.textContent = 'No se pudo cargar el generador de imagen.';
        return;
    }
    status.textContent = 'Generando imagen...';
    const target = document.getElementById('ws5');
    try {
        const canvas = await html2canvas(target, { backgroundColor: null, scale: 2 });
        canvas.toBlob(async (blob) => {
            const file = new File([blob], 'mi-resumen-profesoria.png', { type: 'image/png' });
            if (navigator.canShare && navigator.canShare({ files: [file] })) {
                try {
                    await navigator.share({ files: [file], title: 'Mi resumen semanal' });
                    status.textContent = '¡Compartido!';
                } catch(err) { status.textContent = 'Cancelado.'; }
            } else {
                const link = document.createElement('a');
                link.href = URL.createObjectURL(blob);
                link.download = 'mi-resumen-profesoria.png';
                link.click();
                status.textContent = 'Imagen descargada.';
            }
        }, 'image/png');
    } catch(err) {
        status.textContent = 'No se pudo generar la imagen.';
        console.error('[Wrapped] Error al compartir:', err);
    }
}
// ── PDF Upload ───────────────────────────────────────────────────────────────

function handlePdfUpload(event) {
    const file = event.target.files[0];
    if (!file) return;

    if (file.size > 10 * 1024 * 1024) {
        addBotMsg('⚠️ El PDF es demasiado grande. Máximo 10 MB.');
        event.target.value = '';
        return;
    }

    const reader = new FileReader();
    reader.onload = function(e) {
        const base64 = e.target.result.split(',')[1];
        attachedPdf = { name: file.name, base64: base64 };

        document.getElementById('pdfAttachedName').textContent = '📄 ' + file.name;
        document.getElementById('pdfAttachedBar').style.display = 'flex';
        document.getElementById('pdfBtn').style.opacity = '0.4';
        document.getElementById('pdfBtn').style.pointerEvents = 'none';
        document.getElementById('userInput').placeholder = '¿Qué querés saber sobre el PDF?';
        document.getElementById('userInput').focus();
    };
    reader.readAsDataURL(file);
    event.target.value = '';
}

function removePdf() {
    attachedPdf = null;
    document.getElementById('pdfAttachedBar').style.display = 'none';
    document.getElementById('pdfAttachedName').textContent = '';
    document.getElementById('pdfBtn').style.opacity = '';
    document.getElementById('pdfBtn').style.pointerEvents = '';
    const fi = document.getElementById('pdfFileInput');
    if (fi) fi.value = '';
    document.getElementById('userInput').placeholder = 'Escríbeme tu pregunta...';
}