// ============================================================
//  tienda(1).js — Mi ProfesorIA
//  Igual que tienda.js PERO con compras conectadas al backend:
//    - loadShop()              → GET  /shop
//    - buySelectedBackground   → POST /shop/buy
//    - buyProduct              → POST /shop/buy
//    - equipBackground         → POST /shop/equip
// ============================================================

// ── Estado global ─────────────────────────────────────────────
let userBalance               = 0;         // viene del backend vía sessionStorage
let ownedItemIds              = [];        // IDs enteros de BD que el usuario ya tiene
let selectedBackgroundPrice   = 0;
let selectedBackgroundId      = null;      // data-id HTML  (ej: 'bg-galaxy')
let selectedBackgroundDbId    = null;      // data-db-id    (id entero para el backend)
let selectedBackgroundClass   = null;
let ownedBackgrounds          = ['bg-default'];
let currentEquippedBackground = 'bg-default';
let lastPurchasedBackground   = null;

// ── Context path dinámico (ej: '/project-1.0-SNAPSHOT') ──────
const CTX = window.location.pathname.split('/pages')[0];

// ── Arranque ──────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', function () {
    initGalaxyCanvas();
    initForestCanvas();
    initVolcanoCanvas();
    initOceanCanvas();
    initSkyCanvas();
    initRainCanvas();
    initAuroraCanvas();

    const _tt = PolarisLoading.rotateMessages('tiendaLoadingSub',
        ['Cargando la tienda...', 'Obteniendo tu inventario...', 'Casi listo...']);
    loadShop().finally(() => { clearInterval(_tt); PolarisLoading.hide('tiendaLoading'); });
});

// ============================================================
//  CARGA INICIAL — GET /shop
//  Trae balance real, inventario y fondo equipado
// ============================================================
async function loadShop() {
    try {
        const response = await fetch(CTX + "/shop");
        const data     = await response.json();

        if (!data.success) {
            console.error("[Tienda] Error al cargar:", data.error);
            return;
        }

        // 1. Inyectar data-db-id en el HTML usando los items del backend
        //    Matchea por type + cost (todos los costos son únicos por tipo)
        if (data.items) {
            data.items.forEach(item => {
                if (item.type === 'background') {
                    const el = document.querySelector(`.background-item[data-price="${item.cost}"]`);
                    if (el) el.dataset.dbId = item.id;
                } else {
                    // streak_shield u otros productos
                    const el = document.querySelector(`.product-card[data-price="${item.cost}"]`);
                    if (el) el.dataset.dbId = item.id;
                }
            });
            console.log("[Tienda] data-db-id inyectados desde backend.");
        }

        // 2. Balance real desde el backend (fuente de verdad)
        if (data.userCoins !== undefined) {
            userBalance = data.userCoins;
            updateBalance(userBalance);
        }

        // 3. Marcar items que el usuario ya posee
        ownedItemIds = data.ownedItemIds || [];
        markOwnedItems(ownedItemIds);

        // 4. Restaurar el fondo guardado (solo visual, ya está en BD)
        if (data.equippedBackgroundId) {
            const bgEl = document.querySelector(`[data-db-id="${data.equippedBackgroundId}"]`);
            if (bgEl) applyEquipVisual(bgEl.dataset.class, bgEl);
        }

    } catch (error) {
        console.error("[Tienda] Error de conexion en loadShop:", error);
    }
}

// Marca visualmente los ítems que el usuario ya compró
function markOwnedItems(ids) {
    ids.forEach(dbId => {
        const el = document.querySelector(`[data-db-id="${dbId}"]`);
        if (!el) return;

        el.classList.add('owned');

        const preview = el.querySelector('.background-preview');
        if (preview && !preview.querySelector('.owned-badge')) {
            const badge       = document.createElement('div');
            badge.className   = 'owned-badge';
            badge.textContent = '✓ TUYO';
            preview.appendChild(badge);
        }

        const priceEl = el.querySelector('.background-price');
        if (priceEl) priceEl.textContent = '✓ Comprado';

        // Registrar en ownedBackgrounds para la lógica de equipar al click
        const bgId = el.dataset.id;
        if (bgId && !ownedBackgrounds.includes(bgId)) {
            ownedBackgrounds.push(bgId);
        }
    });
}

