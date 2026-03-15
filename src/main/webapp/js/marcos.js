/**
 * marcos.js — Sistema de marcos por nivel
 * 
 * Uso:
 *   1. Incluir marcos.css y marcos.js en la página
 *   2. Llamar: renderFrame(level, 'containerId')
 *   3. Para Galaxy (Nv.9) y Waves (tienda), llamar initGalaxyCanvas() o initWavesCanvas() después
 *
 * Mapeo nivel → marco:
 *   Nv.1  → Sin marco (default)
 *   Nv.2  → Océano
 *   Nv.3  → Volcán
 *   Nv.4  → Sakura
 *   Nv.5  → Amazonas
 *   Nv.6  → Neón
 *   Nv.7  → Espectro RGB
 *   Nv.8  → Dragón
 *   Nv.9  → Galaxia (canvas)
 *   Nv.10 → Luxury Dorado
 *   Especial → Ondas de Luz (compra en tienda)
 */

const FRAME_CONFIG = {
    1:  { key: 'default',  name: 'Default',         subtitle: '' },
    2:  { key: 'oceano',   name: 'Océano',          subtitle: 'Abismo Profundo' },
    3:  { key: 'volcan',   name: 'Volcán',          subtitle: 'Lava y Ceniza' },
    4:  { key: 'sakura',   name: 'Sakura',          subtitle: 'Flores de Cerezo' },
    5:  { key: 'amazonas', name: 'Amazonas',        subtitle: 'Selva Viviente' },
    6:  { key: 'neon',     name: 'Neón',            subtitle: 'Pulsante' },
    7:  { key: 'espectro', name: 'Espectro',        subtitle: 'Prisma RGB' },
    8:  { key: 'dragon',   name: 'Dragón',          subtitle: 'Ouroboros de Fuego' },
    9:  { key: 'galaxia',  name: 'Galaxia',         subtitle: 'Cósmica' },
    10: { key: 'dorado',   name: 'Luxury',          subtitle: 'Dorado' },
};

/**
 * Renderiza el marco del nivel indicado dentro del contenedor.
 * @param {number} level  Nivel del usuario (1-10)
 * @param {string} containerId  ID del elemento donde insertar el marco
 * @param {string} [avatarContent]  HTML opcional para dentro del avatar circle (ej: <img> o <i>)
 */
/**
 * Renderiza el marco del nivel indicado dentro del contenedor.
 * @param {number} level  Nivel del usuario (1-10)
 * @param {string} containerId  ID del elemento donde insertar el marco
 * @param {string} [avatarContent]  HTML opcional para dentro del avatar circle
 * @param {number} [scale]  Escala: 1=230px, 0.5=115px, 0.35=80px. Default: 1
 */
