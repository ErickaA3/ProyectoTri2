/**
 * resumenes.js
 * Conecta resumenes.html con el SummaryServlet.
 *
 * Flujos:
 *   1. Modo VIEW      → ?id=UUID en la URL  → carga resumen existente (GET)
 *   2. Modo GENERATE  → sin ?id             → muestra form, genera nuevo (POST)
 *
 * API base: /api/summaries  (SummaryServlet)
 * userId: viene de la sesión HTTP del servidor (no del cliente)
 */

const API = '/api/summaries';

// ─────────────────────────────────────────────────────────────────────────────
// INICIALIZACIÓN
// ─────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    generateStars();
    setupTextCounter();

    // Registrar listeners de modales aquí (único DOMContentLoaded)
    document.getElementById('notesTextarea')?.addEventListener('input', updateCharCounter);
    document.getElementById('notesModal')?.addEventListener('click', e => {
        if (e.target === e.currentTarget) closeNotesModal();
    });
    document.getElementById('downloadModal')?.addEventListener('click', function(e) {
        const bar = document.getElementById('downloadBarFill');
        if (e.target === this && bar?.style.width === '100%') this.classList.remove('show');
    });

    const params    = new URLSearchParams(window.location.search);
    const summaryId = params.get('id');

    if (summaryId) {
        // Modo VIEW: viene con ?id=UUID (desde modo-estudio o historial)
        showViewPanel();
        loadSummary(summaryId);
    } else {
        // Modo GENERATE: acceso directo a la página
        // Si el dashboard dejó datos pendientes en sessionStorage, los prellenamos
        const pending = sessionStorage.getItem('pendingSummary');
        if (pending) {
            try {
                const { text, subject } = JSON.parse(pending);
                const textInput    = document.getElementById('textInput');
                const subjectInput = document.getElementById('subjectInput');
                if (textInput)    textInput.value    = text    || '';
                if (subjectInput) subjectInput.value = subject || '';
                updateTextCounter();
            } catch (_) { /* JSON inválido — ignorar */ }
        }
        showGeneratePanel();
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// CONTROL DE PANELES
// ─────────────────────────────────────────────────────────────────────────────
function showGeneratePanel() {
    document.getElementById('generatePanel').style.display = 'block';
    document.getElementById('viewPanel').style.display     = 'none';
}

function showViewPanel() {
    document.getElementById('generatePanel').style.display = 'none';
    document.getElementById('viewPanel').style.display     = 'block';
}

// Contador de caracteres del textarea de generación
function setupTextCounter() {
    const ta = document.getElementById('textInput');
    if (ta) ta.addEventListener('input', updateTextCounter);
}

function updateTextCounter() {
    const ta      = document.getElementById('textInput');
    const counter = document.getElementById('textCounter');
    if (!ta || !counter) return;
    const len = ta.value.length;
    counter.textContent = len.toLocaleString();
    counter.style.color = len > 18000 ? '#ff6b6b' : 'var(--text-secondary)';
}

// ─────────────────────────────────────────────────────────────────────────────
// HANDLER BOTÓN "GENERAR RESUMEN"
// ─────────────────────────────────────────────────────────────────────────────
function handleGenerate() {
    const text    = document.getElementById('textInput')?.value.trim();
    const subject = document.getElementById('subjectInput')?.value.trim() || 'General';

    if (!text) {
        showToast('Escribe o pega un texto primero.', 'fa-exclamation-triangle');
        return;
    }
    if (text.length > 20000) {
        showToast('El texto supera los 20 000 caracteres.', 'fa-exclamation-triangle');
        return;
    }

    sessionStorage.removeItem('pendingSummary');
    showViewPanel();
    generateSummary(text, subject);
}

// ─────────────────────────────────────────────────────────────────────────────
// GENERAR RESUMEN — POST /api/summaries
// ─────────────────────────────────────────────────────────────────────────────
async function generateSummary(text, subject) {
    showLoadingState(true);

    try {
        const res  = await fetch(API, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ text, subject })
        });

        const json = await res.json();

        if (!res.ok || !json.success) {
            throw new Error(json.error || 'Error desconocido del servidor.');
        }

        // Actualizar la URL para que se pueda compartir / recargar
        history.replaceState(null, '', `?id=${json.data.id}`);

        renderSummary(json.data);
        showToast('¡Resumen generado!', 'fa-check-circle');

    } catch (err) {
        showToast('Error: ' + err.message, 'fa-circle-xmark');
        console.error('[generateSummary]', err);
        // Volver al panel de generación para que el usuario pueda reintentar
        showGeneratePanel();
    } finally {
        showLoadingState(false);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CARGAR RESUMEN EXISTENTE — GET /api/summaries/{id}
// ─────────────────────────────────────────────────────────────────────────────
async function loadSummary(summaryId) {
    showLoadingState(true);

    try {
        const res  = await fetch(`${API}/${summaryId}`);
        const json = await res.json();

        if (!res.ok || !json.success) {
            throw new Error(json.error || 'No se pudo cargar el resumen.');
        }

        renderSummary(json.data);

    } catch (err) {
        showToast('Error al cargar: ' + err.message, 'fa-circle-xmark');
        console.error('[loadSummary]', err);
    } finally {
        showLoadingState(false);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RENDERIZAR RESUMEN EN EL DOM
// data = { id, title, subject, readingMinutes, sections[], keywords[], isFavorite, createdAt }
// ─────────────────────────────────────────────────────────────────────────────
function renderSummary(data) {
    // Guardar estado global del resumen activo
    window._currentSummaryId = data.id;
    window._isFavorite       = data.isFavorite;

    // Título de la página y del card
    document.title = `Mi ProfesorIA - ${data.title}`;
    const titleEl = document.getElementById('summaryTitle');
    if (titleEl) titleEl.textContent = data.title;

    // Meta: materia y tiempo de lectura
    const meta = document.getElementById('summaryMeta');
    if (meta) {
        meta.innerHTML = `
            <span><i class="fas fa-book"></i> ${escapeHtml(data.subject || 'General')}</span>
            <span><i class="fas fa-clock"></i> ${data.readingMinutes} min lectura</span>
        `;
    }

    // Botón favorito
    updateFavoriteButton(data.isFavorite);

    // Secciones del resumen
    const contentDiv = document.getElementById('summaryContent');
    let html = '';

    (data.sections || []).forEach(sec => {
        const highlightHTML = sec.highlight
            ? `<div class="highlight-box">
                 <div class="highlight-icon"><i class="fas fa-lightbulb"></i></div>
                 <div class="highlight-content">
                   <h4>Dato importante</h4>
                   <p>${escapeHtml(sec.highlight)}</p>
                 </div>
               </div>`
            : '';

        html += `
            <section class="resumen-section">
                <h2 class="section-title">
                    <span class="section-number">${escapeHtml(sec.number)}</span>
                    ${escapeHtml(sec.heading)}
                </h2>
                <p class="resumen-text">${escapeHtml(sec.body)}</p>
                ${highlightHTML}
            </section>
        `;
    });

    // Palabras clave
    if (data.keywords && data.keywords.length > 0) {
        const kwHTML = data.keywords
            .map(kw => `<span class="keyword-tag">${escapeHtml(kw)}</span>`)
            .join('');
        html += `
            <div class="keywords-section">
                <h3 class="keywords-title">
                    <i class="fas fa-key"></i>
                    Palabras clave
                </h3>
                <div class="keywords-container">${kwHTML}</div>
            </div>
        `;
    }

    if (contentDiv) contentDiv.innerHTML = html;

    // Mostrar barra de acciones
    const actionsBar = document.getElementById('actionsBar');
    if (actionsBar) actionsBar.style.display = '';
}

// ─────────────────────────────────────────────────────────────────────────────
// TOGGLE FAVORITO — PATCH /api/summaries/{id}/favorite
// ─────────────────────────────────────────────────────────────────────────────
async function toggleFavorite() {
    if (!window._currentSummaryId) return;

    const newValue = !window._isFavorite;

    try {
        const res  = await fetch(`${API}/${window._currentSummaryId}/favorite`, {
            method:  'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ isFavorite: newValue })
        });

        const json = await res.json();
        if (!res.ok || !json.success) throw new Error(json.error);

        window._isFavorite = newValue;
        updateFavoriteButton(newValue);

        if (newValue) {
            createHeartSparkles();
            showToast('Añadido a favoritos', 'fa-heart');
        } else {
            showToast('Eliminado de favoritos', 'fa-heart-crack');
        }

    } catch (err) {
        showToast('Error al actualizar favorito', 'fa-circle-xmark');
        console.error('[toggleFavorite]', err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS DE UI
// ─────────────────────────────────────────────────────────────────────────────

/** Muestra u oculta el spinner dentro del panel de vista. */
function showLoadingState(loading) {
    const content   = document.getElementById('summaryContent');
    const actionsBar = document.getElementById('actionsBar');

    if (loading) {
        if (content) {
            content.innerHTML = `
                <div class="loading-state" id="loadingState">
                    <i class="fas fa-circle-notch fa-spin"></i>
                    <p>Generando resumen con IA…</p>
                </div>`;
        }
        if (actionsBar) actionsBar.style.display = 'none';
    } else {
        document.getElementById('loadingState')?.remove();
    }
}

function updateFavoriteButton(isFav) {
    const btn  = document.getElementById('favoriteBtn');
    const icon = btn?.querySelector('i');
    if (!btn || !icon) return;

    if (isFav) {
        icon.classList.replace('far', 'fas');
        btn.classList.add('active');
    } else {
        icon.classList.replace('fas', 'far');
        btn.classList.remove('active');
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NOTAS (localStorage — no requiere backend)
// ─────────────────────────────────────────────────────────────────────────────
function openNotesModal() {
    const key = `notes_${window._currentSummaryId || 'draft'}`;
    window._savedNotes = localStorage.getItem(key) || '';
    const textarea = document.getElementById('notesTextarea');
    if (textarea) { textarea.value = window._savedNotes; updateCharCounter(); }
    generateNotesSparkles();
    document.getElementById('notesModal').classList.add('show');
}

function closeNotesModal() {
    document.getElementById('notesModal').classList.remove('show');
}

function updateCharCounter() {
    const ta      = document.getElementById('notesTextarea');
    const counter = document.getElementById('charCounter');
    if (!ta || !counter) return;
    counter.textContent = ta.value.length;
    counter.style.color = ta.value.length > 1800 ? '#ff6b6b' : 'var(--text-secondary)';
}

function saveNotes() {
    const ta = document.getElementById('notesTextarea');
    if (!ta) return;
    if (ta.value.length > 2000) {
        showToast('El texto excede el límite', 'fa-exclamation-triangle');
        return;
    }
    const key = `notes_${window._currentSummaryId || 'draft'}`;
    window._savedNotes = ta.value;
    localStorage.setItem(key, ta.value);
    closeNotesModal();
    showToast('Nota guardada correctamente', 'fa-check-circle');
}

// ─────────────────────────────────────────────────────────────────────────────
// DESCARGA PDF (simulada)
// ─────────────────────────────────────────────────────────────────────────────
function downloadPDF() {
    const modal   = document.getElementById('downloadModal');
    const bar     = document.getElementById('downloadBarFill');
    const title   = document.getElementById('downloadTitle');
    const message = document.getElementById('downloadMessage');

    modal.classList.add('show');
    bar.style.width     = '0%';
    title.textContent   = 'Preparando descarga...';
    message.textContent = 'Tu PDF estará listo en un momento';

    let progress = 0;
    const interval = setInterval(() => {
        progress += Math.random() * 15;
        if (progress >= 100) {
            progress = 100;
            clearInterval(interval);
            title.textContent   = '¡Descarga completa!';
            message.textContent = 'Tu PDF ha sido descargado';
            setTimeout(() => {
                modal.classList.remove('show');
                showToast('PDF descargado correctamente', 'fa-file-pdf');
            }, 1500);
        }
        bar.style.width = progress + '%';
    }, 200);
}

// ─────────────────────────────────────────────────────────────────────────────
// ANIMACIONES / EFECTOS
// ─────────────────────────────────────────────────────────────────────────────
function generateStars() {
    const bg = document.getElementById('starsBackground');
    if (!bg) return;
    let html = '';
    for (let i = 0; i < 120; i++) {
        const size     = 1 + Math.random() * 2;
        const delay    = Math.random() * 3;
        const duration = 2 + Math.random() * 3;
        html += `<div class="bg-star" style="width:${size}px;height:${size}px;left:${Math.random()*100}%;top:${Math.random()*100}%;animation-delay:${delay}s;animation-duration:${duration}s"></div>`;
    }
    bg.innerHTML = html;
}

function generateNotesSparkles() {
    const container = document.getElementById('notesSparkles');
    if (!container) return;
    let html = '';
    for (let i = 0; i < 15; i++) {
        const size  = 3 + Math.random() * 5;
        const delay = Math.random() * 2;
        html += `<div class="sparkle" style="width:${size}px;height:${size}px;left:${Math.random()*100}%;top:${Math.random()*100}%;animation-delay:${delay}s"></div>`;
    }
    container.innerHTML = html;
}

function createHeartSparkles() {
    const container = document.getElementById('sparklesContainer');
    if (!container) return;
    container.innerHTML = '';
    for (let i = 0; i < 12; i++) {
        const sparkle = document.createElement('div');
        sparkle.className = 'heart-sparkle';
        const angle    = (i / 12) * 360;
        const distance = 20 + Math.random() * 30;
        sparkle.style.setProperty('--x', `${Math.cos(angle * Math.PI / 180) * distance}px`);
        sparkle.style.setProperty('--y', `${Math.sin(angle * Math.PI / 180) * distance}px`);
        sparkle.style.animationDelay = `${Math.random() * 0.3}s`;
        container.appendChild(sparkle);
    }
    setTimeout(() => { container.innerHTML = ''; }, 800);
}

// ─────────────────────────────────────────────────────────────────────────────
// UTILIDADES
// ─────────────────────────────────────────────────────────────────────────────
function goBack() { window.history.back(); }

function showToast(message, icon = 'fa-check-circle') {
    const toast  = document.getElementById('toast');
    const toastI = document.getElementById('toastIcon');
    const toastM = document.getElementById('toastMessage');
    if (!toast) return;
    toastI.className   = 'fas ' + icon;
    toastM.textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g,  '&amp;')
        .replace(/</g,  '&lt;')
        .replace(/>/g,  '&gt;')
        .replace(/"/g,  '&quot;')
        .replace(/'/g,  '&#039;');
}