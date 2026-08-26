// ─────────────────────────────────────────────────────────────
//  favoritos.js — Mi ProfesorIA
//  Conecta favoritos.html con FavoritesServlet (/api/favoritos)
// ─────────────────────────────────────────────────────────────

const userRaw = localStorage.getItem('user');
let userId = null;
if (userRaw) {
    try { userId = JSON.parse(userRaw).id; } catch(e) {}
}

let favorites     = [];
let currentFilter = 'all';
let itemToRemove  = null;

// Mapeo tipo BD → configuración (mismo patrón que historial.js)
const TYPE_CONFIG = {
    flashcard:   { label: 'Flashcard',      icon: 'fas fa-layer-group',     page: '../pages/flashcards.html',      sessionKey: 'flashcards' },
    schema:      { label: 'Esquema',        icon: 'fas fa-project-diagram', page: '../pages/sesion-estudio.html',  sessionKey: 'esquemas' },
    summary:     { label: 'Resumen',        icon: 'fas fa-file-alt',        page: '../pages/resumenes.html',       sessionKey: null },
    quiz:        { label: 'Quiz',           icon: 'fas fa-clipboard-list',  page: '../pages/examen-quiz.html',     sessionKey: 'examenes' },
    expert_exam: { label: 'Examen Experto', icon: 'fas fa-file-signature',  page: '../pages/examen-experto.html',  sessionKey: 'examenes' },
};

// Tipos que van directo por URL (summary hace su propio fetch con ?id=)
const OPEN_BY_URL = new Set(['summary']);

// ─────────────────────────────────────────────────────────────
//  CARGA INICIAL
// ─────────────────────────────────────────────────────────────

async function loadFavorites() {
    if (!userId) {
        window.location.href = '../index.html';
        return;
    }

    try {
        const response = await fetch('../api/favoritos', {
            headers: getAuthHeaders({ 'X-User-Id': userId })
        });

        const data = await response.json();

        if (!response.ok) {
            console.error('[Favoritos] Error del servidor:', data.error);
            return;
        }

        favorites = data.map(item => {
            const cfg = TYPE_CONFIG[item.type] || {};
            return {
                id:         item.id,
                type:       item.type,
                title:      item.title || 'Sin título',
                category:   cfg.label || item.type,
                icon:       cfg.icon || 'fas fa-file',
                schemaType: item.subtype || item.schemaType || null
            };
        });

        renderFavorites();

    } catch (error) {
        console.error('[Favoritos] Error de conexión:', error);
    }
}

// ─────────────────────────────────────────────────────────────
//  QUITAR FAVORITO
// ─────────────────────────────────────────────────────────────

async function removeFavorite(contentId) {
    try {
        const response = await fetch('../api/favoritos', {
            method:  'PUT',
            headers: getAuthHeaders({ 'X-User-Id': userId }),
            body: JSON.stringify({
                contentId:  contentId,
                isFavorite: false
            })
        });

        const data = await response.json();

        if (data.success) {
            favorites = favorites.filter(f => f.id !== contentId);
            renderFavorites();
            showToast('Eliminado de favoritos');
        } else {
            console.error('[Favoritos] No se pudo eliminar:', data.error);
            showToast('Error al eliminar favorito');
        }

    } catch (error) {
        console.error('[Favoritos] Error de conexión:', error);
    }
}

// ─────────────────────────────────────────────────────────────
//  VER CONTENIDO — fetch completo + redirigir a sesion-estudio
// ─────────────────────────────────────────────────────────────

async function openItem(id) {
    // Find the favorite to know its type
    const fav = favorites.find(f => f.id === id);
    const type = fav?.type;
    const cfg = TYPE_CONFIG[type];

    if (!cfg?.page) {
        showToast('Vista no disponible para este tipo');
        return;
    }

    // ── Caso 1: summary → navegar directo con ?id= ──
    if (OPEN_BY_URL.has(type)) {
        window.location.href = `${cfg.page}?id=${id}`;
        return;
    }

    // ── Caso 2: todos los demás → fetch contenido + sessionStorage ──
    if (!cfg.sessionKey) {
        showToast('Tipo de contenido no soportado');
        return;
    }

    try {
        const response = await fetch(`../api/favoritos/${id}`, {
            headers: getAuthHeaders({ 'X-User-Id': userId })
        });

        if (!response.ok) {
            showToast('Error al cargar el contenido');
            return;
        }

        const data = await response.json();
        const contentObj = data.content;
        contentObj.id    = id;
        contentObj.title = data.title;

        // Construir studyResults
        const studyResults = {};
        studyResults[cfg.sessionKey] = contentObj;
        sessionStorage.setItem('studyResults', JSON.stringify(studyResults));
        sessionStorage.setItem('fromHistorial', 'true');

        // Construir modoEstudioFlow
        const flow = {
            userId:  userId,
            options: [cfg.sessionKey],
            configs: {}
        };

        // Config específica para esquemas (tipo de esquema)
        if (type === 'schema' && contentObj.schemaType) {
            flow.configs.esquemas = { tipo: contentObj.schemaType };
        }

        // Config específica para exámenes (quiz vs expert_exam)
        if ((type === 'quiz' || type === 'expert_exam') && contentObj.schemaType) {
            flow.configs.examenes = { tipo: contentObj.schemaType };
        }

        sessionStorage.setItem('modoEstudioFlow', JSON.stringify(flow));

        window.location.href = cfg.page;

    } catch (error) {
        console.error('[Favoritos] Error al abrir contenido:', error);
        showToast('Error de conexión');
    }
}

