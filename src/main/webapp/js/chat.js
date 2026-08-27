const API_BASE = window.API_BASE || '';

let chatHistory   = [];
let currentSession = null;
let busy          = false;

const BOT_AV = `<div class="msg-av bot-av"><svg width="20" height="20" viewBox="0 0 100 100" fill="none"><polygon points="50,5 33,36 67,36" fill="#8b5cf6"/><ellipse cx="50" cy="36" rx="17" ry="5" fill="#6d28d9"/><ellipse cx="50" cy="67" rx="24" ry="27" fill="#6d28d9"/><ellipse cx="50" cy="70" rx="14" ry="18" fill="#8b5cf6" opacity="0.6"/><circle cx="36" cy="55" r="9" fill="#1a1a35"/><circle cx="64" cy="55" r="9" fill="#1a1a35"/><circle cx="36" cy="55" r="6.5" fill="#2dd4bf" opacity="0.9"/><circle cx="64" cy="55" r="6.5" fill="#2dd4bf" opacity="0.9"/><circle cx="36" cy="55" r="4" fill="#0f0f23"/><circle cx="64" cy="55" r="4" fill="#0f0f23"/><polygon points="50,62 44,68 56,68" fill="#f97316"/></svg></div>`;

// ─── Helper: obtener userId del localStorage ─────────────────
function getUserId() {
    try {
        const user = JSON.parse(localStorage.getItem('user') || '{}');
        return user.id || null;
    } catch(e) {
        return null;
    }
}

// ─── Init ────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    // Cargar datos del usuario
    try {
        const user = JSON.parse(localStorage.getItem('user') || '{}');
        const initials = (user.username || 'U').substring(0, 2).toUpperCase();
        document.getElementById('chatAvatar').textContent      = initials;
        document.getElementById('chatProfileName').textContent = user.fullName || user.username || 'Usuario';
    } catch(e) {}

    document.getElementById('initTime').textContent = fmt(new Date());

    // Verificar que el usuario está logueado
    if (!getUserId()) {
        PolarisLoading.hide('chatLoading');
        addBotMsg('No se detectó sesión activa. <a href="../index.html" style="color:#2dd4bf;">Inicia sesión</a> para usar el chat.');
        document.getElementById('sendBtn').disabled = true;
        return;
    }

    const _ct = PolarisLoading.rotateMessages('chatLoadingSub',
        ['Iniciando chat...', 'Cargando historial...', 'Conectando con el búho...']);
    Promise.allSettled([loadSessions(), loadEquippedBackground()])
        .finally(() => { clearInterval(_ct); PolarisLoading.hide('chatLoading'); });
});

