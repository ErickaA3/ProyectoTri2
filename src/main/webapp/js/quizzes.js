// Funcionalidad interactiva para la plataforma educativa

document.addEventListener('DOMContentLoaded', function() {
    
    // Animación de entrada para las tarjetas de quiz
    const quizCards = document.querySelectorAll('.quiz-card');
    quizCards.forEach((card, index) => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        
        setTimeout(() => {
            card.style.transition = 'all 0.5s ease';
            card.style.opacity = '1';
            card.style.transform = 'translateY(0)';
        }, index * 100);
    });

    // Funcionalidad para botones de inicio de quiz
    const startButtons = document.querySelectorAll('.btn-start');
    startButtons.forEach(button => {
        button.addEventListener('click', function() {
            const quizCard = this.closest('.quiz-card');
            const quizTitle = quizCard.querySelector('h3').textContent;
            
            // Animación de clic
            this.style.transform = 'scale(0.95)';
            setTimeout(() => {
                this.style.transform = 'translateX(5px)';
            }, 100);
            
            // Simular inicio de quiz
            showNotification(`Iniciando quiz: ${quizTitle}`, 'success');
            
            // Aquí iría la lógica real para iniciar el quiz
            setTimeout(() => {
                console.log(`Quiz iniciado: ${quizTitle}`);
            }, 500);
        });
    });

    // Funcionalidad para el botón de nuevo quiz
    const newQuizBtn = document.querySelector('.btn-primary');
    if (newQuizBtn) {
        newQuizBtn.addEventListener('click', function() {
            showNotification('Función de crear nuevo quiz en desarrollo', 'info');
        });
    }

    // Funcionalidad para el botón de historial
    const historialBtn = document.querySelector('.btn-secondary');
    if (historialBtn) {
        historialBtn.addEventListener('click', function() {
            showNotification('Cargando historial de quizzes...', 'info');
        });
    }

    // Funcionalidad para el selector de temas
    const topicSelect = document.querySelector('.topic-select');
    if (topicSelect) {
        topicSelect.addEventListener('change', function() {
            const selectedTopic = this.value;
            filterQuizzesByTopic(selectedTopic);
        });
    }

    // Funcionalidad para los items del menú
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
        item.addEventListener('click', function(e) {
            // Solo prevenir el comportamiento por defecto si no es el item activo
            if (!this.classList.contains('active')) {
                e.preventDefault();
                
                // Remover clase active de todos los items
                navItems.forEach(navItem => navItem.classList.remove('active'));
                
                // Agregar clase active al item clickeado
                this.classList.add('active');
                
                const sectionName = this.querySelector('span').textContent;
                showNotification(`Navegando a: ${sectionName}`, 'info');
            }
        });
    });

    // Botón de configuración
    const settingsBtn = document.querySelector('.settings-btn');
    if (settingsBtn) {
        settingsBtn.addEventListener('click', function() {
            showNotification('Panel de configuración', 'info');
        });
    }

    // Efecto hover en las tarjetas de quiz
    quizCards.forEach(card => {
        card.addEventListener('mouseenter', function() {
            this.style.borderColor = 'var(--accent-cyan)';
        });
        
        card.addEventListener('mouseleave', function() {
            this.style.borderColor = 'var(--border-color)';
        });
    });
});

// Función para filtrar quizzes por tema
function filterQuizzesByTopic(topic) {
    const quizCards = document.querySelectorAll('.quiz-card');
    
    if (topic === 'Temas: Todos') {
        quizCards.forEach(card => {
            card.style.display = 'flex';
        });
        showNotification('Mostrando todos los quizzes', 'success');
        return;
    }
    
    // Aquí se implementaría la lógica real de filtrado
    showNotification(`Filtrando por: ${topic}`, 'success');
}

// Función para mostrar notificaciones
function showNotification(message, type = 'info') {
    // Crear elemento de notificación
    const notification = document.createElement('div');
    notification.className = `notification notification-${type}`;
    notification.textContent = message;
    
    // Estilos de la notificación
    notification.style.position = 'fixed';
    notification.style.top = '20px';
    notification.style.right = '20px';
    notification.style.padding = '1rem 1.5rem';
    notification.style.borderRadius = '12px';
    notification.style.color = 'white';
    notification.style.fontWeight = '600';
    notification.style.zIndex = '10000';
    notification.style.boxShadow = '0 4px 20px rgba(0, 0, 0, 0.3)';
    notification.style.animation = 'slideIn 0.3s ease';
    
    // Color según el tipo
    switch(type) {
        case 'success':
            notification.style.background = 'linear-gradient(135deg, #2dd4bf 0%, #14b8a6 100%)';
            break;
        case 'error':
            notification.style.background = 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)';
            break;
        case 'info':
            notification.style.background = 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)';
            break;
        default:
            notification.style.background = 'linear-gradient(135deg, #2dd4bf 0%, #14b8a6 100%)';
    }
    
    // Agregar al body
    document.body.appendChild(notification);
    
    // Remover después de 3 segundos
    setTimeout(() => {
        notification.style.animation = 'slideOut 0.3s ease';
        setTimeout(() => {
            notification.remove();
        }, 300);
    }, 3000);
}

// Agregar animaciones CSS dinámicamente
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from {
            transform: translateX(400px);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    @keyframes slideOut {
        from {
            transform: translateX(0);
            opacity: 1;
        }
        to {
            transform: translateX(400px);
            opacity: 0;
        }
    }
`;
document.head.appendChild(style);

// Actualizar estadísticas (simulado)
function updateStats() {
    const streakElement = document.querySelector('.stat-badge:first-child span');
    const lxpElement = document.querySelector('.lxp');
    const coinsElement = document.querySelector('.coins span');
    
    // Aquí se implementaría la lógica real para obtener las estadísticas del servidor
    console.log('Estadísticas actualizadas');
}

// Simular actualización de estadísticas cada 30 segundos
setInterval(updateStats, 30000);

// Función para detectar modo responsive
function handleResponsive() {
    const sidebar = document.querySelector('.sidebar');
    const isMobile = window.innerWidth <= 768;
    
    if (isMobile) {
        // Agregar botón de menú hamburguesa si no existe
        if (!document.querySelector('.menu-toggle')) {
            const menuToggle = document.createElement('button');
            menuToggle.className = 'menu-toggle';
            menuToggle.innerHTML = '<i class="fas fa-bars"></i>';
            menuToggle.style.cssText = `
                position: fixed;
                top: 20px;
                left: 20px;
                width: 45px;
                height: 45px;
                background: var(--card-bg);
                border: 1px solid var(--border-color);
                border-radius: 50%;
                color: var(--text-primary);
                font-size: 1.2rem;
                z-index: 1001;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
            `;
            
            menuToggle.addEventListener('click', () => {
                sidebar.classList.toggle('active');
            });
            
            document.body.appendChild(menuToggle);
        }
    }
}

// Ejecutar al cargar y al redimensionar
handleResponsive();
window.addEventListener('resize', handleResponsive);