// ============================================================
//  COMPRAR FONDO — POST /shop/buy
// ============================================================
async function buySelectedBackground() {
    if (selectedBackgroundPrice === 0) { showNotSelectedModal('fondo'); return; }
    if (!selectedBackgroundDbId || isNaN(selectedBackgroundDbId)) {
        console.error('[Tienda] Fondo sin data-db-id. ¿Existe en store_items?');
        return;
    }
    // ¿Ya lo tiene?
    if (ownedItemIds.includes(selectedBackgroundDbId) || ownedBackgrounds.includes(selectedBackgroundId)) {
        showAlreadyOwnedModal('fondo');
        return;
    }
    const balance = getBalance();
    if (balance < selectedBackgroundPrice) { showInsufficientModal(selectedBackgroundPrice); return; }

    // Loading state
    const buyBtn = document.querySelector('.category-section .buy-button');
    if (buyBtn) { buyBtn.disabled = true; buyBtn.textContent = 'Comprando...'; }

    try {
        const response = await fetch(CTX + '/shop/buy', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ itemId: selectedBackgroundDbId })
        });
        const result = await response.json();

        if (result.success) {
            ownedItemIds.push(selectedBackgroundDbId);
            updateBalance(result.remainingCoins);
            sessionStorage.setItem('userCoins', result.remainingCoins);

            // Marcar el fondo como poseído visualmente
            const bgItem = document.querySelector(`[data-id="${selectedBackgroundId}"]`);
            if (bgItem) {
                bgItem.classList.add('owned');
                const ob       = document.createElement('div');
                ob.className   = 'owned-badge';
                ob.textContent = '✓ TUYO';
                bgItem.querySelector('.background-preview').appendChild(ob);
                bgItem.querySelector('.background-price').textContent = '✓ Comprado';
            }

            if (!ownedBackgrounds.includes(selectedBackgroundId)) {
                ownedBackgrounds.push(selectedBackgroundId);
            }

            lastPurchasedBackground = selectedBackgroundClass;
            showPurchaseModal(result.itemName || 'Fondo', result.costPaid, result.remainingCoins);

            selectedBackgroundPrice = 0;
            selectedBackgroundId    = null;
            selectedBackgroundDbId  = null;
            selectedBackgroundClass = null;
            document.querySelectorAll('.background-item').forEach(i => i.classList.remove('selected'));
        } else {
            // Mostrar mensaje real del backend (ej: "Ya tienes este ítem")
            showErrorModal(result.message || 'No se pudo completar la compra.');
            console.error('[Tienda] Compra fallida:', result.message);
        }

    } catch (error) {
        console.error('[Tienda] Error de conexión al comprar fondo:', error);
    } finally {
        if (buyBtn) { buyBtn.disabled = false; buyBtn.textContent = 'Comprar Fondo'; }
    }
}

// ============================================================
//  COMPRAR PRODUCTO (streak_shield, etc.) — POST /shop/buy
// ============================================================
async function buyProduct(productName, price, btn) {
    const card = btn.closest('.product-card');
    const dbId = card ? parseInt(card.dataset.dbId) : null;

    if (!dbId) {
        console.error('[Tienda] Falta data-db-id en el product-card de:', productName);
        return;
    }

    const balance = getBalance();
    if (balance < price) { showInsufficientModal(price); return; }

    // Loading state
    btn.disabled = true;
    btn.textContent = 'Comprando...';

    try {
        const response = await fetch(CTX + '/shop/buy', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ itemId: dbId })
        });
        const result = await response.json();

        if (result.success) {
            updateBalance(result.remainingCoins);
            sessionStorage.setItem('userCoins', result.remainingCoins);
            showPurchaseModal(productName, price, result.remainingCoins);
        } else {
            showErrorModal(result.message || 'No se pudo completar la compra.');
            console.error('[Tienda] Compra fallida:', result.message);
        }

    } catch (error) {
        console.error('[Tienda] Error de conexión al comprar producto:', error);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Comprar';
    }
}

// ============================================================
//  SELECCIÓN
// ============================================================
function selectBackground(element, price, id, bgClass) {
    document.querySelectorAll('.background-item').forEach(item => item.classList.remove('selected'));
    element.classList.add('selected');

    const buyBtn = document.querySelector('.section-backgrounds .buy-button');
    const isOwned = ownedBackgrounds.includes(id) || ownedItemIds.includes(parseInt(element.dataset.dbId));

    if (isOwned) {
        // Fondo ya comprado — mostrar confirmación para equipar
        selectedBackgroundPrice = 0;
        selectedBackgroundId    = null;
        selectedBackgroundDbId  = null;
        selectedBackgroundClass = null;

        if (buyBtn) {
            buyBtn.disabled = true;
            buyBtn.innerHTML = '<i class="fas fa-check-circle"></i> Ya comprado';
        }

        showEquipConfirmModal(bgClass);
        return;
    }

    // Fondo no comprado — seleccionar para compra
    selectedBackgroundPrice = price;
    selectedBackgroundId    = id;
    selectedBackgroundDbId  = parseInt(element.dataset.dbId);
    selectedBackgroundClass = bgClass;

    if (buyBtn) {
        buyBtn.disabled = false;
        buyBtn.innerHTML = '<i class="fas fa-shopping-cart"></i> Comprar Fondo';
    }
}

