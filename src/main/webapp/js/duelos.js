/**
 * duelos.js — Mi ProfesorIA (v3)
 * Features: Friends, Invitations, Active Duels, Notifications, Cancel/Reject
 *
 * Cambios v3:
 *   - Crear duelo NO redirige a jugar de inmediato (el usuario elige cuándo)
 *   - Ambos jugadores usan "Jugar Ahora" desde Retos Activos
 *   - Badge de notificaciones eliminado (innecesario)
 *   - Status del duelo: waiting_opponent → in_progress → finished
 */

const CTX = window.location.pathname.split('/pages')[0];

function getUserId() {
    try { return JSON.parse(localStorage.getItem('user'))?.id || null; }
    catch (_) { return null; }
}
function duelHeaders() {
    const uid = getUserId();
    return getAuthHeaders(uid ? { 'X-User-Id': uid } : {});
}

let friends = [], invitations = [], activeDuels = [], notifications = [];

// ═══════════════════════════════════════════════════════════
//  INIT
// ═══════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('headerStars').innerHTML = generateStars(12);
    sessionStorage.removeItem('duelData');
    const _dt = PolarisLoading.rotateMessages('duelLoadingSub',
        ['Obteniendo tus datos...', 'Cargando amigos...', 'Buscando retos activos...']);
    PolarisLoading.wrap('duelLoadingScreen',
        Promise.allSettled([loadFriends(), loadPendingRequests(), loadActiveDuels(), loadNotifications(), loadLeaderboard()])
    ).finally(() => clearInterval(_dt));
});

// ═══════════════════════════════════════════════════════════
//  FRIENDS
// ═══════════════════════════════════════════════════════════
async function loadFriends() {
    try {
        const res = await fetch(CTX + '/api/duels/friends', { headers: duelHeaders() });
        const data = await res.json();
        if (data.success) { friends = data.friends || []; renderFriends(); }
    } catch (e) { console.error('[Duelos] loadFriends:', e); }
}

async function sendFriendRequest() {
    const input = document.getElementById('friendEmailInput');
    const val = input.value.trim();
    if (!val) { showToast('Ingresa un correo o usuario', 'info'); return; }
    try {
        const res = await fetch(CTX + '/api/duels/friends/add', {
            method: 'POST', headers: duelHeaders(),
            body: JSON.stringify({ emailOrUsername: val })
        });
        const data = await res.json();
        if (data.success) { closeAddFriendModal(); showToast('Solicitud enviada', 'success'); }
        else showToast(data.error || 'Error', 'error');
    } catch (e) { showToast('Error de conexión', 'error'); }
}

async function acceptInvitation(fid) {
    try {
        const res = await fetch(CTX + '/api/duels/friends/accept', {
            method: 'POST', headers: duelHeaders(), body: JSON.stringify({ friendshipId: fid })
        });
        if ((await res.json()).success) { showToast('Aceptada', 'success'); loadPendingRequests(); loadFriends(); }
    } catch (_) { showToast('Error', 'error'); }
}

async function declineInvitation(fid) {
    try {
        const res = await fetch(CTX + '/api/duels/friends/reject', {
            method: 'POST', headers: duelHeaders(), body: JSON.stringify({ friendshipId: fid })
        });
        if ((await res.json()).success) { showToast('Rechazada', 'info'); loadPendingRequests(); }
    } catch (_) { showToast('Error', 'error'); }
}

async function removeFriend(fid) {
    if (!confirm('¿Eliminar este amigo?')) return;
    try {
        await fetch(CTX + '/api/duels/friends/remove', {
            method: 'POST', headers: duelHeaders(), body: JSON.stringify({ friendshipId: fid })
        });
        showToast('Amigo eliminado', 'info'); loadFriends();
    } catch (_) { showToast('Error', 'error'); }
}

async function loadPendingRequests() {
    try {
        const res = await fetch(CTX + '/api/duels/requests', { headers: duelHeaders() });
        const data = await res.json();
        if (data.success) { invitations = data.requests || []; renderInvitations(); updateBadge('badgeInvitations', invitations.length); }
    } catch (e) { console.error('[Duelos] loadRequests:', e); }
}

// ═══════════════════════════════════════════════════════════
//  DUELS
// ═══════════════════════════════════════════════════════════
async function loadActiveDuels() {
    try {
        const res = await fetch(CTX + '/api/duels/active', { headers: duelHeaders() });
        const data = await res.json();
        if (data.success) {
            activeDuels = data.duels || [];
            renderChallenges();
            updateBadge('badgeRetos', activeDuels.filter(d => !d.hasPlayed).length);
        }
    } catch (e) { console.error('[Duelos] loadActive:', e); }
}

