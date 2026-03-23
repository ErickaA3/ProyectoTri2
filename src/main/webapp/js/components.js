/* ===== COMPONENTES JS - Polaris / Mi ProfesorIA ===== */

// ── Datos del usuario ──
function getUserData() {
    const raw = localStorage.getItem('user');
    if (!raw) return null;
    try { return JSON.parse(raw); }
    catch { return null; }
}

// ── Ruta base ──
function getBasePath() {
    const path = window.location.pathname;
    if (path.includes('/pages/')) return '../';
    return './';
}

// ── HTML del Navbar ──
function getNavbarHTML(base) {
    const user      = getUserData();
    const racha     = user?.stats?.streakCurrent  ?? 0;
    const xp        = user?.stats?.xp             ?? 0;
    const monedas   = user?.stats?.coins           ?? 0;
    const hasShield = user?.stats?.hasStreakShield ?? false;

    return `
    <nav class="main-navbar">
        <div class="navbar-left">
            <button class="menu-toggle" onclick="toggleMenu()">
                <i class="fas fa-bars"></i>
            </button>
            <div class="logo">
                <img src="${base}images/icons/PolarisLogo.svg" alt="Polaris" class="polaris-logotype" style="height:38px;width:auto;object-fit:contain;" onerror="this.onerror=null;this.style.display='none';this.parentElement.innerHTML='<span class=\\'logo-text\\' style=\\'color:#2dd4bfe6;font-style:italic;font-size:1.4rem;\\'>Polaris</span>';">
            </div>
        </div>
        <div class="navbar-stats">

            <!-- Escudo protector (separado de la racha) — solo si tiene shield Y hay countdown activo -->
            ${(hasShield && localStorage.getItem('shieldActivatedAt')) ? `
            <div class="shield-pill" id="shieldPill">
                <div class="shield-icon-wrap">
                    <i class="fas fa-shield-alt"></i>
                </div>
                <div class="shield-expand">
                    <i class="fas fa-clock"></i>
                    <span class="shield-timer" id="shieldCountdown">activo</span>
                </div>
            </div>
            ` : ''}

            <!-- Racha -->
            <div class="stat-pill stat-pill-streak" id="streakPill">
                <img src="${base}images/gifs/fire.gif" alt="🔥" class="fire-gif" onerror="this.outerHTML='<i class=\\'fas fa-fire\\' style=\\'color:#ff6b6b\\'></i>'">
                <span class="stat-value">${racha} Días</span>
                <span class="stat-label">Racha</span>
            </div>

            <div class="stat-pill" id="xpPill">
                <img src="${base}images/gifs/star.gif" alt="⭐" class="star-gif" onerror="this.outerHTML='<i class=\\'fas fa-star\\' style=\\'color:#ffd93d\\'></i>'">
                <span class="stat-value">${xp.toLocaleString()}</span>
                <span class="stat-label">XP</span>
            </div>
            <div class="stat-pill" id="coinsPill">
                <img src="${base}images/gifs/coin.gif" alt="💵" class="money-gif" onerror="this.outerHTML='<i class=\\'fas fa-coins\\' style=\\'color:#f59e0b\\'></i>'">
                <span class="stat-value">${monedas.toLocaleString()}</span>
                <span class="stat-label">Monedas</span>
            </div>
        </div>
    </nav>
    `;
}

// ── Generar estrellas para el sidebar ──
function generateStars(count) {
    let stars = '';
    for (let i = 0; i < count; i++) {
        const x = Math.random() * 100;
        const y = Math.random() * 100;
        const size = (Math.random() * 2 + 0.5).toFixed(1);
        const delay = (Math.random() * 4).toFixed(1);
        const dur = (Math.random() * 2 + 2).toFixed(1);
        stars += `<div class="sb-star" style="left:${x}%;top:${y}%;width:${size}px;height:${size}px;animation-delay:${delay}s;animation-duration:${dur}s;"></div>`;
    }
    return stars;
}

