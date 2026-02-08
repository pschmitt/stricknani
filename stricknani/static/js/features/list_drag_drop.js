/**
 * Drag and drop handler for list views (projects/yarns).
 * Automatically opens the import dialog when a file is dropped.
 */
(function() {
    let dragCounter = 0;
    const overlayId = 'list-drop-overlay';

    function createOverlay() {
        let overlay = document.getElementById(overlayId);
        if (overlay) return overlay;

        overlay = document.createElement('div');
        overlay.id = overlayId;
        overlay.className = 'fixed inset-0 z-[1000] flex items-center justify-center bg-primary/20 backdrop-blur-sm transition-opacity duration-300 pointer-events-none opacity-0';
        overlay.innerHTML = `
            <div class="bg-base-100 p-8 rounded-3xl shadow-2xl border-4 border-dashed border-primary flex flex-col items-center gap-4 transform transition-transform duration-300 scale-90">
                <div class="w-20 h-20 bg-primary/10 text-primary rounded-full flex items-center justify-center">
                    <span class="mdi mdi-cloud-upload text-5xl"></span>
                </div>
                <div class="text-center">
                    <h3 class="text-2xl font-bold text-base-content mb-1">${window.STRICKNANI_I18N?.drop_to_import || 'Drop to Import'}</h3>
                    <p class="text-base-content/60">${window.STRICKNANI_I18N?.drop_files_hint || 'Release to start AI analysis'}</p>
                </div>
            </div>
        `;
        document.body.appendChild(overlay);
        return overlay;
    }

    function showOverlay() {
        const overlay = createOverlay();
        overlay.classList.remove('opacity-0', 'pointer-events-none');
        overlay.classList.add('opacity-100');
        overlay.querySelector('div').classList.remove('scale-90');
        overlay.querySelector('div').classList.add('scale-100');
    }

    function hideOverlay() {
        const overlay = document.getElementById(overlayId);
        if (!overlay) return;
        overlay.classList.add('opacity-0', 'pointer-events-none');
        overlay.classList.remove('opacity-100');
        overlay.querySelector('div').classList.add('scale-90');
        overlay.querySelector('div').classList.remove('scale-100');
    }

    window.addEventListener('dragenter', (e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        dragCounter++;
        if (dragCounter === 1) {
            showOverlay();
        }
    });

    window.addEventListener('dragover', (e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
    });

    window.addEventListener('dragleave', (e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        dragCounter--;
        if (dragCounter <= 0) {
            dragCounter = 0;
            hideOverlay();
        }
    });

    window.addEventListener('drop', (e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        dragCounter = 0;
        hideOverlay();

        const files = e.dataTransfer.files;
        if (files.length === 0) return;

        // Find the import dialog and components
        const dialog = document.getElementById('importDialog');
        if (!dialog) return;

        const fileInput = dialog.querySelector('[data-import-file-input]');
        const fileForm = dialog.querySelector('[data-import-panel="file"]');
        const tabBtn = dialog.querySelector('[data-import-tab="file"]');

        if (!fileInput || !fileForm || !tabBtn) return;

        // Open dialog and switch to file tab
        if (typeof dialog.showModal === 'function') {
            dialog.showModal();
        }
        tabBtn.click();

        // Assign file and trigger the import
        try {
            const dt = new DataTransfer();
            dt.items.add(files[0]);
            fileInput.files = dt.files;
            
            // Trigger change event so the dialog UI updates
            fileInput.dispatchEvent(new Event('change', { bubbles: true }));

            // Optionally: automatically submit after a small delay
            // setTimeout(() => {
            //     fileForm.dispatchEvent(new Event('submit', { bubbles: true }));
            // }, 500);
        } catch (err) {
            console.error('Failed to handle dropped file:', err);
        }
    });
})();