// ============================================================
//  EQUIPAR FONDO — aplica CSS + POST /shop/equip
// ============================================================
async function equipBackground(bgClass) {
    const newBgItem = document.querySelector(`[data-class="${bgClass}"]`);
    const dbId = newBgItem ? parseInt(newBgItem.dataset.dbId) : null;

    console.log('[Tienda] equipBackground → bgClass:', bgClass, '| dbId:', dbId);

    // bg-default: solo visual (no necesita backend, siempre disponible)
    if (bgClass === 'bg-default') {
        applyEquipVisual(bgClass, newBgItem);
        // Si hay un fondo equipado en BD, desequiparlo
        if (dbId && !isNaN(dbId)) {
            fetch(CTX + '/shop/equip', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ itemId: dbId })
            }).catch(() => {});
        }
        return;
    }

    // Fondos comprados: primero backend, luego visual
    if (!dbId || isNaN(dbId)) {
        showErrorModal('No se pudo equipar: fondo no encontrado en la tienda.');
        return;
    }

    try {
        const res = await fetch(CTX + '/shop/equip', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ itemId: dbId })
        });
        const result = await res.json();
        console.log('[Tienda] Respuesta equip:', result);

        if (result.success) {
            applyEquipVisual(bgClass, newBgItem);
        } else {
            showErrorModal(result.message || 'No se pudo equipar el fondo.');
        }
    } catch (error) {
        console.error('[Tienda] Error al equipar fondo en BD:', error);
        showErrorModal('Error de conexión al equipar.');
    }
}

// Aplica el cambio visual del fondo (solo llamado si el backend confirmó)
function applyEquipVisual(bgClass, bgItem) {
    const contentArea = document.querySelector('.content');
    contentArea.classList.remove('bg-galaxy','bg-volcano','bg-ocean','bg-forest','bg-aurora','bg-sky','bg-rain','bg-default');
    if (bgClass !== 'bg-default') contentArea.classList.add(bgClass);

    document.querySelectorAll('.background-item').forEach(item => {
        const badge = item.querySelector('.equipped-badge');
        if (badge) badge.remove();
    });

    if (bgItem) {
        const badge       = document.createElement('div');
        badge.className   = 'equipped-badge';
        badge.textContent = '★ EQUIPADO';
        bgItem.querySelector('.background-preview').appendChild(badge);
    }

    currentEquippedBackground = bgClass;
}

function equipNow() {
    if (lastPurchasedBackground) {
        equipBackground(lastPurchasedBackground);
        lastPurchasedBackground = null;
    }
    closePurchaseModal();
}

function equipLater() {
    lastPurchasedBackground = null;
    closePurchaseModal();
}

// ============================================================
//  UTILIDADES — balance, modales, insufficient
// ============================================================
function updateBalance(newBalance) {
    userBalance = newBalance;
    const balanceEl = document.getElementById('user-balance-header');
    if (balanceEl) balanceEl.textContent = newBalance.toLocaleString();
    updateInsufficientItems();
}

function updateInsufficientItems() {
    document.querySelectorAll('.background-item').forEach(item => {
        if (item.classList.contains('owned')) return;
        item.classList.toggle('insufficient', parseInt(item.dataset.price) > userBalance);
    });

    document.querySelectorAll('.product-card').forEach(card => {
        const price  = parseInt(card.dataset.price);
        const button = card.querySelector('.product-buy-btn');
        card.classList.toggle('insufficient', price > userBalance);
        if (button) button.disabled = price > userBalance;
    });
}

function showInsufficientModal(price) {
    const balance = getBalance();
    const modal = document.getElementById('insufficientModal');
    modal.querySelector('.modal-title').textContent   = 'Fondos Insuficientes';
    modal.querySelector('.modal-message').textContent = 'No tienes suficientes monedas';
    modal.querySelector('.modal-icon').textContent    = '❌';
    document.getElementById('modal-price').textContent   = price;
    document.getElementById('modal-balance').textContent = balance;
    document.getElementById('modal-needed').textContent  = price - balance;
    modal.classList.add('show');
}

function showNotSelectedModal(tipo) {
    const modal = document.getElementById('insufficientModal');
    modal.querySelector('.modal-title').textContent   = 'Selección requerida';
    modal.querySelector('.modal-message').textContent = `Por favor selecciona un ${tipo} primero.`;
    modal.querySelector('.modal-icon').textContent    = '⚠️';
    document.getElementById('modal-price').textContent   = '—';
    document.getElementById('modal-balance').textContent = userBalance;
    document.getElementById('modal-needed').textContent  = '—';
    modal.classList.add('show');
}

function closeModal() {
    document.getElementById('insufficientModal').classList.remove('show');
}

function showAlreadyOwnedModal(tipo) {
    const modal = document.getElementById('insufficientModal');
    modal.querySelector('.modal-title').textContent   = '¡Ya es tuyo!';
    modal.querySelector('.modal-message').textContent = `Este ${tipo} ya fue comprado. Selecciónalo para equiparlo.`;
    modal.querySelector('.modal-icon').textContent    = '✅';
    document.getElementById('modal-price').textContent   = '—';
    document.getElementById('modal-balance').textContent = userBalance.toLocaleString();
    document.getElementById('modal-needed').textContent  = '—';
    modal.classList.add('show');
}