// ── HTML del Sidebar (misma estructura original, solo cambios cosméticos) ──
function getSidebarHTML(base) {
    const user   = getUserData();
    const nombre = user?.fullName || user?.username || 'Estudiante';
    const nivel  = user?.stats?.level ?? 1;

    return `
    <div class="sidebar-overlay" onclick="toggleMenu()"></div>

    <aside class="sidebar-cards" id="sidebar">
        <!-- Fondo estrellado -->
        <div class="sb-stars">${generateStars(50)}</div>

        <div class="user-profile">
            <div class="user-avatar" id="sidebarFrame" style="width:90px;height:90px;overflow:visible;display:flex;align-items:center;justify-content:center;">
                <i class="fas fa-user"></i>
            </div>
            <div class="user-name">${nombre}</div>
            <div class="user-level">Nivel ${nivel}</div>
        </div>

        <div class="nav-cards">
            <!-- Perfil -->
            <a href="${base}pages/perfil.html" class="nav-card" data-page="perfil">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#818cf8,#6366f1);">
                    <i class="fas fa-user" style="color:#fff;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Perfil</div>
                    <div class="nav-card-desc">Tu información</div>
                </div>
            </a>

            <!-- Modo Estudio (original dorado) -->
            <a href="${base}pages/modo-estudio.html" class="nav-card modo-estudio" data-page="modo-estudio">
                <div class="nav-card-icon gradient-estudio">
                    <i class="fas fa-book-open"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Modo Estudio</div>
                    <div class="nav-card-desc">Aprende a tu ritmo</div>
                </div>
            </a>

            <!-- Mi Profesor -->
            <a href="${base}pages/chat.html" class="nav-card" data-page="chat">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#38bdf8,#6366f1);">
                    <i class="fas fa-robot" style="color:#fff;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Mi Profesor</div>
                    <div class="nav-card-desc">Asistente IA</div>
                </div>
            </a>

            <!-- Duelos -->
            <a href="${base}pages/duelos.html" class="nav-card" data-page="duelos">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#c084fc,#9333ea);">
                    <i class="fas fa-shield-alt" style="color:#fff;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Duelos</div>
                    <div class="nav-card-desc">Reta a otros</div>
                </div>
            </a>

            <!-- Tienda -->
            <a href="${base}pages/tienda.html" class="nav-card" data-page="tienda">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#fbbf24,#f59e0b);">
                    <i class="fas fa-store" style="color:#1a1a2e;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Tienda</div>
                    <div class="nav-card-desc">Personaliza tu perfil</div>
                </div>
            </a>

            <!-- Favoritos -->
            <a href="${base}pages/favoritos.html" class="nav-card" data-page="favoritos">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#fb7185,#e11d48);">
                    <i class="fas fa-heart" style="color:#fff;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Favoritos</div>
                    <div class="nav-card-desc">Contenido guardado</div>
                </div>
            </a>

            <!-- Historial -->
            <a href="${base}pages/historial.html" class="nav-card" data-page="historial">
                <div class="nav-card-icon" style="background:linear-gradient(135deg,#94a3b8,#64748b);">
                    <i class="fas fa-clock-rotate-left" style="color:#fff;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Historial</div>
                    <div class="nav-card-desc">Tu actividad reciente</div>
                </div>
            </a>

            <!-- Cerrar Sesión -->
            <div class="nav-card nav-card-logout" onclick="mostrarModalLogout()">
                <div class="nav-card-icon" style="background:transparent;border:1px solid rgba(248,113,113,0.3);">
                    <i class="fas fa-sign-out-alt" style="color:#f87171;"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title" style="color:#f87171;">Cerrar Sesión</div>
                    <div class="nav-card-desc">Salir de tu cuenta</div>
                </div>
            </div>
        </div>
    </aside>

    <!-- Modal Cerrar Sesión -->
    <div class="logout-modal-overlay" id="logoutModal">
        <div class="logout-modal">
            <div class="logout-modal-icon">
                <i class="fas fa-moon"></i>
            </div>
            <h3 class="logout-modal-title">¿Te vas tan pronto?</h3>
            <p class="logout-modal-text">Tu progreso está guardado. Vuelve cuando quieras seguir aprendiendo.</p>
            <div class="logout-modal-actions">
                <button class="logout-btn-cancel" onclick="cerrarModalLogout()">
                    <i class="fas fa-arrow-left"></i> Quedarme
                </button>
                <button class="logout-btn-confirm" onclick="ejecutarLogout()">
                    Cerrar Sesión <i class="fas fa-sign-out-alt"></i>
                </button>
            </div>
        </div>
    </div>
    `;
}