function renderFrame(level, containerId, avatarContent, scale) {
    const container = document.getElementById(containerId);
    if (!container) return;

    const s = scale || 1;
    const size = Math.round(230 * s);
    const lvl = Math.max(1, Math.min(10, level || 1));
    const config = FRAME_CONFIG[lvl];
    const iconSize = Math.max(1.2, 3 * s);
    const avatarInner = avatarContent || `<i class="fas fa-user" style="font-size:${iconSize}rem;color:rgba(255,255,255,0.3);position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:2"></i>`;

    // Avatar HTML (shared across all frames)
    const avHtml = `<div class="av">${avatarInner}</div>`;
    const badgeHtml = s < 0.5 ? '' : `<div class="badge ${getBadgeClass(lvl)}">Nv. ${lvl}</div>`;

    let frameHtml = '';

    switch (lvl) {
        case 1:
            // Default - simple circle, no animation
            frameHtml = `
                <div style="position:relative;width:230px;height:230px;">
                    <div style="position:absolute;inset:0;border-radius:50%;border:3px solid rgba(139,92,246,0.3);"></div>
                    ${avHtml}
                    ${badgeHtml}
                </div>`;
            break;

        case 2: // Océano
            frameHtml = `
                <div class="fo">
                    <div class="gw"></div>${avHtml}<div class="ro"></div><div class="wv"></div>
                    <div class="bubs">${repeat('<div class="bub"></div>', 8)}</div>
                    <div class="shms">${repeat('<div class="shm"></div>', 5)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 3: // Volcán
            frameHtml = `
                <div class="fv">
                    <div class="gw"></div>${avHtml}<div class="ro"></div><div class="ri"></div>
                    <div class="ef">${repeat('<div class="ash"></div>', 8)}${repeat('<div class="emb"></div>', 6)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 4: // Sakura
            frameHtml = `
                <div class="fs">
                    <div class="gw"></div>${avHtml}
                    <div class="pts">${repeat('<div class="pet"></div>', 8)}</div>
                    <div class="sps">${repeat('<div class="ss"></div>', 5)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 5: // Amazonas
            frameHtml = `
                <div class="fa">
                    <div class="gw"></div>${avHtml}<div class="ro"></div><div class="bio"></div>
                    <div class="lf"><div class="lef">&#127811;</div><div class="lef">&#127807;</div><div class="lef">&#127811;</div><div class="lef">&#127807;</div><div class="lef">&#127811;</div><div class="lef">&#127807;</div></div>
                    <div class="ffs">${repeat('<div class="ff"></div>', 5)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 6: // Neón
            frameHtml = `
                <div class="fn">
                    <div class="gw"></div>
                    <div class="ring1"></div><div class="ring2"></div><div class="ring3"></div>
                    ${avHtml}
                    <div class="scan"></div>
                    <div class="nsps">${repeat('<div class="ns"></div>', 8)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 7: // Espectro RGB
            frameHtml = `
                <div class="fr">
                    <div class="gw"></div>${avHtml}<div class="ro"></div>
                    <div class="rps">${repeat('<div class="rp"></div>', 8)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 8: // Dragón
            frameHtml = `
                <div class="fd">
                    <div class="gw"></div>${avHtml}<div class="ir"></div>
                    <div class="sp">${repeat('<div class="ds"></div>', 10)}</div>
                    ${badgeHtml}
                </div>`;
            break;

        case 9: // Galaxia (canvas)
            frameHtml = `
                <div class="fgx">
                    <canvas id="gxC" width="230" height="230"></canvas>
                    ${avHtml}
                    <div class="orbit-ring ring-earth"></div>
                    <div class="orbit-ring ring-jupiter"></div>
                    <div class="orbit-ring ring-neptune"></div>
                    ${badgeHtml}
                </div>`;
            break;

        case 10: // Luxury Dorado
            frameHtml = `
                <div class="fgd">
                    <div class="gw"></div>${avHtml}<div class="ro"></div>
                    <div class="gsps">${repeat('<div class="gsp"></div>', 8)}</div>
                    ${badgeHtml}
                </div>`;
            break;
    }

    const scaleStyle = s !== 1
        ? `style="width:${size}px;height:${size}px;display:flex;align-items:center;justify-content:center;"`
        : '';
    const innerStyle = s !== 1
        ? `style="transform:scale(${s});transform-origin:center center;"`
        : '';
    container.innerHTML = `<div class="marco-container" ${scaleStyle}><div ${innerStyle}>${frameHtml}</div></div>`;

    // Auto-init canvas frames
    if (lvl === 9) setTimeout(initGalaxyCanvas, 100);
}

// ── Badge class mapping ──
function getBadgeClass(level) {
    const map = { 1:'', 2:'bo', 3:'bv', 4:'bs', 5:'ba', 6:'bn', 7:'br', 8:'bd', 9:'bg', 10:'bgd' };
    return map[level] || '';
}

// ── Repeat helper ──
function repeat(html, n) {
    return new Array(n).fill(html).join('');
}

// ── Get frame info for display ──
function getFrameInfo(level) {
    return FRAME_CONFIG[Math.max(1, Math.min(10, level || 1))];
}

// ═══════════════════════════════════════════════════════════
// CANVAS: Galaxia (Nv.9)
// ═══════════════════════════════════════════════════════════
function initGalaxyCanvas() {
    const c = document.getElementById('gxC');
    if (!c) return;
    const ctx = c.getContext('2d');
    const W = c.width, H = c.height, cx = W/2, cy = H/2, R = W/2;

    const stars = [];
    for (let i = 0; i < 300; i++) {
        const angle = Math.random() * Math.PI * 2;
        const dist = Math.random() * R * 0.92;
        stars.push({
            x: cx + Math.cos(angle) * dist,
            y: cy + Math.sin(angle) * dist,
            r: Math.random() * 1.2 + 0.2,
            phase: Math.random() * Math.PI * 2,
            spd: Math.random() * 0.02 + 0.005
        });
    }

    const nebulae = [
        { x: cx - 30, y: cy + 20, r: 60, c: '#7c3aed' },
        { x: cx + 40, y: cy - 15, r: 45, c: '#6d28d9' },
        { x: cx,      y: cy,      r: 35, c: '#8b5cf6' }
    ];

    let t = 0;
    function draw() {
        ctx.clearRect(0, 0, W, H);
        ctx.save();
        ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.clip();
        ctx.fillStyle = '#050510'; ctx.fillRect(0, 0, W, H);

        // Nebulae
        ctx.save(); ctx.translate(cx, cy); ctx.rotate(t * 0.0006); ctx.translate(-cx, -cy);
        nebulae.forEach(b => {
            const g = ctx.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.r);
            g.addColorStop(0, b.c + '99'); g.addColorStop(.55, b.c + '44'); g.addColorStop(1, 'transparent');
            ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2); ctx.fillStyle = g; ctx.fill();
        });
        ctx.restore();

        // Stars
        stars.forEach(s => {
            s.phase += s.spd;
            const a = .12 + .88 * (Math.sin(s.phase) * .5 + .5);
            ctx.beginPath(); ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(200,180,255,${a})`; ctx.fill();
        });

        // Ring border
        const gr = ctx.createLinearGradient(0, 0, W, H);
        gr.addColorStop(0, '#a78bfa'); gr.addColorStop(.33, '#38bdf8');
        gr.addColorStop(.66, '#6d28d9'); gr.addColorStop(1, '#a78bfa');
        ctx.strokeStyle = gr; ctx.lineWidth = 5;
        ctx.shadowColor = 'rgba(167,139,250,.6)'; ctx.shadowBlur = 8;
        ctx.beginPath(); ctx.arc(cx, cy, R - 3, 0, Math.PI * 2); ctx.stroke();
        ctx.shadowBlur = 0;

        ctx.restore();
        t++;
        requestAnimationFrame(draw);
    }
    draw();
}