function showErrorModal(message) {
    const modal = document.getElementById('insufficientModal');
    modal.querySelector('.modal-title').textContent   = 'Error';
    modal.querySelector('.modal-message').textContent = message;
    modal.querySelector('.modal-icon').textContent    = '⚠️';
    document.getElementById('modal-price').textContent   = '—';
    document.getElementById('modal-balance').textContent = userBalance.toLocaleString();
    document.getElementById('modal-needed').textContent  = '—';
    modal.classList.add('show');
}

function showPurchaseModal(itemName, price, newBalance) {
    document.getElementById('purchase-message').textContent = `Has comprado: ${itemName}`;
    document.getElementById('purchase-price').textContent   = price;
    document.getElementById('purchase-balance').textContent = newBalance;
    document.getElementById('purchaseModal').classList.add('show');
}

function closePurchaseModal() {
    document.getElementById('purchaseModal').classList.remove('show');
}

// ── Modal de confirmación para equipar ──────────────────────
let pendingEquipBgClass = null;

function showEquipConfirmModal(bgClass) {
    pendingEquipBgClass = bgClass;
    const modal = document.getElementById('equipConfirmModal');
    // Buscar nombre del fondo
    const bgItem = document.querySelector(`[data-class="${bgClass}"]`);
    const name = bgItem ? bgItem.querySelector('.background-name').textContent : bgClass;
    modal.querySelector('.equip-confirm-name').textContent = name;
    modal.classList.add('show');
}

function closeEquipModal() {
    document.getElementById('equipConfirmModal').classList.remove('show');
    pendingEquipBgClass = null;
}

function confirmEquip() {
    if (pendingEquipBgClass) {
        equipBackground(pendingEquipBgClass);
        pendingEquipBgClass = null;
    }
    document.getElementById('equipConfirmModal').classList.remove('show');
}

// ── Leer balance — userBalance es la fuente de verdad ────────
// Se sincroniza con el DOM del navbar si existe, pero el valor
// principal viene del backend (loadShop / remainingCoins).
function getBalance() {
    // Si el navbar ya tiene un valor más actualizado, sincronizar
    const el = document.getElementById('user-balance-header');
    if (el) {
        const parsed = parseInt(el.textContent.replace(/[^0-9]/g, ''));
        if (!isNaN(parsed) && parsed > 0 && userBalance === 0) {
            userBalance = parsed;
        }
    }
    return userBalance;
}

// Polling cada 500ms hasta que el balance sea > 0, para actualizar
// la clase 'insufficient' de los ítems correctamente
const _balancePoller = setInterval(() => {
    const balance = getBalance();
    if (balance > 0) {
        updateInsufficientItems();
        clearInterval(_balancePoller);
    }
}, 500);

// Cerrar modales al click en el backdrop
document.getElementById('insufficientModal').addEventListener('click', function (e) {
    if (e.target === this) closeModal();
});
document.getElementById('purchaseModal').addEventListener('click', function (e) {
    if (e.target === this) equipLater();
});
document.getElementById('equipConfirmModal').addEventListener('click', function (e) {
    if (e.target === this) closeEquipModal();
});