// ─────────────────────────────────────────────────────────────
//  RENDER
// ─────────────────────────────────────────────────────────────

function renderFavorites() {
    const grid       = document.getElementById('favoritesGrid');
    const emptyState = document.getElementById('emptyState');
    const searchTerm = document.getElementById('searchInput').value.toLowerCase();

    const filtered = favorites.filter(item => {
        const matchesFilter = currentFilter === 'all' || item.type === currentFilter;
        const matchesSearch = item.title.toLowerCase().includes(searchTerm);
        return matchesFilter && matchesSearch;
    });

    if (filtered.length === 0) {
        grid.style.display = 'none';
        emptyState.style.display = 'block';
        return;
    }

    grid.style.display = 'grid';
    emptyState.style.display = 'none';

    // Nombres bonitos para subtipos de esquema (mismos de historial.js)
    const SUBTYPE_LABELS = {
        'jerarquico':   'Jerárquico',
        'conceptual':   'Mapa Conceptual',
        'timeline':     'Línea del Tiempo',
        'causa-efecto': 'Causa y Efecto',
        'ciclico':      'Cíclico',
    };

    grid.innerHTML = filtered.map(item => {
        // Subtipo para esquemas — mostrar como "Esquema · Mapa Conceptual"
        const rawSub = item.type === 'schema' ? item.schemaType : null;
        const subtypeLabel = rawSub ? (SUBTYPE_LABELS[rawSub] || rawSub) : '';
        const categoryText = subtypeLabel ? `${item.category} · ${subtypeLabel}` : item.category;

        // CSS class — expert_exam tiene su propio estilo rojo
        const cardClass = item.type === 'expert_exam' ? 'expert_exam' : item.type;

        return `
        <div class="favorite-card ${cardClass}" onclick="openItem('${item.id}')">
            <div class="card-stars">${generateStars()}</div>
            <span class="card-type-badge ${cardClass}">${item.category.toUpperCase()}</span>
            <i class="fas fa-star card-star"></i>
            <div class="card-preview ${cardClass}">
                <i class="${item.icon} card-preview-icon"></i>
            </div>
            <div class="card-content">
                <h3 class="card-title">${item.title}</h3>
                <div class="card-category ${cardClass}">
                    <i class="${item.icon}"></i>
                    ${categoryText}
                </div>
                <div class="card-actions">
                    <button class="card-btn-ver" onclick="event.stopPropagation(); openItem('${item.id}')">
                        <i class="fas fa-play"></i> Ver
                    </button>
                    <button class="card-btn-heart active" onclick="event.stopPropagation(); handleHeartClick('${item.id}', '${item.title}', this)">
                        <i class="fas fa-heart"></i>
                    </button>
                </div>
            </div>
        </div>
    `}).join('');
}

// ─────────────────────────────────────────────────────────────
//  FILTROS Y BÚSQUEDA
// ─────────────────────────────────────────────────────────────

function setFilter(filter, element) {
    currentFilter = filter;
    document.querySelectorAll('.filter-tab').forEach(tab => tab.classList.remove('active'));
    element.classList.add('active');
    renderFavorites();
}

function filterFavorites() {
    renderFavorites();
}

// ─────────────────────────────────────────────────────────────
//  CORAZÓN — modal de confirmación
// ─────────────────────────────────────────────────────────────

function handleHeartClick(id, title, btn) {
    btn.classList.remove('active');
    btn.innerHTML = '<i class="far fa-heart"></i>';
    showRemoveModal(id, title);
}

function showRemoveModal(id, title) {
    itemToRemove = { id, title };
    document.getElementById('modalItemName').textContent = `"${title}"`;
    document.getElementById('removeModal').classList.add('show');
}

function closeRemoveModal() {
    document.getElementById('removeModal').classList.remove('show');
    itemToRemove = null;
}

function confirmRemove() {
    if (itemToRemove) {
        removeFavorite(itemToRemove.id);
        closeRemoveModal();
    }
}

// ─────────────────────────────────────────────────────────────
//  UTILIDADES
// ─────────────────────────────────────────────────────────────

function generateStars() {
    let html = '';
    for (let i = 0; i < 20; i++) {
        const size  = 1 + Math.random() * 2;
        const left  = Math.random() * 100;
        const top   = Math.random() * 100;
        const delay = Math.random() * 2;
        html += `<div class="star" style="width:${size}px;height:${size}px;left:${left}%;top:${top}%;animation-delay:${delay}s"></div>`;
    }
    return html;
}

function mapTypeToLabel(type) {
    return TYPE_CONFIG[type]?.label || type;
}

function mapTypeToIcon(type) {
    return TYPE_CONFIG[type]?.icon || 'fas fa-file';
}

function showToast(message) {
    const toast = document.getElementById('toast');
    document.getElementById('toastMessage').textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
}

// ─────────────────────────────────────────────────────────────
//  EVENTOS
// ─────────────────────────────────────────────────────────────

document.getElementById('removeModal').addEventListener('click', function(e) {
    if (e.target === this) closeRemoveModal();
});

document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') closeRemoveModal();
});

document.addEventListener('DOMContentLoaded', function() {
    const t = PolarisLoading.rotateMessages('favoritosLoadingSub', ['Cargando favoritos...', 'Buscando tu contenido...', 'Casi listo...']);
    loadFavorites().finally(() => { clearInterval(t); PolarisLoading.hide('favoritosLoading'); });
});