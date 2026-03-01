/**
 * resumenes.js
 * Conecta el HTML de resumenes.html con el backend (SummaryServlet).
 *
 * Flujos que maneja:
 *   1. Modo GENERACIÓN  → el usuario llega desde el dashboard con texto a resumir
 *   2. Modo VISUALIZAR  → el usuario abre un resumen ya guardado (pasa ?id=UUID en la URL)
 */

const API = '/api/summaries';

// ─────────────────────────────────────────────────────────────────────────────
// INICIALIZACIÓN
// ─────────────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    generateStars();
    setupTextCounter();

    const params    = new URLSearchParams(window.location.search);
    const summaryId = params.get('id');

    if (summaryId) {
        // Modo visualización: viene con ?id=UUID → mostrar panel de vista directamente
        showViewPanel();
        loadSummary(summaryId);
    } else {
        // Modo generación: mostrar panel de input
        // Si el dashboard ya dejó datos en sessionStorage, rellenar los campos
        const pending = sessionStorage.getItem('pendingSummary');
        if (pending) {
            const { text, subject } = JSON.parse(pending);
            const textInput    = document.getElementById('textInput');
            const subjectInput = document.getElementById('subjectInput');
            if (textInput)    textInput.value    = text    || '';
            if (subjectInput) subjectInput.value = subject || '';
            updateTextCounter();
        }
        showGeneratePanel();
    }
});

