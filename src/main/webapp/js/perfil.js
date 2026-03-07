/* ===== PERFIL.JS - Mi ProfesorIA ===== */

const API_BASE = 'http://localhost:8080/project-1.0-SNAPSHOT';

// ─── Init ───────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    loadProfile();
    document.getElementById('btn-edit-profile').addEventListener('click', openEditModal);
    document.getElementById('btn-modal-close').addEventListener('click', closeEditModal);
    document.getElementById('btn-modal-cancel').addEventListener('click', closeEditModal);
    document.getElementById('btn-modal-save').addEventListener('click', saveProfile);
});

// ─── Cargar perfil desde el backend ─────────────────────────
async function loadProfile() {
    try {
        const res = await fetch(`${API_BASE}/api/profile`, {
            method: 'GET',
            credentials: 'include'
        });

        if (res.status === 401) {
            window.location.href = '../index.html';
            return;
        }

        const data = await res.json();

        if (!data.success) {
            console.error('Error cargando perfil:', data.error);
            return;
        }

        renderProfile(data.data);

    } catch (err) {
        console.error('Error de conexión:', err);
    }
}

// ─── Render de todos los datos ───────────────────────────────
function renderProfile(profile) {
    // Profile card
    document.getElementById('profile-username').textContent = profile.username ?? '—';
    document.getElementById('profile-email').textContent    = profile.email    ?? '—';
    document.getElementById('profile-level').textContent    = profile.stats?.level ?? '—';

    // Stats
    document.getElementById('stat-streak').textContent        = profile.stats?.streakCurrent ?? 0;
    document.getElementById('stat-xp').textContent            = (profile.stats?.xp ?? 0).toLocaleString();
    document.getElementById('stat-coins').textContent         = (profile.stats?.coins ?? 0).toLocaleString();
    document.getElementById('stat-streak-record').textContent = profile.stats?.streakRecord ?? 0;

    // Info personal
    document.getElementById('info-fullname').textContent  = profile.fullName  || '—';
    document.getElementById('info-birthdate').textContent = formatDate(profile.birthdate);
    document.getElementById('info-country').textContent   = profile.country   || '—';
    document.getElementById('info-language').textContent  = formatLanguage(profile.language);
    document.getElementById('info-createdat').textContent = formatDate(profile.createdAt);

    // Objetivos semanales
    renderWeeklyObjectives(profile.weeklyObjectives ?? []);

    // Misiones diarias
    renderDailyMissions(profile.dailyMissions ?? []);

    // Label de reinicio semanal
    document.getElementById('weekly-reset-label').textContent = getDaysUntilMonday();
}

// ─── Objetivos Semanales ─────────────────────────────────────
function renderWeeklyObjectives(objectives) {
    const container = document.getElementById('weekly-objectives-list');

    if (objectives.length === 0) {
        container.innerHTML = '<p class="empty-msg">No hay objetivos esta semana.</p>';
        return;
    }

    container.innerHTML = objectives.map(obj => {
        const pct = obj.requiredCount > 0
            ? Math.min(100, Math.round((obj.progress / obj.requiredCount) * 100))
            : 0;
        const completedClass = obj.completed ? 'completed' : '';

        return `
        <div class="objective-item ${completedClass}">
            <div class="objective-checkbox">
                <i class="fas fa-check"></i>
            </div>
            <div class="objective-content">
                <div class="objective-title">${obj.description}</div>
                <div class="objective-progress">
                    <div class="progress-bar">
                        <div class="progress-fill purple" style="width: ${pct}%"></div>
                    </div>
                    <span class="progress-text">${obj.progress}/${obj.requiredCount}</span>
                </div>
            </div>
            <div class="objective-reward">
                <i class="fas fa-coins"></i>
                ${obj.coinReward}
            </div>
        </div>`;
    }).join('');
}