// ============================================================
//  CANVAS 1 — GALAXIA: estrellas titilantes
// ============================================================
function initGalaxyCanvas() {
    const canvas  = document.getElementById('galaxyCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');

    function resizeCanvas() {
        canvas.width  = content.offsetWidth;
        canvas.height = content.offsetHeight;
    }
    resizeCanvas();

    const stars = [];
    for (let i = 0; i < 200; i++) {
        stars.push({
            x:             Math.random() * canvas.width,
            y:             Math.random() * canvas.height,
            size:          Math.random() * 2 + 0.3,
            speedX:        (Math.random() - 0.5) * 0.15,
            speedY:        (Math.random() - 0.5) * 0.15,
            opacity:       Math.random() * 0.4 + 0.1,
            opacityChange: (Math.random() - 0.5) * 0.015,
            color: ['#ffffff','#ffffff','#ffe9c4','#d4f1ff','#ffccaa','#aaddff'][Math.floor(Math.random() * 6)]
        });
    }

    function animate() {
        if (!content.classList.contains('bg-galaxy')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        stars.forEach(star => {
            star.x += star.speedX;
            star.y += star.speedY;
            if (star.x < 0) star.x = canvas.width;
            if (star.x > canvas.width)  star.x = 0;
            if (star.y < 0) star.y = canvas.height;
            if (star.y > canvas.height) star.y = 0;
            star.opacity += star.opacityChange;
            if (star.opacity <= 0.2 || star.opacity >= 1) star.opacityChange *= -1;
            ctx.beginPath();
            ctx.arc(star.x, star.y, star.size, 0, Math.PI * 2);
            ctx.fillStyle   = star.color;
            ctx.globalAlpha = star.opacity;
            ctx.fill();
            if (star.size > 1.5) {
                ctx.beginPath();
                ctx.arc(star.x, star.y, star.size * 2, 0, Math.PI * 2);
                ctx.fillStyle   = star.color;
                ctx.globalAlpha = star.opacity * 0.3;
                ctx.fill();
            }
        });
        ctx.globalAlpha = 1;
        requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 2 — FOREST: hojas PNG cayendo
// ============================================================
function initForestCanvas() {
    const canvas  = document.getElementById('forestCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');
    const leafImg = new Image();
    leafImg.src   = '../images/backgrounds/leaf.png';

    function resizeCanvas() {
        canvas.width  = content.offsetWidth;
        canvas.height = content.offsetHeight;
    }
    resizeCanvas();

    const leaves = [];
    function createLeaf() {
        return {
            x:             Math.random() * canvas.width,
            y:             -100,
            scale:         Math.random() * 0.15 + 0.08,
            speedY:        Math.random() * 0.6 + 0.3,
            speedX:        (Math.random() - 0.5) * 0.5,
            rotation:      Math.random() * Math.PI * 2,
            rotationSpeed: (Math.random() - 0.5) * 0.02,
            opacity:       Math.random() * 0.5 + 0.5,
            wobble:        Math.random() * Math.PI * 2,
            wobbleSpeed:   Math.random() * 0.03 + 0.01
        };
    }
    for (let i = 0; i < 15; i++) {
        const leaf = createLeaf();
        leaf.y = Math.random() * canvas.height;
        leaves.push(leaf);
    }

    function animate() {
        if (!content.classList.contains('bg-forest')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        if (leafImg.complete && leafImg.naturalWidth > 0) {
            leaves.forEach((leaf, index) => {
                leaf.wobble   += leaf.wobbleSpeed;
                const wobbleX  = Math.sin(leaf.wobble) * 2;
                leaf.y        += leaf.speedY;
                leaf.x        += leaf.speedX + wobbleX;
                leaf.rotation += leaf.rotationSpeed;
                if (leaf.y > canvas.height + 100) leaves[index] = createLeaf();
                if (leaf.x < -100)               leaf.x = canvas.width + 50;
                if (leaf.x > canvas.width + 100) leaf.x = -50;
                ctx.save();
                ctx.translate(leaf.x, leaf.y);
                ctx.rotate(leaf.rotation);
                ctx.globalAlpha = leaf.opacity;
                const w = leafImg.width  * leaf.scale;
                const h = leafImg.height * leaf.scale;
                ctx.drawImage(leafImg, -w / 2, -h / 2, w, h);
                ctx.restore();
            });
        }
        ctx.globalAlpha = 1;
        requestAnimationFrame(animate);
    }
    leafImg.onload = function () { animate(); };
    if (leafImg.complete) animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 3 — VOLCÁN: ceniza + lava + chispas + calor
// ============================================================
function initVolcanoCanvas() {
    const canvas  = document.getElementById('volcanoCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');

    function resizeCanvas() {
        canvas.width  = content.offsetWidth;
        canvas.height = content.offsetHeight;
    }
    resizeCanvas();

    let time = 0;
    const ashes = [];
    for (let i = 0; i < 60; i++) ashes.push(createAsh());

    function createAsh() {
        return {
            x: Math.random() * canvas.width, y: canvas.height + Math.random() * 50,
            size: Math.random() * 3 + 1, speedY: -(Math.random() * 0.8 + 0.3),
            speedX: (Math.random() - 0.3) * 0.5, opacity: Math.random() * 0.4 + 0.2,
            wobble: Math.random() * Math.PI * 2, wobbleSpeed: Math.random() * 0.02 + 0.01
        };
    }
    function drawAshes() {
        ashes.forEach((ash, i) => {
            ash.wobble += ash.wobbleSpeed;
            ash.y += ash.speedY;
            ash.x += ash.speedX + Math.sin(ash.wobble) * 1.5 * 0.1;
            if (ash.y < -20) ashes[i] = createAsh();
            ctx.save(); ctx.globalAlpha = ash.opacity; ctx.fillStyle = '#3a3a3a';
            ctx.beginPath(); ctx.arc(ash.x, ash.y, ash.size, 0, Math.PI * 2); ctx.fill(); ctx.restore();
        });
    }
    function drawLavaGlow() {
        const b  = Math.sin(time * 0.8) * 0.08 + 0.15;
        const b2 = Math.sin(time * 0.5 + 1) * 0.05 + 0.12;
        [[0.5, 1, 0, 0.6], [0.2, 0.9, 0.4], [0.8, 0.85, 0.35]].forEach(([cx, cy, r], idx) => {
            const g = ctx.createRadialGradient(canvas.width*cx, canvas.height*cy, 0, canvas.width*cx, canvas.height*cy, (r||0.5)*canvas[idx===0?'height':'width']);
            const bv = idx === 1 ? b2 : b;
            g.addColorStop(0, `rgba(255,${idx===0?100:idx===1?80:90},0,${bv})`);
            g.addColorStop(idx===0?0.3:0.5, `rgba(255,${idx===0?60:idx===1?50:40},0,${bv*(idx===0?0.5:0.3)})`);
            if (idx === 0) g.addColorStop(0.6, `rgba(200,30,0,${bv*0.2})`);
            g.addColorStop(1, 'transparent');
            ctx.fillStyle = g; ctx.fillRect(0, 0, canvas.width, canvas.height);
        });
    }
    const sparks = [];
    function createSpark() {
        return { x: canvas.width*0.3+Math.random()*canvas.width*0.4, y: canvas.height*0.7+Math.random()*canvas.height*0.2,
            size: Math.random()*4+2, life: 1, decay: Math.random()*0.03+0.02,
            speedY: -(Math.random()*2+1), speedX: (Math.random()-0.5)*2 };
    }
    function drawSparks() {
        if (Math.random() < 0.03) sparks.push(createSpark());
        for (let i = sparks.length - 1; i >= 0; i--) {
            const s = sparks[i];
            s.life -= s.decay; s.y += s.speedY; s.x += s.speedX; s.speedY += 0.05;
            if (s.life <= 0) { sparks.splice(i, 1); continue; }
            ctx.save(); ctx.globalAlpha = s.life;
            const g = ctx.createRadialGradient(s.x, s.y, 0, s.x, s.y, s.size*3);
            g.addColorStop(0,'#ffff00'); g.addColorStop(0.3,'#ff8800'); g.addColorStop(1,'transparent');
            ctx.fillStyle = g; ctx.beginPath(); ctx.arc(s.x, s.y, s.size*3, 0, Math.PI*2); ctx.fill();
            ctx.fillStyle = '#ffffff'; ctx.beginPath(); ctx.arc(s.x, s.y, s.size*0.5, 0, Math.PI*2); ctx.fill();
            ctx.restore();
        }
    }
    function drawHeatWaves() {
        const wo = 0.03 + Math.sin(time*2)*0.02;
        for (let i = 0; i < 3; i++) {
            ctx.save(); ctx.globalAlpha = wo; ctx.strokeStyle = 'rgba(255,150,50,0.3)';
            ctx.lineWidth = 30+i*20; ctx.lineCap = 'round'; ctx.beginPath();
            ctx.moveTo(0, canvas.height*(0.5+i*0.1));
            for (let x=0; x<=canvas.width; x+=10)
                ctx.lineTo(x, canvas.height*(0.5+i*0.1)+Math.sin(x*0.01+time+i)*(20+i*10));
            ctx.stroke(); ctx.restore();
        }
    }
    function drawBottomLight() {
        const intensity = 0.1+Math.sin(time*0.3)*0.05;
        const g = ctx.createLinearGradient(0, canvas.height, 0, canvas.height*0.6);
        g.addColorStop(0, `rgba(255,80,20,${intensity})`);
        g.addColorStop(0.5, `rgba(255,50,0,${intensity*0.3})`);
        g.addColorStop(1, 'transparent');
        ctx.fillStyle = g; ctx.fillRect(0, canvas.height*0.5, canvas.width, canvas.height*0.5);
    }
    function animate() {
        if (!content.classList.contains('bg-volcano')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        time += 0.02;
        drawBottomLight(); drawHeatWaves(); drawLavaGlow(); drawAshes(); drawSparks();
        requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 4 — OCÉANO: burbujas
// ============================================================
function initOceanCanvas() {
    const canvas  = document.getElementById('oceanCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');

    function resizeCanvas() { canvas.width = content.offsetWidth; canvas.height = content.offsetHeight; }
    resizeCanvas();

    const bubbles = [];
    function createBubble() {
        return { x: Math.random()*canvas.width, y: canvas.height+Math.random()*100,
            size: Math.random()*8+2, speedY: -(Math.random()*1.2+0.6), speedX: (Math.random()-0.5)*0.4,
            wobble: Math.random()*Math.PI*2, wobbleSpeed: Math.random()*0.03+0.015, opacity: Math.random()*0.5+0.2 };
    }
    for (let i = 0; i < 50; i++) bubbles.push(createBubble());

    function drawBubble(b) {
        ctx.save(); const wx = Math.sin(b.wobble)*2;
        ctx.globalAlpha = b.opacity; ctx.beginPath(); ctx.arc(b.x+wx, b.y, b.size, 0, Math.PI*2);
        ctx.strokeStyle = 'rgba(150,220,255,0.6)'; ctx.lineWidth = 1; ctx.stroke();
        ctx.beginPath(); ctx.arc(b.x+wx-b.size*.3, b.y-b.size*.3, b.size*.3, 0, Math.PI*2);
        ctx.fillStyle = 'rgba(200,240,255,0.5)'; ctx.fill(); ctx.restore();
    }
    function animate() {
        if (!content.classList.contains('bg-ocean')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        bubbles.forEach((b, i) => {
            b.y += b.speedY; b.x += b.speedX; b.wobble += b.wobbleSpeed;
            if (b.y < -20) bubbles[i] = createBubble();
            drawBubble(b);
        });
        ctx.globalAlpha = 1; requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 5 — SKY: estrellas + nubes PNG
// ============================================================
function initSkyCanvas() {
    const canvas   = document.getElementById('skyCanvas');
    const ctx      = canvas.getContext('2d');
    const content  = document.querySelector('.content');
    const cloudImg = new Image();
    cloudImg.src   = '../images/backgrounds/cloud.png';

    function resizeCanvas() { canvas.width = content.offsetWidth; canvas.height = content.offsetHeight; }
    resizeCanvas();

    const stars = [];
    for (let i = 0; i < 100; i++) {
        stars.push({ x: Math.random()*canvas.width, y: Math.random()*canvas.height*0.7,
            size: Math.random()*1.5+0.3, opacity: Math.random()*0.6+0.2, opacityChange: (Math.random()-0.5)*0.01 });
    }
    function drawStars() {
        stars.forEach(s => {
            s.opacity += s.opacityChange;
            if (s.opacity < 0.1 || s.opacity > 0.8) s.opacityChange *= -1;
            ctx.beginPath(); ctx.arc(s.x, s.y, s.size, 0, Math.PI*2);
            ctx.fillStyle = `rgba(255,255,255,${s.opacity})`; ctx.fill();
        });
    }
    const clouds = [];
    function createCloud(index) {
        return { x: index !== undefined ? (canvas.width/8)*index-200 : -300,
            y: Math.random()*canvas.height*0.7, scale: Math.random()*0.4+0.3,
            speed: Math.random()*0.4+0.2, opacity: Math.random()*0.4+0.3 };
    }
    for (let i = 0; i < 8; i++) clouds.push(createCloud(i));

    function drawClouds() {
        if (!cloudImg.complete || !cloudImg.naturalWidth) return;
        clouds.forEach((c, i) => {
            c.x += c.speed;
            if (c.x > canvas.width+100) { clouds[i] = createCloud(); clouds[i].x = -cloudImg.width*clouds[i].scale; }
            ctx.save(); ctx.globalAlpha = c.opacity;
            ctx.drawImage(cloudImg, c.x, c.y, cloudImg.width*c.scale, cloudImg.height*c.scale);
            ctx.restore();
        });
    }
    function animate() {
        if (!content.classList.contains('bg-sky')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        drawStars(); drawClouds(); requestAnimationFrame(animate);
    }
    cloudImg.onload = function () { animate(); };
    if (cloudImg.complete) animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 6 — LLUVIA DIGITAL
// ============================================================
function initRainCanvas() {
    const canvas  = document.getElementById('rainCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');

    function resizeCanvas() { canvas.width = content.offsetWidth; canvas.height = content.offsetHeight; }
    resizeCanvas();

    const drops = [];
    function createDrop() {
        const isTurquoise = Math.random() < 0.2;
        return { x: Math.random()*canvas.width, y: Math.random()*canvas.height-canvas.height,
            length: Math.random()*150+80, speed: Math.random()*3+1.5,
            opacity: Math.random()*0.6+0.4, width: Math.random()*2+1,
            color: isTurquoise ? '#2dd4bf' : '#ffffff' };
    }
    for (let i = 0; i < 100; i++) drops.push(createDrop());

    function drawDrop(d) {
        ctx.save();
        const g = ctx.createLinearGradient(d.x, d.y, d.x, d.y+d.length);
        if (d.color === '#2dd4bf') {
            g.addColorStop(0, `rgba(45,212,191,${d.opacity})`);
            g.addColorStop(0.4, `rgba(45,212,191,${d.opacity*0.6})`);
        } else {
            g.addColorStop(0, `rgba(255,255,255,${d.opacity})`);
            g.addColorStop(0.4, `rgba(255,255,255,${d.opacity*0.5})`);
        }
        g.addColorStop(1, 'transparent');
        ctx.strokeStyle = g; ctx.lineWidth = d.width; ctx.lineCap = 'round';
        ctx.beginPath(); ctx.moveTo(d.x, d.y); ctx.lineTo(d.x, d.y+d.length); ctx.stroke();
        ctx.beginPath(); ctx.arc(d.x, d.y, d.color==='#2dd4bf'?4:2, 0, Math.PI*2);
        ctx.fillStyle = d.color==='#2dd4bf' ? `rgba(45,212,191,${d.opacity})` : `rgba(255,255,255,${d.opacity*0.8})`;
        ctx.fill();
        if (d.color === '#2dd4bf') {
            ctx.beginPath(); ctx.arc(d.x, d.y, 8, 0, Math.PI*2);
            ctx.fillStyle = `rgba(45,212,191,${d.opacity*0.3})`; ctx.fill();
        }
        ctx.restore();
    }
    function animate() {
        if (!content.classList.contains('bg-rain')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        drops.forEach((d, i) => {
            d.y += d.speed;
            if (d.y > canvas.height+d.length) { drops[i] = createDrop(); drops[i].y = -drops[i].length; }
            drawDrop(d);
        });
        ctx.globalAlpha = 1; requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}

// ============================================================
//  CANVAS 7 — AURORA BOREAL: cortinas + flujo magnético + glow
// ============================================================
function initAuroraCanvas() {
    const canvas  = document.getElementById('auroraCanvas');
    const ctx     = canvas.getContext('2d');
    const content = document.querySelector('.content');

    function resizeCanvas() { canvas.width = content.offsetWidth; canvas.height = content.offsetHeight; }
    resizeCanvas();

    let time = 0;
    const curtains = [];
    for (let i = 0; i < 8; i++) {
        curtains.push({ x: (canvas.width/8)*i+Math.random()*50, width: Math.random()*80+40,
            speed: Math.random()*0.3+0.1, opacity: Math.random()*0.1+0.15, phase: Math.random()*Math.PI*2 });
    }
    function drawCurtains() {
        curtains.forEach(c => {
            const wo = Math.sin(time*c.speed+c.phase)*30;
            const g  = ctx.createLinearGradient(c.x+wo-c.width/2,0, c.x+wo+c.width/2,0);
            g.addColorStop(0,'transparent'); g.addColorStop(0.3,`rgba(80,255,120,${c.opacity})`);
            g.addColorStop(0.5,`rgba(100,255,150,${c.opacity*1.2})`); g.addColorStop(0.7,`rgba(80,255,120,${c.opacity})`);
            g.addColorStop(1,'transparent');
            ctx.fillStyle = g; ctx.fillRect(c.x+wo-c.width, 0, c.width*2, canvas.height);
        });
    }
    function drawMagneticFlow() {
        ctx.save(); ctx.globalAlpha = 0.15;
        for (let wave = 0; wave < 5; wave++) {
            ctx.beginPath();
            const baseY = canvas.height*0.3+wave*60, amp = 40+wave*10, freq = 0.003+wave*0.001;
            ctx.moveTo(0, baseY);
            for (let x = 0; x <= canvas.width; x+=5)
                ctx.lineTo(x, baseY + Math.sin(x*freq+time*0.5+wave*0.5)*amp + Math.sin(x*freq*2+time*0.3)*(amp*0.3));
            const wg = ctx.createLinearGradient(0,baseY-amp,0,baseY+amp);
            wg.addColorStop(0,'transparent'); wg.addColorStop(0.5,'rgba(50,255,100,0.6)'); wg.addColorStop(1,'transparent');
            ctx.strokeStyle = wg; ctx.lineWidth = 20+wave*5; ctx.lineCap = 'round'; ctx.filter = 'blur(8px)'; ctx.stroke();
        }
        ctx.filter = 'none'; ctx.restore();
    }
    function drawAtmosphericGlow() {
        ctx.save();
        const breathe = Math.sin(time*0.3)*0.03+0.12;
        [[0.5,0.2,0.6],[0,0.4,0.4]].forEach(([cx,cy,r]) => {
            const g = ctx.createRadialGradient(canvas.width*cx, canvas.height*cy,0, canvas.width*cx, canvas.height*cy, canvas.width*r);
            g.addColorStop(0,`rgba(80,255,120,${breathe})`); g.addColorStop(0.5,`rgba(50,200,100,${breathe*0.5})`); g.addColorStop(1,'transparent');
            ctx.fillStyle = g; ctx.fillRect(0,0,canvas.width,canvas.height);
        });
        const ps = canvas.width*0.4+Math.sin(time*0.5)*50;
        const gc = ctx.createRadialGradient(canvas.width*.5,canvas.height*.4,0, canvas.width*.5,canvas.height*.4,ps);
        gc.addColorStop(0,`rgba(100,255,130,${breathe*0.6})`); gc.addColorStop(0.4,`rgba(60,220,100,${breathe*0.3})`); gc.addColorStop(1,'transparent');
        ctx.fillStyle = gc; ctx.fillRect(0,0,canvas.width,canvas.height); ctx.restore();
    }
    function animate() {
        if (!content.classList.contains('bg-aurora')) { requestAnimationFrame(animate); return; }
        ctx.clearRect(0,0,canvas.width,canvas.height); time += 0.02;
        drawAtmosphericGlow(); drawMagneticFlow(); requestAnimationFrame(animate);
    }
    animate();
    window.addEventListener('resize', resizeCanvas);
}