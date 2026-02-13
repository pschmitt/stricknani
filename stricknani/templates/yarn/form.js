let initialLink = document.getElementById("link")?.value.trim() || "";
let isImportPrompted = false;

const yarnId = parseInt("{{ yarn.id if yarn else 0 }}");
const isEditing = "{{ 'true' if yarn else 'false' }}" === "true";
const deleteErrorMessage = "{{ _("Failed to delete the photo. Please try again") }}";
const deleteNetworkErrorMessage = "{{ _("Network error while deleting photo") }}";

function toggleEditSidebarExpansion(checkbox) {
	const main = document.getElementById("main-column");
	const sidebar = document.getElementById("sidebar-column");
	const restoreButton = document.getElementById("yarn-details-restore-button");
	if (!main || !sidebar) return;

	if (checkbox.checked) {
		main.classList.replace("lg:col-span-3", "lg:col-span-2");
		main.classList.remove("lg:pr-12");
		sidebar.classList.remove(
			"lg:hidden",
			"lg:pointer-events-none",
			"lg:opacity-0",
		);
		sidebar.classList.add("lg:block");
		restoreButton?.classList.add(
			"opacity-0",
			"pointer-events-none",
			"translate-x-2",
			"scale-95",
		);
		restoreButton?.classList.remove(
			"opacity-100",
			"translate-x-0",
			"scale-100",
		);
	} else {
		main.classList.replace("lg:col-span-2", "lg:col-span-3");
		main.classList.add("lg:pr-12");
		sidebar.classList.add(
			"lg:hidden",
			"lg:pointer-events-none",
			"lg:opacity-0",
		);
		sidebar.classList.remove("lg:block");
		restoreButton?.classList.remove(
			"opacity-0",
			"pointer-events-none",
			"translate-x-2",
			"scale-95",
		);
		restoreButton?.classList.add("opacity-100", "translate-x-0", "scale-100");
	}
}

function restoreEditSidebarDetails() {
	const sidebarToggle = document.getElementById("yarn-details-toggle-sidebar");
	if (!sidebarToggle) return;
	sidebarToggle.checked = true;
	toggleEditSidebarExpansion(sidebarToggle);
}

function autoResize(el) {
	if (!el) return;
	// Use setTimeout to ensure the element is visible and has dimensions
	setTimeout(() => {
		el.style.height = "auto";
		el.style.height = el.scrollHeight + 2 + "px";
	}, 0);
}

function syncAiEnhanced(el) {
	const isChecked = el.checked;
	const hiddenInput = document.getElementById("is_ai_enhanced");
	const desktopCheckbox = document.getElementById("is_ai_enhanced_checkbox");
	const mobileCheckbox = document.getElementById(
		"is_ai_enhanced_mobile_checkbox",
	);

	if (hiddenInput) hiddenInput.value = isChecked ? "1" : "";
	if (desktopCheckbox && desktopCheckbox !== el)
		desktopCheckbox.checked = isChecked;
	if (mobileCheckbox && mobileCheckbox !== el)
		mobileCheckbox.checked = isChecked;

	window.unsavedChanges?.setDirty(true);
}

window.populateYarnForm = function (data) {
	if (!data) return;

	// Prevent re-prompting on save since we just imported
	isImportPrompted = true;
	if (typeof data.link === "string") {
		initialLink = data.link.trim();
	}

	const fields = {
		name: data.name,
		brand: data.brand,
		colorway: data.colorway,
		dye_lot: data.dye_lot,
		fiber_content: data.fiber_content,
		weight_category: data.weight_category,
		recommended_needles:
			typeof data.recommended_needles === "string"
				? data.recommended_needles.trim()
				: data.recommended_needles,
		weight_grams: data.weight_grams,
		length_meters: data.length_meters,
		link: data.link,
		description: data.description,
		notes: data.notes,
	};

	for (const [id, value] of Object.entries(fields)) {
		const input =
			document.getElementById(id) || document.getElementsByName(id)[0];
		if (input && value !== undefined && value !== null) {
			input.value = value;
			// Trigger change event for unsaved changes detection
			input.dispatchEvent(new Event("input", { bubbles: true }));
		}
	}

	// Handle images if any
	if (data.image_urls && data.image_urls.length > 0) {
		data.image_urls.forEach((url) => {
			addPendingImageToGallery(url);
		});
	}

	const archiveField = document.getElementById("archive_on_save");
	if (archiveField) {
		archiveField.value = "1";
	}

	const isAiEnhanced = data.is_ai_enhanced === true;
	const hiddenAiInput = document.getElementById("is_ai_enhanced");
	if (hiddenAiInput) hiddenAiInput.value = isAiEnhanced ? "1" : "";
	const aiCheckbox = document.getElementById("is_ai_enhanced_checkbox");
	const aiCheckboxMobile = document.getElementById(
		"is_ai_enhanced_mobile_checkbox",
	);
	if (aiCheckbox) aiCheckbox.checked = isAiEnhanced;
	if (aiCheckboxMobile) aiCheckboxMobile.checked = isAiEnhanced;

	window.unsavedChanges?.setDirty(true);
	window.showToast?.("{{ _("Data imported successfully!") }}", "success");
};

