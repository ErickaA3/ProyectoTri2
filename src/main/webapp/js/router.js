/* ===== POLARIS ROUTER — SPA Navigation =====
 *
 * Intercepts .nav-card clicks → fetches the target page →
 * swaps only <main class="content"> → navbar y sidebar nunca se recargan.
 *
 * Estrategia para re-ejecutar page scripts sin modificarlos:
 *   Antes de inyectar el script se parchea document.addEventListener.
 *   Cuando el script registra su DOMContentLoaded callback, el patch lo
 *   captura en vez de ignorarlo. Después se ejecuta manualmente.
 *   Así todos los archivos como perfil.js, historial.js, etc. funcionan
 *   sin cambio alguno.
 *
 * Páginas excluidas del router (tienen estado en sessionStorage y deben
 * cargarse normalmente):
 *   duelo-play.html, sesion-estudio.html, index.html
 */

const PolarisRouter = (() => {

    // ── Dependencias CSS y JS de cada página ───────────────────
    // Solo lo específico — components.js ya está cargado globalmente
    const PAGE_ASSETS = {
        'perfil':        { css: ['perfil.css', 'marcos.css'], js: ['marcos.js', 'perfil.js'],     inline: true  },
        'historial':     { css: ['historial.css'],            js: ['historial.js'],                inline: false },
        'favoritos':     { css: ['favoritos.css'],            js: ['favoritos.js'],                inline: false },
        'duelos':        { css: ['duelos.css'],               js: ['gamification.js','duelos.js'], inline: false },
        'tienda':        { css: ['tienda.css'],               js: ['tienda.js'],                   inline: false },
        'chat':          { css: ['chat.css'],                 js: ['chat.js'],                     inline: false },
        'modo-estudio':  { css: ['modo-estudio.css'],         js: [],                              inline: true  },
        'flashcards':    { css: ['flashcards.css'],           js: ['flashcards.js'],               inline: false },
    };

    // Páginas que nunca deben entrar al router (estado en sessionStorage)
    const EXCLUDED = ['duelo-play', 'sesion-estudio', 'index'];

    const loadedCss = new Set();
    let isNavigating = false;

    // ──────────────────────────────────────────────────────────
    //  INIT — llamar una vez desde components.js
    // ──────────────────────────────────────────────────────────
    function init() {
        // Marcar CSS ya cargados en esta página como conocidos
        document.querySelectorAll('link[rel="stylesheet"]').forEach(l => {
            const name = l.href.split('/').pop().split('?')[0];
            loadedCss.add(name);
        });

        // Interceptar clicks en el sidebar
        document.addEventListener('click', e => {
            const link = e.target.closest('a.nav-card[href]');
            if (!link) return;

            const url  = link.href;
            const page = url.split('/pages/').pop().replace('.html', '').split('?')[0];

            // No interceptar páginas excluidas ni enlaces externos
            if (EXCLUDED.includes(page)) return;
            if (!url.includes('/pages/')) return;
            if (link.origin !== location.origin) return;

            e.preventDefault();
            navigate(url);
        });

        // Botón atrás / adelante del browser
        window.addEventListener('popstate', e => {
            if (e.state?.url) navigate(e.state.url, false);
        });

        // Guardar estado inicial
        history.replaceState(
            { page: document.body.dataset.page, url: location.href },
            '',
            location.href
        );
    }

    // ──────────────────────────────────────────────────────────
    //  NAVIGATE
    // ──────────────────────────────────────────────────────────
    async function navigate(url, pushState = true) {
        if (isNavigating) return;
        isNavigating = true;

        const main = document.querySelector('main.content');
        if (!main) { window.location.href = url; return; }

        // Fade out rápido
        main.style.transition = 'opacity 0.15s ease';
        main.style.opacity    = '0';
        main.style.pointerEvents = 'none';

        try {
            // Fetch página destino
            const res  = await fetch(url, { credentials: 'include' });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const html = await res.text();
            const doc  = new DOMParser().parseFromString(html, 'text/html');

            const newMain = doc.querySelector('main.content');
            const newPage = doc.body.getAttribute('data-page');
            if (!newMain || !newPage) throw new Error('Página inválida');

            // Cargar CSS nuevos (los que no están cargados aún)
            await loadNewCss(newPage);

            // Esperar fade out
            await sleep(150);

            // Swapear contenido
            main.innerHTML = newMain.innerHTML;

            // Actualizar estado de la página
            document.body.setAttribute('data-page', newPage);
            document.title = doc.title;

            if (pushState) {
                history.pushState({ page: newPage, url }, '', url);
            }

            // Actualizar nav activo
            if (typeof setActivePage === 'function') setActivePage();

            // Fade in
            main.style.opacity = '1';
            main.style.pointerEvents = '';

            // Ejecutar scripts de la nueva página
            await runPageScripts(newPage, doc, main);

        } catch (err) {
            console.error('[PolarisRouter]', err);
            // Fallback: navegación normal
            window.location.href = url;
        } finally {
            isNavigating = false;
        }
    }

    // ──────────────────────────────────────────────────────────
    //  CSS LOADER
    // ──────────────────────────────────────────────────────────
    async function loadNewCss(page) {
        const assets = PAGE_ASSETS[page];
        if (!assets?.css?.length) return;
        const base = typeof getBasePath === 'function' ? getBasePath() : '../';

        const promises = assets.css
            .filter(name => !loadedCss.has(name))
            .map(name => new Promise(resolve => {
                const link = document.createElement('link');
                link.rel  = 'stylesheet';
                link.href = base + 'css/' + name;
                link.onload  = resolve;
                link.onerror = resolve; // no bloquear si falla
                document.head.appendChild(link);
                loadedCss.add(name);
            }));

        if (promises.length) await Promise.all(promises);
    }

    // ──────────────────────────────────────────────────────────
    //  SCRIPT RUNNER
    //  Truco: parchear document.addEventListener temporalmente
    //  para capturar los callbacks de DOMContentLoaded que los
    //  page scripts registran, y ejecutarlos manualmente.
    // ──────────────────────────────────────────────────────────
    async function runPageScripts(page, fetchedDoc, mainEl) {
        const assets = PAGE_ASSETS[page];
        const base   = typeof getBasePath === 'function' ? getBasePath() : '../';

        // ── Patch DOMContentLoaded ──
        const captured    = [];
        const origAddEvt  = document.addEventListener.bind(document);
        document.addEventListener = function(type, fn, opts) {
            if (type === 'DOMContentLoaded') {
                captured.push(fn);
                return;
            }
            return origAddEvt(type, fn, opts);
        };

        // ── Cargar JS secuencialmente (el orden importa) ──
        if (assets?.js?.length) {
            for (const jsFile of assets.js) {
                await loadScript(base + 'js/' + jsFile);
            }
        }

        // ── Restaurar addEventListener ──
        document.addEventListener = origAddEvt;

        // ── Ejecutar callbacks capturados (como si DOMContentLoaded hubiera disparado) ──
        const fakeEvent = new Event('DOMContentLoaded');
        for (const fn of captured) {
            try { fn(fakeEvent); } catch(e) { console.warn('[Router] DOMContentLoaded cb:', e); }
        }

        // ── Ejecutar scripts inline del <main> (ej: marcos init en perfil.html) ──
        if (assets?.inline) {
            const inlineScripts = fetchedDoc.querySelectorAll('main script');
            inlineScripts.forEach(s => {
                if (s.src) return; // externos ya cargados arriba
                try {
                    // eslint-disable-next-line no-new-func
                    new Function(s.textContent)();
                } catch(e) { console.warn('[Router] inline script:', e); }
            });
        }

        // ── Reiniciar estrellas del loading si la nueva página las tiene ──
        const starsCanvas = mainEl.querySelector('.polaris-loading-stars');
        if (starsCanvas && typeof PolarisLoading !== 'undefined') {
            PolarisLoading.initStars(starsCanvas);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────────
    function loadScript(src) {
        return new Promise((resolve, reject) => {
            // Eliminar versión previa para forzar re-ejecución
            const baseSrc = src.split('?')[0];
            document.querySelectorAll(`script[data-polaris-src]`).forEach(s => {
                if (s.getAttribute('data-polaris-src') === baseSrc) s.remove();
            });

            const script = document.createElement('script');
            // Cache-bust para asegurar re-ejecución
            script.src = src + (src.includes('?') ? '&' : '?') + '_r=' + Date.now();
            script.setAttribute('data-polaris-src', baseSrc);
            script.onload  = resolve;
            script.onerror = () => { console.warn('[Router] No se pudo cargar:', src); resolve(); };
            document.body.appendChild(script);
        });
    }

    function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

    return { init, navigate };

})();