// ─── Enviar mensaje ──────────────────────────────────────────
async function send() {
    const inp = document.getElementById('userInput');
    const txt = inp.value.trim();
    if (!txt || busy) return;

    const userId = getUserId();
    if (!userId) {
        addBotMsg('Tu sesión expiró. <a href="../index.html" style="color:#2dd4bf;">Inicia sesión de nuevo</a>.');
        return;
    }

    addMsg('user', txt);
    inp.value = '';
    inp.style.height = 'auto';
    setTyping(true);

    try {
        const res = await fetch(`${API_BASE}/api/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({
                mensaje: txt,
                sessionId: currentSession,
                userId: userId
            })
        });

        const data = await res.json();
        setTyping(false);

        if (!data.success) {
            addBotMsg('Hubo un error: ' + (data.message || 'Intenta de nuevo.'));
            return;
        }

        // Guardar sessionId si es nuevo chat y agregar al top del sidebar
        if (!currentSession) {
            currentSession = data.data.sessionId;
            // [FIX] prepend=true para que el nuevo chat aparezca arriba
            addSessionToSidebar(txt, currentSession, true);
        }

        // Formatear respuesta (convertir **negrita** a <strong>)
        const reply = formatReply(data.data.reply);
        addBotMsg(reply);

    } catch(e) {
        setTyping(false);
        addBotMsg('Error de conexión. Verifica que el servidor esté activo.');
        console.error(e);
    }
}

// ─── Nuevo chat ──────────────────────────────────────────────
function newChat() {
    currentSession = null;
    chatHistory    = [];

    const area   = document.getElementById('msgsArea');
    const typing = document.getElementById('typingRow');
    [...area.children].forEach(c => { if (c !== typing) c.remove(); });

    addBotMsg('¡Nuevo chat iniciado! ¿En qué tema te puedo ayudar?');
    document.querySelectorAll('.chat-item').forEach(c => c.classList.remove('active'));
}

// ─── Cargar sesiones del historial ──────────────────────────
async function loadSessions() {
    const userId = getUserId();
    if (!userId) return;

    try {
        const res = await fetch(`${API_BASE}/api/chat?userId=${userId}`, {
            method: 'GET',
            credentials: 'include'
        });

        if (!res.ok) return;
        const data = await res.json();
        if (!data.success) return;

        const sessions = data.data;
        const container = document.querySelector('.recents');

        // Limpiar items hardcodeados
        container.querySelectorAll('.chat-item').forEach(el => el.remove());

        if (!sessions || sessions.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-sessions';
            empty.textContent = 'No hay chats recientes.';
            empty.style.cssText = 'color:var(--text-secondary);font-size:0.8rem;padding:1rem 0;text-align:center;';
            container.appendChild(empty);
            return;
        }

        // Las sesiones vienen de la API ordenadas DESC (más recientes primero).
        // Usamos append (prepend=false) para respetar ese orden en el sidebar.
        sessions.forEach(s => {
            addSessionToSidebar(s.firstMessage, s.sessionId, false);
        });

    } catch(e) {
        console.error('Error cargando sesiones:', e);
    }
}

// ─── Agregar sesión al sidebar ───────────────────────────────
// [FIX] prepend=true → inserta arriba (debajo del label) para nuevos chats
//       prepend=false → append al final (para cargar historial en orden)
function addSessionToSidebar(firstMessage, sessionId, prepend = false) {
    const container = document.querySelector('.recents');

    // Quitar mensaje de "no hay chats"
    const empty = container.querySelector('.empty-sessions');
    if (empty) empty.remove();

    const item = document.createElement('div');
    item.className = 'chat-item';
    item.dataset.sessionId = sessionId;
    item.innerHTML = `
        <div class="chat-item-icon">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2" stroke-linecap="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </div>
        <div class="chat-item-meta">
            <div class="chat-item-label">${esc(firstMessage ? firstMessage.substring(0, 35) : 'Chat')}${firstMessage && firstMessage.length > 35 ? '...' : ''}</div>
            <div class="chat-item-date">Hoy</div>
        </div>
        <button class="chat-item-delete" title="Eliminar chat">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
        </button>
    `;

    // Click en el item → cargar chat
    item.addEventListener('click', (e) => {
        if (e.target.closest('.chat-item-delete')) return;
        selectSession(item, sessionId);
    });
    // Click en la X → mostrar confirmación
    item.querySelector('.chat-item-delete').addEventListener('click', (e) => {
        e.stopPropagation();
        showDeleteConfirm(sessionId, item);
    });

    if (prepend) {
        // Insertar justo después del label "Chats recientes"
        const label = container.querySelector('.section-label');
        if (label && label.nextSibling) {
            container.insertBefore(item, label.nextSibling);
        } else {
            container.appendChild(item);
        }
    } else {
        container.appendChild(item);
    }
}

// ─── Eliminar sesión ─────────────────────────────────────────
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
            credentials: 'include'
        });
        const data = await res.json();

        if (data.success) {
            // Quitar del sidebar
            if (pendingDeleteElement) pendingDeleteElement.remove();

            // Si era el chat activo, limpiar
            if (currentSession === pendingDeleteSessionId) {
                newChat();
            }

            // Si no quedan chats, mostrar mensaje vacío
            const remaining = document.querySelectorAll('.chat-item');
            if (remaining.length === 0) {
                const container = document.querySelector('.recents');
                const empty = document.createElement('div');
                empty.className = 'empty-sessions';
                empty.textContent = 'No hay chats recientes.';
                empty.style.cssText = 'color:var(--text-secondary);font-size:0.8rem;padding:1rem 0;text-align:center;';
                container.appendChild(empty);
            }
        }
    } catch (e) {
        console.error('Error eliminando chat:', e);
    }

    closeDeleteModal();
}

// ─── Seleccionar sesión del historial ────────────────────────
async function selectSession(el, sessionId) {
    document.querySelectorAll('.chat-item').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    currentSession = sessionId;

    const userId = getUserId();
    if (!userId) return;

    try {
        const res = await fetch(`${API_BASE}/api/chat?sessionId=${sessionId}&userId=${userId}`, {
            credentials: 'include'
        });
        const data = await res.json();
        if (!data.success) return;

        // Limpiar mensajes actuales
        const area   = document.getElementById('msgsArea');
        const typing = document.getElementById('typingRow');
        [...area.children].forEach(c => { if (c !== typing) c.remove(); });

        // [FIX] El campo de chat_history en la DB es "message", no "content".
        // Se usa msg.message con fallback a msg.content para compatibilidad.
        data.data.forEach(msg => {
            const texto = msg.message || msg.content || '';
            if (msg.role === 'user') {
                addMsg('user', texto);
            } else {
                addBotMsg(formatReply(texto));
            }
        });

    } catch(e) {
        console.error('Error cargando historial:', e);
    }
}

// ─── Helpers UI ──────────────────────────────────────────────
function addBotMsg(txt) { addMsg('bot', txt); }

function addMsg(role, txt) {
    const area   = document.getElementById('msgsArea');
    const typing = document.getElementById('typingRow');
    const div    = document.createElement('div');
    div.className = `msg ${role}`;

    const initials = document.getElementById('chatAvatar').textContent || 'U';
    if (role === 'bot') {
        div.innerHTML = `${BOT_AV}<div class="msg-body"><div class="msg-who">Búho ProfesorIA</div><div class="bubble">${txt}</div><div class="msg-time">${fmt(new Date())}</div></div>`;
    } else {
        div.innerHTML = `<div class="msg-av user-av">${initials}</div><div class="msg-body"><div class="msg-who" style="text-align:right">Tú</div><div class="bubble">${esc(txt)}</div><div class="msg-time">${fmt(new Date())}</div></div>`;
    }
    area.insertBefore(div, typing);
    scroll();
}

function setTyping(on) {
    busy = on;
    const owl = document.getElementById('owlSvg');
    const tr  = document.getElementById('typingRow');
    document.getElementById('sendBtn').disabled = on;
    if (on) { owl.classList.add('talking'); tr.classList.add('show'); }
    else    { owl.classList.remove('talking'); tr.classList.remove('show'); }
    scroll();
}

function scroll() {
    const a = document.getElementById('msgsArea');
    setTimeout(() => a.scrollTop = a.scrollHeight, 60);
}

function fmt(d) {
    return d.toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' });
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
}

function handleKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
}

function selectChat(el) {
    document.querySelectorAll('.chat-item').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
}

function formatReply(txt) {
    if (!txt) return '';
    return txt
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>');
}

function esc(t) {
    if (!t) return '';
    return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>');
}

// ─── Cargar fondo equipado ───────────────────────────────────
const BG_CLASS_MAP = {
    'Noche Oscura':   'bg-default',
    'Galaxia':        'bg-galaxy',
    'Volcán':         'bg-volcano',
    'Océano':         'bg-ocean',
    'Amazonas':       'bg-forest',
    'Cielo Nocturno': 'bg-sky',
    'Lluvia Digital': 'bg-rain',
    'Aurora Boreal':  'bg-aurora'
};

async function loadEquippedBackground() {
    const userId = getUserId();
    if (!userId) return;

    try {
        const res = await fetch(`${API_BASE}/shop`, {
            credentials: 'include',
            headers: { 'X-User-Id': userId }
        });
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
            if (bgClass !== 'bg-default') {
                content.classList.add(bgClass);
            }
        }
        console.log('[Chat] Fondo equipado:', item.name, '→', bgClass);
    } catch (e) {
        console.error('[Chat] Error cargando fondo equipado:', e);
    }
}

// ─── Backdrop click para cerrar modales ──────────────────────
document.addEventListener('click', function(e) {
    if (e.target.id === 'deleteConfirmModal') closeDeleteModal();
});