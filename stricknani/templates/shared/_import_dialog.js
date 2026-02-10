    (function () {
        const formId = {{ import_form_id | tojson
    }};
    const storageKey = {{ import_storage_key | tojson }};
    const redirectUrl = {{ import_redirect_url | tojson }};
    const populateFnName = {{ import_populate_fn | tojson }};

    const dialog = document.getElementById('importDialog');
    if (!dialog) {
        return;
    }

    let currentTab = 'url';

    const urlForm = document.getElementById(formId);
    const fileForm = document.getElementById(formId + 'File');
    const loading = document.getElementById('importLoading');

    const tabs = Array.from(dialog.querySelectorAll('[data-import-tab]'));
    const panels = {
        url: urlForm,
        file: fileForm
    };

    const dropZone = dialog.querySelector('[data-import-file-dropzone]');
    const fileInput = dialog.querySelector('[data-import-file-input]');
    const fileSubmitBtn = dialog.querySelector('[data-import-file-submit]');

    const fileListEl = document.getElementById('importFileList');
    const filesToUpload = new Map(); // filename -> File object
    let nextFileId = 0;

    function getCheckedItems() {
        if (!fileListEl) return [];
        return Array.from(fileListEl.querySelectorAll('input[type="checkbox"]:checked'));
    }

    function updateSubmitButton() {
        if (!fileSubmitBtn) return;
        const count = getCheckedItems().length;
        fileSubmitBtn.disabled = count === 0;
        if (count > 0) {
            fileSubmitBtn.classList.remove('btn-disabled', 'opacity-50');
            fileSubmitBtn.innerHTML = `<span class="mdi mdi-auto-fix mr-2"></span> ${'Analyze & Import'} <span class="badge badge-sm badge-ghost ml-2">${count}</span>`;
        } else {
            fileSubmitBtn.classList.add('btn-disabled', 'opacity-50');
            fileSubmitBtn.innerHTML = `<span class="mdi mdi-auto-fix mr-2"></span> Analyze & Import`;
        }
    }

    // Initial binding for existing attachments
    if (fileListEl) {
        fileListEl.addEventListener('change', (e) => {
            if (e.target.matches('input[type="checkbox"]')) {
                updateSubmitButton();
            }
        });
    }

    function formatFileSize(bytes) {
        if (!bytes) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    function handleNewFiles(fileList) {
        if (!fileListEl) return;

        fileListEl.classList.remove('hidden');
        const divider = dialog.querySelector('.divider');
        if (divider) divider.classList.remove('hidden');

        Array.from(fileList).forEach(file => {
            if (filesToUpload.has(file.name)) return;

            filesToUpload.set(file.name, file);
            nextFileId++;

            const label = document.createElement('label');
            label.className = "flex items-center gap-4 p-3 rounded-xl border border-base-200 cursor-pointer hover:bg-base-200/50 transition-colors group has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:checked]:ring-1 has-[:checked]:ring-primary relative overflow-hidden animate-in fade-in slide-in-from-bottom-2 duration-300";

            let icon = "mdi-file-outline";
            if (file.type.includes('image')) icon = "mdi-file-image-outline";
            else if (file.type.includes('pdf')) icon = "mdi-file-pdf-outline";

            label.innerHTML = `
                <input type="checkbox" name="new_files_checked" value="${file.name}" class="checkbox checkbox-primary checkbox-sm" checked>
                <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2">
                        <span class="font-medium truncate" title="${file.name}">${file.name}</span>
                        <span class="badge badge-sm badge-secondary font-bold text-xs">NEW</span>
                    </div>
                    <div class="text-xs opacity-60 flex items-center gap-1 min-w-fit">
                        <span class="mdi ${icon}"></span> ${formatFileSize(file.size)}
                    </div>
                </div>
                <button type="button" class="btn btn-ghost btn-xs btn-circle text-error hover:bg-error/10" onclick="event.preventDefault(); this.parentElement.remove(); updateSubmitButton();">
                    <span class="mdi mdi-close"></span>
                </button>
            `;

            fileListEl.appendChild(label);
        });

        updateSubmitButton();
        if (currentTab !== 'file') setTab('file');
    }

    function setTab(tab) {
        let nextTab = tab;
        if (!panels[nextTab]) nextTab = 'url';
        currentTab = nextTab;

        if (loading) loading.hidden = true;

        tabs.forEach((btn) => {
            const isActive = btn.getAttribute('data-import-tab') === nextTab;
            btn.classList.toggle('tab-active', isActive);
            btn.classList.toggle('bg-base-100', isActive);
            btn.classList.toggle('shadow-sm', isActive);
            btn.classList.toggle('hover:bg-base-100/50', !isActive);
        });

        Object.entries(panels).forEach(([key, panel]) => {
            if (!panel) return;
            panel.hidden = key !== nextTab;
        });
    }

    tabs.forEach((btn) => {
        if (btn.dataset.importTabInitialized) return;
        btn.dataset.importTabInitialized = '1';
        btn.addEventListener('click', () => setTab(btn.getAttribute('data-import-tab') || 'url'));
    });

    function handleDrop(files) {
        if (!files || files.length === 0) return;
        handleNewFiles(files);
    }

    if (dialog) {
        const handleDrag = (e, add) => {
            e.preventDefault();
            if (dropZone) {
                if (add) dropZone.classList.add('border-primary', 'bg-primary/5');
                else dropZone.classList.remove('border-primary', 'bg-primary/5');
            }
        };
        dialog.addEventListener('dragenter', e => handleDrag(e, true));
        dialog.addEventListener('dragover', e => handleDrag(e, true));
        dialog.addEventListener('dragleave', e => {
            if (e.relatedTarget === null || !dialog.contains(e.relatedTarget)) handleDrag(e, false);
        });
        dialog.addEventListener('drop', e => {
            handleDrag(e, false);
            handleDrop(e.dataTransfer?.files);
        });
    }

    if (dropZone && !dropZone.dataset.importDropInitialized) {
        dropZone.dataset.importDropInitialized = '1';
        dropZone.addEventListener('click', () => fileInput?.click());
        dropZone.addEventListener('dragenter', e => e.preventDefault());
        dropZone.addEventListener('dragover', e => e.preventDefault());
        dropZone.addEventListener('dragleave', e => e.preventDefault());
        dropZone.addEventListener('drop', e => {
            e.preventDefault();
            handleDrop(e.dataTransfer?.files);
        });
    }

    if (fileInput && !fileInput.dataset.importInputInitialized) {
        fileInput.dataset.importInputInitialized = '1';
        fileInput.addEventListener('change', (e) => {
            handleNewFiles(e.target.files);
            e.target.value = '';
        });
    }

    // Default state - use 'file' tab if no URL is provided
    const hasUrl = {{ 'true' if import_url_value else 'false' }};
    setTab(hasUrl ? 'url' : 'file');
    updateSubmitButton();

    [urlForm, fileForm].forEach(form => {
        if (!form || form.dataset.importSubmitInitialized) return;
        form.dataset.importSubmitInitialized = '1';
        form.addEventListener('submit', handleFormSubmit);
    });

    async function handleFormSubmit(e) {
        e.preventDefault();
        const form = e.target;
        const formData = new FormData(form);

        if (form === fileForm) {
            currentTab = 'file';

            const checkedNewInputs = fileListEl.querySelectorAll('input[name="new_files_checked"]:checked');
            formData.delete('files');

            checkedNewInputs.forEach(input => {
                const file = filesToUpload.get(input.value);
                if (file) formData.append('files', file);
            });

            if (!formData.has('type')) formData.set('type', 'file');

        } else {
            currentTab = 'url';
        }

        if (urlForm) urlForm.hidden = true;
        if (fileForm) fileForm.hidden = true;
        if (loading) loading.hidden = false;

        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                const error = await response.json().catch(() => ({}));
                throw new Error(error.detail || 'Unknown error');
            }

            const data = await response.json();
            const populate = populateFnName ? window[populateFnName] : null;

            if (typeof populate === 'function') {
                populate(data);
                dialog?.close();
                return;
            }

            sessionStorage.setItem(storageKey, JSON.stringify(data));
            window.location.href = redirectUrl;
        } catch (err) {
            console.error(err);
            alert('Import failed: ' + (err?.message || 'Unknown error'));
        } finally {
            if (loading) loading.hidden = true;
            setTab(currentTab);
        }
    }
    }) ();
