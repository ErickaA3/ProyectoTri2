/**
 * historial.js — versión corregida
 *
 * Fixes:
 *  1. TYPE_CONFIG incluye "schema", "expert_exam" y alias de quiz
 *  2. getDateLabel usa fecha LOCAL (no UTC) — evita el doble "Hoy"
 *  3. Favoritos: usa POST con header X-HTTP-Method-Override en lugar de PATCH
 *  4. Paginación: muestra 20 ítems con botón "Ver más"
 *  5. openItem navega correctamente según tipo
 */

const API = '/project-1.0-SNAPSHOT/api/historial';

// ─────────────────────────────────────────────────────────────────────────────
// CONFIGURACIÓN POR TIPO
// Claves = valores exactos que guarda la BD en la columna "type"
// ─────────────────────────────────────────────────────────────────────────────
const TYPE_CONFIG = {
    flashcard:   { label: 'Flashcard',      icon: 'fas fa-layer-group',     page: '../pages/flashcards.html'     },
    schema:      { label: 'Esquema',        icon: 'fas fa-project-diagram', page: '../pages/sesion-estudio.html' },
    summary:     { label: 'Resumen',        icon: 'fas fa-file-alt',        page: '../pages/resumenes.html'      },
    quiz:        { label: 'Quiz',           icon: 'fas fa-clipboard-list',  page: '../pages/examen-quiz.html'    },
    expert_exam: { label: 'Examen Experto', icon: 'fas fa-file-signature',  page: '../pages/examen-experto.html' },
};

const PAGE_SIZE = 20;

let allItems        = [];
let visibleCount    = PAGE_SIZE;
let currentView     = 'recientes';
let currentTypeFilter = null;   // null = grilla de tipos; string = tipo seleccionado
let itemToDelete    = null;

// ─────────────────────────────────────────────────────────────────────────────
// AUTH
// ─────────────────────────────────────────────────────────────────────────────
function getUserId() {
    try { return JSON.parse(localStorage.getItem('user'))?.id || null; }
    catch (_) { return null; }
}

function authHeaders() {
    const uid = getUserId();
    return { 'Content-Type': 'application/json', ...(uid ? { 'X-User-Id': uid } : {}) };
}

// ─────────────────────────────────────────────────────────────────────────────
// INICIALIZACIÓN
// ─────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    setupListeners();
    const _ht = PolarisLoading.rotateMessages('historialLoadingSub',
        ['Cargando historial...', 'Obteniendo tu actividad...', 'Casi listo...']);
    loadHistory().finally(() => { clearInterval(_ht); PolarisLoading.hide('historialLoading'); });
});

function setupListeners() {
    document.getElementById('searchInput').addEventListener('input', () => {
        visibleCount = PAGE_SIZE;
        renderHistory();
    });
    const datePicker = document.getElementById('datePicker');
    // Bloquear fechas futuras
    datePicker.max = new Date().toISOString().split('T')[0];
    datePicker.addEventListener('change', () => {
        visibleCount = PAGE_SIZE;
        renderHistory();
    });
    document.getElementById('deleteModal').addEventListener('click', e => {
        if (e.target === e.currentTarget) closeDeleteModal();
    });
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') { closeDeleteModal(); }
    });
}

