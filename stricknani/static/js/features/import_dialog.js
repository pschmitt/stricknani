(() => {
	function initImportDialog(dialog) {
		if (!dialog || dialog.dataset.importDialogInitialized === "1") {
			return;
		}
		dialog.dataset.importDialogInitialized = "1";

		const storageKey = dialog.dataset.importStorageKey || "";
		const redirectUrl = dialog.dataset.importRedirectUrl || "";
		const populateFnName = dialog.dataset.importPopulateFn || "";

		const form = dialog.querySelector("form");
		if (!form) {
			return;
		}

		const loading = dialog.querySelector("#importLoading");
		const content = dialog.querySelector("[data-import-content]");
		const urlInput = form.querySelector("#importUrl");
		const dropZone = dialog.querySelector("[data-import-file-dropzone]");
		const fileInput = dialog.querySelector("[data-import-file-input]");
		const submitBtn = dialog.querySelector("[data-import-submit]");
		const fileListEl = dialog.querySelector("#importFileList");

		const filesToUpload = new Map();

		function getCheckedItems() {
			if (!fileListEl) {
				return [];
			}
			return Array.from(
				fileListEl.querySelectorAll('input[type="checkbox"]:checked'),
			);
		}

		function getCheckedNewFileNames() {
			if (!fileListEl) {
				return [];
			}
			return Array.from(
				fileListEl.querySelectorAll('input[name="new_files_checked"]:checked'),
			).map((input) => input.value);
		}

		function formatFileSize(bytes) {
			if (!bytes) {
				return "0 Bytes";
			}
			const k = 1024;
			const sizes = ["Bytes", "KB", "MB", "GB"];
			const i = Math.floor(Math.log(bytes) / Math.log(k));
			return `${Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
		}

		function updateSubmitButton() {
			if (!submitBtn) {
				return;
			}

			const hasUrl = Boolean(urlInput?.value.trim());
			const fileCount = getCheckedItems().length;

			if (hasUrl && fileCount === 0) {
				submitBtn.innerHTML =
					'<span class="mdi mdi-auto-fix mr-2"></span>Analyze URL';
				return;
			}

			if (fileCount > 0) {
				submitBtn.innerHTML = `<span class="mdi mdi-auto-fix mr-2"></span>Analyze Files <span class="badge badge-sm badge-ghost ml-2">${fileCount}</span>`;
				return;
			}

			submitBtn.innerHTML =
				'<span class="mdi mdi-auto-fix mr-2"></span>Analyze & Import';
		}

		function handleNewFiles(fileList) {
			if (!fileListEl) {
				return;
			}

			fileListEl.classList.remove("hidden");

			Array.from(fileList).forEach((file) => {
				const key = `${file.name}:${file.size}:${file.lastModified}`;
				if (filesToUpload.has(key)) {
					return;
				}
				filesToUpload.set(key, file);

				const label = document.createElement("label");
				label.className =
					"flex items-center gap-4 p-3 rounded-xl border border-base-200 cursor-pointer hover:bg-base-200/50 transition-colors group has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:checked]:ring-1 has-[:checked]:ring-primary relative overflow-hidden";

				let icon = "mdi-file-outline";
				if (file.type.includes("image")) {
					icon = "mdi-file-image-outline";
				} else if (file.type.includes("pdf")) {
					icon = "mdi-file-pdf-outline";
				}

				label.innerHTML = `
        <input type="checkbox" name="new_files_checked" value="${key}" class="checkbox checkbox-primary checkbox-sm" checked>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-medium truncate" title="${file.name}">${file.name}</span>
            <span class="badge badge-sm badge-secondary font-bold text-xs">NEW</span>
          </div>
          <div class="text-xs opacity-60 flex items-center gap-1 min-w-fit">
            <span class="mdi ${icon}"></span> ${formatFileSize(file.size)}
          </div>
        </div>
      `;

				fileListEl.appendChild(label);
			});

			updateSubmitButton();
		}

		if (fileListEl) {
			fileListEl.addEventListener("change", (e) => {
				if (e.target.matches('input[type="checkbox"]')) {
					updateSubmitButton();
				}
			});
		}

		if (urlInput) {
			urlInput.addEventListener("input", updateSubmitButton);
		}

		function handleDrop(files) {
			if (!files || files.length === 0) {
				return;
			}
			handleNewFiles(files);
		}

		const handleDrag = (e, add) => {
			e.preventDefault();
			if (dropZone) {
				if (add) {
					dropZone.classList.add("border-primary", "bg-primary/5");
				} else {
					dropZone.classList.remove("border-primary", "bg-primary/5");
				}
			}
		};

		dialog.addEventListener("dragenter", (e) => handleDrag(e, true));
		dialog.addEventListener("dragover", (e) => handleDrag(e, true));
		dialog.addEventListener("dragleave", (e) => {
			if (e.relatedTarget === null || !dialog.contains(e.relatedTarget)) {
				handleDrag(e, false);
			}
		});
		dialog.addEventListener("drop", (e) => {
			handleDrag(e, false);
			handleDrop(e.dataTransfer?.files);
		});

		if (dropZone && !dropZone.dataset.importDropInitialized) {
			dropZone.dataset.importDropInitialized = "1";
			dropZone.addEventListener("click", () => fileInput?.click());
			dropZone.addEventListener("dragenter", (e) => e.preventDefault());
			dropZone.addEventListener("dragover", (e) => e.preventDefault());
			dropZone.addEventListener("dragleave", (e) => e.preventDefault());
			dropZone.addEventListener("drop", (e) => {
				e.preventDefault();
				handleDrop(e.dataTransfer?.files);
			});
		}

		if (fileInput && !fileInput.dataset.importInputInitialized) {
			fileInput.dataset.importInputInitialized = "1";
			fileInput.addEventListener("change", (e) => {
				handleNewFiles(e.target.files);
				e.target.value = "";
			});
		}

		if (!form.dataset.importSubmitInitialized) {
			form.dataset.importSubmitInitialized = "1";
			form.addEventListener("submit", async (e) => {
				e.preventDefault();

				const formData = new FormData(form);
				const hasUrl = Boolean(urlInput?.value.trim());
				const checkedItems = getCheckedItems();
				const checkedNewFiles = getCheckedNewFileNames();
				const hasFiles = checkedItems.length > 0;

				formData.delete("files");
				checkedNewFiles.forEach((key) => {
					const file = filesToUpload.get(key);
					if (file) {
						if (form.action.endsWith("/yarn/import")) {
							formData.append("file", file);
						} else {
							formData.append("files", file);
						}
					}
				});

				if (!hasUrl && !hasFiles) {
					alert("Please provide a URL or select at least one file.");
					return;
				}

				if (hasFiles) {
					formData.set("type", "file");
				} else {
					formData.set("type", "url");
				}

				if (content) {
					content.hidden = true;
				}
				if (loading) {
					loading.hidden = false;
				}

				try {
					const response = await fetch(form.action, {
						method: "POST",
						body: formData,
					});

					if (!response.ok) {
						const error = await response.json().catch(() => ({}));
						throw new Error(error.detail || "Unknown error");
					}

					const data = await response.json();
					const populate = populateFnName ? window[populateFnName] : null;

					if (typeof populate === "function") {
						populate(data);
						dialog?.close();
						return;
					}

					if (storageKey) {
						sessionStorage.setItem(storageKey, JSON.stringify(data));
					}
					if (redirectUrl) {
						window.location.href = redirectUrl;
					}
				} catch (err) {
					console.error(err);
					alert(`Import failed: ${err?.message || "Unknown error"}`);
				} finally {
					if (loading) {
						loading.hidden = true;
					}
					if (content) {
						content.hidden = false;
					}
				}
			});
		}

		updateSubmitButton();
	}

	function initAll() {
		document.querySelectorAll("[data-import-dialog]").forEach(initImportDialog);
	}

	document.addEventListener("DOMContentLoaded", initAll);
	if (typeof htmx !== "undefined") {
		htmx.on("htmx:afterSettle", initAll);
	}
})();