function addPendingImageToGallery(url) {
	const container = document.getElementById("image-preview-container");
	container.classList.remove("hidden");

	const div = document.createElement("div");
	div.className =
		"relative aspect-[4/3] overflow-hidden rounded-lg border border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800 group";
	div.setAttribute("data-pending-url", url);

	div.innerHTML = `
            <a href="${url}" data-pswp-width="1200" data-pswp-height="1200" data-pswp-promote="true" data-pswp-delete="true" data-pswp-is-primary="false" class="block h-full w-full">
                <img src="${url}" class="h-full w-full object-cover cursor-zoom-in">
            </a>
            <input type="hidden" name="import_image_urls" value="${url}">
	            <button type="button" data-call="promotePendingYarnImage" data-call-args='["$this","$dataset:url"]' data-url="${url}"
	                class="promote-pending-yarn-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	                title="{{ _('Make primary photo') }}">
	                <span class="mdi mdi-star-outline text-sm"></span>
	            </button>
	            <button type="button" data-call="removePendingYarnImage" class="absolute top-1 right-1 bg-red-600/90 text-white rounded-full p-1 opacity-0 group-hover:opacity-100 transition-opacity hover:bg-red-700">
	                <span class="mdi mdi-close"></span>
	            </button>
        `;
	container.appendChild(div);

	// If this is the first image, automatically promote it
	const currentPrimaryUrl = document.getElementById(
		"import_primary_image_url",
	).value;
	if (!currentPrimaryUrl) {
		promotePendingYarnImage(
			div.querySelector(".promote-pending-yarn-btn"),
			url,
		);
	}

	window.refreshPhotoSwipeGallery?.(container);
}

function promotePendingYarnImage(btn, url) {
	// Update hidden field
	const input = document.getElementById("import_primary_image_url");
	if (input) input.value = url;

	// Visual update
	document.querySelectorAll(".promote-pending-yarn-btn").forEach((b) => {
		b.classList.remove("bg-amber-400", "text-white", "opacity-100");
		b.classList.add("bg-white/90", "text-slate-400", "opacity-0");
		const star = b.querySelector("span");
		if (star) {
			star.classList.remove("mdi-star");
			star.classList.add("mdi-star-outline");
		}
		const anchor = b
			.closest("[data-pending-url]")
			?.querySelector("a[data-pswp-promote]");
		if (anchor) {
			anchor.setAttribute("data-pswp-is-primary", "false");
		}
	});

	if (btn) {
		btn.classList.add("bg-amber-400", "text-white", "opacity-100");
		btn.classList.remove("bg-white/90", "text-slate-400", "opacity-0");
		const star = btn.querySelector("span");
		if (star) {
			star.classList.remove("mdi-star-outline");
			star.classList.add("mdi-star");
		}
		const anchor = btn
			.closest("[data-pending-url]")
			?.querySelector("a[data-pswp-promote]");
		if (anchor) {
			anchor.setAttribute("data-pswp-is-primary", "true");
		}
	}
	window.unsavedChanges?.setDirty(true);
}

function removePendingYarnImage(button) {
	const container = button?.closest("[data-pending-url]");
	if (!container) {
		return;
	}
	const url = container.getAttribute("data-pending-url");
	const wasPrimary =
		document.getElementById("import_primary_image_url")?.value === url;
	container.remove();
	if (wasPrimary) {
		const remaining = Array.from(
			document.querySelectorAll("[data-pending-url]"),
		)
			.map((el) => el.getAttribute("data-pending-url"))
			.filter(Boolean);
		if (remaining.length > 0) {
			const nextUrl = remaining[0];
			const nextBtn = document.querySelector(
				`[data-pending-url="${CSS.escape(nextUrl)}"] .promote-pending-yarn-btn`,
			);
			promotePendingYarnImage(nextBtn, nextUrl);
		} else {
			const input = document.getElementById("import_primary_image_url");
			if (input) {
				input.value = "";
			}
		}
	}
	window.unsavedChanges?.setDirty(true);
}

