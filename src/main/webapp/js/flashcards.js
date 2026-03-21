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

// Mapeo tipo BD → nombre de opción en sesion-estudio
const typeToOption = {
    flashcard: 'flashcards',
    schema:    'esquemas',
    summary:   'resumenes',
    quiz:      'examenes'
};

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
            headers: { 'X-User-Id': userId }
        });

        const data = await response.json();

        if (!response.ok) {
            console.error('[Favoritos] Error del servidor:', data.error);
            return;
        }

        favorites = data.map(item => ({
            id:       item.id,
            type:     item.type,
            title:    item.title || 'Sin título',
            category: mapTypeToLabel(item.type),
            icon:     mapTypeToIcon(item.type)
        }));

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
            headers: {
                'Content-Type': 'application/json',
                'X-User-Id': userId
            },
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
    try {
        const response = await fetch(`../api/favoritos/${id}`, {
            headers: { 'X-User-Id': userId }
        });

        if (!response.ok) {
            showToast('Error al cargar el contenido');
            return;
        }

        const data = await response.json();
        // data = { type: "schema", title: "...", isFavorite: true, content: {...} }

        const dbType = data.type;
        const option = typeToOption[dbType];

        if (!option) {
            showToast('Tipo de contenido no soportado');
            return;
        }

        // Construir studyResults en el formato que espera sesion-estudio
        const contentObj = data.content;
        contentObj.id    = id;
        contentObj.title = data.title;

        const studyResults = {};
        studyResults[option] = contentObj;

        const flow = {
            userId:  userId,
            options: [option],
            configs: {}
        };

        // Recuperar el tipo específico del contenido (esquema, examen, etc.)
        if (option === 'esquemas' && contentObj.schemaType) {
            flow.configs.esquemas = { tipo: contentObj.schemaType };
        }
        if (option === 'examenes' && contentObj.schemaType) {
            flow.configs.examenes = { tipo: contentObj.schemaType };
        }

        sessionStorage.setItem('studyResults', JSON.stringify(studyResults));
        sessionStorage.setItem('modoEstudioFlow', JSON.stringify(flow));
        sessionStorage.setItem('fromHistorial', 'true');

        window.location.href = '../pages/sesion-estudio.html';

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

    grid.innerHTML = filtered.map(item => `
        <div class="favorite-card ${item.type}" onclick="openItem('${item.id}')">
            <div class="card-stars">${generateStars()}</div>
            <span class="card-type-badge ${item.type}">${item.type.toUpperCase()}</span>
            <i class="fas fa-star card-star"></i>
            <div class="card-preview ${item.type}">
                <i class="${item.icon} card-preview-icon"></i>
            </div>
            <div class="card-content">
                <h3 class="card-title">${item.title}</h3>
                <div class="card-category ${item.type}">
                    <i class="${item.icon}"></i>
                    ${item.category}
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
    `).join('');
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
    const labels = {
        flashcard: 'Flashcard',
        schema:    'Esquema',
        summary:   'Resumen',
        quiz:      'Quiz'
    };
    return labels[type] || type;
}

function mapTypeToIcon(type) {
    const icons = {
        flashcard: 'fas fa-layer-group',
        schema:    'fas fa-project-diagram',
        summary:   'fas fa-file-alt',
        quiz:      'fas fa-clipboard-list'
    };
    return icons[type] || 'fas fa-file';
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

const _ft = PolarisLoading.rotateMessages('favoritosLoadingSub',
    ['Cargando favoritos...', 'Buscando tu contenido...', 'Casi listo...']);
loadFavorites().finally(() => { clearInterval(_ft); PolarisLoading.hide('favoritosLoading'); });