// ─── Misiones Diarias ────────────────────────────────────────
function renderDailyMissions(missions) {
    const container = document.getElementById('daily-missions-list');

    if (missions.length === 0) {
        container.innerHTML = '<p class="empty-msg">No hay misiones por hoy.</p>';
        return;
    }

    // Calcular horas hasta medianoche
    document.getElementById('daily-reset-label').textContent = getHoursUntilMidnight();

    container.innerHTML = missions.map(m => {
        const pct = m.requiredCount > 0
            ? Math.min(100, Math.round((m.progress / m.requiredCount) * 100))
            : 0;
        const completedClass = m.completed ? 'completed' : '';

        return `
        <div class="objective-item ${completedClass}">
            <div class="objective-checkbox">
                <i class="fas fa-check"></i>
            </div>
            <div class="objective-content">
                <div class="objective-title">${m.description}</div>
                <div class="objective-progress">
                    <div class="progress-bar">
                        <div class="progress-fill orange" style="width: ${pct}%"></div>
                    </div>
                    <span class="progress-text">${m.progress}/${m.requiredCount}</span>
                </div>
            </div>
            <div class="objective-reward">
                <i class="fas fa-coins"></i>
                ${m.coinReward}
            </div>
        </div>`;
    }).join('');
}

// ─── Modal Editar ────────────────────────────────────────────
function openEditModal() {
    // Pre-llenar con valores actuales
    document.getElementById('edit-fullname').value  = document.getElementById('info-fullname').textContent.replace('—', '');
    document.getElementById('edit-country').value   = document.getElementById('info-country').textContent.replace('—', '');
    document.getElementById('edit-birthdate').value = getRawBirthdate();

    const langSelect = document.getElementById('edit-language');
    const currentLang = document.getElementById('info-language').dataset.raw ?? 'es';
    langSelect.value = currentLang;

    document.getElementById('modal-error-msg').style.display = 'none';
    document.getElementById('modal-edit').style.display = 'flex';
}

function closeEditModal() {
    document.getElementById('modal-edit').style.display = 'none';
}

async function saveProfile() {
    const fullName  = document.getElementById('edit-fullname').value.trim();
    const country   = document.getElementById('edit-country').value.trim();
    const language  = document.getElementById('edit-language').value;
    const birthdate = document.getElementById('edit-birthdate').value;

    const errorEl = document.getElementById('modal-error-msg');
    errorEl.style.display = 'none';

    if (!fullName) {
        errorEl.textContent = 'El nombre no puede estar vacío.';
        errorEl.style.display = 'block';
        return;
    }

    try {
        const res = await fetch(`${API_BASE}/api/profile`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ fullName, country, language, birthdate })
        });

        const data = await res.json();

        if (!data.success) {
            errorEl.textContent = data.error ?? 'Error al guardar.';
            errorEl.style.display = 'block';
            return;
        }

        // Actualizar vista sin recargar
        document.getElementById('info-fullname').textContent  = fullName;
        document.getElementById('info-country').textContent   = country || '—';
        document.getElementById('info-language').textContent  = formatLanguage(language);
        document.getElementById('info-language').dataset.raw  = language;
        if (birthdate) {
            document.getElementById('info-birthdate').textContent = formatDate(birthdate);
            document.getElementById('info-birthdate').dataset.raw = birthdate;
        }

        closeEditModal();

    } catch (err) {
        errorEl.textContent = 'Error de conexión.';
        errorEl.style.display = 'block';
        console.error(err);
    }
}

// ─── Utilidades ──────────────────────────────────────────────
function formatDate(dateStr) {
    if (!dateStr) return '—';
    try {
        const date = new Date(dateStr + (dateStr.length === 10 ? 'T00:00:00' : ''));
        return date.toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' });
    } catch { return dateStr; }
}

function formatLanguage(code) {
    const map = { es: 'Español', en: 'English', pt: 'Português' };
    return map[code] ?? code ?? '—';
}

function getRawBirthdate() {
    const el = document.getElementById('info-birthdate');
    return el.dataset.raw ?? '';
}

function getDaysUntilMonday() {
    const today = new Date();
    const day = today.getDay(); // 0=dom, 1=lun...
    const daysLeft = day === 1 ? 7 : (8 - day) % 7;
    return `Reinicia en ${daysLeft} día${daysLeft !== 1 ? 's' : ''}`;
}

function getHoursUntilMidnight() {
    const now  = new Date();
    const midnight = new Date();
    midnight.setHours(24, 0, 0, 0);
    const hours = Math.round((midnight - now) / 1000 / 3600);
    return `Reinicia en ${hours} hora${hours !== 1 ? 's' : ''}`;
}