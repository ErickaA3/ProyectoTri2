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

const API = (window.API_BASE || '') + '/api/summaries';

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
    (content.sections || []).forEach((sec, idx) => {
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
                    <span class="section-number">${sec.number && sec.number !== 'null' ? escapeHtml(String(sec.number)) : String(idx + 1).padStart(2, '0')}</span>
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

// ─── Descarga PDF real con jsPDF ─────────────────────────────
function downloadPDF() {
    if (!window.jspdf?.jsPDF) {
        showToast('jsPDF no cargado, intenta de nuevo', 'fa-circle-xmark');
        return;
    }

    const modal = document.getElementById('downloadModal');
    const bar   = document.getElementById('downloadBarFill');
    modal.classList.add('show');
    bar.style.width = '0%';
    setEl('downloadTitle',   'Generando PDF...');
    setEl('downloadMessage', 'Un momento por favor');

    // Pequeño delay para mostrar el modal antes de bloquear el hilo
    setTimeout(() => {
        try {
            const { jsPDF } = window.jspdf;
            const doc = new jsPDF({ unit: 'mm', format: 'a4' });

            const pageW  = doc.internal.pageSize.getWidth();
            const pageH  = doc.internal.pageSize.getHeight();
            const margin = 20;
            const inner  = pageW - margin * 2;
            let y = 0;

            // Colores del tema
            const BG       = [12, 12, 28];      // #0c0c1c
            const BG2      = [22, 22, 46];       // #16162e
            const CARD     = [30, 30, 58];       // #1e1e3a
            const CYAN     = [45, 212, 191];     // #2dd4bf
            const PURPLE   = [139, 92, 246];     // #8b5cf6
            const AMBER    = [251, 191, 36];     // #fbbf24
            const TXT      = [230, 230, 248];    // texto principal
            const TXT2     = [140, 140, 180];    // texto secundario
            const BORDER   = [45, 45, 80];       // bordes

            const setRGB  = (arr) => { doc.setTextColor(...arr); };
            const setFill = (arr) => { doc.setFillColor(...arr); };
            const setDraw = (arr) => { doc.setDrawColor(...arr); };

            const addPage = () => {
                // Footer en página anterior
                doc.setFontSize(7.5);
                setRGB([70, 70, 110]);
                doc.setFont('helvetica', 'normal');
                doc.text(`Polaris  ·  página ${doc.internal.getNumberOfPages()}`, pageW / 2, pageH - 8, { align: 'center' });
                // Línea footer
                setDraw([40, 40, 75]);
                doc.setLineWidth(0.3);
                doc.line(margin, pageH - 12, pageW - margin, pageH - 12);
                doc.addPage();
                // Fondo nueva página
                setFill(BG);
                doc.rect(0, 0, pageW, pageH, 'F');
                y = margin;
            };

            const checkY = (needed = 10) => { if (y + needed > pageH - 18) addPage(); };

            // ═══════════════════════════════════════════════════════
            // PÁGINA 1 — PORTADA
            // ═══════════════════════════════════════════════════════
            setFill(BG);
            doc.rect(0, 0, pageW, pageH, 'F');

            // Franja decorativa superior (gradiente simulado con rectángulos)
            setFill(BG2);
            doc.rect(0, 0, pageW, 40, 'F');

            // Acento cyan izquierdo
            setFill(CYAN);
            doc.rect(0, 0, 4, 40, 'F');

            // Texto "RESUMEN" en la franja
            y = 16;
            setRGB(CYAN);
            doc.setFontSize(8.5);
            doc.setFont('helvetica', 'bold');
            doc.text('RESUMEN DE ESTUDIO', margin + 4, y);

            // Fecha a la derecha
            const createdAt = document.getElementById('createdAt')?.textContent || '';
            setRGB(TXT2);
            doc.setFontSize(8);
            doc.setFont('helvetica', 'normal');
            doc.text(createdAt, pageW - margin, y, { align: 'right' });

            // Subtítulo
            y = 28;
            const readTime = document.getElementById('readTime')?.textContent || '--';
            setRGB([80, 80, 120]);
            doc.setFontSize(7.5);
            doc.text(`${readTime} min de lectura`, margin + 4, y);

            // ── Título principal ──
            y = 64;
            const title = document.getElementById('resumenTitle')?.textContent || 'Resumen';
            setRGB(TXT);
            doc.setFontSize(22);
            doc.setFont('helvetica', 'bold');
            const titleLines = doc.splitTextToSize(title, inner);
            doc.text(titleLines, margin, y);
            y += titleLines.length * 10 + 6;

            // Línea decorativa bajo el título
            setFill(CYAN);
            doc.rect(margin, y, 40, 1.2, 'F');
            setFill(PURPLE);
            doc.rect(margin + 42, y, 20, 1.2, 'F');
            setFill([50, 50, 90]);
            doc.rect(margin + 64, y, inner - 64, 1.2, 'F');
            y += 14;

            // ── Keywords en portada (los primeros 6) ──
            const kwEls = Array.from(document.querySelectorAll('.keyword-tag')).slice(0, 7);
            if (kwEls.length > 0) {
                const kwColors = [CYAN, PURPLE, AMBER, [236, 72, 153], [56, 189, 248], [52, 211, 153], [251, 146, 60]];
                let kwX = margin;
                kwEls.forEach((el, i) => {
                    const kw = el.textContent.trim();
                    const col = kwColors[i % kwColors.length];
                    doc.setFontSize(8);
                    doc.setFont('helvetica', 'normal');
                    const tw = doc.getTextWidth(kw) + 10;
                    if (kwX + tw > pageW - margin) { kwX = margin; y += 10; }
                    // Fondo pill
                    doc.setFillColor(col[0], col[1], col[2]);
                    doc.setGState(new doc.GState({ opacity: 0.15 }));
                    doc.roundedRect(kwX, y - 5, tw, 8, 2, 2, 'F');
                    doc.setGState(new doc.GState({ opacity: 1 }));
                    // Borde
                    doc.setDrawColor(col[0], col[1], col[2]);
                    doc.setLineWidth(0.3);
                    doc.roundedRect(kwX, y - 5, tw, 8, 2, 2, 'S');
                    // Texto
                    doc.setTextColor(col[0], col[1], col[2]);
                    doc.text(kw, kwX + 5, y);
                    kwX += tw + 4;
                });
                y += 18;
            }

            // ── Caja de estadísticas ──
            checkY(28);
            setFill(CARD);
            doc.roundedRect(margin, y, inner, 22, 4, 4, 'F');
            setDraw(BORDER);
            doc.setLineWidth(0.3);
            doc.roundedRect(margin, y, inner, 22, 4, 4, 'S');

            const sections = document.querySelectorAll('.resumen-section');
            const highlights = document.querySelectorAll('.highlight-box');
            const kwAll = document.querySelectorAll('.keyword-tag');

            const stats = [
                { label: 'SECCIONES', value: String(sections.length), color: CYAN },
                { label: 'HIGHLIGHTS', value: String(highlights.length), color: PURPLE },
                { label: 'PALABRAS CLAVE', value: String(kwAll.length), color: AMBER },
                { label: 'TIEMPO LECTURA', value: readTime + ' min', color: [56, 189, 248] },
            ];

            const colW = inner / stats.length;
            stats.forEach((s, i) => {
                const cx = margin + colW * i + colW / 2;
                doc.setFont('helvetica', 'bold');
                doc.setFontSize(14);
                doc.setTextColor(...s.color);
                doc.text(s.value, cx, y + 10, { align: 'center' });
                doc.setFont('helvetica', 'normal');
                doc.setFontSize(6.5);
                doc.setTextColor(...TXT2);
                doc.text(s.label, cx, y + 17, { align: 'center' });
                // separador
                if (i < stats.length - 1) {
                    setDraw(BORDER);
                    doc.setLineWidth(0.3);
                    doc.line(margin + colW * (i + 1), y + 4, margin + colW * (i + 1), y + 19);
                }
            });
            y += 32;

            // ═══════════════════════════════════════════════════════
            // SECCIONES DE CONTENIDO
            // ═══════════════════════════════════════════════════════
            bar.style.width = '30%';

            sections.forEach((sec, i) => {
                checkY(32);

                const num     = sec.querySelector('.section-number')?.textContent?.trim() || String(i + 1).padStart(2, '0');
                const heading = sec.querySelector('.section-title')?.textContent?.replace(num, '').trim() || '';
                const body    = sec.querySelector('.resumen-text')?.textContent?.trim() || '';
                const hl      = sec.querySelector('.highlight-content p')?.textContent?.trim();

                // ── Header de sección ──
                setFill(BG2);
                doc.roundedRect(margin, y, inner, 12, 2, 2, 'F');

                // Acento izquierdo de color variable
                const sColors = [CYAN, PURPLE, AMBER, [236, 72, 153], [56, 189, 248]];
                const sc = sColors[i % sColors.length];
                setFill(sc);
                doc.roundedRect(margin, y, 2.5, 12, 1, 1, 'F');

                // Número
                doc.setFont('helvetica', 'bold');
                doc.setFontSize(8);
                doc.setTextColor(...sc);
                doc.text(num, margin + 7, y + 7.5);

                // Heading
                doc.setFont('helvetica', 'bold');
                doc.setFontSize(10.5);
                setRGB(TXT);
                const hLines = doc.splitTextToSize(heading, inner - 26);
                doc.text(hLines[0] || '', margin + 18, y + 7.5);
                y += 16;

                // ── Body ──
                doc.setFont('helvetica', 'normal');
                doc.setFontSize(9.5);
                setRGB(TXT2);
                const bodyLines = doc.splitTextToSize(body, inner);
                bodyLines.forEach(line => {
                    checkY(6);
                    doc.text(line, margin, y);
                    y += 5.8;
                });
                y += 3;

                // ── Highlight ──
                if (hl && hl.length > 2) {
                    checkY(22);
                    const hlH = Math.max(18, Math.ceil(doc.splitTextToSize(hl, inner - 22).length * 5.5) + 12);
                    setFill(CARD);
                    doc.roundedRect(margin, y, inner, hlH, 3, 3, 'F');
                    setFill(CYAN);
                    doc.roundedRect(margin, y, 3, hlH, 1.5, 1.5, 'F');

                    doc.setFont('helvetica', 'bold');
                    doc.setFontSize(7.5);
                    setRGB(CYAN);
                    doc.text('DATO IMPORTANTE', margin + 8, y + 7);

                    doc.setFont('helvetica', 'normal');
                    doc.setFontSize(9);
                    setRGB(TXT);
                    const hlLines = doc.splitTextToSize(hl, inner - 12);
                    hlLines.forEach((line, li) => {
                        doc.text(line, margin + 8, y + 13 + li * 5.5);
                    });
                    y += hlH + 4;
                }

                y += 6;
                bar.style.width = Math.min(30 + ((i + 1) / sections.length) * 55, 85) + '%';
            });

            // ═══════════════════════════════════════════════════════
            // KEYWORDS COMPLETAS
            // ═══════════════════════════════════════════════════════
            const allKws = Array.from(document.querySelectorAll('.keyword-tag')).map(k => k.textContent.trim());
            if (allKws.length > 0) {
                checkY(30);
                y += 4;
                setFill(BG2);
                doc.rect(margin, y, inner, 0.5, 'F');
                y += 8;

                doc.setFont('helvetica', 'bold');
                doc.setFontSize(9);
                setRGB(PURPLE);
                doc.text('PALABRAS CLAVE', margin, y);
                y += 8;

                const kwColors2 = [CYAN, PURPLE, AMBER, [236, 72, 153], [56, 189, 248], [52, 211, 153], [251, 146, 60]];
                let kwX2 = margin;
                allKws.forEach((kw, i) => {
                    const col = kwColors2[i % kwColors2.length];
                    doc.setFontSize(8);
                    doc.setFont('helvetica', 'normal');
                    const tw = doc.getTextWidth(kw) + 10;
                    if (kwX2 + tw > pageW - margin) { kwX2 = margin; y += 10; }
                    checkY(10);
                    doc.setFillColor(col[0], col[1], col[2]);
                    doc.setGState(new doc.GState({ opacity: 0.12 }));
                    doc.roundedRect(kwX2, y - 5, tw, 8, 2, 2, 'F');
                    doc.setGState(new doc.GState({ opacity: 1 }));
                    doc.setDrawColor(col[0], col[1], col[2]);
                    doc.setLineWidth(0.3);
                    doc.roundedRect(kwX2, y - 5, tw, 8, 2, 2, 'S');
                    doc.setTextColor(col[0], col[1], col[2]);
                    doc.text(kw, kwX2 + 5, y);
                    kwX2 += tw + 5;
                });
                y += 14;
            }

            // Footer última página
            doc.setFontSize(7.5);
            setRGB([70, 70, 110]);
            doc.setFont('helvetica', 'normal');
            doc.text(`Polaris  ·  página ${doc.internal.getNumberOfPages()}`, pageW / 2, pageH - 8, { align: 'center' });
            setDraw([40, 40, 75]);
            doc.setLineWidth(0.3);
            doc.line(margin, pageH - 12, pageW - margin, pageH - 12);

            bar.style.width = '100%';

            const fileName = title.replace(/[^a-zA-Z\u00C0-\u024F0-9\s]/g, '').trim().substring(0, 60) || 'resumen';
            doc.save(`${fileName}.pdf`);

            setEl('downloadTitle',   '¡PDF generado!');
            setEl('downloadMessage', 'La descarga comenzó automáticamente');
            setTimeout(() => {
                modal.classList.remove('show');
                showToast('PDF descargado', 'fa-file-pdf');
            }, 1000);

        } catch (err) {
            modal.classList.remove('show');
            showToast('Error generando PDF', 'fa-circle-xmark');
            console.error('[downloadPDF]', err);
        }
    }, 80);
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