function syncLinkInputs(el) {
	const value = el.value.trim();
	document.querySelectorAll('input[name="link"]').forEach((input) => {
		if (input !== el) input.value = value;
	});
	document.getElementById("reimport-btn")?.classList.toggle("hidden", !value);
	document
		.getElementById("reimport-btn-mobile")
		?.classList.toggle("hidden", !value);

	const visitBtnMobile = document.getElementById("visit-link-btn-mobile");
	if (visitBtnMobile) {
		visitBtnMobile.classList.toggle("hidden", !value);
		visitBtnMobile.href = value;
	}

	const visitBtn = document.getElementById("visit-link-btn");
	if (visitBtn) {
		visitBtn.classList.toggle("hidden", !value);
		visitBtn.href = value;
	}

	// Enable/disable AI and Wayback checkboxes
	const waybackCheckboxes = [
		document.getElementById("archive_on_save_mobile"),
		document.getElementById("archive_on_save_sidebar"),
	];
	const aiCheckboxes = [
		document.getElementById("is_ai_enhanced_checkbox"),
		document.getElementById("is_ai_enhanced_mobile_checkbox"),
	];
	const containers = [
		document.getElementById("wayback_mobile_container"),
		document.getElementById("wayback_sidebar_container"),
		document.getElementById("ai_enhanced_mobile_container"),
		document.getElementById("ai_enhanced_sidebar_container"),
	];

	const isEnabled = value.length > 0;

	[...waybackCheckboxes, ...aiCheckboxes].forEach((cb) => {
		if (cb) {
			const wasDisabled = cb.disabled;
			cb.disabled = !isEnabled;
			if (!isEnabled) {
				cb.checked = false;
				// Trigger sync for AI hidden field if it's an AI checkbox
				if (aiCheckboxes.includes(cb)) {
					const hiddenInput = document.getElementById("is_ai_enhanced");
					if (hiddenInput) hiddenInput.value = "";
				}
			} else if (wasDisabled && waybackCheckboxes.includes(cb)) {
				// Auto-set wayback to true when it becomes enabled
				cb.checked = true;
			}
		}
	});

	containers.forEach((container) => {
		if (container) {
			if (isEnabled) {
				container.classList.remove(
					"opacity-50",
					"grayscale",
					"pointer-events-none",
				);
			} else {
				container.classList.add(
					"opacity-50",
					"grayscale",
					"pointer-events-none",
				);
			}
		}
	});

	// If the user manually changes the link, we might want to prompt for import again
	if (value !== initialLink) {
		isImportPrompted = false;
	}
}

async function importFromUrl(url) {
	if (!url) return;

	const importDialog = document.getElementById("importDialog");
	const importForm = document.getElementById("importYarnForm");
	const importLoading = document.getElementById("importLoading");

	if (!importDialog || !importForm || !importLoading) return;

	// Reset and show loading state
	importDialog.showModal();
	importForm.classList.add("hidden");
	importLoading.classList.remove("hidden");

	const formData = new FormData();
	formData.append("url", url);

	try {
		const response = await fetch("/yarn/import", {
			method: "POST",
			headers: {
				"X-CSRF-Token": "{{ csrf_token }}",
			},
			body: formData,
		});

		if (!response.ok) {
			throw new Error("Import failed");
		}

		const data = await response.json();
		window.populateYarnForm(data);

		importDialog.close();
	} catch (error) {
		console.error("Import failed:", error);
		window.showToast?.("{{ _("Failed to import yarn data") }}", "error");
		importDialog.close();
	} finally {
		importForm.classList.remove("hidden");
		importLoading.classList.add("hidden");
	}
}

