let initialLink = document.getElementById("link")?.value.trim() || "";
let isImportPrompted = false;

function toggleEditSidebarExpansion(checkbox) {
	const main = document.getElementById("main-column");
	const sidebar = document.getElementById("sidebar-column");
	const restoreButton = document.getElementById(
		"project-details-restore-button",
	);
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
	const sidebarToggle = document.getElementById(
		"project-details-toggle-sidebar",
	);
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

function canonicalizeImportImageUrl(rawUrl) {
	if (!rawUrl) {
		return "";
	}

	const trimmed = String(rawUrl).trim();
	if (!trimmed) {
		return "";
	}

	// Treat protocol-relative URLs as HTTPS.
	const withProtocol = trimmed.startsWith("//") ? `https:${trimmed}` : trimmed;

	try {
		const parsed = new URL(withProtocol);
		// For import de-duping we want the effective file identity; query/fragment are often
		// cache-busters or size parameters that don't change the underlying image.
		return `${parsed.origin}${parsed.pathname}`;
	} catch (e) {
		// Not a valid absolute URL; fall back to a conservative key.
		return withProtocol;
	}
}

function dedupeImportImageUrls(urls) {
	const container = document.getElementById("titleImagesContainer");
	const existingKeys = new Set();
	if (container) {
		container.querySelectorAll("a[href]").forEach((anchor) => {
			const href = anchor.getAttribute("href");
			const key = canonicalizeImportImageUrl(href);
			if (key) existingKeys.add(key);
		});
	}

	const seenKeys = new Set();
	const result = [];
	urls.forEach((url) => {
		const normalized = String(url || "").trim();
		if (!normalized) return;

		const key = canonicalizeImportImageUrl(normalized);
		if (!key) return;
		if (existingKeys.has(key) || seenKeys.has(key)) return;

		seenKeys.add(key);
		result.push(normalized);
	});

	return result;
}

/**
 * Update image visibility in galleries based on their presence in the Markdown text.
 */
function updateImageVisibility(textarea) {
	if (!textarea) return;
	let container;
	let label;
	const stepItem = textarea.closest(".step-item");
	const isStepDescription = Boolean(stepItem);
	if (textarea.id === "stitch_sample") {
		container = document.getElementById("stitchSampleImagesContainer");
		label = document.getElementById("stitchSamplePhotosLabel");
	} else if (isStepDescription) {
		container = stepItem.querySelector(".step-images");
		label = stepItem.querySelector(".step-photos-label");
	}

	if (!container) return;

	const images = container.querySelectorAll(
		"[data-image-id], [data-pending-url]",
	);
	if (textarea.id === "stitch_sample" || isStepDescription) {
		images.forEach((imgWrapper) => {
			imgWrapper.classList.remove("hidden");
			imgWrapper.querySelectorAll(".hidden").forEach((child) => {
				child.classList.remove("hidden");
			});
		});
		if (images.length === 0) {
			container.classList.add("hidden");
			if (label) label.classList.add("hidden");
		} else {
			container.classList.remove("hidden");
			if (label) label.classList.remove("hidden");
		}
		return;
	}

	const text = textarea.value;
	const urlsInText = [];
	const regex = /!\[.*?\]\((.*?)\)/g;
	let match;
	while ((match = regex.exec(text)) !== null) {
		urlsInText.push(match[1]);
	}

	let visibleCount = 0;
	images.forEach((imgWrapper) => {
		const anchor = imgWrapper.querySelector("a");
		if (!anchor) return;
		const url = anchor.getAttribute("href");

		// Check if URL (decoded or literal) is in the text
		const isUsed = urlsInText.some((u) => {
			try {
				return u === url || decodeURI(u) === url || u.endsWith(url);
			} catch (e) {
				return u === url || u.endsWith(url);
			}
		});

		if (isUsed) {
			imgWrapper.classList.add("hidden");
		} else {
			imgWrapper.classList.remove("hidden");
			visibleCount++;
		}
	});

	// Hide section if no images or all are used in text
	if (images.length === 0 || visibleCount === 0) {
		container.classList.add("hidden");
		if (label) label.classList.add("hidden");
	} else {
		container.classList.remove("hidden");
		if (label) label.classList.remove("hidden");
	}
}

document.addEventListener("DOMContentLoaded", () => {
	const titleImageInput = document.getElementById("titleImageInput");
	const titleImageDropZone = document.getElementById("titleImageDropZone");
	if (titleImageInput && titleImageDropZone) {
		window.setupImageUploadWidget(
			titleImageInput,
			titleImageDropZone,
			uploadTitleImage,
		);
	}

	initStepImageUploaders();
	initStitchSampleImageUploader();
	initAttachmentUploader();
	initYarnSelector();
	initTagEditor();
	const sidebarToggle = document.getElementById(
		"project-details-toggle-sidebar",
	);
	if (sidebarToggle) toggleEditSidebarExpansion(sidebarToggle);

	const importedData = sessionStorage.getItem("importedData");
	if (importedData) {
		try {
			const data = JSON.parse(importedData);

			// Prevent re-prompting on save
			isImportPrompted = true;

			const setFieldValue = (id, value) => {
				const el = document.getElementById(id);
				if (el && value !== undefined && value !== null) {
					if (id === "notes" && el.value.trim().length > 0) return false;
					el.value = value;
					if (typeof autoResize === "function") autoResize(el);
					return true;
				}
				return false;
			};

			const warningBanner = document.getElementById("importWarning");
			if (warningBanner) warningBanner.classList.remove("hidden");

			// Metadata
			setFieldValue("name", data.title || data.name);
			setFieldValue("needles", data.needles);
			setFieldValue("yarn_brand", data.brand);

			const yarnDetailsField = document.getElementById("yarn_details");
			if (yarnDetailsField && data.yarn_details) {
				yarnDetailsField.value = JSON.stringify(data.yarn_details);
			}

			if (data.yarn || data.yarn_details) {
				window.yarnSelector?.selectByName(data.yarn, data.yarn_details || []);
			}

			setFieldValue("stitch_sample", data.stitch_sample);
			setFieldValue("description", data.description);
			setFieldValue("category", data.category);
			setFieldValue("tags", data.tags);
			setFieldValue("notes", data.notes);
			setFieldValue("link", data.link);
			if (data.link) initialLink = data.link;

			// Set AI enhanced flag
			const isAiEnhanced = data.is_ai_enhanced === true;
			const hiddenAiInput = document.getElementById("is_ai_enhanced");
			if (hiddenAiInput) hiddenAiInput.value = isAiEnhanced ? "1" : "";
			const aiCheckbox = document.getElementById("is_ai_enhanced_checkbox");
			const aiCheckboxMobile = document.getElementById(
				"is_ai_enhanced_mobile_checkbox",
			);
			if (aiCheckbox) aiCheckbox.checked = isAiEnhanced;
			if (aiCheckboxMobile) aiCheckboxMobile.checked = isAiEnhanced;

			// Image URLs (Project level)
			const importImagesField = document.getElementById("import_image_urls");
			const imageUrls = Array.isArray(data.image_urls) ? data.image_urls : [];
			const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
			if (importImagesField) {
				importImagesField.value = dedupedImageUrls.length
					? JSON.stringify(dedupedImageUrls)
					: "";
			}
			const importAttachmentTokensField = document.getElementById(
				"import_attachment_tokens",
			);
			if (
				importAttachmentTokensField &&
				Array.isArray(data.import_attachment_tokens)
			) {
				let existingTokens = [];
				try {
					existingTokens = JSON.parse(
						importAttachmentTokensField.value || "[]",
					);
				} catch (e) {
					existingTokens = [];
				}
				const merged = [
					...new Set([
						...(Array.isArray(existingTokens) ? existingTokens : []),
						...data.import_attachment_tokens,
					]),
				];
				importAttachmentTokensField.value = JSON.stringify(merged);
			}
			const archiveField = document.getElementById("archive_on_save");
			if (archiveField) {
				archiveField.value = "1";
			}

			// Render pending images for preview
			if (dedupedImageUrls.length > 0) {
				dedupedImageUrls.forEach((url) => {
					if (typeof addPendingTitleImageToGallery === "function") {
						try {
							addPendingTitleImageToGallery(url);
						} catch (e) {
							console.error(e);
						}
					}
				});
			}

			// Steps
			if (data.steps && data.steps.length > 0) {
				const stepsContainer = document.getElementById("stepsContainer");
				if (stepsContainer) {
					stepsContainer.innerHTML = "";
					data.steps.forEach((step, index) => {
						if (typeof addStep === "function") {
							try {
								addStep(
									step.title || `Step ${index + 1}`,
									step.description || "",
									step.images || [],
								);
							} catch (e) {
								console.error("Failed to add step from storage", e, step);
							}
						}
					});
				}
			}

			// Source Attachments (PDFs, etc.)
			if (
				Array.isArray(data.source_attachments) &&
				data.source_attachments.length > 0
			) {
				data.source_attachments.forEach((attachment) => {
					if (typeof addAttachmentToUI === "function") {
						try {
							addAttachmentToUI(attachment);
						} catch (e) {
							console.error(
								"Failed to add attachment from storage",
								e,
								attachment,
							);
						}
					}
				});
			}

			sessionStorage.removeItem("importedData");
			window.unsavedChanges?.setDirty(true);
			window.showToast?.("{{ _("Pattern data loaded - please review and save") }}", "success");

			setTimeout(() => {
				const saveButton = document.querySelector('button[type="submit"]');
				if (saveButton) {
					saveButton.scrollIntoView({ behavior: "smooth", block: "center" });
					saveButton.classList.add("ring-4", "ring-primary", "ring-opacity-50");
					setTimeout(() => {
						saveButton.classList.remove(
							"ring-4",
							"ring-primary",
							"ring-opacity-50",
						);
					}, 3000);
				}
			}, 500);
		} catch (error) {
			console.error("Error loading imported data:", error);
			sessionStorage.removeItem("importedData");
		}
	}

	document.querySelectorAll("textarea").forEach((el) => {
		el.style.overflowY = "hidden";
		autoResize(el);
		updateImageVisibility(el);
		el.addEventListener("input", () => {
			autoResize(el);
			updateImageVisibility(el);
		});

		setupTextareaDrop(el);
	});

	// Initialize checkbox states based on link presence
	const linkEl = document.getElementById("link");
	if (linkEl) syncLinkInputs(linkEl);

	const urlParams = new URLSearchParams(window.location.search);
	if (urlParams.get("import") === "1") {
		const currentUrl = document.getElementById("link")?.value;
		if (currentUrl) {
			importFromUrl(currentUrl);
		} else {
			document.getElementById("importDialog")?.showModal();
		}
	}
});

// Expose a minimal API for feature modules (e.g. WYSIWYG editor) so they can upload
// dropped images into the correct project section and insert Markdown.
window.STRICKNANI = window.STRICKNANI || {};
window.STRICKNANI.projectUploads = {
	ensureProjectId,
	ensureStepId,
	uploadTitleImageData,
	uploadStepImage,
	uploadStitchSampleImageData,
	addTitleImageToGallery,
	addStepImagePreview,
	addStitchSampleImagePreview,
};

function initTagEditor() {
	const hiddenInput = document.getElementById("tags");
	if (!hiddenInput) return;

	const desktop = {
		input: document.getElementById("tags_input"),
		chips: document.getElementById("tags_chips"),
		suggestions: document.getElementById("tags_suggestions"),
	};
	const mobile = {
		input: document.getElementById("tags_input_mobile"),
		chips: document.getElementById("tags_chips_mobile"),
		suggestions: document.getElementById("tags_suggestions_mobile"),
	};
	const views = [desktop, mobile].filter((view) => view.input && view.chips);

	const normalizeTag = (raw) => raw.replace(/^#/, "").trim();
	const splitTags = (raw) =>
		raw
			.split(/[,#\\s]+/)
			.map(normalizeTag)
			.filter(Boolean);

	const tags = [];
	const tagIndex = new Set();

	const addTag = (tag) => {
		const normalized = normalizeTag(tag);
		if (!normalized) return;
		const key = normalized.toLowerCase();
		if (tagIndex.has(key)) return;
		tagIndex.add(key);
		tags.push(normalized);
		renderChips();
		syncHidden();
	};

	const removeTag = (tag) => {
		const key = tag.toLowerCase();
		if (!tagIndex.has(key)) return;
		tagIndex.delete(key);
		const index = tags.findIndex((item) => item.toLowerCase() === key);
		if (index >= 0) tags.splice(index, 1);
		renderChips();
		syncHidden();
	};

	const syncHidden = () => {
		hiddenInput.value = tags.join(", ");
	};

	const renderChips = () => {
		views.forEach((view) => {
			view.chips.innerHTML = "";
			tags.forEach((tag) => {
				const chip = document.createElement("button");
				chip.type = "button";
				chip.className = "badge badge-outline gap-1";
				chip.setAttribute("data-tag", tag);
				chip.innerHTML = `#${tag}<span class="mdi mdi-close text-[10px]"></span>`;
				chip.addEventListener("click", () => removeTag(tag));
				view.chips.appendChild(chip);
			});
		});
		refreshSuggestions();
	};

	const refreshSuggestions = () => {
		const selected = new Set(tags.map((tag) => tag.toLowerCase()));
		views.forEach((view) => {
			if (!view.suggestions) return;
			view.suggestions.querySelectorAll(".tag-suggestion").forEach((button) => {
				const tag = button.dataset.tag || "";
				const isHidden = selected.has(tag.toLowerCase());
				button.classList.toggle("hidden", isHidden);
			});
		});
	};

	const addFromInput = (input) => {
		const raw = input.value;
		splitTags(raw).forEach(addTag);
		input.value = "";
		refreshSuggestions();
	};

	const handleInput = (event, view) => {
		if (!view.suggestions) return;
		const query = event.target.value.trim().toLowerCase();
		const hasQuery = query.length > 0;
		let anyVisible = false;
		view.suggestions.querySelectorAll(".tag-suggestion").forEach((button) => {
			const tag = (button.dataset.tag || "").toLowerCase();
			const matches = !query || tag.includes(query);
			const isSelected = tagIndex.has(tag);
			const shouldShow = matches && !isSelected;
			button.classList.toggle("hidden", !shouldShow);
			if (shouldShow) anyVisible = true;
		});
		view.suggestions.classList.toggle("hidden", !hasQuery || !anyVisible);
	};

	const handleKeydown = (event, view) => {
		if (event.key === "Enter" || event.key === "Tab") {
			if (event.target.value.trim()) {
				event.preventDefault();
				addFromInput(event.target);
				view.suggestions?.classList.add("hidden");
			}
		}
		if (event.key === "Escape") {
			view.suggestions?.classList.add("hidden");
		}
	};

	const handleBlur = (view) => {
		setTimeout(() => {
			view.suggestions?.classList.add("hidden");
			if (view.input && view.input.value.trim()) {
				addFromInput(view.input);
			}
		}, 100);
	};

	const bindView = (view) => {
		if (!view.input) return;
		view.input.addEventListener("input", (event) => handleInput(event, view));
		view.input.addEventListener("keydown", (event) =>
			handleKeydown(event, view),
		);
		view.input.addEventListener("blur", () => handleBlur(view));
		view.suggestions?.querySelectorAll(".tag-suggestion").forEach((button) => {
			button.addEventListener("click", () => {
				addTag(button.dataset.tag || "");
				view.suggestions?.classList.add("hidden");
			});
		});
	};

	splitTags(hiddenInput.value).forEach(addTag);
	views.forEach(bindView);
	refreshSuggestions();
}

function initYarnSelector() {
	const searchInput = document.getElementById("yarn_search");
	const dropdown = document.getElementById("yarn_dropdown");
	const selectedContainer = document.getElementById("selected_yarns");
	const hiddenInput = document.getElementById("yarn_ids");
	const yarnOptions = document.querySelectorAll(".yarn-option");

	const selectedYarns = new Map();
	const pendingYarns = new Map(); // Yarns that don't exist in DB yet: name -> { name, imageUrl }

	function selectYarn(id, name, brand, colorway, dyeLot, imageUrl) {
		if (selectedYarns.has(id)) return;

		selectedYarns.set(id, { name, brand, colorway, dyeLot, imageUrl });
		updateSelectedDisplay();
		updateHiddenInput();
		filterOptions();
	}

	function selectPendingYarn(name, imageUrl = "") {
		const normalized = name.trim();
		if (!normalized || pendingYarns.has(normalized)) return;

		// Check if it already exists in selectedYarns by name
		for (const y of selectedYarns.values()) {
			if (y.name.toLowerCase() === normalized.toLowerCase()) return;
		}

		pendingYarns.set(normalized, { name: normalized, imageUrl });
		updateSelectedDisplay();
		updateHiddenInput();
	}

	function removeYarn(id) {
		selectedYarns.delete(id);
		updateSelectedDisplay();
		updateHiddenInput();
		filterOptions();
	}

	function removePendingYarn(name) {
		pendingYarns.delete(name);
		updateSelectedDisplay();
		updateHiddenInput();
	}

	// Expose API
	window.yarnSelector = {
		select: selectYarn,
		remove: removeYarn,
		selectByName: (name, details = []) => {
			if (!name && details.length === 0)
				return { anySelected: false, remaining: "" };

			let anySelected = false;

			// If we have structured details, use them first
			if (details && details.length > 0) {
				details.forEach((detail) => {
					const dName = detail.name || detail.yarn;
					if (!dName) return;

					const nLower = dName.toLowerCase();
					const dLink = detail.link;

					// Try to find an existing option by link OR name
					const option = Array.from(yarnOptions).find((opt) => {
						const optLink = opt.dataset.yarnLink; // We might need to add this to the dataset
						if (dLink && optLink === dLink) return true;

						const optName = opt.dataset.yarnName.toLowerCase();
						const optBrand = (opt.dataset.yarnBrand || "").toLowerCase();
						return (
							optName === nLower ||
							`${optBrand} ${optName}`.toLowerCase() === nLower ||
							(optBrand &&
								nLower.includes(optBrand) &&
								nLower.includes(optName))
						);
					});

					if (option) {
						selectYarn(
							parseInt(option.dataset.yarnId),
							option.dataset.yarnName,
							option.dataset.yarnBrand,
							option.dataset.yarnColorway,
							option.dataset.yarnDyeLot,
							option.dataset.yarnImage,
						);
						anySelected = true;
					} else {
						selectPendingYarn(dName, detail.image_url || "");
						anySelected = true;
					}
				});
			} else if (name) {
				// Fallback to name parsing if no details provided
				let rawNames = [];
				if (name.includes("\n")) {
					rawNames = name
						.split("\n")
						.map((n) => n.trim())
						.filter(Boolean);
				} else {
					if (/(?:farbe|color|colour)\s*\d+\s*,\s*/i.test(name)) {
						rawNames = [name.trim()];
					} else {
						rawNames = name
							.split(",")
							.map((n) => n.trim())
							.filter(Boolean);
					}
				}

				rawNames.forEach((n) => {
					const nLower = n.toLowerCase();
					const option = Array.from(yarnOptions).find((opt) => {
						const optName = opt.dataset.yarnName.toLowerCase();
						const optBrand = (opt.dataset.yarnBrand || "").toLowerCase();
						return (
							optName === nLower ||
							`${optBrand} ${optName}`.toLowerCase() === nLower ||
							(optBrand &&
								nLower.includes(optBrand) &&
								nLower.includes(optName))
						);
					});

					if (option) {
						selectYarn(
							parseInt(option.dataset.yarnId),
							option.dataset.yarnName,
							option.dataset.yarnBrand,
							option.dataset.yarnColorway,
							option.dataset.yarnDyeLot,
							option.dataset.yarnImage,
						);
						anySelected = true;
					} else {
						selectPendingYarn(n);
						anySelected = true;
					}
				});
			}

			// Clear the visible search input
			if (searchInput) {
				searchInput.value = "";
			}

			return { anySelected, remaining: "" };
		},
	}; {% if project and project.yarn_ids %}
	const preSelectedIds = {{ project.yarn_ids | tojson }};
	yarnOptions.forEach((option) => {
		const yarnId = parseInt(option.dataset.yarnId);
		if (preSelectedIds.includes(yarnId)) {
			selectYarn(
				yarnId,
				option.dataset.yarnName,
				option.dataset.yarnBrand,
				option.dataset.yarnColorway,
				option.dataset.yarnDyeLot,
				option.dataset.yarnImage,
			);
		}
	});
	{% endif %}

	function updateSelectedDisplay() {
		if (selectedYarns.size === 0 && pendingYarns.size === 0) {
			selectedContainer.innerHTML =
				'<span class="text-base-content/40 text-sm">{{ _("No yarns selected") }}</span>';
			return;
		}

		selectedContainer.innerHTML = "";

		// Render existing yarns
		selectedYarns.forEach((yarn, id) => {
			const chip = document.createElement("div");
			chip.className =
				"flex items-center gap-3 py-2.5 px-3.5 bg-primary/10 text-primary border border-primary/20 rounded-xl";

			const imageHtml = yarn.imageUrl
				? `<img src="${yarn.imageUrl}" alt="${yarn.name}" class="w-10 h-10 rounded-lg object-cover" onerror="this.replaceWith(this.parentElement.querySelector('[data-fallback-icon]').cloneNode(true)); this.parentElement.querySelector('[data-fallback-icon]').classList.remove('hidden')">`
				: "";

			chip.innerHTML = `
	                    ${imageHtml}
	                    <div class="w-10 h-10 rounded-lg bg-base-300 flex items-center justify-center text-base-content/60${yarn.imageUrl ? " hidden" : ""}" data-fallback-icon>
	                        <span class="mdi mdi-image-off text-base" aria-hidden="true"></span>
	                    </div>
	                    <span class="text-base flex-1 min-w-0 truncate">
	                        ${yarn.name}${yarn.brand ? ` • ${yarn.brand}` : ""}
	                    </span>
	                    <button type="button" class="btn btn-ghost btn-xs btn-circle" data-remove-yarn="${id}">
	                        <span class="mdi mdi-close"></span>
	                    </button>
	                `;
			selectedContainer.appendChild(chip);

			chip.querySelector("[data-remove-yarn]").addEventListener("click", () => {
				removeYarn(id);
			});
		});

		// Render pending yarns
		pendingYarns.forEach((yarn) => {
			const name = yarn.name;
			const chip = document.createElement("div");
			chip.className =
				"flex items-center gap-3 py-2.5 px-3.5 bg-secondary/10 text-secondary border border-secondary/20 rounded-xl";

			const imageHtml = yarn.imageUrl
				? `<img src="${yarn.imageUrl}" alt="${name}" class="w-10 h-10 rounded-lg object-cover" onerror="this.replaceWith(this.parentElement.querySelector('[data-fallback-icon]').cloneNode(true)); this.parentElement.querySelector('[data-fallback-icon]').classList.remove('hidden')">`
				: "";

			chip.innerHTML = `
	                    ${imageHtml}
	                    <div class="relative w-10 h-10 rounded-lg bg-base-300 flex items-center justify-center text-base-content/60${yarn.imageUrl ? " hidden" : ""}" data-fallback-icon>
	                        <span class="mdi mdi-sheep text-base" aria-hidden="true"></span>
	                        <span class="absolute -top-1.5 -right-1.5 badge badge-secondary font-black text-[8px] h-3 min-h-0 px-1 border-none shadow-sm scale-90">NEW</span>
	                    </div>
	                    <div class="flex-1 min-w-0">
	                        <span class="text-base block truncate">${name}</span>
	                    </div>
	                    <button type="button" class="btn btn-ghost btn-xs btn-circle" data-remove-pending="${name}">
	                        <span class="mdi mdi-close"></span>
	                    </button>
	                `;
			selectedContainer.appendChild(chip);

			chip
				.querySelector("[data-remove-pending]")
				.addEventListener("click", () => {
					removePendingYarn(name);
				});
		});
	}

	function updateHiddenInput() {
		hiddenInput.value = Array.from(selectedYarns.keys()).join(",");

		const yarnTextHidden = document.getElementById("yarn_text_hidden");
		if (yarnTextHidden) {
			yarnTextHidden.value = Array.from(pendingYarns.keys()).join("\n");
		}

		// Explicitly clear yarn_search if we have pending yarns as chips
		const searchInput = document.getElementById("yarn_search");
		if (
			searchInput &&
			pendingYarns.size > 0 &&
			document.activeElement !== searchInput
		) {
			// If the user isn't typing, make sure it's clean
			if (
				searchInput.value.includes("\n") ||
				Array.from(pendingYarns.keys()).some((y) =>
					searchInput.value.includes(y),
				)
			) {
				searchInput.value = "";
			}
		}
	}

	function filterOptions() {
		const searchTerm = searchInput.value.toLowerCase();
		let visibleCount = 0;

		yarnOptions.forEach((option) => {
			const yarnId = parseInt(option.dataset.yarnId);
			const isSelected = selectedYarns.has(yarnId);
			const name = option.dataset.yarnName.toLowerCase();
			const brand = (option.dataset.yarnBrand || "").toLowerCase();
			const colorway = (option.dataset.yarnColorway || "").toLowerCase();
			const dyeLot = (option.dataset.yarnDyeLot || "").toLowerCase();
			const matchesSearch =
				!searchTerm ||
				name.includes(searchTerm) ||
				brand.includes(searchTerm) ||
				colorway.includes(searchTerm) ||
				dyeLot.includes(searchTerm);

			if (!isSelected && matchesSearch) {
				option.classList.remove("hidden");
				visibleCount++;
			} else {
				option.classList.add("hidden");
			}
		});

		if (
			visibleCount > 0 &&
			(searchInput === document.activeElement || searchTerm)
		) {
			dropdown.classList.remove("hidden");
		} else {
			dropdown.classList.add("hidden");
		}
	}

	searchInput.addEventListener("focus", () => {
		filterOptions();
	});

	searchInput.addEventListener("input", () => {
		filterOptions();
	});

	searchInput.addEventListener("blur", (e) => {
		setTimeout(() => {
			if (!dropdown.contains(document.activeElement)) {
				dropdown.classList.add("hidden");
			}
		}, 200);
	});

	yarnOptions.forEach((option) => {
		option.addEventListener("click", (e) => {
			e.preventDefault();
			const yarnId = parseInt(option.dataset.yarnId);
			const yarnName = option.dataset.yarnName;
			const yarnBrand = option.dataset.yarnBrand;
			const yarnColorway = option.dataset.yarnColorway;
			const yarnDyeLot = option.dataset.yarnDyeLot;
			const yarnImage = option.dataset.yarnImage;

			selectYarn(
				yarnId,
				yarnName,
				yarnBrand,
				yarnColorway,
				yarnDyeLot,
				yarnImage,
			);
			searchInput.value = "";
			searchInput.focus();
		});
	});

	document.addEventListener("click", (e) => {
		if (!searchInput.contains(e.target) && !dropdown.contains(e.target)) {
			dropdown.classList.add("hidden");
		}
	});
}

function setupTextareaDrop(textarea) {
	textarea.addEventListener("dragover", (e) => {
		e.preventDefault();
		textarea.classList.add("border-blue-500", "ring-2", "ring-blue-500");
	});

	textarea.addEventListener("dragleave", (e) => {
		e.preventDefault();
		textarea.classList.remove("border-blue-500", "ring-2", "ring-blue-500");
	});

	textarea.addEventListener("drop", async (e) => {
		e.preventDefault();
		textarea.classList.remove("border-blue-500", "ring-2", "ring-blue-500");

		const files = e.dataTransfer.files;
		if (files && files.length > 0) {
			const imageFiles = Array.from(files).filter((file) =>
				file.type.startsWith("image/"),
			);
			if (imageFiles.length === 0) return;

			const stepItem = textarea.closest(".step-item");
			let sid = stepItem ? stepItem.getAttribute("data-step-id") : null;

			if (stepItem && !sid) {
				sid = await ensureStepId(stepItem);
				if (!sid) return;
			}

			if (!sid && !projectId) {
				if (!(await ensureProjectId())) return;
			}

			for (const file of imageFiles) {
				await uploadAndInsertImage(file, textarea, sid);
			}
			return;
		}

		const text = e.dataTransfer.getData("text/plain");
		if (text) {
			insertAtCursor(textarea, text);
		}
	});
}

function handleImageDragStart(event) {
	// Find the anchor element (either target itself or parent)
	const anchor =
		event.target.tagName === "A" ? event.target : event.target.closest("a");
	if (!anchor) return;

	const src = anchor.getAttribute("href");
	const alt =
		anchor.getAttribute("data-pswp-caption") ||
		anchor.querySelector("img")?.alt ||
		"";
	const markdown = `![${alt}](${src})`;

	// Debug
	console.log("Dragging image as markdown:", markdown);

	// Set data
	event.dataTransfer.setData("text/plain", markdown);
	event.dataTransfer.effectAllowed = "copy";

	// Important: set dropEffect in dragover/drop handlers, here we just set effectAllowed
}
function insertAtCursor(textarea, text) {
	const start = textarea.selectionStart;
	const end = textarea.selectionEnd;
	const val = textarea.value;
	textarea.value = val.substring(0, start) + text + val.substring(end);
	textarea.selectionStart = textarea.selectionEnd = start + text.length;
	textarea.focus();
	textarea.dispatchEvent(new Event("input", { bubbles: true }));
	autoResize(textarea);
}
/**
 * Helper to create a unified image preview card in JavaScript.
 * Matches the image_preview Jinja macro.
 */
function createImagePreviewHTML(imageData, options = {}) {
	const {
		showPromote = false,
		showDelete = true,
		isPrimary = false,
		projectId = window.projectId,
		imageClass = "h-32",
	} = options;

	const pswpWidth = imageData.width || 1200;
	const pswpHeight = imageData.height || 1200;
	const altText = imageData.alt_text || "";
	const thumbUrl = imageData.thumbnail_url || imageData.url;

	return `
        <div class="relative group" data-image-id="${imageData.id}">
            <a href="${imageData.url}"
               data-pswp-width="${pswpWidth}"
               data-pswp-height="${pswpHeight}"
               data-pswp-caption="${altText}"
               ${showPromote ? 'data-pswp-promote="true"' : ""}
               ${showDelete ? 'data-pswp-delete="true"' : ""}
               data-pswp-is-primary="${isPrimary ? "true" : "false"}"
               draggable="true" ondragstart="handleImageDragStart(event)"
               class="block">
                <img src="${thumbUrl}"
                     alt="${altText}"
                     class="${imageClass} w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
            </a>
	            ${
								showPromote
									? `
	            <button type="button" data-call="promoteImage" data-call-args='[${projectId},${imageData.id}]'
	                class="title-promote-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all ${isPrimary ? "bg-amber-400 text-white opacity-100" : "bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500"} hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	                title="{{ _('Make title image') }}">
	                <span class="mdi ${isPrimary ? "mdi-star" : "mdi-star-outline"}"></span>
	            </button>
	            `
									: ""
							}
	            ${
								showDelete
									? `
	            <button type="button" data-call="deleteImage" data-call-args='[${imageData.id}]'
	                class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
	                <span class="mdi mdi-delete"></span>
	            </button>
	            `
									: ""
							}
        </div>
        `;
}

async function uploadAndInsertImage(file, textarea, stepId) {
	if (!stepId && !(await ensureProjectId())) return;

	const formData = new FormData();
	formData.append("file", file);
	formData.append("alt_text", file.name);

	const url = stepId
		? `/projects/${projectId}/steps/${stepId}/images`
		: `/projects/${projectId}/images/title`;

	try {
		window.showToast?.("{{ _("Uploading image...") }}", "info");
		const response = await fetch(url, {
			method: "POST",
			body: formData,
		});

		if (response.ok) {
			const data = await response.json();
			const markdown = `![${data.alt_text}](${data.url})`;
			insertAtCursor(textarea, markdown);
			window.showToast?.("{{ _("Image uploaded!") }}", "success");
			window.unsavedChanges?.setDirty(true);

			if (stepId) {
				const stepItem = document.querySelector(
					`.step-item[data-step-id="${stepId}"]`,
				);
				if (stepItem) {
					addStepImagePreview(stepItem, data);
				}
			} else {
				addTitleImageToGallery(data);
			}
		} else {
			window.showToast?.("{{ _("Upload failed") }}", "error");
		}
	} catch (error) {
		console.error("Upload failed", error);
		window.showToast?.("{{ _("Upload failed") }}", "error");
	}
}

let projectId = {{ (project.id if project else none) | tojson }};
let wasSilentlyCreated = false;

// Initialize discard hook
document.addEventListener("DOMContentLoaded", () => {
	if (window.unsavedChanges) {
		window.unsavedChanges.onBeforeDiscard = async () => {
			if (wasSilentlyCreated && projectId) {
				console.log("Discarding silent project:", projectId);
				try {
					const response = await fetch(`/projects/${projectId}`, {
						method: "DELETE",
						headers: {
							"HX-Request": "true", // Trigger HTMX-like response if needed, although we are navigating away
							"X-CSRF-Token": "{{ csrf_token }}",
						},
					});
					if (!response.ok) {
						console.error("Failed to delete silent project");
					}
				} catch (error) {
					console.error("Error deleting silent project:", error);
				}
			}
		};
	}
});

/**
 * Ensures the project exists before performing actions that require a projectId.
 * If project doesn't exist, it performs a minimal save to get an ID.
 */
async function ensureProjectId() {
	if (projectId) return true;

	const nameInput = document.getElementById("name");
	if (nameInput && !nameInput.value.trim()) {
		const now = new Date();
		const dateStr =
			now.getFullYear() +
			"-" +
			String(now.getMonth() + 1).padStart(2, "0") +
			"-" +
			String(now.getDate()).padStart(2, "0");
		nameInput.value = `{{ _("New Project") }} ${dateStr}`;
	}

	const formData = new FormData(document.getElementById("projectForm"));

	try {
		const response = await fetch("/projects", {
			method: "POST",
			headers: {
				Accept: "application/json",
			},
			body: formData,
		});

		if (response.ok) {
			const data = await response.json();
			projectId = data.id;
			wasSilentlyCreated = true;
			window.unsavedChanges?.setDirty(true);

			// Update form action and other links
			const form = document.getElementById("projectForm");
			if (form) {
				form.action = `/projects/${projectId}`;
			}

			// Update back button link
			const backBtn = document.querySelector("[data-unsaved-confirm]");
			if (backBtn && backBtn.tagName.toLowerCase() === "a") {
				backBtn.href = "/projects";
			}

			// Update browser URL without reloading
			window.history.replaceState({}, "", `/projects/${projectId}/edit`);
			return true;
		} else {
			window.showToast?.("{{ _("Failed to initialize project") }}", "error");
			return false;
		}
	} catch (error) {
		console.error("Project initialization failed", error);
		window.showToast?.("{{ _("Network error") }}", "error");
		return false;
	}
}

const uploadInstructionsText = "{{ _("Drag and drop images here or click to upload") }}";
const stepUploadDisabledMessage = "{{ _("Save the project before adding images to this step") }}";
const uploadErrorMessage = "{{ _("Image upload failed. Please try again") }}";
const uploadNetworkErrorMessage = "{{ _("Network error while uploading image") }}";
const unsupportedFileMessage = "{{ _("Only image files are supported") }}";
const deleteErrorMessage = "{{ _("Failed to delete the image. Please try again") }}";
const deleteNetworkErrorMessage = "{{ _("Network error while deleting image") }}";
const uploadingMessage = "{{ _("Uploading…") }}";

const parseErrorMessage =
	typeof extractErrorMessage === "function"
		? extractErrorMessage
		: async (_response, fallback) => fallback;

function bindPendingStepDropzone(dropzone, input, instructions) {
	if (!dropzone || dropzone.dataset.pendingBound === "true") {
		return;
	}

	dropzone.dataset.pendingBound = "true";
	dropzone.classList.add("opacity-60", "cursor-not-allowed");
	dropzone.classList.remove("pointer-events-none");
	dropzone.setAttribute("aria-disabled", "true");

	if (instructions) {
		instructions.textContent =
			instructions.dataset.disabledText || stepUploadDisabledMessage;
	}

	const showDisabledToast = () => {
		window.showToast?.(stepUploadDisabledMessage, "info");
	};

	dropzone.addEventListener("click", (event) => {
		event.preventDefault();
		showDisabledToast();
	});

	["dragover", "drop"].forEach((eventName) => {
		dropzone.addEventListener(eventName, (event) => {
			event.preventDefault();
			event.stopPropagation();
			if (eventName === "dragover") {
				dropzone.classList.add("border-blue-500");
			} else {
				dropzone.classList.remove("border-blue-500");
				showDisabledToast();
			}
		});
	});

	dropzone.addEventListener("dragleave", (event) => {
		event.preventDefault();
		event.stopPropagation();
		dropzone.classList.remove("border-blue-500");
	});

	const label = dropzone.querySelector("label");
	if (label) {
		["click", "dragover", "drop", "dragleave"].forEach((eventName) => {
			label.addEventListener(eventName, (event) => {
				event.preventDefault();
				event.stopPropagation();
				if (eventName === "dragover") {
					dropzone.classList.add("border-blue-500");
					return;
				}

				if (eventName === "dragleave") {
					dropzone.classList.remove("border-blue-500");
					return;
				}

				if (eventName === "drop") {
					dropzone.classList.remove("border-blue-500");
				}

				showDisabledToast();
			});
		});
	}

	input.addEventListener("change", (event) => {
		event.preventDefault();
		input.value = "";
		showDisabledToast();
	});
}

async function uploadTitleImageData(file) {
	if (!(await ensureProjectId())) return null;

	const formData = new FormData();
	formData.append("file", file);
	formData.append("alt_text", file.name);

	let response;
	try {
		response = await fetch(`/projects/${projectId}/images/title`, {
			method: "POST",
			headers: {
				"X-CSRF-Token": "{{ csrf_token }}",
			},
			body: formData,
		});
	} catch (error) {
		console.error("Title image upload failed", error);
		window.showToast?.(uploadNetworkErrorMessage, "error");
		return null;
	}

	if (!response.ok) {
		const message = await parseErrorMessage(response, uploadErrorMessage);
		window.showToast?.(message, "error");
		return null;
	}

	const data = await response.json();
	addTitleImageToGallery(data);
	window.unsavedChanges?.setDirty(true);
	return data;
}

async function uploadTitleImage(file) {
	return Boolean(await uploadTitleImageData(file));
}

function addTitleImageToGallery(imageData) {
	const container = document.getElementById("titleImagesContainer");
	if (!container) return;

	container.insertAdjacentHTML(
		"beforeend",
		createImagePreviewHTML(imageData, {
			showPromote: true,
			isPrimary: false,
		}),
	);
	window.refreshPhotoSwipeGallery?.(container);
}

function initAttachmentUploader() {
	const attachmentInput = document.getElementById("attachmentInput");
	const attachmentDropZone = document.getElementById("attachmentDropZone");
	if (attachmentInput && attachmentDropZone) {
		window.setupImageUploadWidget(
			attachmentInput,
			attachmentDropZone,
			uploadAttachment,
		);
	}
}

async function uploadAttachment(file) {
	if (!(await ensureProjectId())) return false;

	const formData = new FormData();
	formData.append("file", file);

	try {
		window.showToast?.("{{ _("Uploading attachment...") }}", "info");
		const response = await fetch(`/projects/${projectId}/attachments`, {
			method: "POST",
			headers: {
				"X-CSRF-Token": "{{ csrf_token }}",
			},
			body: formData,
		});

		if (response.ok) {
			const data = await response.json();
			addAttachmentToUI(data);
			window.showToast?.("{{ _("Attachment uploaded!") }}", "success");
			window.unsavedChanges?.setDirty(true);
			return true;
		} else {
			window.showToast?.("{{ _("Upload failed") }}", "error");
			return false;
		}
	} catch (error) {
		console.error("Upload failed", error);
		window.showToast?.("{{ _("Upload failed") }}", "error");
		return false;
	}
}

function addAttachmentToUI(data) {
	const container = document.getElementById("attachmentsContainer");
	if (!container) return;

	const escapeHtml = (value) => {
		const map = {
			"&": "&amp;",
			"<": "&lt;",
			">": "&gt;",
			'"': "&quot;",
			"'": "&#039;",
		};
		return String(value ?? "").replace(/[&<>"']/g, (m) => map[m]);
	};

	const sizeMb = (data.size_bytes / 1024 / 1024).toFixed(2);
	let icon = "mdi-file-outline";
	if (data.content_type === "application/pdf") icon = "mdi-file-pdf-outline";
	else if (data.content_type.startsWith("image/"))
		icon = "mdi-file-image-outline";

	const originalName = escapeHtml(data.original_filename);
	const url = escapeHtml(data.url);
	const thumbUrl = data.thumbnail_url ? escapeHtml(data.thumbnail_url) : "";
	const kind =
		data.content_type === "application/pdf"
			? "pdf"
			: data.content_type.startsWith("image/")
				? "image"
				: "other";
	const token = data.token ? escapeHtml(data.token) : "";
	const openIndex =
		kind === "image"
			? container.querySelectorAll("a[data-pswp-width]").length
			: null;

	const thumbOrIcon = thumbUrl
		? `
                            <img src="${thumbUrl}" alt="" class="w-full h-full object-cover" loading="lazy">
            `
		: `
                            <span class="mdi ${icon} text-2xl"></span>
            `;

	const pswpWidth = typeof data.width === "number" ? data.width : 1200;
	const pswpHeight = typeof data.height === "number" ? data.height : 1200;
	const html = `
                <div class="flex items-center justify-between p-3 rounded-xl border border-base-200 bg-base-200/30 group cursor-pointer"
                    role="button"
                    tabindex="0"
                    data-action="open-attachment"
                    data-attachment-kind="${kind}"
                    data-attachment-url="${url}"
                    data-attachment-name="${originalName}"
                    ${data.id ? `data-attachment-id="${data.id}"` : `data-pending-token="${data.token}"`}
                    ${kind === "image" ? `data-pswp-open-index="${openIndex}"` : ""}>
                    <div class="flex items-center gap-3 min-w-0">
                        <div class="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center text-primary shrink-0 overflow-hidden">
                            ${thumbOrIcon}
                        </div>
                    <div class="min-w-0">
                        <p class="font-medium text-sm truncate" title="${originalName}">
                            ${originalName}
                        </p>
                        <p class="text-[10px] text-base-content/50 uppercase font-bold tracking-wider">
                            ${sizeMb} MB
                        </p>
                    </div>
                </div>
                <div class="flex items-center gap-1">
                    <a href="${url}" target="_blank"
                        class="btn btn-ghost btn-sm btn-circle text-primary"
                        title="{{ _('Download') }}">
                        <span class="mdi mdi-download text-xl"></span>
                    </a>
                    <button type="button" data-call="deleteAttachment"
                        data-call-args='[${data.id ? data.id : `"${token}"`}]'
                        class="btn btn-ghost btn-sm btn-circle text-error"
                        title="{{ _('Delete') }}">
                        <span class="mdi mdi-delete-outline text-xl"></span>
                    </button>
                </div>
                    ${
											kind === "image"
												? `
                        <a href="${url}" class="hidden" aria-hidden="true" tabindex="-1"
                            data-pswp-width="${pswpWidth}"
                            data-pswp-height="${pswpHeight}"
                            data-pswp-caption="${originalName}"></a>
                    `
												: ""
										}
                </div>
            `;
	container.insertAdjacentHTML("beforeend", html);
	if (kind === "image") {
		window.refreshPhotoSwipeGallery?.(container);
	}
}

async function deleteAttachment(idOrToken) {
	let contentHtml = "";

	if (
		typeof idOrToken === "number" ||
		(typeof idOrToken === "string" && idOrToken.length !== 32)
	) {
		const id = idOrToken;
		const attachmentEl = document.querySelector(`[data-attachment-id="${id}"]`);
		const filename =
			attachmentEl?.dataset.attachmentName || "{{ _("Attachment") }}";
		const kind = attachmentEl?.dataset.attachmentKind || "other";
		const sizeBytes = parseInt(attachmentEl?.dataset.attachmentSize || "0");
		const thumbnailImg = attachmentEl?.querySelector("img");
		const thumbnailUrl = thumbnailImg?.src || "";

		const typeLabels = {
			pdf: "PDF",
			image: "{{ _("Image") }}",
			other: "{{ _("File") }}",
		};
		const typeLabel = typeLabels[kind] || typeLabels.other;

		const sizeText =
			sizeBytes > 0 ? (sizeBytes / 1024 / 1024).toFixed(2) + " MB" : "";

		contentHtml = '<div class="flex items-start gap-4 py-4">';

		contentHtml +=
			'<div class="shrink-0 w-24 h-24 rounded-lg bg-base-200 flex items-center justify-center overflow-hidden">';
		if (thumbnailUrl) {
			contentHtml += `<img src="${thumbnailUrl}" alt="" class="w-full h-full object-cover">`;
		} else {
			let iconClass = "mdi-file-outline";
			if (kind === "pdf") {
				iconClass = "mdi-file-pdf-outline";
			} else if (kind === "image") {
				iconClass = "mdi-file-image-outline";
			}
			contentHtml += `<span class="mdi ${iconClass} text-4xl text-primary/60"></span>`;
		}
		contentHtml += "</div>";

		contentHtml += '<div class="flex-1 min-w-0">';
		contentHtml += `<p class="font-medium text-base-content truncate" title="${filename}">${filename}</p>`;
		contentHtml += `<p class="text-sm text-base-content/60 mt-1">${typeLabel}</p>`;
		if (sizeText) {
			contentHtml += `<p class="text-sm text-base-content/60">${sizeText}</p>`;
		}
		contentHtml += "</div>";

		contentHtml += "</div>";
	}

	window.confirmAction(
		"{{ _("Delete Attachment") }}",
		"{{ _("Are you sure you want to delete this attachment?") }}",
		async () => {
			if (typeof idOrToken === "string" && idOrToken.length === 32) {
				const token = idOrToken;
				const el = document.querySelector(`[data-pending-token="${token}"]`);
				el?.remove();

				const input = document.getElementById("import_attachment_tokens");
				if (input && input.value) {
					try {
						const tokens = JSON.parse(input.value);
						const updated = tokens.filter((t) => t !== token);
						input.value = JSON.stringify(updated);
					} catch (e) {
						console.error(e);
					}
				}
				window.unsavedChanges?.setDirty(true);
				return;
			}

			const id = idOrToken;
			try {
				const response = await fetch(
					`/projects/${projectId}/attachments/${id}`,
					{
						method: "DELETE",
						headers: {
							"X-CSRF-Token": "{{ csrf_token }}",
						},
					},
				);

				if (response.ok) {
					document.querySelector(`[data-attachment-id="${id}"]`)?.remove();
					window.showToast?.("{{ _("Attachment deleted") }}", "success");
					window.unsavedChanges?.setDirty(true);
				} else {
					window.showToast?.("{{ _("Delete failed") }}", "error");
				}
			} catch (error) {
				console.error("Delete failed", error);
				window.showToast?.("{{ _("Delete failed") }}", "error");
			}
		},
		null,
		{ ...(contentHtml ? { content: contentHtml } : {}), variant: "error" },
	);
}

function addPendingTitleImageToGallery(url, options = {}) {
	const container = document.getElementById("titleImagesContainer");
	if (!container) {
		return;
	}

	const skipAutoPromote = options.skipAutoPromote === true;

	// Defensive: avoid showing duplicates even if the import runs multiple times
	// or returns slightly different variants of the same URL.
	const key = canonicalizeImportImageUrl(url);
	if (key) {
		const alreadyInDom = Array.from(container.querySelectorAll("a[href]")).some(
			(anchor) => {
				return canonicalizeImportImageUrl(anchor.getAttribute("href")) === key;
			},
		);
		if (alreadyInDom) {
			return;
		}
	}
	const div = document.createElement("div");
	div.className = "relative group";
	div.setAttribute("data-pending-url", url);
	div.innerHTML = `
        <a href="${url}" data-pswp-width="1200" data-pswp-height="1200" data-pswp-caption=""
            data-pswp-promote="true" data-pswp-delete="true" data-pswp-is-primary="false"
            draggable="true" ondragstart="handleImageDragStart(event)"
            class="block">
            <img src="${url}" class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
        </a>
	        <button type="button" data-call="promotePendingImage" data-call-args='["$this","$dataset:url"]' data-url="${url}"
	            class="promote-pending-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	            title="{{ _('Make title image') }}">
	            <span class="mdi mdi-star-outline"></span>
	        </button>
	        <button type="button" data-call="deletePendingImage" data-call-args='["$this","$dataset:url"]' data-url="${url}" class="absolute top-1 right-1 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
	            <span class="mdi mdi-delete"></span>
	        </button>
    `;
	container.appendChild(div);

	// If this is the first image, automatically promote it
	if (!skipAutoPromote) {
		const currentTitleUrl = document.getElementById(
			"import_title_image_url",
		).value;
		if (!currentTitleUrl) {
			promotePendingImage(div.querySelector(".promote-pending-btn"), url);
		}
	}

	window.refreshPhotoSwipeGallery?.(container);
}

function promotePendingImage(btn, url) {
	// Update hidden field
	const input = document.getElementById("import_title_image_url");
	if (input) input.value = url;

	// Visual update
	document.querySelectorAll(".promote-pending-btn").forEach((b) => {
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

function deletePendingImage(button, url) {
	window.confirmAction(
		"{{ _("Remove Image") }}",
		"{{ _("Remove this image from the import list?") }}",
		() => {
			// Remove visual element
			const container = button.closest(".relative");
			const wasPrimary =
				document.getElementById("import_title_image_url")?.value === url;
			container?.remove();

			// Update hidden input
			const importInput = document.getElementById("import_image_urls");
			if (importInput && importInput.value) {
				try {
					let urls = JSON.parse(importInput.value);
					urls = urls.filter((u) => u !== url);
					importInput.value = JSON.stringify(urls);
					if (wasPrimary) {
						if (urls.length > 0) {
							const nextUrl = urls[0];
							const nextBtn = document.querySelector(
								`[data-pending-url="${CSS.escape(nextUrl)}"] .promote-pending-btn`,
							);
							promotePendingImage(nextBtn, nextUrl);
						} else {
							const titleInput = document.getElementById(
								"import_title_image_url",
							);
							if (titleInput) {
								titleInput.value = "";
							}
						}
					}
				} catch (e) {
					console.error("Error updating import_image_urls:", e);
				}
			} else if (wasPrimary) {
				const titleInput = document.getElementById("import_title_image_url");
				if (titleInput) {
					titleInput.value = "";
				}
			}
			window.unsavedChanges?.setDirty(true);
		},
	);
}

async function deleteImage(imageId) {
	const imageEl = document.querySelector(`[data-image-id="${imageId}"]`);
	const imgTag = imageEl?.querySelector("img");
	const linkTag = imageEl?.querySelector("a[data-pswp-width]");

	const thumbnailUrl = imgTag?.src || "";
	const filename =
		imgTag?.alt ||
		linkTag?.dataset.pswpCaption ||
		"{{ _("Image") }}";
	const width = linkTag?.dataset.pswpWidth || "";
	const height = linkTag?.dataset.pswpHeight || "";

	let contentHtml = '<div class="flex flex-col items-center gap-4 py-4">';
	if (thumbnailUrl) {
		contentHtml += `<img src="${thumbnailUrl}" alt="" class="max-h-48 rounded-lg shadow-md object-contain">`;
	}
	contentHtml += '<div class="text-center">';
	contentHtml += `<p class="font-medium text-base-content">${filename}</p>`;
	if (width && height) {
		contentHtml += `<p class="text-sm text-base-content/60 mt-1">${width} × ${height} px</p>`;
	}
	contentHtml += "</div></div>";

	window.confirmAction(
		"{{ _("Delete Image") }}",
		"{{ _("Are you sure you want to delete this image? This action cannot be undone.") }}",
		async () => {
			let response;
			try {
				response = await fetch(`/projects/${projectId}/images/${imageId}`, {
					method: "DELETE",
					headers: {
						"X-CSRF-Token": "{{ csrf_token }}",
					},
				});
			} catch (error) {
				console.error("Image deletion failed", error);
				window.showToast?.(deleteNetworkErrorMessage, "error");
				return;
			}

			if (!response.ok) {
				const message = await parseErrorMessage(response, deleteErrorMessage);
				window.showToast?.(message, "error");
				return;
			}

			const element = document.querySelector(`[data-image-id="${imageId}"]`);
			if (element) {
				element.remove();
			}
			window.unsavedChanges?.setDirty(true);
		},
		null,
		{ content: contentHtml },
	);
}

async function promoteImage(pId, imageId) {
	try {
		const response = await fetch(`/projects/${pId}/images/${imageId}/promote`, {
			method: "POST",
			headers: {
				"X-Requested-With": "XMLHttpRequest",
				"X-CSRF-Token": "{{ csrf_token }}",
			},
		});

		if (response.ok) {
			// If PhotoSwipe is open, close it before reloading
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			setPrimaryTitleImage(imageId);
		} else {
			console.error("Failed to promote image");
		}
	} catch (error) {
		console.error("Error promoting image:", error);
	}
}

function setPrimaryTitleImage(imageId) {
	const container = document.getElementById("titleImagesContainer");
	if (!container) {
		return;
	}
	container.querySelectorAll("[data-image-id]").forEach((item) => {
		const anchor = item.querySelector("a[data-pswp-promote]");
		if (anchor) {
			anchor.setAttribute("data-pswp-is-primary", "false");
		}
		const button = item.querySelector(".title-promote-btn");
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

	const target = container.querySelector(`[data-image-id="${imageId}"]`);
	if (!target) {
		return;
	}
	const targetAnchor = target.querySelector("a[data-pswp-promote]");
	if (targetAnchor) {
		targetAnchor.setAttribute("data-pswp-is-primary", "true");
	}
	const targetButton = target.querySelector(".title-promote-btn");
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
	const container = anchor.closest("[data-image-id]");
	if (container) {
		const imageId = container.dataset.imageId;
		if (imageId && projectId) {
			promoteImage(projectId, imageId);
		}
	} else {
		// Handle pending images
		const pendingContainer = anchor.closest("[data-pending-url]");
		if (pendingContainer) {
			const btn = pendingContainer.querySelector(".promote-pending-btn");
			const url = pendingContainer.dataset.pendingUrl;
			if (btn && url) promotePendingImage(btn, url);
		}
	}
});

document.addEventListener("pswp:delete", (e) => {
	const anchor = e.detail.element;
	const container = anchor.closest("[data-image-id]");
	if (container) {
		const imageId = container.dataset.imageId;
		if (imageId) {
			// Close PhotoSwipe before showing confirmation
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			deleteImage(imageId);
		}
	} else {
		// Handle pending images
		const pendingContainer = anchor.closest("[data-pending-url]");
		if (pendingContainer) {
			const url = pendingContainer.dataset.pendingUrl;
			const deleteBtn = pendingContainer.querySelector(
				'button[onclick*="deletePendingImage"]',
			);
			const removeBtn = pendingContainer.querySelector(
				'button[onclick*="removePendingStepImage"]',
			);
			if (window.pswpLightboxes) {
				for (const lb of window.pswpLightboxes.values()) {
					if (lb.pswp) lb.pswp.close();
				}
			}
			if (deleteBtn && url) {
				deletePendingImage(deleteBtn, url);
			} else if (removeBtn) {
				removePendingStepImage(removeBtn);
			}
		}
	}
});

function initStepImageUploaders() {
	document.querySelectorAll(".step-item").forEach((stepItem) => {
		const dropzone = stepItem.querySelector(".step-image-dropzone");
		const input = stepItem.querySelector(".step-image-input");
		const instructions = dropzone?.querySelector(".upload-instructions");
		const stepId = stepItem.getAttribute("data-step-id");

		if (!dropzone || !input || dropzone.dataset.initialized === "true") {
			return;
		}

		dropzone.classList.remove(
			"opacity-60",
			"opacity-50",
			"cursor-not-allowed",
			"pointer-events-none",
		);
		dropzone.removeAttribute("aria-disabled");
		if (instructions) {
			instructions.textContent =
				instructions.dataset.enabledText || uploadInstructionsText;
		}

		window.setupImageUploadWidget(input, dropzone, async (file) => {
			const sid = await ensureStepId(stepItem);
			if (sid) {
				const data = await uploadStepImage(sid, file);
				if (data) {
					addStepImagePreview(stepItem, data);
				}
			}
		});
	});
}

async function ensureStepId(stepItem) {
	if (!(await ensureProjectId())) return null;
	const stepId = stepItem.getAttribute("data-step-id");
	if (stepId) return stepId;

	return await saveStepInternal(stepItem);
}

async function uploadStepImage(stepId, file) {
	const formData = new FormData();
	formData.append("file", file);
	formData.append("alt_text", file.name);

	let response;
	try {
		response = await fetch(`/projects/${projectId}/steps/${stepId}/images`, {
			method: "POST",
			headers: {
				"X-CSRF-Token": "{{ csrf_token }}",
			},
			body: formData,
		});
	} catch (error) {
		console.error("Step image upload failed", error);
		window.showToast?.(uploadNetworkErrorMessage, "error");
		return null;
	}

	if (!response.ok) {
		const message = await parseErrorMessage(response, uploadErrorMessage);
		window.showToast?.(message, "error");
		return null;
	}

	const data = await response.json();
	window.unsavedChanges?.setDirty(true);
	return data;
}

function addStepImagePreview(stepItem, imageData) {
	const imagesContainer = stepItem.querySelector(".step-images");
	if (!imagesContainer) return;

	imagesContainer.insertAdjacentHTML(
		"beforeend",
		createImagePreviewHTML(imageData, {
			imageClass: "h-20",
		}),
	);
	window.refreshPhotoSwipeGallery?.(imagesContainer);
	const textarea = stepItem.querySelector(".step-description");
	if (textarea) updateImageVisibility(textarea);
}

function initStitchSampleImageUploader() {
	const dropzone = document.getElementById("stitchSampleDropzone");
	const input = document.getElementById("stitchSampleImageInput");

	if (!dropzone || !input || dropzone.dataset.initialized === "true") {
		return;
	}

	window.setupImageUploadWidget(input, dropzone, async (file) => {
		await uploadStitchSampleImageData(file);
	});
}

async function uploadStitchSampleImageData(file) {
	if (!(await ensureProjectId())) return null;

	const formData = new FormData();
	formData.append("file", file);
	formData.append("alt_text", file.name);

	try {
		const response = await fetch(
			`/projects/${projectId}/images/stitch-sample`,
			{
				method: "POST",
				headers: {
					"X-CSRF-Token": "{{ csrf_token }}",
				},
				body: formData,
			},
		);

		if (!response.ok) {
			const message = await parseErrorMessage(response, uploadErrorMessage);
			window.showToast?.(message, "error");
			return null;
		}

		const data = await response.json();
		addStitchSampleImagePreview(data);
		window.unsavedChanges?.setDirty(true);
		return data;
	} catch (error) {
		console.error("Stitch sample image upload failed", error);
		window.showToast?.(uploadNetworkErrorMessage, "error");
		return null;
	}
}

function addStitchSampleImagePreview(imageData) {
	const container = document.getElementById("stitchSampleImagesContainer");
	if (!container) return;

	container.insertAdjacentHTML("beforeend", createImagePreviewHTML(imageData));
	if (window.refreshPhotoSwipeGallery) {
		window.refreshPhotoSwipeGallery(container);
	}
	const textarea = document.getElementById("stitch_sample");
	if (textarea) updateImageVisibility(textarea);
}

function addStep(elOrTitle = "", description = "", stepImages = []) {
	// `data-call="addStep"` invokes this with the clicked element as the first arg.
	// Programmatic calls pass (title, description, stepImages).
	let title = "";
	if (
		elOrTitle &&
		(elOrTitle instanceof Element || elOrTitle?.nodeType === 1)
	) {
		title = "";
		description = "";
		stepImages = [];
	} else {
		title = elOrTitle;
	}

	title =
		title == null ? "" : typeof title === "string" ? title : String(title);
	description =
		description == null
			? ""
			: typeof description === "string"
				? description
				: String(description);
	if (!Array.isArray(stepImages)) stepImages = [];

	const escapeAttr = (s) =>
		String(s)
			.replace(/&/g, "&amp;")
			.replace(/"/g, "&quot;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;");
	const escapeHtml = (s) =>
		String(s)
			.replace(/&/g, "&amp;")
			.replace(/</g, "&lt;")
			.replace(/>/g, "&gt;");

	const container = document.getElementById("stepsContainer");
	const stepNumber = container.children.length + 1;
	const div = document.createElement("div");
	div.className =
		"step-item border rounded-lg p-2 md:p-4 bg-base-200 border-base-300 dark:bg-base-300/50 dark:border-base-700";
	div.setAttribute("data-step-number", stepNumber);
	const inputId = `newStepImageInput${Date.now()}`;
	const textareaId = `step-description-new-${Date.now()}`;
	div.innerHTML = `
		        <div class="mb-3 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
		            <h4 class="text-lg font-medium text-base-content">{{ _('Step') }} <span class="step-number">${stepNumber}</span></h4>
		            <div class="flex flex-wrap gap-2">
		                <button type="button" data-call="moveStepUp" class="btn btn-xs btn-ghost gap-1" title="{{ _('Move Up') }}">
		                    <span class="mdi mdi-arrow-up"></span><span class="hidden sm:inline">{{ _('Move Up') }}</span>
		                </button>
		                <button type="button" data-call="moveStepDown" class="btn btn-xs btn-ghost gap-1" title="{{ _('Move Down') }}">
	                    <span class="mdi mdi-arrow-down"></span><span class="hidden sm:inline">{{ _('Move Down') }}</span>
	                </button>
	                <button type="button" data-call="saveStep" class="btn btn-xs btn-primary gap-1" title="{{ _('Save') }}">
	                    <span class="mdi mdi-content-save"></span><span class="hidden sm:inline">{{ _('Save') }}</span>
	                </button>
	                <button type="button" data-call="removeStep" class="btn btn-xs btn-error text-white gap-1" title="{{ _('Remove Step') }}">
	                    <span class="mdi mdi-delete"></span><span class="hidden sm:inline">{{ _('Remove') }}</span>
	                </button>
            </div>
	        </div>
	        <div class="mb-2">
		            <input type="text" class="step-title input  w-full" placeholder="{{ _('Step Title') }}" value="${escapeAttr(title)}">
		        </div>
	            <div data-wysiwyg data-wysiwyg-input="${textareaId}" data-wysiwyg-step="true" class="wysiwyg-container mb-2">
	                <div class="wysiwyg-toolbar">
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="bold" title="{{ _('Bold') }}"><span class="mdi mdi-format-bold"></span></button>
                        <button type="button" data-action="italic" title="{{ _('Italic') }}"><span class="mdi mdi-format-italic"></span></button>
                        <button type="button" data-action="underline" title="{{ _('Underline') }}"><span class="mdi mdi-format-underline"></span></button>
                    </div>
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="heading" data-value="2" title="{{ _('Heading 2') }}"><span class="mdi mdi-format-header-2"></span></button>
                        <button type="button" data-action="heading" data-value="3" title="{{ _('Heading 3') }}"><span class="mdi mdi-format-header-3"></span></button>
                        <button type="button" data-action="paragraph" title="{{ _('Paragraph') }}"><span class="mdi mdi-format-paragraph"></span></button>
                    </div>
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="bulletList" title="{{ _('Bullet list') }}"><span class="mdi mdi-format-list-bulleted"></span></button>
                        <button type="button" data-action="orderedList" title="{{ _('Numbered list') }}"><span class="mdi mdi-format-list-numbered"></span></button>
                    </div>
		                    <div class="wysiwyg-toolbar-group">
		                        <button type="button" data-action="link" title="{{ _('Add link') }}"><span class="mdi mdi-link"></span></button>
		                        <button type="button" data-action="image" title="{{ _('Insert image') }}"><span class="mdi mdi-image"></span></button>
		                        <button type="button" data-action="imageSize" title="{{ _('Image size') }}"><span class="mdi mdi-image-size-select-large"></span><span class="wysiwyg-image-size-label" data-image-size-label>S</span></button>
		                        <button type="button" data-action="toggleRaw" title="{{ _('Edit Markdown') }}"><span class="mdi mdi-language-markdown"></span></button>
		                    </div>
		                </div>
	                <div class="wysiwyg-content"></div>
		            </div>
		            <textarea id="${textareaId}" class="step-description wysiwyg-raw textarea textarea-bordered w-full text-sm font-mono hidden" data-markdown-images="true">${escapeHtml(description)}</textarea>
			        <h4 class="step-photos-label text-xs font-bold text-base-content/50 uppercase tracking-wider mb-2 flex items-center gap-1 mt-4 pt-4 border-t border-base-100 ${stepImages.length > 0 ? "" : "hidden"}">
			            <span class="mdi mdi-image-outline"></span> {{ _('Step Photos') }}
			        </h4>
		        <div class="step-images mb-2 grid grid-cols-3 gap-2 pswp-gallery ${stepImages.length > 0 ? "" : "hidden"}" data-pswp-gallery>
		            ${stepImages
									.map((img) => {
										const url = typeof img === "string" ? img : img.url;
										return `
		                <div class="relative" data-pending-url="${url}">
		                    <a href="${url}" data-pswp-width="1200" data-pswp-height="1200" data-pswp-caption="" data-pswp-delete="true" class="block">
		                        <img src="${url}" class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30" draggable="true" ondragstart="handleImageDragStart(event)">
		                    </a>
		                    <button type="button" data-call="removePendingStepImage" class="absolute top-0 right-0 bg-red-600 text-white rounded-full p-1 hover:bg-red-700">
		                        <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
		                    </button>
		                </div>
		                `;
									})
									.join("")}
		        </div>
		        <div class="step-image-dropzone border-2 border-dashed border-slate-300 dark:border-slate-600 p-4 sm:p-8 text-center transition-colors dark:border-slate-600 dark:hover:border-blue-400" data-step-id="">
		            <input type="file" class="step-image-input hidden" accept="image/*" multiple data-step-id="" id="${inputId}">
		            <label for="${inputId}" class="block cursor-pointer">
		                <svg class="mx-auto h-12 w-12 text-slate-400 dark:text-slate-500" stroke="currentColor" fill="none" viewBox="0 0 48 48">
		                    <path d="M28 8H12a4 4 0 00-4 4v20m32-12v8m0 0v8a4 4 0 01-4 4H12a4 4 0 01-4-4v-4m32-4l-3.172-3.172a4 4 0 00-5.656 0L28 28M8 32l9.172-9.172a4 4 0 015.656 0L28 28m0 0l4 4m4-24h8m-4-4v8m-12 4h.02" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
		                </svg>
		                <p class="upload-instructions mt-2 text-sm text-gray-600 dark:text-slate-300" data-enabled-text="${uploadInstructionsText}" data-disabled-text="${stepUploadDisabledMessage}">${stepUploadDisabledMessage}</p>
		            </label>
		        </div>
		    `;
	container.appendChild(div);
	initStepImageUploaders();
	const newStepGallery = div.querySelector(".step-images");
	if (newStepGallery) {
		window.refreshPhotoSwipeGallery?.(newStepGallery);
	}

	if (window.STRICKNANI?.wysiwyg?.init) {
		window.STRICKNANI.wysiwyg.init({ i18n: window.STRICKNANI.i18n || {} });
	}

	const newTextarea = div.querySelector("textarea");
	if (newTextarea) {
		newTextarea.style.overflowY = "hidden";
		autoResize(newTextarea);
		updateImageVisibility(newTextarea);
		newTextarea.addEventListener("input", () => {
			autoResize(newTextarea);
			updateImageVisibility(newTextarea);
		});
		setupTextareaDrop(newTextarea);
	}
	window.unsavedChanges?.setDirty(true);
}

function removeStep(button) {
	button.closest(".step-item").remove();
	updateStepNumbers();
	window.unsavedChanges?.setDirty(true);
}

function removePendingStepImage(button) {
	const container = button?.closest("[data-pending-url]");
	if (!container) {
		return;
	}
	container.remove();
	window.unsavedChanges?.setDirty(true);
}

function moveStepUp(button) {
	const item = button.closest(".step-item");
	const prev = item.previousElementSibling;
	if (prev) {
		item.parentNode.insertBefore(item, prev);
		updateStepNumbers();
		window.unsavedChanges?.setDirty(true);
	}
}

function moveStepDown(button) {
	const item = button.closest(".step-item");
	const next = item.nextElementSibling;
	if (next) {
		item.parentNode.insertBefore(next, item);
		updateStepNumbers();
		window.unsavedChanges?.setDirty(true);
	}
}

function updateStepNumbers() {
	document.querySelectorAll(".step-item").forEach((item, index) => {
		const number = index + 1;
		item.setAttribute("data-step-number", number);
		item.querySelector(".step-number").textContent = number;
	});
}

async function saveStep(button) {
	const stepItem = button.closest(".step-item");
	button.disabled = true;
	const originalText = button.innerHTML;
	button.innerHTML =
		'<span class="mdi mdi-loading mdi-spin"></span> {{ _("Saving...") }}';

	try {
		await saveStepInternal(stepItem);
	} finally {
		button.disabled = false;
		button.innerHTML = originalText;
	}
}

async function saveStepInternal(stepItem) {
	const stepId = stepItem.getAttribute("data-step-id");
	const title = stepItem.querySelector(".step-title").value;
	const description = stepItem.querySelector(".step-description").value;
	const stepNumber = parseInt(stepItem.getAttribute("data-step-number")) || 1;

	if (!(await ensureProjectId())) return null;

	try {
		const url = stepId
			? `/projects/${projectId}/steps/${stepId}`
			: `/projects/${projectId}/steps`;
		const method = stepId ? "PUT" : "POST";

		const response = await fetch(url, {
			method: method,
			headers: {
				"Content-Type": "application/json",
				"X-CSRF-Token": "{{ csrf_token }}",
			},
			body: JSON.stringify({
				title: title,
				description: description,
				step_number: stepNumber,
			}),
		});

		if (!response.ok) {
			throw new Error("Failed to save step");
		}

		const data = await response.json();

		if (!stepId && data.id) {
			stepItem.setAttribute("data-step-id", data.id);
			const dropzone = stepItem.querySelector(".step-image-dropzone");
			const fileInput = stepItem.querySelector(".step-image-input");
			if (dropzone) dropzone.setAttribute("data-step-id", data.id);
			if (fileInput) fileInput.setAttribute("data-step-id", data.id);

			// Also add visual confirmation of "Save" button if it exists
			const saveBtn = stepItem.querySelector('button[data-call="saveStep"]');
			if (!saveBtn && !projectId) {
				// Logic to add save button if it was hidden?
			}
		}

		window.showToast?.("{{ _("Step saved successfully") }}", "success");
		window.unsavedChanges?.setDirty(true);
		return data.id;
	} catch (error) {
		console.error("Save step failed", error);
		window.showToast?.("{{ _("Failed to save step") }}", "error");
		return null;
	}
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

	const existingProjectId = {{ (project.id if project else None) | tojson }};
	const importDialog = document.getElementById("importDialog");
	const importForm = document.getElementById("importForm");
	const importLoading = document.getElementById("importLoading");

	if (!importDialog || !importForm || !importLoading) return;

	// Reset and show loading state
	importDialog.showModal();
	importForm.classList.add("hidden");
	importLoading.classList.remove("hidden");

	const formData = new FormData();
	formData.append("url", url);
	formData.append("type", "url");
	if (existingProjectId) {
		formData.append("project_id", String(existingProjectId));
	}

	try {
		const response = await fetch("/projects/import", {
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
		populateProjectForm(data);

		importDialog.close();
	} catch (error) {
		console.error("Import failed:", error);
		window.showToast?.("{{ _("Failed to import pattern") }}", "error");
		importDialog.close();
	} finally {
		importForm.classList.remove("hidden");
		importLoading.classList.add("hidden");
	}
}

function populateProjectForm(data) {
	// Prevent re-prompting on save since we just imported
	isImportPrompted = true;

	// Map fields helper
	const setFieldValue = (id, value) => {
		const el = document.getElementById(id);
		if (el && value) {
			if (id === "notes" && el.value.trim().length > 0) return false;
			el.value = value;
			if (typeof autoResize === "function") autoResize(el);
			if (typeof updateImageVisibility === "function")
				updateImageVisibility(el);
			return true;
		}
		return false;
	};

	// Metadata
	setFieldValue("name", data.title || data.name);
	setFieldValue("needles", data.needles);
	setFieldValue("recommended_needles", data.recommended_needles);
	setFieldValue("yarn_brand", data.brand);

	const yarnDetailsField = document.getElementById("yarn_details");
	if (yarnDetailsField && data.yarn_details) {
		yarnDetailsField.value = JSON.stringify(data.yarn_details);
	}

	if (data.yarn || data.yarn_details) {
		window.yarnSelector?.selectByName(data.yarn, data.yarn_details || []);
	}
	setFieldValue("gauge_stitches", data.gauge_stitches);
	setFieldValue("gauge_rows", data.gauge_rows);
	setFieldValue("stitch_sample", data.stitch_sample);
	setFieldValue("other_materials", data.other_materials);
	setFieldValue("description", data.description);
	setFieldValue("notes", data.notes);
	setFieldValue("link", data.link);
	if (data.link) initialLink = data.link;

	// AI Enhanced flag
	const isAiEnhanced = data.is_ai_enhanced === true;
	const hiddenAiInput = document.getElementById("is_ai_enhanced");
	if (hiddenAiInput) hiddenAiInput.value = isAiEnhanced ? "1" : "";
	const aiCheckbox = document.getElementById("is_ai_enhanced_checkbox");
	const aiCheckboxMobile = document.getElementById(
		"is_ai_enhanced_mobile_checkbox",
	);
	if (aiCheckbox) aiCheckbox.checked = isAiEnhanced;
	if (aiCheckboxMobile) aiCheckboxMobile.checked = isAiEnhanced;

	// Project Image Previews
	const importImagesField = document.getElementById("import_image_urls");
	const imageUrls = Array.isArray(data.image_urls) ? data.image_urls : [];
	const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
	if (importImagesField) {
		importImagesField.value = dedupedImageUrls.length
			? JSON.stringify(dedupedImageUrls)
			: "";
	}
	const importAttachmentTokensField = document.getElementById(
		"import_attachment_tokens",
	);
	if (
		importAttachmentTokensField &&
		Array.isArray(data.import_attachment_tokens)
	) {
		let existingTokens = [];
		try {
			existingTokens = JSON.parse(importAttachmentTokensField.value || "[]");
		} catch (e) {
			existingTokens = [];
		}
		const merged = [
			...new Set([
				...(Array.isArray(existingTokens) ? existingTokens : []),
				...data.import_attachment_tokens,
			]),
		];
		importAttachmentTokensField.value = JSON.stringify(merged);
	}

	if (dedupedImageUrls.length > 0) {
		// Find the best title image: prefer regular URLs over /media/imports/
		const bestTitleUrl =
			dedupedImageUrls.find((u) => !u.includes("/media/imports/")) ||
			dedupedImageUrls[0];

		dedupedImageUrls.forEach((url) => {
			if (typeof addPendingTitleImageToGallery === "function") {
				addPendingTitleImageToGallery(url, {
					skipAutoPromote: true,
				});
			}
		});

		// Explicitly promote the best one
		const bestImgBtn = document.querySelector(
			`.promote-pending-btn[data-url="${bestTitleUrl}"]`,
		);
		if (bestImgBtn) {
			promotePendingImage(bestImgBtn, bestTitleUrl);
		}
	}

	// Steps
	if (data.steps && data.steps.length > 0) {
		const stepsContainer = document.getElementById("stepsContainer");
		if (stepsContainer) {
			stepsContainer.innerHTML = "";
			data.steps.forEach((step, index) => {
				if (typeof addStep === "function") {
					const stepImages = step.images || step.image_urls || [];
					addStep(
						step.title || `Step ${index + 1}`,
						step.description || "",
						stepImages,
					);
				}
			});
		}
	}

	// Source Attachments (PDFs, etc.)
	if (
		Array.isArray(data.source_attachments) &&
		data.source_attachments.length > 0
	) {
		data.source_attachments.forEach((attachment) => {
			if (typeof addAttachmentToUI === "function") {
				addAttachmentToUI(attachment);
			}
		});
	}

	window.showToast?.("{{ _("Pattern imported successfully") }}", "success");
	window.unsavedChanges?.setDirty(true);
	document
		.getElementById("name")
		.scrollIntoView({ behavior: "smooth", block: "center" });
}

document.getElementById("projectForm").addEventListener("submit", function (e) {
	const currentLink = document.getElementById("link")?.value.trim() || "";
	const needlesInput = document.getElementById("recommended_needles");
	if (needlesInput && typeof needlesInput.value === "string") {
		needlesInput.value = needlesInput.value.trim();
	}

	// If a link was added to a project that didn't have one, prompt for import
	if (!initialLink && currentLink && !isImportPrompted) {
		e.preventDefault();

		window.confirmAction(
			"{{ _("Import Data?") }}",
			"{{ _("You added a pattern source. Would you like to import details (notes, steps, etc.) from this URL before saving?") }}",
			() => {
				isImportPrompted = true;
				importFromUrl(currentLink);
			},
			() => {
				isImportPrompted = true;
				// Gather steps data manually since we are bypassing the submit event
				const steps = [];
				const container = document.getElementById("stepsContainer");
				const items = container ? container.querySelectorAll(".step-item") : [];

				items.forEach((item, index) => {
					const pendingImages = Array.from(
						item.querySelectorAll("[data-pending-url]"),
					).map((img) => img.dataset.pendingUrl);
					steps.push({
						id: item.getAttribute("data-step-id") || null,
						title: item.querySelector(".step-title").value,
						description: item.querySelector(".step-description").value,
						step_number: index + 1,
						image_urls: pendingImages,
					});
				});
				document.getElementById("stepsData").value = JSON.stringify(steps);
				this.submit();
			},
			{
				confirmText: "{{ _("Import") }}",
				cancelText: "{{ _("Just Save") }}",
			},
		);
		return;
	}

	const steps = [];
	const container = document.getElementById("stepsContainer");
	const items = container ? container.querySelectorAll(".step-item") : [];

	if (items.length > 0) {
		items.forEach((item, index) => {
			const pendingImages = Array.from(
				item.querySelectorAll("[data-pending-url]"),
			).map((img) => img.dataset.pendingUrl);
			steps.push({
				id: item.getAttribute("data-step-id") || null,
				title: item.querySelector(".step-title").value,
				description: item.querySelector(".step-description").value,
				step_number: index + 1,
				image_urls: pendingImages,
			});
		});
	}
	document.getElementById("stepsData").value = JSON.stringify(steps);
});

document.addEventListener("DOMContentLoaded", () => {
	const importDialog = document.getElementById("importDialog");
	const importForm = document.getElementById("importForm");
	const importLoading = document.getElementById("importLoading");

	let currentTab = "url";
	const tabs = document.querySelectorAll(".tabs .tab");
	const tabContents = document.querySelectorAll(".tab-content");

	tabs.forEach((tab) => {
		tab.addEventListener("click", (e) => {
			e.preventDefault();
			const tabName = tab.dataset.tab;

			tabs.forEach((t) => t.classList.remove("tab-active"));
			tab.classList.add("tab-active");

			tabContents.forEach((content) => {
				if (content.id === `tab-${tabName}`) {
					content.classList.remove("hidden");
				} else {
					content.classList.add("hidden");
				}
			});

			currentTab = tabName;
		});
	});

	const dropZone = document.getElementById("dropZone");
	const fileInput = document.getElementById("importFile");
	const dropZoneContent = document.getElementById("dropZoneContent");
	const filePreview = document.getElementById("filePreview");
	const fileName = document.getElementById("fileName");
	const removeFileBtn = document.getElementById("removeFile");

	if (dropZone && fileInput) {
		dropZone.addEventListener("click", (e) => {
			if (e.target !== removeFileBtn && !removeFileBtn.contains(e.target)) {
				fileInput.click();
			}
		});

		fileInput.addEventListener("change", (e) => {
			const file = e.target.files[0];
			if (file) {
				showFilePreview(file);
			}
		});

		dropZone.addEventListener("dragover", (e) => {
			e.preventDefault();
			dropZone.classList.add("border-primary", "bg-base-200");
		});

		dropZone.addEventListener("dragleave", (e) => {
			e.preventDefault();
			dropZone.classList.remove("border-primary", "bg-base-200");
		});

		dropZone.addEventListener("drop", (e) => {
			e.preventDefault();
			dropZone.classList.remove("border-primary", "bg-base-200");

			const files = e.dataTransfer.files;
			if (files.length > 0) {
				const dataTransfer = new DataTransfer();
				dataTransfer.items.add(files[0]);
				fileInput.files = dataTransfer.files;
				showFilePreview(files[0]);
			}
		});

		if (removeFileBtn) {
			removeFileBtn.addEventListener("click", (e) => {
				e.stopPropagation();
				fileInput.value = "";
				hideFilePreview();
			});
		}

		function showFilePreview(file) {
			fileName.textContent = file.name;
			dropZoneContent.classList.add("hidden");
			filePreview.classList.remove("hidden");
		}

		function hideFilePreview() {
			dropZoneContent.classList.remove("hidden");
			filePreview.classList.add("hidden");
			fileName.textContent = "";
		}
	}

	if (importForm) {
		importForm.addEventListener("submit", async (e) => {
			e.preventDefault();

			const formData = new FormData();

			if (currentTab === "url") {
				const url = document.getElementById("importUrl").value.trim();
				if (!url) {
					window.showToast?.("{{ _('Please enter a URL') }}", "error");
					return;
				}
				formData.append("url", url);
				formData.append("type", "url");
				const existingProjectId = {{ (project.id if project else None) | tojson }};
				if (existingProjectId) {
					formData.append("project_id", String(existingProjectId));
				}
			} else if (currentTab === "file") {
				const fileInput = document.getElementById("importFile");
				const file = fileInput.files[0];
				if (!file) {
					window.showToast?.("{{ _('Please select a file') }}", "error");
					return;
				}
				formData.append("file", file);
				formData.append("type", "file");
			} else if (currentTab === "text") {
				const text = document.getElementById("importText").value.trim();
				if (!text) {
					window.showToast?.("{{ _('Please enter some text') }}", "error");
					return;
				}
				formData.append("text", text);
				formData.append("type", "text");
			}

			importForm.classList.add("hidden");
			importLoading.classList.remove("hidden");

			try {
				console.log("Importing with type:", currentTab);

				const response = await fetch("/projects/import", {
					method: "POST",
					headers: {
						"X-CSRF-Token": "{{ csrf_token }}",
					},
					body: formData,
				});

				if (!response.ok) {
					let errorMsg = "Failed to import";
					try {
						const error = await response.json();
						errorMsg = error.detail || errorMsg;
					} catch (e) {
						errorMsg = `HTTP ${response.status}: ${response.statusText}`;
					}
					throw new Error(errorMsg);
				}

				const data = await response.json();
				console.log("Import successful, data:", data);

				// Prevent re-prompting on save
				isImportPrompted = true;

				if (data.ai_fallback) {
					window.showToast?.("{{ _('AI extraction failed - using basic parser') }}", "warning");
				}

				// Map fields helper
				const setFieldValue = (id, value) => {
					const el = document.getElementById(id);
					if (el && value) {
						if (id === "notes" && el.value.trim().length > 0) return false;
						el.value = value;
						if (typeof autoResize === "function") autoResize(el);
						return true;
					}
					return false;
				};

				// Metadata
				try {
					setFieldValue("name", data.title || data.name);
					setFieldValue("needles", data.needles);
					setFieldValue("recommended_needles", data.recommended_needles);
					setFieldValue("yarn_brand", data.brand);

					if (data.yarn || data.yarn_details) {
						window.yarnSelector?.selectByName(
							data.yarn,
							data.yarn_details || [],
						);
					}
					setFieldValue("gauge_stitches", data.gauge_stitches);
					setFieldValue("gauge_rows", data.gauge_rows);
					setFieldValue("stitch_sample", data.stitch_sample);
					setFieldValue("description", data.description);
					setFieldValue("notes", data.notes);

					if (currentTab === "url" && !data.link) {
						data.link = document.getElementById("importUrl").value.trim();
					}
					setFieldValue("link", data.link);
					if (data.link) initialLink = data.link;
				} catch (e) {
					console.error("Error mapping metadata", e);
				}

				// Project Image Previews
				try {
					const importImagesField =
						document.getElementById("import_image_urls");
					const imageUrls = Array.isArray(data.image_urls)
						? data.image_urls
						: [];
					const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
					if (importImagesField) {
						importImagesField.value = dedupedImageUrls.length
							? JSON.stringify(dedupedImageUrls)
							: "";
					}
					const archiveField = document.getElementById("archive_on_save");
					if (archiveField) {
						archiveField.value = "1";
					}

					if (dedupedImageUrls.length > 0) {
						window.showToast?.("{{ _("Imported images will be previewed in the gallery") }}", "info");
						dedupedImageUrls.forEach((url) => {
							if (typeof addPendingTitleImageToGallery === "function") {
								try {
									addPendingTitleImageToGallery(url);
								} catch (e) {
									console.error(e);
								}
							}
						});
					}
				} catch (e) {
					console.error("Error mapping images", e);
				}

				// Steps
				try {
					if (data.steps && data.steps.length > 0) {
						const stepsContainer = document.getElementById("stepsContainer");
						if (stepsContainer) {
							stepsContainer.innerHTML = "";
							data.steps.forEach((step, index) => {
								if (typeof addStep === "function") {
									try {
										addStep(
											step.title || `Step ${index + 1}`,
											step.description || "",
											step.images || [],
										);
									} catch (e) {
										console.error("Failed to add step:", e, step);
									}
								}
							});
						}
					}
				} catch (e) {
					console.error("Error processing steps", e);
				}

				importDialog.close();

				importForm.reset();
				importForm.classList.remove("hidden");
				importLoading.classList.add("hidden");

				if (fileInput) fileInput.value = "";
				if (typeof hideFilePreview === "function") hideFilePreview();

				window.showToast?.("{{ _("Pattern imported successfully") }}", "success");
				window.unsavedChanges?.setDirty(true);

				document
					.getElementById("name")
					.scrollIntoView({ behavior: "smooth", block: "center" });
			} catch (error) {
				console.error("Import failed:", error);
				window.showToast?.(
					error.message || "{{ _("Failed to import pattern") }}",
					"error",
				);

				importForm.classList.remove("hidden");
				importLoading.classList.add("hidden");
			}
		});
	}
});

async function deleteProject() {
	const url = new URL(
		"/projects/{{ project.id }}",
		window.location.origin,
	);
	const yarnIds = Array.from(
		document.querySelectorAll(
			"#deleteProjectDialog .exclusive-yarn-checkbox:checked",
		),
	).map((el) => el.value);
	yarnIds.forEach((id) => url.searchParams.append("delete_yarn_ids", id));

	try {
		const response = await fetch(url, {
			method: "DELETE",
			headers: {
				"X-CSRF-Token": "{{ csrf_token }}",
			},
		});

		if (response.ok) {
			window.location.href = "/projects";
			return;
		}

		if (response.status === 401 || response.status === 403) {
			window.showToast?.("{{ _("Your session has expired. Reload the page to continue.") }}", "error");
			document.getElementById("csrfErrorDialog")?.showModal?.();
			return;
		}

		window.showToast?.("{{ _("Failed to delete project") }}", "error");
	} catch (error) {
		window.showToast?.("{{ _("Failed to delete project") }}", "error");
	}
}

function setupExclusiveYarnDeleteSelection() {
	const dialog = document.getElementById("deleteProjectDialog");
	if (!dialog) return;

	const selectAll = dialog.querySelector("#select_all_exclusive_yarns");
	const yarnCheckboxes = Array.from(
		dialog.querySelectorAll(".exclusive-yarn-checkbox"),
	);
	if (!selectAll || yarnCheckboxes.length === 0) return;

	function updateSelectAllState() {
		const checkedCount = yarnCheckboxes.filter((cb) => cb.checked).length;
		if (checkedCount === 0) {
			selectAll.checked = false;
			selectAll.indeterminate = false;
		} else if (checkedCount === yarnCheckboxes.length) {
			selectAll.checked = true;
			selectAll.indeterminate = false;
		} else {
			selectAll.checked = false;
			selectAll.indeterminate = true;
		}
	}

	selectAll.addEventListener("change", () => {
		yarnCheckboxes.forEach((cb) => {
			cb.checked = selectAll.checked;
		});
		selectAll.indeterminate = false;
	});

	yarnCheckboxes.forEach((cb) => {
		cb.addEventListener("change", updateSelectAllState);
	});
}

document.addEventListener("DOMContentLoaded", () => {
	setupExclusiveYarnDeleteSelection();
});
