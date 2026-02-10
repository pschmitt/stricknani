/**
 * Drag and drop handler for list views (projects/yarns).
 * Automatically opens the import dialog when a file is dropped.
 */
(() => {
	let dragCounter = 0;
	const overlayId = "list-drop-overlay";

	function init() {
		// console.log('Initializing drag and drop handler');
		window.addEventListener("dragenter", handleDragEnter);
		window.addEventListener("dragover", handleDragOver);
		window.addEventListener("dragleave", handleDragLeave);
		window.addEventListener("drop", handleDrop);
	}

	function createOverlay() {
		let overlay = document.getElementById(overlayId);
		if (overlay) return overlay;

		overlay = document.createElement("div");
		overlay.id = overlayId;
		overlay.className =
			"fixed inset-0 z-[1000] flex items-center justify-center bg-primary/20 backdrop-blur-sm transition-opacity duration-300 pointer-events-none opacity-0";
		overlay.innerHTML = `
            <div class="bg-base-100 p-8 rounded-3xl shadow-2xl border-4 border-dashed border-primary flex flex-col items-center gap-4 transform transition-transform duration-300 scale-90">
                <div class="w-20 h-20 bg-primary/10 text-primary rounded-full flex items-center justify-center">
                    <span class="mdi mdi-cloud-upload text-5xl"></span>
                </div>
                <div class="text-center">
                    <h3 class="text-2xl font-bold text-base-content mb-1">${window.STRICKNANI_I18N?.drop_to_import || "Drop to Import"}</h3>
                    <p class="text-base-content/60">${window.STRICKNANI_I18N?.drop_files_hint || "Release to start AI analysis"}</p>
                </div>
            </div>
        `;
		document.body.appendChild(overlay);
		return overlay;
	}

	function showOverlay() {
		const overlay = createOverlay();
		overlay.classList.remove("opacity-0", "pointer-events-none");
		overlay.classList.add("opacity-100");
		const content = overlay.querySelector("div");
		if (content) {
			content.classList.remove("scale-90");
			content.classList.add("scale-100");
		}
	}

	function hideOverlay() {
		const overlay = document.getElementById(overlayId);
		if (!overlay) return;
		overlay.classList.add("opacity-0", "pointer-events-none");
		overlay.classList.remove("opacity-100");
		const content = overlay.querySelector("div");
		if (content) {
			content.classList.add("scale-90");
			content.classList.remove("scale-100");
		}
	}

	function isFilesDrag(e) {
		if (!e.dataTransfer || !e.dataTransfer.types) return false;
		// console.log('Drag types:', e.dataTransfer.types);
		for (let i = 0; i < e.dataTransfer.types.length; i++) {
			if (e.dataTransfer.types[i].toLowerCase() === "files") return true;
		}
		return false;
	}

	function handleDragEnter(e) {
		if (!isFilesDrag(e)) return;
		e.preventDefault();
		e.stopPropagation();
		dragCounter++;
		if (dragCounter === 1) {
			// console.log('Showing overlay');
			showOverlay();
		}
	}

	function handleDragOver(e) {
		if (!isFilesDrag(e)) return;
		e.preventDefault();
		e.stopPropagation();
		e.dataTransfer.dropEffect = "copy";
	}

	function handleDragLeave(e) {
		if (!isFilesDrag(e)) return;
		e.preventDefault();
		e.stopPropagation();
		dragCounter--;
		if (dragCounter <= 0) {
			dragCounter = 0;
			// console.log('Hiding overlay');
			hideOverlay();
		}
	}

	function handleDrop(e) {
		if (!isFilesDrag(e)) return;
		// console.log('File dropped');
		e.preventDefault();
		e.stopPropagation();
		dragCounter = 0;
		hideOverlay();

		const files = e.dataTransfer.files;
		if (!files || files.length === 0) return;

		// Find the import dialog and components
		const dialog = document.getElementById("importDialog");
		if (!dialog) {
			console.warn("Import dialog not found");
			return;
		}

		const fileInput = dialog.querySelector("[data-import-file-input]");
		if (!fileInput) {
			console.warn("File input not found in dialog");
			return;
		}

		// Open dialog (unified import form includes URL and file import together)
		if (typeof dialog.showModal === "function") {
			dialog.showModal();
		} else {
			dialog.setAttribute("open", "true");
		}

		// Assign ALL files to the input (not just the first one)
		try {
			const dt = new DataTransfer();
			for (let i = 0; i < files.length; i++) {
				dt.items.add(files[i]);
			}
			fileInput.files = dt.files;

			// Trigger change event so the dialog UI updates and shows all files
			fileInput.dispatchEvent(new Event("change", { bubbles: true }));

			// DO NOT auto-submit - let the user review and confirm
			// The user can click the "Analyze & Import" button when ready
		} catch (err) {
			console.error("Failed to handle dropped files:", err);
		}
	}

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", init);
	} else {
		init();
	}
})();