async function uploadYarnImageData(file) {
	if (!isEditing || !yarnId) {
		window.showToast?.("{{ _("Save the yarn before adding photos") }}", "info");
		return null;
	}

	const formData = new FormData();
	formData.append("file", file);

	try {
		window.showToast?.("{{ _("Uploading image...") }}", "info");
		const response = await fetch(`/yarn/${yarnId}/photos`, {
			method: "POST",
			headers: { "X-CSRF-Token": "{{ csrf_token }}" },
			body: formData,
		});

		if (response.ok) {
			const data = await response.json();
			addYarnPhotoToGallery(data);
			window.showToast?.("{{ _("Image uploaded!") }}", "success");
			window.unsavedChanges?.setDirty(true);
			return data;
		} else {
			const message = await parseErrorMessage(
				response,
				"{{ _("Upload failed") }}",
			);
			window.showToast?.(message, "error");
			return null;
		}
	} catch (error) {
		console.error("Upload failed", error);
		window.showToast?.("{{ _("Network error while uploading image") }}", "error");
		return null;
	}
}

async function uploadYarnImage(file) {
	await uploadYarnImageData(file);
}

function addYarnPhotoToGallery(photoData) {
	const grid = document.getElementById("existing-photos-grid");
	if (grid) {
		grid.insertAdjacentHTML("beforeend", createYarnPhotoPreviewHTML(photoData));
		window.refreshPhotoSwipeGallery?.(grid);
	} else {
		window.location.reload();
	}
}

function createYarnPhotoPreviewHTML(photo) {
	return `
        <div id="photo-card-${photo.id}"
            class="relative group aspect-[4/3] overflow-hidden rounded-lg border border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800">
            <a href="${photo.full_url}" data-pswp-width="${photo.width || 1200}"
                data-pswp-height="${photo.height || 1200}" data-pswp-caption="${photo.alt_text}"
                data-pswp-promote="true" data-pswp-delete="true"
                data-pswp-is-primary="${photo.is_primary ? "true" : "false"}"
                class="block h-full w-full">
                <img src="${photo.thumbnail_url}" alt="${photo.alt_text}"
                    class="h-full w-full object-cover cursor-zoom-in">
            </a>
	            <button type="button" data-call="promoteYarnPhoto" data-call-args='[${photo.id}]'
	                class="yarn-promote-btn absolute top-2 left-2 z-10 rounded-full p-1.5 shadow-sm transition-all ${photo.is_primary ? "bg-amber-400 text-white opacity-100" : "bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500"} hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	                title="{{ _('Make primary photo') }}">
	                <span class="mdi ${photo.is_primary ? "mdi-star" : "mdi-star-outline"}"></span>
	            </button>
	            <button type="button" data-call="deleteYarnPhoto" data-call-args='[${photo.id}]'
	                class="absolute top-2 right-2 z-10 bg-white/90 text-red-600 rounded-full p-1.5 hover:bg-red-50 shadow-sm transition-colors dark:bg-slate-900/90 dark:text-red-400 dark:hover:bg-red-900/50"
	                title="{{ _('Delete photo') }}">
	                <span class="mdi mdi-trash-can-outline"></span>
	            </button>
        </div>
        `;
}

document.addEventListener("DOMContentLoaded", () => {
	const photosInput = document.getElementById("photos");
	const dropzone = photosInput?.closest(".border-2");
	if (photosInput && dropzone) {
		window.setupImageUploadWidget(photosInput, dropzone, async (file) => {
			if (isEditing && yarnId) {
				await uploadYarnImage(file);
			} else {
				// For new yarns, add to preview list
				const exists = Array.from(yarnFiles.files).some(
					(f) =>
						f.name === file.name &&
						f.size === file.size &&
						f.lastModified === file.lastModified,
				);
				if (!exists) {
					yarnFiles.items.add(file);
					photosInput.files = yarnFiles.files;
					previewImages(photosInput);
				}
			}
		});
	}

	document.querySelectorAll("textarea").forEach((el) => {
		el.style.overflowY = "hidden";
		autoResize(el);
		el.addEventListener("input", () => {
			autoResize(el);
		});
	});

	const sidebarToggle = document.getElementById("yarn-details-toggle-sidebar");
	if (sidebarToggle) toggleEditSidebarExpansion(sidebarToggle);

	const importedData = sessionStorage.getItem("importedYarnData");
	if (importedData) {
		try {
			const data = JSON.parse(importedData);
			window.populateYarnForm(data);
			sessionStorage.removeItem("importedYarnData");
		} catch (err) {
			console.error("Failed to parse imported yarn data", err);
		}
	}

	const urlParams = new URLSearchParams(window.location.search);
	// Initialize checkbox states based on link presence
	const linkEl = document.getElementById("link");
	if (linkEl) syncLinkInputs(linkEl);

	if (urlParams.get("import") === "1") {
		const currentUrl = document.getElementById("link")?.value;
		if (currentUrl) {
			importFromUrl(currentUrl);
		} else {
			document.getElementById("importDialog")?.showModal();
		}
	}
});