// ─────────────────────────────────────────────────────────────────────────────
// CARGAR — GET /api/historial
// ─────────────────────────────────────────────────────────────────────────────
async function loadHistory() {
    showLoadingState(true);
    try {
        const res  = await fetch(API, { headers: authHeaders() });
        const json = await res.json();
        if (!res.ok || !json.success) throw new Error(json.error || 'Error del servidor.');
        allItems = json.data || [];
        renderHistory();
    } catch (err) {
        showToast('Error al cargar: ' + err.message, 'toast-danger', 'fas fa-circle-xmark');
        console.error('[loadHistory]', err);
    } finally {
        showLoadingState(false);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RENDERIZAR con paginación
// ─────────────────────────────────────────────────────────────────────────────
function renderHistory() {
    const container  = document.getElementById('historyContainer');
    const emptyState = document.getElementById('emptyState');
    const search     = document.getElementById('searchInput').value.toLowerCase().trim();
    const dateFilter = document.getElementById('datePicker').value;

    document.getElementById('btnClearDate').classList.toggle('visible', dateFilter !== '');

    const filtered = allItems.filter(item => {
        // Excluir contenido de duelos (se maneja desde la sección Duelos)
        if (item.type === 'duel_quiz') return false;

        const cfg         = TYPE_CONFIG[item.type];
        const matchSearch = !search
            || item.title.toLowerCase().includes(search)
            || (cfg?.label || item.type).toLowerCase().includes(search);
        const matchDate   = !dateFilter
            || getLocalDateStr(parseCreatedAt(item.createdAt)) === dateFilter;
        return matchSearch && matchDate;
    });

    if (filtered.length === 0) {
        container.innerHTML = '';
        emptyState.style.display = 'block';
        return;
    }
    emptyState.style.display = 'none';

    if (currentView === 'recientes') {
        // Recientes: paginar normalmente
        const paginated = filtered.slice(0, visibleCount);
        const hasMore   = filtered.length > visibleCount;
        renderByDate(container, paginated);
        if (hasMore) appendLoadMore(container, filtered.length - visibleCount);
    } else {
        // Por tipo
        if (currentTypeFilter === null) {
            // Grilla: SIEMPRE usar TODOS los filtrados (sin paginar)
            renderTypeGrid(container, filtered);
        } else {
            // Items de un tipo: paginar dentro del tipo
            const typeItems = filtered.filter(i => i.type === currentTypeFilter);
            const paginated = typeItems.slice(0, visibleCount);
            const hasMore   = typeItems.length > visibleCount;
            renderTypeItems(container, paginated, currentTypeFilter);
            if (hasMore) appendLoadMore(container, typeItems.length - visibleCount);
        }
    }
}

function appendLoadMore(container, remaining) {
    const wrapper = document.createElement('div');
    wrapper.className = 'load-more-wrapper';
    wrapper.innerHTML = `
        <button class="load-more-btn" onclick="loadMore()">
            <i class="fas fa-chevron-down"></i>
            Ver más (${remaining} restantes)
        </button>`;
    container.appendChild(wrapper);
}

function loadMore() {
    visibleCount += PAGE_SIZE;
    renderHistory();
}

// ─────────────────────────────────────────────────────────────────────────────
// AGRUPACIONES
// ─────────────────────────────────────────────────────────────────────────────
function renderByDate(container, items) {
    const groups = {}, order = [];
    items.forEach(item => {
        const label = getDateLabel(item.createdAt);
        if (!groups[label]) { groups[label] = []; order.push(label); }
        groups[label].push(item);
    });
    container.innerHTML = order.map(label => `
        <div class="date-group">
            <div class="date-label">${label}</div>
            <div class="history-list">${groups[label].map(renderItem).join('')}</div>
        </div>`).join('');
}

/** Grilla de tarjetas coloridas — una por cada tipo presente */
function renderTypeGrid(container, items) {
    const counts = {};
    items.forEach(item => { counts[item.type] = (counts[item.type] || 0) + 1; });

    const types = Object.keys(counts);
    if (types.length === 0) { container.innerHTML = ''; return; }

    const cards = types.map(type => {
        const cfg = TYPE_CONFIG[type] || { label: type, icon: 'fas fa-file' };
        const n   = counts[type];
        return `
            <div class="type-card ${type}" onclick="selectTypeFilter('${type}')">
                <div class="type-card-icon"><i class="${cfg.icon}"></i></div>
                <div class="type-card-label">${cfg.label}</div>
                <div class="type-card-count">${n} ${n === 1 ? 'actividad' : 'actividades'}</div>
            </div>`;
    });

    container.innerHTML = `<div class="type-cards-grid">${cards.join('')}</div>`;
}

/** Items del tipo seleccionado, con breadcrumb para volver */
function renderTypeItems(container, items, type) {
    const cfg = TYPE_CONFIG[type] || { label: type, icon: 'fas fa-file' };

    container.innerHTML = `
        <div class="type-breadcrumb">
            <button onclick="clearTypeFilter()">Todos los tipos</button>
            <i class="fas fa-chevron-right"></i>
            <span>${cfg.label}</span>
        </div>
        <div class="history-list">${items.map(renderItem).join('')}</div>`;
}

function selectTypeFilter(type) {
    currentTypeFilter = type;
    visibleCount      = PAGE_SIZE;
    renderHistory();
}

function clearTypeFilter() {
    currentTypeFilter = null;
    renderHistory();
}

// ─────────────────────────────────────────────────────────────────────────────
// TARJETA
// ─────────────────────────────────────────────────────────────────────────────
function renderItem(item) {
    const cfg      = TYPE_CONFIG[item.type] || { label: item.type, icon: 'fas fa-file' };
    const favClass = item.isFavorite ? 'active' : '';
    const favIcon  = item.isFavorite ? 'fas fa-heart' : 'far fa-heart';
    const safeTitle = item.title.replace(/\\/g, '\\\\').replace(/'/g, "\\'");

    // Subtipo: solo para esquemas (nombres exactos de AIService.java)
    const SUBTYPE_LABELS = {
        'jerarquico':    'Jerárquico',
        'conceptual':    'Mapa Conceptual',
        'timeline':      'Línea del Tiempo',
        'causa-efecto':  'Causa y Efecto',
        'ciclico':       'Cíclico',
    };
    const subtype = item.type === 'schema' ? (item.subtype || item.schemaType || null) : null;
    const subtypeLabel = subtype ? (SUBTYPE_LABELS[subtype] || subtype) : '';
    const badgeText = subtypeLabel ? `${cfg.label} · ${subtypeLabel}` : cfg.label;

    return `
        <div class="history-item ${item.type}" onclick="openItem('${item.id}','${item.type}')">
            <div class="item-stars">${generateStars()}</div>
            <div class="history-icon ${item.type}"><i class="${cfg.icon}"></i></div>
            <div class="history-info">
                <div class="history-title">
                    <i class="fas fa-bookmark icon-${item.type}"></i>
                    ${escapeHtml(item.title)}
                </div>
                <div class="history-meta">
                    <span class="history-type-badge">${badgeText}</span>
                </div>
            </div>
            <div class="history-right">
                <span class="history-time">${formatTime(item.createdAt)}</span>
                <button class="btn-fav ${favClass}"
                    onclick="event.stopPropagation();toggleFav('${item.id}',this)"
                    title="Favorito"><i class="${favIcon}"></i></button>
                <button class="btn-delete"
                    onclick="event.stopPropagation();showDeleteModal('${item.id}','${safeTitle}')"
                    title="Eliminar"><i class="fas fa-trash-alt"></i></button>
            </div>
        </div>`;
}

// ─────────────────────────────────────────────────────────────────────────────
// NAVEGACIÓN
//
// Cada tipo tiene una estrategia distinta:
//
//  summary     → resumenes.js NO usa sessionStorage — lee ?id= y llama a SummaryServlet.
//                Solo hay que navegar con el id en la URL.
//
//  flashcard   → flashcards.js lee sessionStorage.studyResults.flashcards
//                Hay que hacer fetch del contenido, meterlo en sessionStorage, navegar.
//
//  quiz /      → pendiente de confirmar cómo lee examen-quiz.js (mismo patrón que flashcards
//  expert_exam   probablemente). Por ahora usa fetch + sessionStorage.
//
//  schema      → usa sesion-estudio.html con sessionStorage (mismo flujo que favoritos)
// ─────────────────────────────────────────────────────────────────────────────

// Tipos que van directo por URL (la página hace su propio fetch con ?id=)
const OPEN_BY_URL = new Set(['summary']);

// Tipos que necesitan sessionStorage (la página lee studyResults.<sessionKey>)
const TYPE_TO_SESSION_KEY = {
    flashcard:   'flashcards',
    schema:      'esquemas',
    quiz:        'examenes',
    expert_exam: 'examenes',
};

async function openItem(id, type) {
    const cfg = TYPE_CONFIG[type];

    if (!cfg?.page) {
        showToast('Vista no disponible para este tipo.', 'toast-danger', 'fas fa-circle-xmark');
        return;
    }

    // ── Caso 1: summary → navegar directo con ?id= ─────────────────────────
    if (OPEN_BY_URL.has(type)) {
        window.location.href = `${cfg.page}?id=${id}`;
        return;
    }

    // ── Caso 2: todos los demás → fetch contenido + sessionStorage ─────────
    const sessionKey = TYPE_TO_SESSION_KEY[type];
    if (!sessionKey) {
        showToast('Vista no disponible para este tipo.', 'toast-danger', 'fas fa-circle-xmark');
        return;
    }

    showToast('Cargando...', 'toast-success', 'fas fa-circle-notch fa-spin');

    try {
        const res  = await fetch(`${API}/${id}`, { headers: authHeaders() });
        const text = await res.text();
        let json;
        try { json = JSON.parse(text); }
        catch (_) { throw new Error(`Respuesta inválida del servidor (${res.status})`); }

        if (!res.ok || !json.success) throw new Error(json.error || `Error ${res.status}`);

        const rawData = json.data;

        // ── Extraer el contenido real ─────────────────────────────
        // El servidor devuelve { id, type, title, content: { ... } }
        // Necesitamos el objeto DENTRO de .content (igual que favoritos.js)
        const contentData = rawData.content || rawData;
        contentData.id    = contentData.id || id;
        contentData.title = contentData.title || rawData.title || 'Sin título';

        // Preservar schemaType del nivel superior si existe
        if (rawData.schemaType && !contentData.schemaType) {
            contentData.schemaType = rawData.schemaType;
        }

        const studyResults = {};
        studyResults[sessionKey] = contentData;
        sessionStorage.setItem('studyResults', JSON.stringify(studyResults));
        sessionStorage.setItem('fromHistorial', 'true');

        // ── Construir modoEstudioFlow (mismo patrón que favoritos.js) ──
        const flow = {
            userId:  getUserId(),
            options: [sessionKey],
            configs: {}
        };

        // Config específica para esquemas
        if (type === 'schema' && contentData.schemaType) {
            flow.configs.esquemas = { tipo: contentData.schemaType };
        }

        // Config específica para exámenes (tipo quiz vs expert_exam)
        if ((type === 'quiz' || type === 'expert_exam') && contentData.schemaType) {
            flow.configs.examenes = { tipo: contentData.schemaType };
        }

        sessionStorage.setItem('modoEstudioFlow', JSON.stringify(flow));

        window.location.href = cfg.page;

    } catch (err) {
        showToast('Error al abrir: ' + err.message, 'toast-danger', 'fas fa-circle-xmark');
        console.error('[openItem]', err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FILTROS
// ─────────────────────────────────────────────────────────────────────────────
function setFilter(view, el) {
    currentView       = view;
    visibleCount      = PAGE_SIZE;
    currentTypeFilter = null;
    document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
    el.classList.add('active');
    renderHistory();
}

function clearDate() {
    document.getElementById('datePicker').value = '';
    visibleCount = PAGE_SIZE;
    renderHistory();
}

// ─────────────────────────────────────────────────────────────────────────────
// TOGGLE FAVORITO
// Usa POST + header X-HTTP-Method-Override porque HttpServlet no tiene doPatch.
// HistorialServlet detecta el header y llama a la lógica de favorito.
// ─────────────────────────────────────────────────────────────────────────────
async function toggleFav(id, btnEl) {
    const item = allItems.find(i => i.id === id);
    if (!item) return;
    const newValue = !item.isFavorite;

    try {
        const res  = await fetch(`${API}/${id}/favorite`, {
            method:  'POST',
            headers: { ...authHeaders(), 'X-HTTP-Method-Override': 'PATCH' },
            body:    JSON.stringify({ isFavorite: newValue })
        });
        const json = await res.json();
        if (!res.ok || !json.success) throw new Error(json.error);

        item.isFavorite = newValue;
        if (newValue) {
            btnEl.classList.add('active', 'animating');
            btnEl.querySelector('i').className = 'fas fa-heart';
            createSparkles(btnEl);
            setTimeout(() => btnEl.classList.remove('animating'), 500);
            showToast('Agregado a favoritos', 'toast-fav', 'fas fa-heart');
        } else {
            btnEl.classList.remove('active');
            btnEl.querySelector('i').className = 'far fa-heart';
            showToast('Eliminado de favoritos', 'toast-danger', 'fas fa-heart-crack');
        }
    } catch (err) {
        showToast('Error al actualizar favorito', 'toast-danger', 'fas fa-circle-xmark');
        console.error('[toggleFav]', err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ELIMINAR
// ─────────────────────────────────────────────────────────────────────────────
function showDeleteModal(id, title) {
    itemToDelete = id;
    document.getElementById('deleteItemName').textContent = `"${title}"`;
    document.getElementById('deleteModal').classList.add('show');
}
function closeDeleteModal() {
    document.getElementById('deleteModal').classList.remove('show');
    itemToDelete = null;
}
async function confirmDelete() {
    if (!itemToDelete) return;
    try {
        const res  = await fetch(`${API}/${itemToDelete}`, { method: 'DELETE', headers: authHeaders() });
        const json = await res.json();
        if (!res.ok || !json.success) throw new Error(json.error);
        allItems = allItems.filter(i => i.id !== itemToDelete);
        closeDeleteModal();
        renderHistory();
        showToast('Eliminado del historial', 'toast-danger', 'fas fa-trash-alt');
    } catch (err) {
        showToast('Error al eliminar: ' + err.message, 'toast-danger', 'fas fa-circle-xmark');
        console.error('[confirmDelete]', err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI HELPERS
// ─────────────────────────────────────────────────────────────────────────────
function showLoadingState(_) { /* Reemplazado por PolarisLoading */ }

function showToast(message, type = 'toast-success', iconClass = 'fas fa-check-circle') {
    const toast = document.getElementById('toast');
    toast.querySelector('i').className = iconClass;
    document.getElementById('toastMessage').textContent = message;
    toast.className = `toast show ${type}`;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { toast.className = 'toast'; }, 3000);
}

// ─────────────────────────────────────────────────────────────────────────────
// ANIMACIONES
// ─────────────────────────────────────────────────────────────────────────────
function generateStars(count = 15) {
    let html = '';
    for (let i = 0; i < count; i++) {
        const size = 1 + Math.random() * 1.5, delay = Math.random() * 2;
        html += `<div class="item-star" style="width:${size}px;height:${size}px;left:${Math.random()*100}%;top:${Math.random()*100}%;animation-delay:${delay}s"></div>`;
    }
    return html;
}

function createSparkles(btn) {
    const c = document.createElement('div');
    c.className = 'sparkle-container';
    [0,45,90,135,180,225,270,315].forEach((angle, i) => {
        const s = document.createElement('div');
        s.className = 'sparkle';
        const d = [18,22,16,24,20,18,22,16][i], rad = angle * Math.PI / 180;
        s.style.setProperty('--sparkle-end', `translate(${Math.cos(rad)*d}px,${Math.sin(rad)*d}px)`);
        c.appendChild(s);
    });
    btn.appendChild(c);
    setTimeout(() => c.remove(), 700);
}

// ─────────────────────────────────────────────────────────────────────────────
// UTILIDADES DE FECHA
// El servidor puede mandar epoch ms (número) O un string tipo Postgres
// ("2026-03-15 19:49:58.716"). Esta función normaliza ambos formatos.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Convierte createdAt (number o string) a Date en hora local.
 * - Si es número → new Date(ms) funciona directo.
 * - Si es string sin 'T' (Postgres) → reemplaza espacio por 'T' y forza
 *   interpretación LOCAL (no UTC) añadiendo offset del navegador.
 */
function parseCreatedAt(value) {
    if (typeof value === 'number') return new Date(value);
    if (!value) return new Date();
    const str = String(value).trim();
    // Si ya tiene 'T' o '+' o 'Z' → formato ISO, parsea normal
    if (str.includes('T') || str.includes('+') || str.endsWith('Z')) {
        return new Date(str);
    }
    // Postgres sin timezone: "2026-03-15 19:49:58.716"
    // Reemplazar espacio por T y NO agregar Z → el navegador lo trata como local
    return new Date(str.replace(' ', 'T'));
}

/** "YYYY-MM-DD" en hora local del navegador */
function getLocalDateStr(date) {
    return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}

function getDateLabel(createdAt) {
    const d    = parseCreatedAt(createdAt);
    const hoy  = new Date();
    const ayer = new Date(); ayer.setDate(hoy.getDate() - 1);

    if (getLocalDateStr(d) === getLocalDateStr(hoy))  return 'Hoy';
    if (getLocalDateStr(d) === getLocalDateStr(ayer))  return 'Ayer';
    return d.toLocaleDateString('es-CR', { day:'2-digit', month:'long', year:'numeric', timeZone:'America/Costa_Rica' });
}

function formatTime(createdAt) {
    return parseCreatedAt(createdAt).toLocaleTimeString('es-CR', { hour:'2-digit', minute:'2-digit', hour12:true, timeZone:'America/Costa_Rica' });
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#039;');
}