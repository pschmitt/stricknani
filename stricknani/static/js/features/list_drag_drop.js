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
        const content = overlay.querySelector('div');
        if (content) {
            content.classList.remove('scale-90');
            content.classList.add('scale-100');
        }
    }

    function hideOverlay() {
        const overlay = document.getElementById(overlayId);
        if (!overlay) return;
        overlay.classList.add('opacity-0', 'pointer-events-none');
        overlay.classList.remove('opacity-100');
        const content = overlay.querySelector('div');
        if (content) {
            content.classList.add('scale-90');
            content.classList.remove('scale-100');
        }
    }

    function isFilesDrag(e) {
        if (!e.dataTransfer || !e.dataTransfer.types) return false;
        // console.log('Drag types:', e.dataTransfer.types);
        for (let i = 0; i < e.dataTransfer.types.length; i++) {
            if (e.dataTransfer.types[i].toLowerCase() === 'files') return true;
        }
        return false;
    }

    window.addEventListener('dragenter', (e) => {
        if (!isFilesDrag(e)) return;
        e.preventDefault();
        e.stopPropagation();
        dragCounter++;
        if (dragCounter === 1) {
            // console.log('Showing overlay');
            showOverlay();
        }
    });

    window.addEventListener('dragover', (e) => {
        if (!isFilesDrag(e)) return;
        e.preventDefault();
        e.stopPropagation();
        e.dataTransfer.dropEffect = 'copy';
    });

    window.addEventListener('dragleave', (e) => {
        if (!isFilesDrag(e)) return;
        e.preventDefault();
        e.stopPropagation();
        dragCounter--;
        if (dragCounter <= 0) {
            dragCounter = 0;
            // console.log('Hiding overlay');
            hideOverlay();
        }
    });

    window.addEventListener('drop', (e) => {
        if (!isFilesDrag(e)) return;
        // console.log('File dropped');
        e.preventDefault();
        e.stopPropagation();
        dragCounter = 0;
        hideOverlay();

        const files = e.dataTransfer.files;
        if (!files || files.length === 0) return;

        // Find the import dialog and components
        const dialog = document.getElementById('importDialog');
        if (!dialog) {
            console.warn('Import dialog not found');
            return;
        }

        const fileInput = dialog.querySelector('[data-import-file-input]');
        const tabBtn = dialog.querySelector('[data-import-tab="file"]');

        if (!fileInput || !tabBtn) {
            console.warn('File input or tab button not found in dialog');
            return;
        }

        // Open dialog and switch to file tab
        if (typeof dialog.showModal === 'function') {
            dialog.showModal();
        } else {
            dialog.setAttribute('open', 'true');
        }
        tabBtn.click();

        // Assign file and trigger the import
        try {
            const dt = new DataTransfer();
            dt.items.add(files[0]);
            fileInput.files = dt.files;
            
            // Trigger change event so the dialog UI updates
            fileInput.dispatchEvent(new Event('change', { bubbles: true }));

            // Automatically submit the form to start analysis
            setTimeout(() => {
                const submitBtn = dialog.querySelector('[data-import-file-submit]');
                if (submitBtn && !submitBtn.disabled) {
                    if (typeof fileForm.requestSubmit === 'function') {
                        fileForm.requestSubmit();
                    } else {
                        submitBtn.click();
                    }
                }
            }, 300);
        } catch (err) {
            console.error('Failed to handle dropped file:', err);
        }
    });
})();