window.STRICKNANI = window.STRICKNANI || {};
window.STRICKNANI.yarnUploads = {
	uploadYarnImageData,
	addYarnPhotoToGallery,
};

document.getElementById("yarnForm").addEventListener("submit", function (e) {
	const currentLink = document.getElementById("link")?.value.trim() || "";
	const needlesInput = document.getElementById("recommended_needles");
	if (needlesInput && typeof needlesInput.value === "string") {
		needlesInput.value = needlesInput.value.trim();
	}

	// If a link was added to a yarn that didn't have one, prompt for import
	if (!initialLink && currentLink && !isImportPrompted) {
		e.preventDefault();
		window.confirmAction(
			"{{ _("Import Data?") }}",
			"{{ _("You added a source link. Would you like to import details from this URL before saving?") }}",
			() => {
				isImportPrompted = true;
				importFromUrl(currentLink);
			},
			() => {
				isImportPrompted = true;
				this.submit();
			},
			{
				confirmText: "{{ _("Import") }}",
				cancelText: "{{ _("Just Save") }}",
			},
		);
		return;
	}
});

async function deleteYarnPhoto(photoId) {
	window.confirmAction(
		"{{ _("Delete Photo") }}",
		"{{ _("Are you sure you want to delete this photo? This action cannot be undone.") }}",
		async () => {
			let response;
			try {
				response = await fetch(`/yarn/${yarnId}/photos/${photoId}/delete`, {
					method: "POST",
					headers: {
						Accept: "application/json",
						"X-CSRF-Token": "{{ csrf_token }}",
					},
				});
			} catch (error) {
				console.error("Photo deletion failed", error);
				window.showToast?.(deleteNetworkErrorMessage, "error");
				return;
			}

			if (!response.ok) {
				const message = await parseErrorMessage(response, deleteErrorMessage);
				window.showToast?.(message, "error");
				return;
			}

			const element = document.getElementById(`photo-card-${photoId}`);
			if (element) {
				element.remove();
			}
			window.unsavedChanges?.setDirty(true);
			window.showToast?.("{{ _("Photo deleted") }}", "success");
		},
		null,
		{ variant: "error", confirmText: "{{ _("Delete") }}" },
	);
}

async function promoteYarnPhoto(photoId) {
	if (!yarnId) return;

	try {
		const response = await fetch(`/yarn/${yarnId}/photos/${photoId}/promote`, {
			method: "POST",
			headers: {
				"X-Requested-With": "XMLHttpRequest",
				"X-CSRF-Token": "{{ csrf_token }}",
			},
		});

		if (response.ok) {
			// Close PhotoSwipe if open
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			setPrimaryYarnPhoto(photoId);
		} else {
			console.error("Failed to promote photo");
		}
	} catch (error) {
		console.error("Error promoting photo:", error);
	}
}

function setPrimaryYarnPhoto(photoId) {
	document.querySelectorAll('[id^="photo-card-"]').forEach((card) => {
		const anchor = card.querySelector("a[data-pswp-promote]");
		if (anchor) {
			anchor.setAttribute("data-pswp-is-primary", "false");
		}
		const button = card.querySelector(".yarn-promote-btn");
		if (button) {
			button.classList.remove("bg-amber-400", "text-white", "opacity-100");
			button.classList.add("bg-white/90", "text-slate-400", "opacity-0");
			const icon = button.querySelector("span");
			if (icon) {
				icon.classList.remove("mdi-star");
				icon.classList.add("mdi-star-outline");
			}
		}
	});

	const target = document.getElementById(`photo-card-${photoId}`);
	if (!target) {
		return;
	}
	const targetAnchor = target.querySelector("a[data-pswp-promote]");
	if (targetAnchor) {
		targetAnchor.setAttribute("data-pswp-is-primary", "true");
	}
	const targetButton = target.querySelector(".yarn-promote-btn");
	if (targetButton) {
		targetButton.classList.add("bg-amber-400", "text-white", "opacity-100");
		targetButton.classList.remove("bg-white/90", "text-slate-400", "opacity-0");
		const icon = targetButton.querySelector("span");
		if (icon) {
			icon.classList.add("mdi-star");
			icon.classList.remove("mdi-star-outline");
		}
	}
	window.unsavedChanges?.setDirty(true);
}