// ══════════════════════════════════════════════════
// ESTILOS — Solo cosméticos, NO tocan layout/estructura
// ══════════════════════════════════════════════════

function injectSidebarStyles() {
    if (document.getElementById('polaris-sidebar-styles')) return;
    const style = document.createElement('style');
    style.id = 'polaris-sidebar-styles';
    style.textContent = `
    /* ── Fondo estrellado (solo background, no toca layout) ── */
    #sidebar {
        background: linear-gradient(165deg, #1a1a3e 0%, #23234b 40%, #1e1240 70%, #191935 100%) !important;
    }
    .sb-stars {
        position: absolute;
        inset: 0;
        pointer-events: none;
        z-index: 0;
        overflow: hidden;
    }
    .sb-star {
        position: absolute;
        background: #fff;
        border-radius: 50%;
        opacity: 0;
        animation: sbTwinkle ease-in-out infinite;
    }
    @keyframes sbTwinkle {
        0%, 100% { opacity: 0.1; transform: scale(0.8); }
        50% { opacity: 0.75; transform: scale(1.2); }
    }

    /* Quitar degradado celeste-morado del user-profile */
    #sidebar .user-profile {
        background: transparent !important;
    }

    /* Contenido sobre las estrellas */
    #sidebar .user-profile,
    #sidebar .nav-cards {
        position: relative;
        z-index: 1;
    }

    /* Logout card diferenciado */
    .nav-card-logout {
        cursor: pointer;
        opacity: 0.75;
        transition: opacity 0.2s ease;
    }
    .nav-card-logout:hover {
        opacity: 1;
        border-color: rgba(248,113,113,0.3) !important;
    }

    /* ── Modal Cerrar Sesión ── */
    .logout-modal-overlay {
        position: fixed;
        inset: 0;
        background: rgba(0,0,0,0);
        backdrop-filter: blur(0px);
        z-index: 9999;
        display: flex;
        align-items: center;
        justify-content: center;
        pointer-events: none;
        opacity: 0;
        transition: all 0.3s ease;
    }
    .logout-modal-overlay.active {
        background: rgba(0,0,0,0.55);
        backdrop-filter: blur(6px);
        pointer-events: all;
        opacity: 1;
    }
    .logout-modal {
        background: linear-gradient(165deg, #23234b, #1a1a3e);
        border: 1px solid rgba(139,92,246,0.2);
        border-radius: 20px;
        padding: 2rem 2.25rem;
        max-width: 380px;
        width: 90%;
        text-align: center;
        transform: scale(0.85) translateY(20px);
        transition: transform 0.35s cubic-bezier(0.34,1.56,0.64,1);
        box-shadow: 0 25px 60px rgba(0,0,0,0.5), 0 0 40px rgba(139,92,246,0.08);
    }
    .logout-modal-overlay.active .logout-modal {
        transform: scale(1) translateY(0);
    }
    .logout-modal-icon {
        width: 56px;
        height: 56px;
        border-radius: 50%;
        background: linear-gradient(135deg, #6366f1, #8b5cf6);
        display: flex;
        align-items: center;
        justify-content: center;
        margin: 0 auto 1rem;
        font-size: 1.4rem;
        color: #fff;
        box-shadow: 0 4px 20px rgba(99,102,241,0.3);
    }
    .logout-modal-title {
        font-size: 1.2rem;
        font-weight: normal;
        color: #fff;
        margin-bottom: 0.5rem;
    }
    .logout-modal-text {
        font-size: 0.85rem;
        color: rgba(255,255,255,0.5);
        margin-bottom: 1.5rem;
        line-height: 1.5;
    }
    .logout-modal-actions {
        display: flex;
        gap: 0.75rem;
    }
    .logout-btn-cancel,
    .logout-btn-confirm {
        flex: 1;
        padding: 0.7rem 1rem;
        border-radius: 12px;
        font-size: 0.85rem;
        font-family: inherit;
        cursor: pointer;
        transition: all 0.2s ease;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0.4rem;
        border: none;
    }
    .logout-btn-cancel {
        background: rgba(255,255,255,0.06);
        border: 1px solid rgba(255,255,255,0.1);
        color: rgba(255,255,255,0.7);
    }
    .logout-btn-cancel:hover {
        background: rgba(255,255,255,0.1);
        color: #fff;
    }
    .logout-btn-confirm {
        background: linear-gradient(135deg, #ef4444, #dc2626);
        color: #fff;
        box-shadow: 0 4px 15px rgba(239,68,68,0.3);
    }
    .logout-btn-confirm:hover {
        transform: translateY(-1px);
        box-shadow: 0 6px 20px rgba(239,68,68,0.4);
    }
    `;
    document.head.appendChild(style);
}

