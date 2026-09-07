// ================================================================
// MODO ESTUDIO — lógica de la página de configuración inicial
// ================================================================

// ================================================================
// ESTADO
// ================================================================
let selectedOptions  = [];
let selectedDataType = null;
let uploadedFile     = null;
let userText         = '';

// Límite de caracteres para texto pegado directamente (evita
// desbordar sessionStorage sin avisar al usuario)
const MAX_TEXT_LENGTH = 50000;

// Opciones que requieren una página de configuración propia
const CONFIG_REQUIRED = {
    esquemas: { label: 'Tipo de esquema',   icon: 'fa-project-diagram', page: '../pages/seleccion-esquema.html' },
    examenes: { label: 'Configurar examen', icon: 'fa-file-signature',  page: '../pages/config-examen.html'     }
};

// ================================================================
// SPARKLES
// ================================================================
function generateSparkles() {
    const container = document.getElementById('sparklesContainer');
    const starSVG = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 0L14.5 9.5L24 12L14.5 14.5L12 24L9.5 14.5L0 12L9.5 9.5L12 0Z" fill="url(#goldGradient)"/><defs><linearGradient id="goldGradient" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" style="stop-color:#FFF8DC"/><stop offset="30%" style="stop-color:#FFD700"/><stop offset="60%" style="stop-color:#FFC125"/><stop offset="100%" style="stop-color:#FFB347"/></linearGradient></defs></svg>';
    const positions = [
        {left:-5,top:-5,size:'large',delay:0,anim:'star-glow'},{left:8,top:15,size:'small',delay:0.8,anim:'star-twinkle'},
        {left:-2,top:35,size:'tiny',delay:1.5,anim:'star-glow-alt'},{left:20,top:-8,size:'small',delay:0.3,anim:'star-glow-alt'},
        {left:75,top:-6,size:'small',delay:0.6,anim:'star-twinkle'},{left:98,top:-8,size:'large',delay:0.2,anim:'star-glow'},
        {left:92,top:12,size:'tiny',delay:1.2,anim:'star-glow-alt'},{left:105,top:25,size:'small',delay:0.9,anim:'star-twinkle'},
        {left:-8,top:55,size:'small',delay:0.4,anim:'star-glow'},{left:3,top:75,size:'tiny',delay:1.8,anim:'star-twinkle'},
        {left:102,top:50,size:'small',delay:0.7,anim:'star-glow-alt'},{left:95,top:70,size:'tiny',delay:1.1,anim:'star-glow'},
        {left:-6,top:95,size:'large',delay:0.5,anim:'star-glow'},{left:10,top:88,size:'tiny',delay:1.6,anim:'star-twinkle'},
        {left:5,top:108,size:'small',delay:0.1,anim:'star-glow-alt'},{left:35,top:105,size:'tiny',delay:1.3,anim:'star-glow'},
        {left:65,top:103,size:'small',delay:0.4,anim:'star-twinkle'},{left:100,top:92,size:'large',delay:0.3,anim:'star-glow-alt'},
        {left:88,top:105,size:'small',delay:1.0,anim:'star-glow'},{left:105,top:75,size:'tiny',delay:1.7,anim:'star-twinkle'}
    ];
    container.innerHTML = positions.map(p => {
        const dur = 2 + Math.random() * 2;
        return `<div class="star-sparkle ${p.size}" style="left:${p.left}%;top:${p.top}%;animation:${p.anim} ${dur}s ease-in-out infinite;animation-delay:${p.delay}s">${starSVG}</div>`;
    }).join('');
}

// ================================================================
// TOGGLE OPCIONES
// ================================================================
function toggleOption(el) {
    el.classList.toggle('selected');
    const type = el.dataset.type;
    if (el.classList.contains('selected')) {
        if (!selectedOptions.includes(type)) selectedOptions.push(type);
    } else {
        selectedOptions = selectedOptions.filter(o => o !== type);
    }
    updateGenerateButton();
    updateStepsPreview();
}

function updateGenerateButton() {
    const hasOptions = selectedOptions.length > 0;
    const hasData     = selectedDataType === 'file'
                            ? uploadedFile !== null
                            : selectedDataType === 'text'
                                ? userText.trim().length > 0
                                : false;
    document.getElementById('generateBtn').disabled = !(hasOptions && hasData);
}

// Muestra el resumen de pasos de config que habrá
function updateStepsPreview() {
    const queue   = selectedOptions.filter(o => CONFIG_REQUIRED[o]);
    const preview = document.getElementById('stepsPreview');
    const list    = document.getElementById('stepsPreviewList');

    if (queue.length === 0) { preview.style.display = 'none'; return; }

    preview.style.display = 'flex';
    list.innerHTML = queue.map((opt, i) =>
        `${i > 0 ? '<div class="step-arrow"><i class="fas fa-chevron-right"></i></div>' : ''}
         <div class="step-preview-item">
             <div class="step-preview-num">${i + 1}</div>
             <i class="fas ${CONFIG_REQUIRED[opt].icon}"></i>
             <span>${CONFIG_REQUIRED[opt].label}</span>
         </div>`
    ).join('');
}

// ================================================================
// ARCHIVO / TEXTO
// ================================================================
function handleFileSelect(event) {
    const file = event.target.files[0];
    if (!file) return;

    const ext = file.name.toLowerCase().split('.').pop();
    const allowed = ['pdf', 'doc', 'docx', 'txt', 'pptx'];

    if (!allowed.includes(ext)) {
        showToast('Formato no soportado. Usa: PDF, DOC, DOCX, PPTX o TXT');
        event.target.value = '';
        return;
    }

    // Límite 1.5 MB. Base64 infla ~33%: 1.5 MB → ~2 MB en sessionStorage,
    // bien dentro del límite de 5 MB.
    if (file.size > 1_500_000) {
        showToast('Archivo muy grande (máx 1.5 MB). Intenta con uno más corto o recórtalo.');
        event.target.value = '';
        return;
    }

    uploadedFile     = file;
    selectedDataType = 'file';
    document.getElementById('fileOption').classList.add('selected');
    document.getElementById('textOption').classList.remove('selected');
    document.getElementById('textInputContainer').classList.remove('show');
    document.getElementById('fileName').textContent = file.name;
    document.getElementById('fileSize').textContent = formatFileSize(file.size);
    document.getElementById('fileAttached').classList.add('show');
    showToast('Archivo adjuntado: ' + file.name);

    updateGenerateButton();
}

function formatFileSize(bytes) {
    // [FIX] antes "if (!bytes)" trataba 0 igual que null/undefined.
    // Aquí distinguimos explícitamente: 0 bytes es un caso real (archivo vacío).
    if (bytes === null || bytes === undefined) return '0 Bytes';
    if (bytes === 0) return '0 Bytes';
    const k = 1024, sizes = ['Bytes','KB','MB','GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function removeFile() {
    uploadedFile = null; selectedDataType = null;
    document.getElementById('fileInput').value = '';
    document.getElementById('fileOption').classList.remove('selected');
    document.getElementById('fileAttached').classList.remove('show');
    showToast('Archivo eliminado');
    updateGenerateButton();
}

function toggleTextInput() {
    const tc = document.getElementById('textInputContainer');
    const to = document.getElementById('textOption');
    const fo = document.getElementById('fileOption');
    if (tc.classList.contains('show')) {
        tc.classList.remove('show'); to.classList.remove('selected'); selectedDataType = null;
    } else {
        tc.classList.add('show'); to.classList.add('selected');
        fo.classList.remove('selected');
        document.getElementById('fileAttached').classList.remove('show');
        uploadedFile = null; document.getElementById('fileInput').value = '';
        selectedDataType = 'text'; document.getElementById('textInput').focus();
    }
    updateGenerateButton();
}

// ================================================================
// FLUJO PRINCIPAL — "Siguiente"
// ================================================================
function goToNextStep() {
    // Validar opciones
    if (selectedOptions.length === 0) {
        showToast('Selecciona al menos una opción de estudio'); return;
    }

    // Leer texto si aplica
    if (selectedDataType === 'text') userText = document.getElementById('textInput').value;

    // Validar contenido
    if (!uploadedFile && (!userText || !userText.trim())) {
        showToast('Agrega texto o adjunta un archivo primero'); return;
    }

    // [FIX] Validar longitud de texto pegado antes de intentar guardarlo
    if (selectedDataType === 'text' && userText.length > MAX_TEXT_LENGTH) {
        showToast(`Texto muy largo (máx ${MAX_TEXT_LENGTH.toLocaleString()} caracteres). Recórtalo un poco.`);
        return;
    }

    // Verificar sesión
    // [FIX] Antes solo se validaba la presencia de "user" en localStorage,
    // sin considerar el token JWT (manejado por JwtFilter/JwtUtil/getAuthHeaders
    // en el resto del proyecto). Si el flujo de auth de tu compañero expone
    // una función como isTokenValid() o similar, úsala aquí en vez de este
    // chequeo básico para evitar dejar pasar sesiones expiradas.
    const userRaw = localStorage.getItem('user');
    let userId = null;
    if (userRaw) {
        try {
            userId = JSON.parse(userRaw).id;
        } catch (e) {
            // [FIX] antes se tragaba el error en silencio (catch(e) {})
            console.warn('No se pudo parsear "user" de localStorage:', e);
        }
    }
    if (!userId) {
        showToast('Debes iniciar sesión primero');
        setTimeout(() => window.location.href = '../index.html', 1200);
        return;
    }
    sessionStorage.setItem('userId', userId);

    // Construir el objeto de flujo
    const configQueue = selectedOptions.filter(o => CONFIG_REQUIRED[o]);
    const flow = {
        userId,
        options:            [...selectedOptions],
        dataType:           selectedDataType || 'text',
        text:               selectedDataType === 'text' ? userText : null,
        fileName:           uploadedFile ? uploadedFile.name : null,
        fileType:           uploadedFile ? uploadedFile.type : null,
        fileBase64:         null,
        configs:            {},
        configQueue,
        currentConfigIndex: 0
    };

    const navigate = () => {
        sessionStorage.removeItem('studyResults');
        sessionStorage.setItem('modoEstudioFlow', JSON.stringify(flow));
        if (configQueue.length > 0) {
            window.location.href = CONFIG_REQUIRED[configQueue[0]].page;
        } else {
            window.location.href = '../pages/sesion-estudio.html';
        }
    };

    if (uploadedFile && selectedDataType === 'file') {
        const reader = new FileReader();
        reader.onload = function (e) {
            // [FIX] readAsDataURL() devuelve "data:<mime>;base64,XXXX".
            // Antes se guardaba el string completo con el prefijo, lo cual
            // rompe cualquier decodificación base64 pura en el backend.
            // Guardamos solo la parte después de la coma.
            const dataUrl = e.target.result;
            const commaIndex = dataUrl.indexOf(',');
            flow.fileBase64 = commaIndex >= 0 ? dataUrl.slice(commaIndex + 1) : dataUrl;

            try {
                navigate();
            } catch (storageErr) {
                showToast('Error guardando el archivo. Intenta con uno más pequeño.');
                console.error('SessionStorage overflow:', storageErr);
            }
        };
        reader.onerror = () => showToast('Error leyendo el archivo.');
        reader.readAsDataURL(uploadedFile);
    } else {
        try {
            navigate();
        } catch (storageErr) {
            showToast('El texto es demasiado largo. Recórtalo un poco.');
            console.error('SessionStorage overflow:', storageErr);
        }
    }
}

// ================================================================
// TOAST / INIT
// ================================================================
function showToast(msg) {
    const t = document.getElementById('toast');
    document.getElementById('toastMessage').textContent = msg;
    t.classList.add('show');
    clearTimeout(t._t);
    t._t = setTimeout(() => t.classList.remove('show'), 3000);
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('textInput').addEventListener('input', function () {
        userText = this.value;
        document.getElementById('charCount').textContent = this.value.length.toLocaleString();
        updateGenerateButton();
    });
    generateSparkles();
    updateStepsPreview();
});