/**
 * Context menu handler for project and yarn cards.
 * Supports right-click and long-press (for mobile).
 */
(function() {
    let longPressTimer;
    let currentMenu = null;

    function init() {
        document.addEventListener('contextmenu', handleContextMenu);
        document.addEventListener('touchstart', handleTouchStart, { passive: true });
        document.addEventListener('touchend', handleTouchEnd);
        document.addEventListener('touchmove', handleTouchMove);
        document.addEventListener('click', hideMenu);
        window.addEventListener('resize', hideMenu);
        window.addEventListener('scroll', hideMenu, true);
    }

    function handleContextMenu(e) {
        const card = e.target.closest('[data-project-card], [data-yarn-card]');
        if (!card) return;

        e.preventDefault();
        showMenu(card, e.clientX, e.clientY);
    }

    function handleTouchStart(e) {
        const card = e.target.closest('[data-project-card], [data-yarn-card]');
        if (!card) return;

        longPressTimer = setTimeout(() => {
            const touch = e.touches[0];
            showMenu(card, touch.clientX, touch.clientY);
            // Vibrate if supported
            if (window.navigator.vibrate) {
                window.navigator.vibrate(50);
            }
        }, 600);
    }

    function handleTouchEnd() {
        clearTimeout(longPressTimer);
    }

    function handleTouchMove() {
        clearTimeout(longPressTimer);
    }

    function showMenu(card, x, y) {
        hideMenu();

        const menuSource = card.querySelector('[data-card-context-menu]');
        if (!menuSource) return;

        const menu = menuSource.cloneNode(true);
        menu.id = 'active-context-menu';
        menu.classList.remove('hidden');
        menu.classList.add('fixed', 'z-[2000]', 'opacity-0', 'transition-opacity', 'duration-200');
        
        // Position the menu
        document.body.appendChild(menu);
        
        const menuRect = menu.getBoundingClientRect();
        let posX = x;
        let posY = y;

        // Adjust if menu goes off screen
        if (posX + menuRect.width > window.innerWidth) {
            posX = window.innerWidth - menuRect.width - 10;
        }
        if (posY + menuRect.height > window.innerHeight) {
            posY = window.innerHeight - menuRect.height - 10;
        }

        menu.style.left = `${posX}px`;
        menu.style.top = `${posY}px`;
        
        // Re-bind htmx for the cloned menu
        if (window.htmx) {
            window.htmx.process(menu);
        }
        
        // Re-bind events for the cloned menu
        menu.querySelectorAll('[data-call]').forEach(el => {
            el.addEventListener('click', (e) => {
                const call = el.dataset.call;
                const args = JSON.parse(el.dataset.callArgs || '[]');
                if (typeof window[call] === 'function') {
                    window[call](...args);
                }
                hideMenu();
            });
        });

        // Special handling for delete dialogs since they need to update the dialog content
        menu.querySelectorAll('[data-action="open-dialog"]').forEach(el => {
            el.addEventListener('click', (e) => {
                const dialogId = el.dataset.dialogId;
                const dialog = document.getElementById(dialogId);
                if (dialog) {
                    // Update dialog with data from the button if needed
                    if (dialogId === 'deleteProjectDialog') {
                        const pid = el.dataset.projectId;
                        const pname = el.dataset.projectName;
                        const nameEl = dialog.querySelector('strong.text-base-content');
                        if (nameEl) nameEl.textContent = pname;
                        
                        const confirmBtn = dialog.querySelector('[data-call="deleteProject"]');
                        if (confirmBtn) confirmBtn.dataset.callArgs = `[${pid}]`;
                    } else if (dialogId === 'deleteYarnDialog') {
                        const yid = el.dataset.yarnId;
                        const yname = el.dataset.yarnName;
                        const nameEl = dialog.querySelector('strong.text-base-content');
                        if (nameEl) nameEl.textContent = yname;
                        
                        const confirmBtn = dialog.querySelector('[data-call="deleteYarn"]');
                        if (confirmBtn) confirmBtn.dataset.callArgs = `[${yid}]`;
                    }
                    
                    if (typeof dialog.showModal === 'function') {
                        dialog.showModal();
                    }
                }
                hideMenu();
            });
        });

        requestAnimationFrame(() => {
            menu.classList.remove('opacity-0');
            menu.classList.add('opacity-100');
        });

        currentMenu = menu;
    }

    function hideMenu() {
        if (currentMenu) {
            currentMenu.remove();
            currentMenu = null;
        }
    }

    // Initialize
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