// ── Modal logout ──
function mostrarModalLogout() {
    const modal = document.getElementById('logoutModal');
    if (modal) {
        modal.classList.add('active');
        const sidebar = document.getElementById('sidebar');
        if (sidebar?.classList.contains('active')) toggleMenu();
    }
}

function cerrarModalLogout() {
    const modal = document.getElementById('logoutModal');
    if (modal) modal.classList.remove('active');
}

function ejecutarLogout() {
    localStorage.removeItem('user');
    localStorage.removeItem('token');
    localStorage.removeItem('supabase.auth.token');
    window.location.href = getBasePath() + 'index.html';
}

// ── Inicializar componentes ──
function initComponents() {
    const base = getBasePath();

    injectSidebarStyles();

    // Insertar navbar
    const navbarContainer = document.getElementById('navbar-container');
    if (navbarContainer) {
        navbarContainer.innerHTML = getNavbarHTML(base);
    }

    // Insertar sidebar
    const sidebarContainer = document.getElementById('sidebar-container');
    if (sidebarContainer) {
        sidebarContainer.innerHTML = getSidebarHTML(base);
    }

    // Marcar página activa
    setActivePage();

    // Auto-cargar marcos
    loadMarcosIfNeeded();

    // Sincronización reactiva
    listenForUserUpdates();

    // Auto-refrescar stats desde el servidor (no depender solo de localStorage)
    autoRefreshStats();

    // Countdown del escudo protector
    initShieldCountdown();

    // Transición suave entre páginas
    initPageTransitions();

    // Modal: cerrar con Escape o click fuera
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') cerrarModalLogout();
    });
    document.getElementById('logoutModal')?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) cerrarModalLogout();
    });
}

// Marcar la página activa en el menú
function setActivePage() {
    // Limpiar activo anterior
    document.querySelectorAll('.nav-card.active').forEach(el => el.classList.remove('active'));
    const currentPage = document.body.dataset.page;
    if (currentPage) {
        const activeLink = document.querySelector(`.nav-card[data-page="${currentPage}"]`);
        if (activeLink) activeLink.classList.add('active');
    }
}

// Toggle del menú mobile
function toggleMenu() {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.querySelector('.sidebar-overlay');

    if (sidebar && overlay) {
        sidebar.classList.toggle('active');
        overlay.classList.toggle('active');
        document.body.style.overflow = sidebar.classList.contains('active') ? 'hidden' : '';
    }
}

// ══════════════════════════════════════════════════
// MARCOS — Carga y sincronización
// ══════════════════════════════════════════════════

let _sidebarCurrentLevel = null;

// Auto-cargar marcos.css y marcos.js para el sidebar
function loadMarcosIfNeeded() {
    const base = getBasePath();

    // Cargar CSS si no existe
    if (!document.querySelector('link[href*="marcos.css"]')) {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = base + 'css/marcos.css';
        document.head.appendChild(link);
    }

    // Cargar fuente Cinzel si no existe
    if (!document.querySelector('link[href*="Cinzel"]')) {
        const font = document.createElement('link');
        font.rel = 'stylesheet';
        font.href = 'https://fonts.googleapis.com/css2?family=Cinzel+Decorative:wght@700;900&display=swap';
        document.head.appendChild(font);
    }

    // Si marcos.js ya está cargado, renderizar directo
    if (typeof renderFrame === 'function') {
        renderSidebarFrame();
        return;
    }

    // Si no, cargarlo dinámicamente
    const script = document.createElement('script');
    script.src = base + 'js/marcos.js';
    script.onload = () => renderSidebarFrame();
    document.body.appendChild(script);
}

