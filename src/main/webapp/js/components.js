// Obtener datos del usuario desde localStorage
function getUserData() {
    const raw = localStorage.getItem('user');
    if (!raw) return null;
    try { return JSON.parse(raw); } 
    catch { return null; }
}

function getNavbarHTML(base) {
    const user = getUserData();
    const racha    = user?.stats?.streakDays  ?? 0;
    const xp       = user?.stats?.totalXp     ?? 0;
    const monedas  = user?.stats?.coins        ?? 0;

    return `
    <nav class="main-navbar">
        <div class="navbar-left">
            <button class="menu-toggle" onclick="toggleMenu()">
                <i class="fas fa-bars"></i>
            </button>
            <div class="logo">
                <div class="logo-icon"><i class="fas fa-robot"></i></div>
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

function getSidebarHTML(base) {
    const user = getUserData();
    const nombre = user?.username  ?? 'Estudiante';
    const nivel  = user?.stats?.level ?? 1;

    return `
    <div class="sidebar-overlay" onclick="toggleMenu()"></div>
    <aside class="sidebar-cards" id="sidebar">
        <div class="user-profile">
            <div class="user-avatar"><i class="fas fa-user"></i></div>
            <div class="user-name">${nombre}</div>
            <div class="user-level">Nivel ${nivel}</div>
        </div>
        <!-- el resto igual... -->
    </aside>
    `;
}