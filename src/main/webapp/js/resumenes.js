/**
 * resumenes.js
 * Solo modo VIEW — la generación la hace ModoEstudioServlet.
 *
 * Flujo:
 *   1. La página llega con ?id=UUID (desde sesion-estudio o historial)
 *   2. GET /api/summaries?id=UUID  con header X-User-Id
 *   3. Renderiza el JSON devuelto por el servlet
 *
 * Estructura del JSON del servidor:
 * {
 *   success, id, title, isFavorite, createdAt, sessionId,
 *   content: {            ← JSONB guardado por la IA
 *     subject, readingMinutes,
 *     sections: [{ number, heading, body, highlight? }],
 *     keywords: [...]
 *   }
 * }
 */

const API = '/project-1.0-SNAPSHOT/api/summaries';

// ─── Auth (mismo patrón que historial.js) ────────────────────
function getUserId() {
    try { return JSON.parse(localStorage.getItem('user'))?.id || null; }
    catch (_) { return null; }
}

function authHeaders() {
    const uid = getUserId();
    return { 'Content-Type': 'application/json', ...(uid ? { 'X-User-Id': uid } : {}) };
}

// ─── Init ─────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    generateStars();

    document.getElementById('notesTextarea')?.addEventListener('input', updateCharCounter);
    document.getElementById('notesModal')?.addEventListener('click', e => {
        if (e.target === e.currentTarget) closeNotesModal();
    });
    document.getElementById('downloadModal')?.addEventListener('click', function(e) {
        const bar = document.getElementById('downloadBarFill');
        if (e.target === this && bar?.style.width === '100%') this.classList.remove('show');
    });

    const summaryId = new URLSearchParams(window.location.search).get('id');

    if (!summaryId) {
        showError(
            'No se especificó un resumen',
            'Genera un resumen desde Modo Estudio primero.'
        );
        return;
    }

    loadSummary(summaryId);
});

// ─── Cargar desde servidor ─────────────────────────────────────
async function loadSummary(summaryId) {
    showState('loading');
    try {
        const res  = await fetch(`${API}?id=${summaryId}`, { headers: authHeaders() });
        const json = await res.json();

        if (!res.ok || !json.success) throw new Error(json.error || 'Error desconocido.');

        renderSummary(json);
        showState('card');

        // ── GAMIFICACIÓN: reward por ver resumen completo ──
        if (typeof sendReward === 'function') {
            sendReward('resumen', 0, summaryId, 0, 0).catch(() => {});
        }

    } catch (err) {
        showError('No se pudo cargar el resumen', err.message);
        console.error('[loadSummary]', err);
    }
}