function renderSidebarFrame() {
    if (typeof renderFrame !== 'function') return;
    const base = getBasePath();
    const user = getUserData();
    const lvl  = user?.stats?.level ?? 1;

    // Solo re-renderizar si el nivel cambió
    if (_sidebarCurrentLevel === lvl) return;
    _sidebarCurrentLevel = lvl;

    renderFrame(lvl, 'sidebarFrame', null, 0.35);
    const av = document.querySelector('#sidebarFrame .av');
    if (av) {
        const img = document.createElement('img');
        img.src = base + 'images/perfil/perfil_ejemplo.png';
        img.style.cssText = 'position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:100%;height:100%;object-fit:cover;border-radius:50%;z-index:2';
        img.onerror = function() { this.style.display = 'none'; };
        av.appendChild(img);
    }
}

// ══════════════════════════════════════════════════
// SINCRONIZACIÓN REACTIVA (sin recargar página)
// ══════════════════════════════════════════════════

function listenForUserUpdates() {
    // Cambios de localStorage desde otras pestañas
    window.addEventListener('storage', (e) => {
        if (e.key === 'user') {
            refreshNavbarStats();
            refreshSidebarInfo();
        }
    });

    // Evento custom para cambios dentro de la misma pestaña
    window.addEventListener('userDataUpdated', () => {
        refreshNavbarStats();
        refreshSidebarInfo();
    });
}

// Llamar después de cualquier cambio a localStorage.user:
//   window.dispatchEvent(new Event('userDataUpdated'));
function notifyUserUpdate() {
    window.dispatchEvent(new Event('userDataUpdated'));
}

// Refrescar stats del navbar sin recargar la página
function refreshNavbarStats() {
    const user = getUserData();
    if (!user) return;
    const xp      = user.stats?.xp            ?? 0;
    const monedas = user.stats?.coins          ?? 0;
    const racha   = user.stats?.streakCurrent  ?? 0;

    // ── Actualizar por ID (robusto, no depende del orden del DOM) ──
    animateStatUpdate('streakPill', racha + ' Días');
    animateStatUpdate('xpPill',     xp.toLocaleString());
    animateStatUpdate('coinsPill',  monedas.toLocaleString());

    // Sincronizar pill del escudo protector (separado)
    const hasShield = user.stats?.hasStreakShield ?? false;
    const hasTimer  = !!localStorage.getItem('shieldActivatedAt');
    const shieldPill = document.getElementById('shieldPill');
    if (hasShield && hasTimer && !shieldPill) {
        const stats = document.querySelector('.navbar-stats');
        const streakPill = document.getElementById('streakPill');
        if (stats && streakPill) {
            const sp = document.createElement('div');
            sp.className = 'shield-pill';
            sp.id = 'shieldPill';
            sp.innerHTML = `
                <div class="shield-icon-wrap">
                    <i class="fas fa-shield-alt"></i>
                </div>
                <div class="shield-expand">
                    <i class="fas fa-clock"></i>
                    <span class="shield-timer" id="shieldCountdown">activo</span>
                </div>`;
            stats.insertBefore(sp, streakPill);
            initShieldCountdown();
        }
    } else if ((!hasShield || !hasTimer) && shieldPill) {
        shieldPill.remove();
    }
}

/**
 * Actualiza el .stat-value dentro de un pill por ID.
 * Si el valor cambió, hace un breve flash visual para que el usuario lo note.
 */
function animateStatUpdate(pillId, newValue) {
    const pill = document.getElementById(pillId);
    if (!pill) return;
    const el = pill.querySelector('.stat-value');
    if (!el) return;

    const oldValue = el.textContent.trim();
    if (oldValue === String(newValue)) return; // Sin cambio → no animar

    el.textContent = newValue;

    // Flash: scale up brevemente + color highlight
    pill.style.transition = 'transform 0.2s ease, box-shadow 0.2s ease';
    pill.style.transform = 'scale(1.12)';
    pill.style.boxShadow = '0 0 12px rgba(45,212,191,0.35)';
    setTimeout(() => {
        pill.style.transform = '';
        pill.style.boxShadow = '';
    }, 350);
}

