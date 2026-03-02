/* ===== COMPONENTES JS - Mi ProfesorIA ===== */

// Obtener datos del usuario desde localStorage
function getUserData() {
    const raw = localStorage.getItem('user');
    if (!raw) return null;
    try { return JSON.parse(raw); }
    catch { return null; }
}

// Detectar ruta base para los links
function getBasePath() {
    const path = window.location.pathname;
    if (path.includes('/pages/')) {
        return '../';
    }
    return './';
}

// HTML del Navbar
function getNavbarHTML(base) {
    const user    = getUserData();
    const racha   = user?.stats?.streakCurrent ?? 0;
    const xp      = user?.stats?.xp            ?? 0;
    const monedas = user?.stats?.coins          ?? 0;

    return `
    <nav class="main-navbar">
        <div class="navbar-left">
            <button class="menu-toggle" onclick="toggleMenu()">
                <i class="fas fa-bars"></i>
            </button>
            <div class="logo">
                <div class="logo-icon">
                    <i class="fas fa-robot"></i>
                </div>
                <span class="logo-text" style="color:#2dd4bfe6">Mi ProfesorIA</span>
            </div>
        </div>
        <div class="navbar-stats">
            <div class="stat-pill">
                <img src="${base}images/gifs/fire.gif" alt="🔥" class="fire-gif" onerror="this.outerHTML='<i class=\\'fas fa-fire\\' style=\\'color:#ff6b6b\\'></i>'">
                <span class="stat-value">${racha} Días</span>
                <span class="stat-label">Racha</span>
            </div>
            <div class="stat-pill">
                <img src="${base}images/gifs/star.gif" alt="⭐" class="star-gif" onerror="this.outerHTML='<i class=\\'fas fa-star\\' style=\\'color:#ffd93d\\'></i>'">
                <span class="stat-value">${xp.toLocaleString()}</span>
                <span class="stat-label">XP</span>
            </div>
            <div class="stat-pill">
                <img src="${base}images/gifs/coin.gif" alt="💵" class="money-gif" onerror="this.outerHTML='<i class=\\'fas fa-coins\\' style=\\'color:#f59e0b\\'></i>'">
                <span class="stat-value">${monedas.toLocaleString()}</span>
                <span class="stat-label">Monedas</span>
            </div>
        </div>
    </nav>
    `;
}

// HTML del Sidebar
function getSidebarHTML(base) {
    const user   = getUserData();
    const nombre = user?.username    ?? 'Estudiante';
    const nivel  = user?.stats?.level ?? 1;

    return `
    <div class="sidebar-overlay" onclick="toggleMenu()"></div>

    <aside class="sidebar-cards" id="sidebar">
        <div class="user-profile">
            <div class="user-avatar">
                <i class="fas fa-user"></i>
            </div>
            <div class="user-name">${nombre}</div>
            <div class="user-level">Nivel ${nivel}</div>
        </div>

        <div class="nav-cards">
            <!-- Perfil -->
            <a href="${base}pages/perfil.html" class="nav-card" data-page="perfil">
                <div class="nav-card-icon gradient-perfil">
                    <i class="fas fa-user"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Perfil</div>
                    <div class="nav-card-desc">Tu información</div>
                </div>
            </a>

            <!-- Modo Estudio -->
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
            <a href="${base}pages/mi-profesor.html" class="nav-card" data-page="mi-profesor">
                <div class="nav-card-icon gradient-profesor">
                    <i class="fas fa-robot"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Mi Profesor</div>
                    <div class="nav-card-desc">Asistente IA</div>
                </div>
            </a>

            <!-- Duelos -->
            <a href="${base}pages/duelos.html" class="nav-card" data-page="duelos">
                <div class="nav-card-icon gradient-duelo">
                    <i class="fas fa-shield-alt"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Duelos</div>
                    <div class="nav-card-desc">Reta a otros</div>
                </div>
            </a>

            <!-- Tienda -->
            <a href="${base}pages/tienda.html" class="nav-card" data-page="tienda">
                <div class="nav-card-icon gradient-tienda">
                    <i class="fas fa-store"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Tienda</div>
                    <div class="nav-card-desc">Personaliza tu perfil</div>
                </div>
            </a>

            <!-- Favoritos -->
            <a href="${base}pages/favoritos.html" class="nav-card" data-page="favoritos">
                <div class="nav-card-icon gradient-favoritos">
                    <i class="fas fa-heart"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Favoritos</div>
                    <div class="nav-card-desc">Contenido guardado</div>
                </div>
            </a>

            <!-- Historial -->
            <a href="${base}pages/historial.html" class="nav-card" data-page="historial">
                <div class="nav-card-icon gradient-historial">
                    <i class="fas fa-clock-rotate-left"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Historial</div>
                    <div class="nav-card-desc">Tu actividad reciente</div>
                </div>
            </a>

            <!-- Ajustes -->
            <a href="${base}pages/ajustes.html" class="nav-card" data-page="ajustes">
                <div class="nav-card-icon gradient-ajustes">
                    <i class="fas fa-cog"></i>
                </div>
                <div class="nav-card-content">
                    <div class="nav-card-title">Ajustes</div>
                    <div class="nav-card-desc">Configuración</div>
                </div>
            </a>
        </div>
    </aside>
    `;
}

// Inicializar componentes
function initComponents() {
    const base = getBasePath();

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
}

// Marcar la página activa en el menú
function setActivePage() {
    const currentPage = document.body.dataset.page;
    if (currentPage) {
        const activeLink = document.querySelector(`.nav-card[data-page="${currentPage}"]`);
        if (activeLink) {
            activeLink.classList.add('active');
        }
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

// Inicializar cuando el DOM esté listo
document.addEventListener('DOMContentLoaded', initComponents);