// ─── Renderizar ───────────────────────────────────────────────
function renderSummary(json) {
    const content = json.content || {};

    // Estado global para favorito y notas
    window._currentSummaryId = json.id;
    window._isFavorite       = json.isFavorite;

    document.title = `Mi ProfesorIA - ${json.title}`;

    setEl('resumenTitle', json.title);
    setEl('readTime',  content.readingMinutes ?? '--');
    setEl('createdAt', formatDate(json.createdAt));

    updateFavoriteButton(json.isFavorite);

    // ── Secciones ──
    let html = '';
    (content.sections || []).forEach(sec => {
        const highlight = sec.highlight
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
                    <span class="section-number">${escapeHtml(String(sec.number ?? ''))}</span>
                    ${escapeHtml(sec.heading ?? '')}
                </h2>
                <p class="resumen-text">${escapeHtml(sec.body ?? '')}</p>
                ${highlight}
            </section>`;
    });

    const contentDiv = document.getElementById('resumenContent');
    if (contentDiv) contentDiv.innerHTML = html;

    // ── Palabras clave ──
    const kws       = content.keywords || [];
    const kwSection = document.getElementById('keywordsSection');
    if (kws.length > 0 && kwSection) {
        document.getElementById('keywordsContainer').innerHTML =
            kws.map(kw => `<span class="keyword-tag">${escapeHtml(kw)}</span>`).join('');
        kwSection.style.display = '';
    } else if (kwSection) {
        kwSection.style.display = 'none';
    }
}

// ─── Control de estados ───────────────────────────────────────
function showState(state) {
    document.getElementById('loadingState').style.display = state === 'loading' ? '' : 'none';
    document.getElementById('errorState').style.display   = state === 'error'   ? '' : 'none';
    document.getElementById('resumenCard').style.display  = state === 'card'    ? '' : 'none';
}

function showError(title, message) {
    setEl('errorTitle',   title);
    setEl('errorMessage', message);
    showState('error');
}

// ─── Favorito ─────────────────────────────────────────────────
async function toggleFavorite() {
    if (!window._currentSummaryId) return;
    const newValue = !window._isFavorite;

    try {
        const res  = await fetch(`${API}/favorite`, {
            method:  'POST',
            headers: { ...authHeaders(), 'X-HTTP-Method-Override': 'PATCH' },
            body:    JSON.stringify({ contentId: window._currentSummaryId, isFavorite: newValue })
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

function updateFavoriteButton(isFav) {
    const btn  = document.getElementById('favoriteBtn');
    const icon = btn?.querySelector('i');
    if (!btn || !icon) return;
    if (isFav) { icon.classList.replace('far', 'fas'); btn.classList.add('active'); }
    else       { icon.classList.replace('fas', 'far'); btn.classList.remove('active'); }
}

// ─── Notas (localStorage — sin backend) ──────────────────────
function openNotesModal() {
    const key      = `notes_${window._currentSummaryId || 'draft'}`;
    const textarea = document.getElementById('notesTextarea');
    if (textarea) { textarea.value = localStorage.getItem(key) || ''; updateCharCounter(); }
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
    localStorage.setItem(`notes_${window._currentSummaryId || 'draft'}`, ta.value);
    closeNotesModal();
    showToast('Nota guardada correctamente', 'fa-check-circle');
}

// ─── Descarga PDF (simulada) ───────────────────────────────────
function downloadPDF() {
    const modal = document.getElementById('downloadModal');
    const bar   = document.getElementById('downloadBarFill');
    modal.classList.add('show');
    bar.style.width = '0%';
    setEl('downloadTitle',   'Preparando descarga...');
    setEl('downloadMessage', 'Tu PDF estará listo en un momento');

    let progress = 0;
    const interval = setInterval(() => {
        progress += Math.random() * 15;
        if (progress >= 100) {
            progress = 100;
            clearInterval(interval);
            setEl('downloadTitle',   '¡Descarga completa!');
            setEl('downloadMessage', 'Tu PDF ha sido descargado');
            setTimeout(() => {
                modal.classList.remove('show');
                showToast('PDF descargado correctamente', 'fa-file-pdf');
            }, 1500);
        }
        bar.style.width = progress + '%';
    }, 200);
}

// ─── Animaciones ──────────────────────────────────────────────
function generateStars() {
    const bg = document.getElementById('starsBackground');
    if (!bg) return;
    let html = '';
    for (let i = 0; i < 120; i++) {
        const size  = 1 + Math.random() * 2;
        const delay = Math.random() * 3;
        const dur   = 2 + Math.random() * 3;
        html += `<div class="bg-star" style="width:${size}px;height:${size}px;left:${Math.random()*100}%;top:${Math.random()*100}%;animation-delay:${delay}s;animation-duration:${dur}s"></div>`;
    }
    bg.innerHTML = html;
}

function generateNotesSparkles() {
    const c = document.getElementById('notesSparkles');
    if (!c) return;
    let html = '';
    for (let i = 0; i < 15; i++) {
        const size = 3 + Math.random() * 5, delay = Math.random() * 2;
        html += `<div class="sparkle" style="width:${size}px;height:${size}px;left:${Math.random()*100}%;top:${Math.random()*100}%;animation-delay:${delay}s"></div>`;
    }
    c.innerHTML = html;
}

function createHeartSparkles() {
    const c = document.getElementById('sparklesContainer');
    if (!c) return;
    c.innerHTML = '';
    for (let i = 0; i < 12; i++) {
        const s     = document.createElement('div');
        s.className = 'heart-sparkle';
        const angle = (i / 12) * 360, dist = 20 + Math.random() * 30;
        s.style.setProperty('--x', `${Math.cos(angle * Math.PI / 180) * dist}px`);
        s.style.setProperty('--y', `${Math.sin(angle * Math.PI / 180) * dist}px`);
        s.style.animationDelay = `${Math.random() * 0.3}s`;
        c.appendChild(s);
    }
    setTimeout(() => c.innerHTML = '', 800);
}

// ─── Utilidades ───────────────────────────────────────────────
function goBack() { window.history.back(); }

function setEl(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function formatDate(str) {
    if (!str) return '--';
    try {
        return new Date(str).toLocaleDateString('es-ES', {
            day: '2-digit', month: 'long', year: 'numeric'
        });
    } catch (_) { return str; }
}

function showToast(message, icon = 'fa-check-circle') {
    const toast = document.getElementById('toast');
    if (!toast) return;
    document.getElementById('toastIcon').className      = 'fas ' + icon;
    document.getElementById('toastMessage').textContent = message;
    toast.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => toast.classList.remove('show'), 3000);
}

function escapeHtml(str) {
    return String(str || '')
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}