// Refrescar sidebar (nombre, nivel, marco)
function refreshSidebarInfo() {
    const user = getUserData();
    if (!user) return;

    const nameEl = document.querySelector('#sidebar .user-name');
    if (nameEl) nameEl.textContent = user.fullName || user.username || 'Estudiante';

    const levelEl = document.querySelector('#sidebar .user-level');
    const newLevel = user.stats?.level ?? 1;
    if (levelEl) levelEl.textContent = 'Nivel ' + newLevel;

    renderSidebarFrame();
}

/**
 * Auto-fetch stats del servidor al cargar cada página.
 * Así el navbar SIEMPRE muestra datos frescos, incluso si cambiaron
 * desde otra pestaña, otro dispositivo, o por el backend directamente.
 * Usa gamification.js si está cargado, sino hace el fetch directo.
 */
function autoRefreshStats() {
    // Solo si hay un usuario logueado
    const user = getUserData();
    if (!user?.id) return;

    // Si gamification.js está cargado, usar su función
    if (typeof fetchPlayerStats === 'function') {
        fetchPlayerStats().then(() => {
            refreshNavbarStats();
            refreshSidebarInfo();
        }).catch(() => {}); // Silencioso si falla (offline, etc.)
        return;
    }

    // Fallback: fetch directo si gamification.js no está en la página
    const API_BASE = '/project-1.0-SNAPSHOT/api/gamification';
    fetch(`${API_BASE}/stats`, {
        headers: { 'X-User-Id': user.id }
    })
    .then(r => r.json())
    .then(data => {
        if (!data.success) return;
        try {
            const stored = JSON.parse(localStorage.getItem('user')) || {};
            if (!stored.stats) stored.stats = {};
            if (data.xp !== undefined)            stored.stats.xp            = data.xp;
            if (data.level !== undefined)          stored.stats.level         = data.level;
            if (data.coins !== undefined)          stored.stats.coins         = data.coins;
            if (data.streakCurrent !== undefined)  stored.stats.streakCurrent = data.streakCurrent;
            if (data.streakLongest !== undefined)  stored.stats.streakRecord  = data.streakLongest;
            if (data.hasStreakShield !== undefined) stored.stats.hasStreakShield = data.hasStreakShield;
            localStorage.setItem('user', JSON.stringify(stored));
            refreshNavbarStats();
            refreshSidebarInfo();
        } catch (_) {}
    })
    .catch(() => {}); // Silencioso — no bloquear la página si falla
}



// ══════════════════════════════════════════════════
// TRANSICIÓN SUAVE ENTRE PÁGINAS
// Fade out del content al hacer click en nav links.
// El navbar y sidebar ya están cacheados por el browser
// desde la segunda visita — visualmente no parpadean.
// ══════════════════════════════════════════════════
function initPageTransitions() {
    // Fade in al entrar a la página
    const main = document.querySelector('main.content');
    if (main) {
        main.style.opacity = '0';
        main.style.transition = 'opacity 0.18s ease';
        requestAnimationFrame(() => { main.style.opacity = '1'; });
    }

    // Fade out al salir
    document.addEventListener('click', e => {
        const link = e.target.closest('a.nav-card[href]');
        if (!link || !link.href || link.href === location.href) return;
        // No interceptar excluidas
        if (link.href.includes('index.html')) return;
        e.preventDefault();
        const target = link.href;
        const m = document.querySelector('main.content');
        if (m) {
            m.style.transition = 'opacity 0.15s ease';
            m.style.opacity = '0';
            setTimeout(() => { window.location.href = target; }, 150);
        } else {
            window.location.href = target;
        }
    });
}