// Add event listeners for PhotoSwipe managed actions
document.addEventListener("pswp:promote", (e) => {
	const anchor = e.detail.element;
	const container = anchor.closest('[id^="photo-card-"]');
	if (container) {
		const photoId = container.id.replace("photo-card-", "");
		if (photoId && yarnId) {
			promoteYarnPhoto(photoId);
		}
	}
});

document.addEventListener("pswp:delete", (e) => {
	const anchor = e.detail.element;
	const container = anchor.closest('[id^="photo-card-"]');
	if (container) {
		const photoId = container.id.replace("photo-card-", "");
		if (photoId) {
			// Close PhotoSwipe before showing confirmation
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			deleteYarnPhoto(photoId);
		}
	} else {
		// Handle pending previews
		const previewContainer = anchor.closest("[data-preview-index]");
		if (previewContainer) {
			const index = parseInt(previewContainer.dataset.previewIndex);
			if (!isNaN(index)) {
				if (window.pswpLightboxes) {
					for (const lb of window.pswpLightboxes.values()) {
						if (lb.pswp) lb.pswp.close();
					}
				}
				removeYarnFile(index);
				return;
			}
		}

		// Handle pending imported images
		const pendingContainer = anchor.closest("[data-pending-url]");
		if (pendingContainer) {
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			const removeButton = pendingContainer.querySelector(
				'button[onclick^="removePendingYarnImage"]',
			);
			if (removeButton) {
				removePendingYarnImage(removeButton);
			} else {
				pendingContainer.remove();
				window.unsavedChanges?.setDirty(true);
			}
		}
	}
});

const parseErrorMessage =
	typeof extractErrorMessage === "function"
		? extractErrorMessage
		: async (_response, fallback) => fallback;

const yarnFiles = new DataTransfer();

function previewImages(input) {
	const container = document.getElementById("image-preview-container");

	if (input.files && input.files.length > 0) {
		Array.from(input.files).forEach((file) => {
			const exists = Array.from(yarnFiles.files).some(
				(f) =>
					f.name === file.name &&
					f.size === file.size &&
					f.lastModified === file.lastModified,
			);
			if (!exists) {
				yarnFiles.items.add(file);
			}
		});
		input.files = yarnFiles.files;
	}

	container.innerHTML = "";

	if (yarnFiles.files.length > 0) {
		container.classList.remove("hidden");
		Array.from(yarnFiles.files).forEach((file, index) => {
			const reader = new FileReader();
			reader.onload = function (e) {
				const div = document.createElement("div");
				div.className =
					"relative aspect-[4/3] overflow-hidden rounded-lg border border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800 group";
				div.setAttribute("data-preview-index", index);
				div.innerHTML = `
                    <a href="${e.target.result}" data-pswp-width="1200" data-pswp-height="1200" data-pswp-delete="true" class="block h-full w-full">
                        <img src="${e.target.result}" class="h-full w-full object-cover opacity-90 transition group-hover:opacity-100 cursor-zoom-in">
                    </a>
	                    <button type="button" data-call="removeYarnFile" data-call-args='[${index}]' class="absolute top-1 right-1 bg-red-600/90 text-white rounded-full p-1 opacity-100 transition-opacity hover:bg-red-700 z-20" aria-label="{{ _('Remove image') }}">
	                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
	                    </button>
                    `;
				container.appendChild(div);
			};
			reader.readAsDataURL(file);
		});
		window.refreshPhotoSwipeGallery?.(container);
	} else {
		container.classList.add("hidden");
	}
}

function removeYarnFile(index) {
	const input = document.getElementById("photos");
	const newDt = new DataTransfer();

	Array.from(yarnFiles.files).forEach((file, i) => {
		if (i !== index) {
			newDt.items.add(file);
		}
	});

	yarnFiles.items.clear();
	Array.from(newDt.files).forEach((file) => yarnFiles.items.add(file));

	if (input) {
		input.files = yarnFiles.files;
		previewImages(input);
	}
	window.unsavedChanges?.setDirty(true);
}