// ─────────────────────────────────────────────────────────────────────────────
// HANDLER DEL BOTÓN "GENERAR RESUMEN"
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

    // Limpiar sessionStorage si vino desde el dashboard
    sessionStorage.removeItem('pendingSummary');

    // Cambiar al panel de vista y mostrar el spinner
    showViewPanel();
    generateSummary(text, subject);
}

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
// GENERAR RESUMEN (POST)
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

        // Guardar el ID en la URL sin recargar
        history.replaceState(null, '', `?id=${json.data.id}`);

        renderSummary(json.data);
        showToast('¡Resumen generado!', 'fa-check-circle');

    } catch (err) {
        showToast('Error: ' + err.message, 'fa-circle-xmark');
        console.error('[generateSummary]', err);
    } finally {
        showLoadingState(false);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CARGAR RESUMEN EXISTENTE (GET /{id})
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
// ─────────────────────────────────────────────────────────────────────────────
function renderSummary(data) {
    // Guardar estado de favorito
    window._currentSummaryId  = data.id;
    window._isFavorite        = data.isFavorite;

    // Título e ícono
    const titleEl = document.getElementById('summaryTitle');
    if (titleEl) titleEl.textContent = data.title;

    // Meta: materia, tiempo lectura
    const meta = document.getElementById('summaryMeta');
    if (meta) meta.innerHTML = `
        <span><i class="fas fa-book"></i> ${data.subject || 'General'}</span>
        <span><i class="fas fa-clock"></i> ${data.readingMinutes} min lectura</span>
    `;

    // Mostrar barra de acciones
    const actionsBar = document.getElementById('actionsBar');
    if (actionsBar) actionsBar.style.display = '';

    // Botón favorito
    updateFavoriteButton(data.isFavorite);

    // Secciones
    const contentDiv = document.getElementById('summaryContent');
    let sectionsHTML = '';

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

        sectionsHTML += `
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

    // Keywords
    const kwHTML = (data.keywords || []).map(kw =>
        `<span class="keyword-tag">${escapeHtml(kw)}</span>`
    ).join('');

    sectionsHTML += `
        <div class="keywords-section">
            <h3 class="keywords-title">
                <i class="fas fa-key"></i>
                Palabras clave
            </h3>
            <div class="keywords-container">${kwHTML}</div>
        </div>
    `;

    contentDiv.innerHTML = sectionsHTML;
}

// ─────────────────────────────────────────────────────────────────────────────
// TOGGLE FAVORITO (PATCH)
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
        createHeartSparkles();
        showToast(
            newValue ? 'Añadido a favoritos' : 'Eliminado de favoritos',
            newValue ? 'fa-heart' : 'fa-heart-crack'
        );

    } catch (err) {
        showToast('Error al actualizar favorito', 'fa-circle-xmark');
        console.error('[toggleFavorite]', err);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS DE UI
// ─────────────────────────────────────────────────────────────────────────────

/** Muestra u oculta el spinner de carga dentro del contenido del resumen. */
function showLoadingState(loading) {
    const content  = document.getElementById('summaryContent');
    const loadingEl = document.getElementById('loadingState');

    if (loading) {
        if (content && !loadingEl) {
            content.innerHTML = `
                <div class="loading-state" id="loadingState">
                    <i class="fas fa-spinner fa-spin"></i>
                    <p>Generando resumen con IA…</p>
                </div>`;
        } else if (loadingEl) {
            loadingEl.style.display = '';
        }
        const actionsBar = document.getElementById('actionsBar');
        if (actionsBar) actionsBar.style.display = 'none';
    } else {
        const el = document.getElementById('loadingState');
        if (el) el.remove();
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

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g,  '&amp;')
        .replace(/</g,  '&lt;')
        .replace(/>/g,  '&gt;')
        .replace(/"/g,  '&quot;')
        .replace(/'/g,  '&#039;');
}

// ─────────────────────────────────────────────────────────────────────────────
// FUNCIONES EXISTENTES DEL HTML (se mantienen igual)
// ─────────────────────────────────────────────────────────────────────────────
function generateStars() {
    const bg = document.getElementById('starsBackground');
    if (!bg) return;
    let html = '';
    for (let i = 0; i < 120; i++) {
        const size     = 1 + Math.random() * 2;
        const left     = Math.random() * 100;
        const top      = Math.random() * 100;
        const delay    = Math.random() * 3;
        const duration = 2 + Math.random() * 3;
        html += `<div class="star" style="width:${size}px;height:${size}px;left:${left}%;top:${top}%;animation-delay:${delay}s;animation-duration:${duration}s"></div>`;
    }
    bg.innerHTML = html;
}

function createHeartSparkles() {
    const container = document.getElementById('sparklesContainer');
    if (!container) return;
    container.innerHTML = '';
    for (let i = 0; i < 12; i++) {
        const sparkle  = document.createElement('div');
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

function goBack() { window.history.back(); }

function openNotesModal() {
    document.getElementById('notesModal').classList.add('show');
    const textarea = document.getElementById('notesTextarea');
    if (textarea) { textarea.value = window._savedNotes || ''; updateCharCounter(); }
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
    if (ta.value.length > 2000) { showToast('El texto excede el límite', 'fa-exclamation-triangle'); return; }
    window._savedNotes = ta.value;
    closeNotesModal();
    showToast('Nota guardada correctamente', 'fa-check-circle');
}

function downloadPDF() {
    const modal    = document.getElementById('downloadModal');
    const bar      = document.getElementById('downloadBarFill');
    const title    = document.getElementById('downloadTitle');
    const message  = document.getElementById('downloadMessage');
    modal.classList.add('show');
    bar.style.width = '0%';
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
            setTimeout(() => { modal.classList.remove('show'); showToast('PDF descargado', 'fa-file-pdf'); }, 1500);
        }
        bar.style.width = progress + '%';
    }, 200);
}

function showToast(message, icon = 'fa-check-circle') {
    const toast   = document.getElementById('toast');
    const toastI  = document.getElementById('toastIcon');
    const toastM  = document.getElementById('toastMessage');
    if (!toast) return;
    toastI.className    = 'fas ' + icon;
    toastM.textContent  = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
}

// Event listeners de los modales (evitar duplicar los del HTML inline)
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('notesTextarea')?.addEventListener('input', updateCharCounter);
    document.getElementById('notesModal')?.addEventListener('click', e => { if (e.target === e.currentTarget) closeNotesModal(); });
    document.getElementById('downloadModal')?.addEventListener('click', function(e) {
        if (e.target === this && document.getElementById('downloadBarFill')?.style.width === '100%') this.classList.remove('show');
    });
});