// ══════════════════════════════════════════════════
// ══════════════════════════════════════════════════
// SHIELD PILL — countdown 24h desde activación
// ══════════════════════════════════════════════════
function initShieldCountdown() {
    const pill = document.getElementById('shieldPill');
    if (!pill) return;

    // Solo iniciar si hay un timestamp de activación (lo pone tienda.js al comprar)
    if (!localStorage.getItem('shieldActivatedAt')) {
        pill.remove();
        return;
    }

    function updateShieldTimer() {
        const el = document.getElementById('shieldCountdown');
        const shieldPill = document.getElementById('shieldPill');
        if (!el || !shieldPill) return;

        const activatedAt = parseInt(localStorage.getItem('shieldActivatedAt') || '0');
        const expiresAt = activatedAt + 24 * 60 * 60 * 1000; // 24 horas
        const diff = expiresAt - Date.now();

        if (diff <= 0) {
            // Expiró — quitar escudo visual y marcar en localStorage
            shieldPill.style.opacity = '0';
            shieldPill.style.pointerEvents = 'none';
            setTimeout(() => shieldPill.remove(), 400);
            localStorage.removeItem('shieldActivatedAt');
            // Actualizar user data
            try {
                const user = JSON.parse(localStorage.getItem('user') || '{}');
                if (user.stats) { user.stats.hasStreakShield = false; localStorage.setItem('user', JSON.stringify(user)); }
            } catch(_) {}
            return;
        }

        const h = Math.floor(diff / 3600000);
        const m = Math.floor((diff % 3600000) / 60000);
        const s = Math.floor((diff % 60000) / 1000);
        const pad = n => String(n).padStart(2, '0');
        el.textContent = `${pad(h)}:${pad(m)}:${pad(s)}`;

        // Urgente si queda menos de 2 horas
        if (h < 2) {
            shieldPill.style.borderColor = 'rgba(239,68,68,0.5)';
            el.style.color = '#fca5a5';
        }
    }

    updateShieldTimer();
    setInterval(updateShieldTimer, 1000);
}

// ══════════════════════════════════════════════════
// POLARIS LOADING — utilidad global reutilizable
// ══════════════════════════════════════════════════
const PolarisLoading = {
    initStars(canvas) {
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const resize = () => {
            const p = canvas.parentElement;
            canvas.width  = p ? p.offsetWidth  : window.innerWidth;
            canvas.height = p ? p.offsetHeight : window.innerHeight;
        };
        resize();
        const stars = Array.from({ length: 180 }, () => ({
            x: Math.random() * canvas.width, y: Math.random() * canvas.height,
            size: Math.random() * 2 + 0.2,
            speedX: (Math.random() - 0.5) * 0.08, speedY: (Math.random() - 0.5) * 0.08,
            opacity: Math.random() * 0.5 + 0.1, opacityChange: (Math.random() - 0.5) * 0.01,
            color: ['#ffffff','#ffe9c4','#d4f1ff','#c4b5fd'][Math.floor(Math.random() * 4)]
        }));
        const animate = () => {
            if (!canvas.isConnected) return;
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            stars.forEach(s => {
                s.x += s.speedX; s.y += s.speedY;
                if (s.x < 0) s.x = canvas.width;  if (s.x > canvas.width)  s.x = 0;
                if (s.y < 0) s.y = canvas.height; if (s.y > canvas.height) s.y = 0;
                s.opacity += s.opacityChange;
                if (s.opacity <= 0.05 || s.opacity >= 0.7) s.opacityChange *= -1;
                ctx.beginPath(); ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
                ctx.fillStyle = s.color; ctx.globalAlpha = s.opacity; ctx.fill();
            });
            ctx.globalAlpha = 1;
            requestAnimationFrame(animate);
        };
        animate();
        window.addEventListener('resize', resize);
    },

    show(id) {
        const el = document.getElementById(id);
        if (el) el.classList.remove('polaris-loading--hidden');
    },

    hide(id) {
        const el = document.getElementById(id);
        if (!el) return;
        el.classList.add('polaris-loading--hidden');
        setTimeout(() => el.remove(), 500);
    },

    rotateMessages(subtitleId, messages, intervalMs = 1400) {
        const el = document.getElementById(subtitleId);
        if (!el || !messages.length) return null;
        let i = 0;
        return setInterval(() => {
            i = (i + 1) % messages.length;
            el.textContent = messages[i];
        }, intervalMs);
    }
};

// Inicializar cuando el DOM esté listo
document.addEventListener('DOMContentLoaded', initComponents);