async function loadNotifications() {
    try {
        const res = await fetch(CTX + '/api/duels/history', { headers: duelHeaders() });
        const data = await res.json();
        if (data.success) {
            notifications = data.history || [];
            renderNotifications();
        }
    } catch (e) { console.error('[Duelos] loadHistory:', e); }

    const wins = (notifications || []).filter(n => n.result === 'win').length;
    const el = document.getElementById('victoriesCount');
    if (el) el.textContent = wins;
}

async function createDuel() {
    const opponentId = document.getElementById('opponentSelect').value;
    const mode = document.getElementById('topicUploadMode').style.display === 'none' ? 'text' : 'upload';
    const topicInput = document.getElementById('duelTopic').value.trim();
    const fileInput = document.getElementById('topicFile');
    const questionCount = parseInt(document.getElementById('numQuestions').value) || 10;
    const timePerQuestion = parseInt(document.getElementById('timePerQuestion').value) || 30;

    if (!opponentId) { showToast('Selecciona un oponente', 'info'); return; }

    let topic, file;
    if (mode === 'text') {
        if (!topicInput) { showToast('Ingresa un tema', 'info'); return; }
        topic = topicInput;
    } else {
        file = fileInput.files[0];
        if (!file) { showToast('Selecciona un archivo', 'info'); return; }
        topic = file.name;
    }

    closeCreateDuelModal();
    showCreatingScreen();

    try {
        activateStep('step-analyze');
        updateCreatingProgress(20);
        await delay(600);

        activateStep('step-generate');
        updateCreatingProgress(50);

        let res;
        if (file) {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('opponentId', opponentId);
            formData.append('topic', topic);
            formData.append('questionCount', questionCount);
            formData.append('timePerQuestion', timePerQuestion);
            res = await fetch(CTX + '/api/duels/create', {
                method: 'POST',
                headers: { 'X-User-Id': getUserId() },
                body: formData
            });
        } else {
            res = await fetch(CTX + '/api/duels/create', {
                method: 'POST', headers: duelHeaders(),
                body: JSON.stringify({ opponentId, topic, questionCount, timePerQuestion, text: topic })
            });
        }

        const data = await res.json();

        activateStep('step-create');
        updateCreatingProgress(90);
        await delay(400);

        if (data.success) {
            updateCreatingProgress(100);
            await delay(300);
            showToast('¡Duelo creado! Juega cuando quieras desde Retos Activos.', 'success');
            await loadActiveDuels();
            const retosTab = document.querySelector('.tab-btn:nth-child(3)');
            if (retosTab) switchTab('retos', retosTab);
        } else {
            showToast(data.error || 'Error creando duelo', 'error');
        }
    } catch (e) {
        showToast('Error de conexión', 'error');
        console.error('[Duelos] createDuel:', e);
    } finally {
        hideCreatingScreen();
    }
}

function delay(ms) { return new Promise(r => setTimeout(r, ms)); }

function showCreatingScreen() {
    const screen = document.getElementById('duelCreatingScreen');
    if (!screen) return;
    screen.classList.add('show');
    document.querySelectorAll('.duel-creating-step').forEach(s => {
        s.classList.remove('active', 'done');
    });
    updateCreatingProgress(0);
}

function hideCreatingScreen() {
    const screen = document.getElementById('duelCreatingScreen');
    if (!screen) return;
    screen.style.opacity = '0';
    setTimeout(() => { screen.classList.remove('show'); screen.style.opacity = ''; }, 400);
}

function activateStep(stepId) {
    document.querySelectorAll('.duel-creating-step.active').forEach(s => {
        s.classList.remove('active');
        s.classList.add('done');
        s.querySelector('.duel-step-status').innerHTML = '<i class="fas fa-check" style="color:#2dd4bf;font-size:0.8rem;"></i>';
    });
    const step = document.getElementById(stepId);
    if (step) step.classList.add('active');
    const labels = { 'step-analyze': 'Analizando contenido...', 'step-generate': 'Generando preguntas con IA...', 'step-create': 'Finalizando el duelo...' };
    const sub = document.getElementById('creatingSubtitle');
    if (sub) sub.textContent = labels[stepId] || '';
}

function updateCreatingProgress(pct) {
    const fill = document.getElementById('creatingProgressFill');
    if (fill) fill.style.width = pct + '%';
}

async function playChallenge(duelId) {
    showToast('Cargando duelo...', 'info');
    try {
        const res = await fetch(CTX + `/api/duels/play?id=${duelId}`, { headers: duelHeaders() });
        const data = await res.json();
        if (data.success) {
            sessionStorage.setItem('duelData', JSON.stringify({
                duelId, title: data.title, questions: data.questions,
                questionCount: data.questionCount, topic: data.topic,
                timePerQuestion: data.timePerQuestion || 30
            }));
            window.location.href = 'duelo-play.html';
        } else {
            showToast(data.error || 'Error al cargar duelo', 'error');
        }
    } catch (e) { showToast('Error de conexión', 'error'); }
}

async function cancelDuel(duelId) {
    if (!confirm('¿Cancelar este duelo?')) return;
    try {
        const res = await fetch(CTX + '/api/duels/decline', {
            method: 'POST', headers: duelHeaders(), body: JSON.stringify({ duelId })
        });
        if ((await res.json()).success) { showToast('Duelo cancelado', 'info'); loadActiveDuels(); }
    } catch (_) { showToast('Error', 'error'); }
}

async function rejectDuel(duelId) {
    if (!confirm('¿Rechazar este duelo?')) return;
    try {
        const res = await fetch(CTX + '/api/duels/decline', {
            method: 'POST', headers: duelHeaders(), body: JSON.stringify({ duelId })
        });
        if ((await res.json()).success) { showToast('Duelo rechazado', 'info'); loadActiveDuels(); }
    } catch (_) { showToast('Error', 'error'); }
}

// ═══════════════════════════════════════════════════════════
//  RENDERS
// ═══════════════════════════════════════════════════════════

function renderFriends() {
    const grid = document.getElementById('friendsGrid');
    const term = document.getElementById('searchFriends').value.toLowerCase();
    const filtered = friends.filter(f => (f.username || '').toLowerCase().includes(term) || (f.email || '').toLowerCase().includes(term));

    if (filtered.length === 0) {
        grid.innerHTML = `<div class="empty-state" style="grid-column:1/-1;"><i class="fas fa-users"></i><h3>${friends.length === 0 ? 'Aún no tienes amigos' : 'Sin resultados'}</h3><p>${friends.length === 0 ? 'Agrega amigos con el botón de arriba.' : 'Intenta otro término.'}</p></div>`;
        return;
    }

    grid.innerHTML = filtered.map(f => `
        <div class="friend-card">
            <div class="friend-stars">${generateStars(8)}</div>
            <div class="friend-avatar-wrapper">
                <div class="friend-avatar">
                    <i class="fas fa-user" style="font-size:1.8rem;color:rgba(255,255,255,0.35)"></i>
                </div>
            </div>
            <div class="friend-info">
                <h4 class="friend-name">${f.username || 'Usuario'}</h4>
                <p class="friend-username">Nivel ${f.level || 1}</p>
                <div class="friend-stats">
                    <span class="stat-item win"><i class="fas fa-star"></i> ${(f.xp || 0).toLocaleString()} XP</span>
                    <span class="stat-item"><i class="fas fa-fire"></i> ${f.streak || 0}</span>
                </div>
            </div>
            <div style="display:flex;gap:6px;flex-wrap:wrap;">
                <button class="btn-challenge" onclick="challengeFriend('${f.id}')"><i class="fas fa-bolt"></i> Retar</button>
                <button class="btn-decline" style="padding:8px 10px;font-size:0.75rem;border-radius:8px;" onclick="removeFriend('${f.friendshipId}')" title="Eliminar amigo"><i class="fas fa-user-minus"></i></button>
            </div>
        </div>
    `).join('');
}

function renderInvitations() {
    const list = document.getElementById('invitationsList');
    const empty = document.getElementById('emptyInvitations');
    if (invitations.length === 0) { list.style.display = 'none'; empty.style.display = 'block'; return; }
    list.style.display = 'block'; empty.style.display = 'none';

    list.innerHTML = invitations.map(inv => `
        <div class="invitation-card">
            <div class="invitation-stars">${generateStars(8)}</div>
            <div style="width:50px;height:50px;border-radius:50%;background:linear-gradient(135deg,#1a1a3e,#2a2a5e);display:flex;align-items:center;justify-content:center;flex-shrink:0;">
                <i class="fas fa-user" style="font-size:1.2rem;color:rgba(255,255,255,0.4)"></i>
            </div>
            <div class="invitation-info">
                <h4 class="invitation-name">${inv.username || 'Usuario'}</h4>
                <p class="invitation-username">${inv.email || ''}</p>
                <p class="invitation-time"><i class="fas fa-clock"></i> ${timeAgo(inv.createdAt)}</p>
            </div>
            <div class="invitation-actions">
                <button class="btn-accept" onclick="acceptInvitation('${inv.friendshipId}')"><i class="fas fa-check"></i> Aceptar</button>
                <button class="btn-decline" onclick="declineInvitation('${inv.friendshipId}')"><i class="fas fa-times"></i></button>
            </div>
        </div>
    `).join('');
}

function renderChallenges() {
    const list = document.getElementById('challengesList');
    const empty = document.getElementById('emptyChallenges');
    const uid = getUserId();

    if (activeDuels.length === 0) { list.style.display = 'none'; empty.style.display = 'block'; return; }
    list.style.display = 'block'; empty.style.display = 'none';

    list.innerHTML = activeDuels.map(d => {
        const isCh = d.isChallenger;
        const oppName = isCh ? d.opponentName : d.challengerName;
        const oppLevel = isCh ? d.opponentLevel : d.challengerLevel;
        const canPlay = !d.hasPlayed;

        let statusHtml, statusClass;
        if (d.hasPlayed) {
            statusHtml = '<i class="fas fa-hourglass-half"></i> Esperando oponente';
            statusClass = 'waiting';
        } else if (d.status === 'in_progress') {
            statusHtml = '<i class="fas fa-bolt"></i> ¡Tu turno!';
            statusClass = 'pending';
        } else {
            statusHtml = isCh ? '<i class="fas fa-play"></i> Pendiente' : '<i class="fas fa-bolt"></i> ¡Te retaron!';
            statusClass = 'pending';
        }

        return `
            <div class="challenge-card" style="background:var(--secondary-bg,rgba(26,26,53,0.9));border:1px solid var(--border-color,#2a2a4a);border-radius:16px;padding:16px;position:relative;overflow:hidden;">
                <div class="friend-stars" style="position:absolute;inset:0;pointer-events:none;">${generateStars(10)}</div>
                <div class="challenge-header" style="display:flex;align-items:center;justify-content:space-between;position:relative;z-index:1;">
                    <div style="display:flex;align-items:center;gap:12px;">
                        <div style="width:45px;height:45px;border-radius:50%;background:linear-gradient(135deg,#1a1a3e,#2a2a5e);display:flex;align-items:center;justify-content:center;">
                            <i class="fas fa-user" style="color:rgba(255,255,255,0.4)"></i>
                        </div>
                        <div>
                            <h4 style="margin:0;font-size:0.95rem;">${oppName}</h4>
                            <p style="margin:0;font-size:0.75rem;color:var(--text-secondary)">Nivel ${oppLevel} · ${d.questionCount} preguntas</p>
                            <p style="margin:2px 0 0;font-size:0.75rem;color:var(--accent-purple,#8b5cf6)"><i class="fas fa-book"></i> ${d.topic}</p>
                        </div>
                    </div>
                    <span style="padding:6px 14px;border-radius:20px;font-size:0.78rem;font-weight:600;
                        ${statusClass === 'pending' ? 'background:rgba(45,212,191,0.15);color:var(--accent-cyan);border:1px solid rgba(45,212,191,0.3);' : 'background:rgba(251,191,36,0.12);color:var(--accent-yellow);border:1px solid rgba(251,191,36,0.25);animation:timerPulse 2s ease-in-out infinite;'}">
                        ${statusHtml}
                    </span>
                </div>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-top:12px;position:relative;z-index:1;">
                    <span style="font-size:0.78rem;color:var(--text-secondary)"><i class="fas fa-clock"></i> ${timeAgo(d.createdAt)}</span>
                    <div style="display:flex;gap:8px;">
                        ${canPlay ? `<button class="btn-challenge" onclick="playChallenge('${d.id}')" style="padding:8px 16px;"><i class="fas fa-play"></i> Jugar Ahora</button>` : ''}
                        ${isCh && canPlay ? `<button onclick="cancelDuel('${d.id}')" style="background:rgba(239,68,68,0.12);border:1px solid rgba(239,68,68,0.25);color:#f87171;padding:8px 12px;border-radius:10px;cursor:pointer;font-size:0.78rem;font-family:inherit;" title="Cancelar duelo"><i class="fas fa-times"></i> Cancelar</button>` : ''}
                        ${!isCh && canPlay ? `<button onclick="rejectDuel('${d.id}')" style="background:rgba(239,68,68,0.12);border:1px solid rgba(239,68,68,0.25);color:#f87171;padding:8px 12px;border-radius:10px;cursor:pointer;font-size:0.78rem;font-family:inherit;" title="Rechazar duelo"><i class="fas fa-ban"></i> Rechazar</button>` : ''}
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

function renderNotifications() {
    const list = document.getElementById('notificationsList');
    const empty = document.getElementById('emptyNotifications');

    if (!notifications || notifications.length === 0) {
        list.style.display = 'none'; empty.style.display = 'block'; return;
    }
    list.style.display = 'block'; empty.style.display = 'none';

    list.innerHTML = notifications.map(n => {
        let icon, color, title, detail;
        if (n.result === 'win') {
            icon = 'fa-trophy'; color = 'var(--accent-yellow,#fbbf24)';
            title = `¡Victoria vs ${n.isChallenger ? n.opponentName : n.challengerName}!`;
            detail = `${n.challengerScore} - ${n.opponentScore}`;
        } else if (n.result === 'loss') {
            icon = 'fa-heart-broken'; color = 'var(--accent-red,#ef4444)';
            title = `Derrota vs ${n.isChallenger ? n.opponentName : n.challengerName}`;
            detail = `${n.challengerScore} - ${n.opponentScore}`;
        } else {
            icon = 'fa-handshake'; color = 'var(--accent-cyan,#2dd4bf)';
            title = `Empate vs ${n.isChallenger ? n.opponentName : n.challengerName}`;
            detail = `${n.challengerScore} - ${n.opponentScore}`;
        }

        return `
            <div style="background:var(--secondary-bg,rgba(26,26,53,0.9));border:1px solid var(--border-color,#2a2a4a);border-radius:14px;padding:14px 16px;display:flex;align-items:center;gap:14px;margin-bottom:8px;position:relative;">
                <div style="width:42px;height:42px;border-radius:12px;background:${n.result==='win'?'rgba(251,191,36,0.15)':n.result==='loss'?'rgba(239,68,68,0.15)':'rgba(45,212,191,0.15)'};display:flex;align-items:center;justify-content:center;flex-shrink:0;">
                    <i class="fas ${icon}" style="color:${color};font-size:1.1rem;"></i>
                </div>
                <div style="flex:1;">
                    <div style="font-size:0.92rem;font-weight:600;color:${color};">${title}</div>
                    <div style="font-size:0.78rem;color:var(--text-secondary);margin-top:2px;">
                        ${n.topic || 'Duelo'} · ${detail} · ${timeAgo(n.finishedAt)}
                    </div>
                </div>
                <div style="font-size:0.78rem;padding:4px 10px;border-radius:8px;
                    ${n.result==='win'?'background:rgba(34,197,94,0.12);color:var(--accent-green);':''}
                    ${n.result==='loss'?'background:rgba(239,68,68,0.12);color:var(--accent-red);':''}
                    ${n.result==='draw'?'background:rgba(45,212,191,0.12);color:var(--accent-cyan);':''}">
                    ${n.result==='win'?'+XP':n.result==='loss'?'-XP':'±XP'}
                </div>
            </div>
        `;
    }).join('');
}

// ═══════════════════════════════════════════════════════════
//  UI HELPERS
// ═══════════════════════════════════════════════════════════

function generateStars(count = 15) {
    let html = '';
    for (let i = 0; i < count; i++) {
        const s = 1 + Math.random() * 2, l = Math.random() * 100, t = Math.random() * 100, d = Math.random() * 3;
        html += `<div class="star" style="width:${s}px;height:${s}px;left:${l}%;top:${t}%;animation-delay:${d}s"></div>`;
    }
    return html;
}

function switchTab(tab, el) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    el.classList.add('active');
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    document.getElementById(`tab-${tab}`).classList.add('active');
    if (tab === 'leaderboard') loadLeaderboard();
}

function searchFriends() { renderFriends(); }

function challengeFriend(id) {
    document.getElementById('opponentSelect').value = id;
    openCreateDuelModal();
}

function updateBadge(id, count) {
    const el = document.getElementById(id);
    if (!el) return;
    if (count > 0) { el.textContent = count; el.style.display = 'inline-flex'; }
    else { el.style.display = 'none'; }
}

// ── Modals ──
function openAddFriendModal() { document.getElementById('addFriendModal').classList.add('show'); }
function closeAddFriendModal() {
    document.getElementById('addFriendModal').classList.remove('show');
    document.getElementById('friendEmailInput').value = '';
}

function openCreateDuelModal() {
    const select = document.getElementById('opponentSelect');
    select.innerHTML = '<option value="">Elige un amigo...</option>' +
        friends.map(f => `<option value="${f.id}">${f.username || f.email}</option>`).join('');
    document.getElementById('createDuelModal').classList.add('show');
}
function closeCreateDuelModal() {
    document.getElementById('createDuelModal').classList.remove('show');
    document.getElementById('duelTopic').value = '';
    clearFile();
    setTopicMode('text', document.querySelector('.topic-mode-btn'));
}

// ── Topic mode ──
function setTopicMode(mode, btn) {
    document.querySelectorAll('.topic-mode-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('topicTextMode').style.display = mode === 'text' ? 'block' : 'none';
    document.getElementById('topicUploadMode').style.display = mode === 'text' ? 'none' : 'block';
}

// ── File ──
function handleFileSelect(input) { if (input.files && input.files[0]) showFilePreview(input.files[0]); }
function handleDragOver(e) { e.preventDefault(); document.getElementById('fileDropZone').classList.add('drag-over'); }
function handleDragLeave() { document.getElementById('fileDropZone').classList.remove('drag-over'); }
function handleDrop(e) { e.preventDefault(); document.getElementById('fileDropZone').classList.remove('drag-over'); if (e.dataTransfer.files[0]) showFilePreview(e.dataTransfer.files[0]); }
function showFilePreview(file) {
    const ext = file.name.split('.').pop().toLowerCase();
    const icons = { pdf: 'fa-file-pdf', doc: 'fa-file-word', docx: 'fa-file-word', pptx: 'fa-file-powerpoint', txt: 'fa-file-alt' };
    document.getElementById('fileTypeIcon').className = `fas ${icons[ext] || 'fa-file'}`;
    document.getElementById('fileName').textContent = file.name;
    document.getElementById('fileSize').textContent = formatFileSize(file.size);
    document.getElementById('fileDropZone').style.display = 'none';
    document.getElementById('filePreview').style.display = 'flex';
}
function clearFile() {
    document.getElementById('topicFile').value = '';
    document.getElementById('fileDropZone').style.display = 'flex';
    document.getElementById('filePreview').style.display = 'none';
}
function formatFileSize(b) { if (b < 1024) return b + ' B'; if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'; return (b / 1048576).toFixed(1) + ' MB'; }

// ── Toast ──
function showToast(msg, type = 'success') {
    const t = document.getElementById('toast');
    t.className = `toast show toast-${type}`;
    document.getElementById('toastMessage').textContent = msg;
    setTimeout(() => t.classList.remove('show'), 3000);
}

// ── Time ago ──
function timeAgo(val) {
    if (!val) return '';
    let ts;
    if (typeof val === 'number') {
        ts = val;
    } else {
        let s = String(val).trim();
        if (!s.includes('+') && !s.endsWith('Z') && !s.includes('T')) {
            s = s.replace(' ', 'T') + 'Z';
        }
        ts = new Date(s).getTime();
    }
    const diff = Date.now() - ts;
    if (diff < 0 || isNaN(diff)) return 'ahora';
    const m = Math.floor(diff / 60000), h = Math.floor(diff / 3600000), d = Math.floor(diff / 86400000);
    if (m < 1) return 'ahora';
    if (m < 60) return `hace ${m} min`;
    if (h < 24) return `hace ${h}h`;
    return `hace ${d}d`;
}

// ── Close modals on backdrop ──
document.querySelectorAll('.modal-overlay').forEach(m =>
    m.addEventListener('click', function (e) { if (e.target === this) this.classList.remove('show'); })
);

// ═══════════════════════════════════════════════════════════
//  LEADERBOARD
// ═══════════════════════════════════════════════════════════
let leaderboardRanking = [];
let lbPeriod = 'week';

async function loadLeaderboard() {
    showLeaderboardSkeleton();
    try {
        // ★ URL CORREGIDA — apunta al nuevo LeaderboardServlet ★
        const res = await fetch(
            CTX + '/api/leaderboard/duels?period=' + lbPeriod + '&scope=friends',
            { headers: duelHeaders() }
        );
        const data = await res.json();
        if (data.success) {
            leaderboardRanking = data.leaderboard || [];  // ← campo correcto
            renderLeaderboard();
        }
    } catch (e) { console.error('[Duelos] loadLeaderboard:', e); }
}

function lbSetPeriod(period, btn) {
    lbPeriod = period;
    document.querySelectorAll('.lb-period-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    loadLeaderboard();
}

function showLeaderboardSkeleton() {
    const podium = document.getElementById('lbPodium');
    const list   = document.getElementById('lbList');
    if (podium) podium.innerHTML = '<div class="lb-empty"><i class="fas fa-spinner fa-spin"></i>&nbsp;Cargando ranking...</div>';
    if (list) { list.innerHTML = ''; list.classList.remove('has-sticky-me'); }
}

// ── Tendencia de posición (vs. la última vez que viste este período) ──
// No requiere cambios de backend: guardamos el ranking anterior en
// localStorage por período y comparamos contra el nuevo al renderizar.
function lbGetPrevRanks(period) {
    try { return JSON.parse(localStorage.getItem('lbPrevRanks_' + period)) || {}; }
    catch (_) { return {}; }
}
function lbSavePrevRanks(period, ranking) {
    const map = {};
    ranking.forEach((r, i) => { map[String(r.userId)] = i + 1; });
    try { localStorage.setItem('lbPrevRanks_' + period, JSON.stringify(map)); }
    catch (_) { /* localStorage no disponible, se omite silenciosamente */ }
}
function lbTrendHtml(userId, rank, prevRanks) {
    const prev = prevRanks[String(userId)];
    if (prev === undefined || prev === null || prev === rank) return '';
    const diff = Math.abs(prev - rank);
    const dir  = prev > rank ? 'up' : 'down';
    const label = dir === 'up' ? `Subió ${diff} posición(es)` : `Bajó ${diff} posición(es)`;
    return `<span class="lb-trend ${dir}" title="${label}">${diff}</span>`;
}

// ── Animación de conteo (ease-out) para números que suben de golpe ──
const _lbReduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
function lbAnimateValue(el, endValue, opts) {
    if (!el) return;
    const format = (opts && opts.format) || (v => Math.round(v));
    if (_lbReduceMotion) { el.textContent = format(endValue); return; }
    const duration = (opts && opts.duration) || 700;
    const start = 0;
    const t0 = performance.now();
    function step(now) {
        const p = Math.min((now - t0) / duration, 1);
        const eased = 1 - Math.pow(1 - p, 3);
        el.textContent = format(start + (endValue - start) * eased);
        if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
}

function renderLeaderboard() {
    const podiumEl = document.getElementById('lbPodium');
    const listEl   = document.getElementById('lbList');
    const myId     = getUserId();

    if (!podiumEl) return;

    const prevRanks = lbGetPrevRanks(lbPeriod);

    // Banner mi posición
    const myIdx = leaderboardRanking.findIndex(r => String(r.userId) === String(myId));
    const banner = document.getElementById('lbMyBanner');
    if (banner) {
        if (myIdx >= 0) {
            const me = leaderboardRanking[myIdx];
            banner.style.display = 'flex';
            document.getElementById('lbMyPos').textContent = '#' + (myIdx + 1);
            lbAnimateValue(document.getElementById('lbMyWins'), me.wins || 0);
            lbAnimateValue(document.getElementById('lbMyXp'), me.xp || 0, { format: lbFmt });
            lbAnimateValue(document.getElementById('lbMyStreak'), me.streak || 0);
        } else {
            banner.style.display = 'none';
        }
    }

    const top3 = leaderboardRanking.slice(0, 3);
    const rest = leaderboardRanking.slice(3);

    // Podio — orden visual: 2° | 1° | 3°
    if (top3.length === 0) {
        podiumEl.innerHTML = '<div class="lb-empty"><i class="fas fa-ghost"></i>Aún no hay datos.<br><small>Juega duelos para aparecer aquí.</small></div>';
    } else {
        const visOrder = top3.length === 3 ? [top3[1], top3[0], top3[2]] : top3;
        const visRanks = top3.length === 3 ? [2, 1, 3] : [1, 2, 3];
        const visCls   = ['p2', 'p1', 'p3'];

        podiumEl.innerHTML = visOrder.map((row, vi) => {
            if (!row) return `<div class="lb-pc ${visCls[vi]}"></div>`;
            const rank = visRanks[vi];
            const isMe = String(row.userId) === String(myId);
            const init = lbInitials(row.username);
            const trend = lbTrendHtml(row.userId, rank, prevRanks);
            return `
            <div class="lb-pc ${visCls[vi]}${isMe ? ' lb-me' : ''}" style="animation-delay:${vi * 70}ms">
                <div class="lb-pc-stars">${generateStars(5)}</div>
                ${rank === 1 ? '<div class="lb-crown">👑</div>' : ''}
                <div class="lb-av">
                    <span class="lb-av-initials">${init}</span>
                    <div class="lb-badge">${rank}</div>
                </div>
                <div class="lb-name">${escHtml(row.username || 'Jugador')}${isMe ? ' <span class="lb-you-tag">tú</span>' : ''}${trend}</div>
                <div class="lb-handle">#${rank}</div>
                <div class="lb-metrics">
                    <div class="lb-mrow">
                        <i class="fas fa-swords lb-micon" style="color:#eab308;"></i>
                        <span class="lb-mlabel">Victorias</span>
                        <span class="lb-mval gold" data-lb-wins>${row.wins || 0}</span>
                    </div>
                    <div class="lb-mrow">
                        <i class="fas fa-star lb-micon" style="color:var(--accent-cyan,#2dd4bf);"></i>
                        <span class="lb-mlabel">XP</span>
                        <span class="lb-mval cy" data-lb-xp>${lbFmt(row.xp || 0)}</span>
                    </div>
                    <div class="lb-mrow">
                        <i class="fas fa-fire lb-micon" style="color:#8b5cf6;"></i>
                        <span class="lb-mlabel">Racha</span>
                        <span class="lb-mval pu" data-lb-streak>${row.streak || 0}d</span>
                    </div>
                </div>
            </div>`;
        }).join('');

        // Animar los contadores del podio una vez montado el HTML
        podiumEl.querySelectorAll('.lb-pc').forEach((card, vi) => {
            const row = visOrder[vi];
            if (!row) return;
            lbAnimateValue(card.querySelector('[data-lb-wins]'), row.wins || 0);
            lbAnimateValue(card.querySelector('[data-lb-xp]'), row.xp || 0, { format: lbFmt });
            lbAnimateValue(card.querySelector('[data-lb-streak]'), row.streak || 0, { format: v => Math.round(v) + 'd' });
        });
    }

    // Lista 4+
    if (!listEl) { lbSavePrevRanks(lbPeriod, leaderboardRanking); return; }
    if (rest.length === 0) {
        listEl.innerHTML = '';
        listEl.classList.remove('has-sticky-me');
        lbSavePrevRanks(lbPeriod, leaderboardRanking);
        return;
    }

    const buildRow = (row, rank, isMe, extraClass, delayMs) => {
        const init = lbInitials(row.username);
        const trend = lbTrendHtml(row.userId, rank, prevRanks);
        return `
        <div class="lb-row${isMe ? ' lb-me' : ''}${extraClass ? ' ' + extraClass : ''}" style="animation-delay:${delayMs}ms">
            <div class="lb-rrank">${rank}</div>
            <div class="lb-ruser">
                <div class="lb-rav">${init}</div>
                <div>
                    <div class="lb-rname">${escHtml(row.username || 'Jugador')}${isMe ? ' <span class="lb-you-tag">tú</span>' : ''}${trend}</div>
                    <div class="lb-rhandle">#${rank}</div>
                </div>
            </div>
            <div class="lb-rpills">
                <span class="lb-pill w"><i class="fas fa-swords"></i>&nbsp;${row.wins || 0}</span>
                <span class="lb-pill x"><i class="fas fa-star"></i>&nbsp;${lbFmt(row.xp || 0)}</span>
                <span class="lb-pill s"><i class="fas fa-fire"></i>&nbsp;${row.streak || 0}d</span>
            </div>
        </div>`;
    };

    let html = rest.map((row, i) => {
        const rank = i + 4;
        const isMe = String(row.userId) === String(myId);
        return buildRow(row, rank, isMe, '', Math.min(i, 10) * 30);
    }).join('');

    // Si "tú" quedaste fuera del podio, clonamos tu fila fijada al fondo
    // de la lista para que siempre sepas dónde estás sin tener que scrollear.
    const restMyIdx = myIdx >= 3 ? myIdx - 3 : -1;
    if (restMyIdx >= 0) {
        const me = rest[restMyIdx];
        html += buildRow(me, restMyIdx + 4, true, 'lb-me-sticky', 0);
        listEl.classList.add('has-sticky-me');
    } else {
        listEl.classList.remove('has-sticky-me');
    }

    listEl.innerHTML = html;

    // Guarda el ranking actual para poder calcular la tendencia la próxima vez
    lbSavePrevRanks(lbPeriod, leaderboardRanking);
}

function lbFmt(n) { return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : Math.round(n); }
function lbInitials(name) {
    if (!name) return '?';
    const p = name.trim().split(' ');
    return p.length >= 2 ? (p[0][0] + p[1][0]).toUpperCase() : p[0].substring(0, 2).toUpperCase();
}

function escHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}