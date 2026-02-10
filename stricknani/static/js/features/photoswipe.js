// PhotoSwipe gallery wiring and OCR helper.
//
// Bootstrapped from `base.html` via a tiny inline ESM script that:
// - imports PhotoSwipeLightbox from the vendored ESM bundle
// - injects it into `window.STRICKNANI.photoswipe.PhotoSwipeLightbox`
// - imports this module

(() => {
	const cfg = window.STRICKNANI?.photoswipe || {};
	const i18n = window.STRICKNANI?.i18n || {};

	const t = (key, fallback = "") => {
		const value = i18n?.[key];
		if (typeof value === "string" && value.length) {
			return value;
		}
		return fallback;
	};

	const pswpModuleUrl = cfg.pswpModuleUrl;
	const ocrEndpoint = cfg.ocrEndpoint || "/utils/ocr";
	const PhotoSwipeLightbox = cfg.PhotoSwipeLightbox;

	if (!pswpModuleUrl || typeof PhotoSwipeLightbox !== "function") {
		return;
	}

	const lightboxes = window.pswpLightboxes || new Map();
	window.pswpLightboxes = lightboxes;

	const pswpThumbsLabel = t("pswpGalleryThumbnails", "Gallery thumbnails");
	const pswpThumbActionLabel = t("pswpOpenImage", "Open image");

	const initGallery = (gallery) => {
		if (!gallery) {
			return null;
		}

		const existing = lightboxes.get(gallery);
		if (existing) {
			return existing;
		}

		const lightbox = new PhotoSwipeLightbox({
			gallery,
			children: "a[data-pswp-width]",
			pswpModule: () => import(pswpModuleUrl),
		});

		// Auto-detect dimensions if they are missing or placeholders (1200).
		lightbox.addFilter("domItemData", (itemData, element) => {
			if (itemData.width === 1200 && itemData.height === 1200) {
				const img = element.querySelector("img");
				if (img?.naturalWidth && img.naturalHeight) {
					itemData.width = img.naturalWidth;
					itemData.height = img.naturalHeight;
				}
			}
			return itemData;
		});

		lightbox.on("uiRegister", () => {
			if (!lightbox.pswp?.ui) {
				return;
			}

			const pswp = lightbox.pswp;

			// Register Thumbs.
			pswp.ui.registerElement({
				name: "thumbs",
				order: 9,
				isButton: false,
				appendTo: "root",
				onInit: (el, pswpInstance) => {
					el.classList.add("pswp__thumbs");
					el.setAttribute("aria-label", pswpThumbsLabel);
					el.setAttribute("role", "toolbar");

					const buildItems = () => {
						const count =
							typeof pswpInstance.getNumItems === "function"
								? pswpInstance.getNumItems()
								: pswpInstance.options?.dataSource?.length ||
									pswpInstance.options?.dataSource?.items?.length ||
									0;
						el.innerHTML = "";
						if (!count || count <= 1) {
							return;
						}
						const fragment = document.createDocumentFragment();
						for (let index = 0; index < count; index += 1) {
							const item =
								typeof pswpInstance.getItemData === "function"
									? pswpInstance.getItemData(index)
									: pswpInstance.options?.dataSource?.[index] ||
										pswpInstance.options?.dataSource?.items?.[index] ||
										{};
							const thumbSrc = item?.msrc || item?.src;
							if (!thumbSrc) {
								continue;
							}
							const button = document.createElement("button");
							button.type = "button";
							button.className = "pswp__thumb";
							button.setAttribute(
								"aria-label",
								`${pswpThumbActionLabel} ${index + 1}`,
							);
							button.setAttribute("data-pswp-thumb-index", `${index}`);
							const img = document.createElement("img");
							img.src = thumbSrc;
							img.alt = item?.alt || item?.title || "";
							button.appendChild(img);
							button.addEventListener("click", () => {
								pswpInstance.goTo(index);
							});
							fragment.appendChild(button);
						}
						el.appendChild(fragment);
					};

					const updateActive = () => {
						const activeIndex = pswpInstance.currIndex || 0;
						el.querySelectorAll("[data-pswp-thumb-index]").forEach((button) => {
							const buttonIndex = Number.parseInt(
								button.getAttribute("data-pswp-thumb-index") || "0",
								10,
							);
							button.classList.toggle("is-active", buttonIndex === activeIndex);
						});
					};

					buildItems();
					updateActive();
					pswpInstance.on("change", updateActive);
				},
			});

			// Register "Set as primary" button.
			pswp.ui.registerElement({
				name: "promote-button",
				ariaLabel: t("pswpSetAsPrimary", "Set as primary"),
				order: 9,
				isButton: true,
				html: '<span class="pswp__icn mdi mdi-star"></span>',
				appendTo: "bar",
				onClick: () => {
					const item = pswp.currItem;
					const element =
						item?.element ||
						item?.data?.element ||
						pswp.currSlide?.data?.element;
					if (!element) {
						return;
					}
					const customEvent = new CustomEvent("pswp:promote", {
						detail: { element },
						bubbles: true,
					});
					element.dispatchEvent(customEvent);
				},
				onInit: (el, pswpInstance) => {
					const update = () => {
						const item = pswpInstance.currItem;
						const element =
							item?.element ||
							item?.data?.element ||
							pswpInstance.currSlide?.data?.element;
						const isPromotable = element?.hasAttribute("data-pswp-promote");
						const isAlreadyPrimary =
							element?.getAttribute("data-pswp-is-primary") === "true";

						el.style.display = isPromotable ? "inline-flex" : "none";
						el.classList.toggle("is-primary", isAlreadyPrimary);
					};
					pswpInstance.on("change", update);
					pswpInstance.on("afterInit", update);
				},
			});

			// Register Delete button.
			pswp.ui.registerElement({
				name: "delete-button",
				ariaLabel: t("pswpDeleteImage", "Delete image"),
				order: 8,
				isButton: true,
				html: '<span class="pswp__icn mdi mdi-delete"></span>',
				appendTo: "bar",
				onClick: () => {
					const item = pswp.currItem;
					const element =
						item?.element ||
						item?.data?.element ||
						pswp.currSlide?.data?.element;
					if (!element) {
						return;
					}
					const customEvent = new CustomEvent("pswp:delete", {
						detail: { element },
						bubbles: true,
					});
					element.dispatchEvent(customEvent);
				},
				onInit: (el, pswpInstance) => {
					const update = () => {
						const item = pswpInstance.currItem;
						const element =
							item?.element ||
							item?.data?.element ||
							pswpInstance.currSlide?.data?.element;
						const isDeletable = element?.hasAttribute("data-pswp-delete");
						el.style.display = isDeletable ? "inline-flex" : "none";
					};
					pswpInstance.on("change", update);
					pswpInstance.on("afterInit", update);
				},
			});

			// Register Download button.
			pswp.ui.registerElement({
				name: "download-button",
				ariaLabel: t("pswpDownloadImage", "Download image"),
				order: 7,
				isButton: true,
				tagName: "a",
				html: '<span class="pswp__icn mdi mdi-download"></span>',
				appendTo: "bar",
				onInit: (el, pswpInstance) => {
					el.setAttribute("download", "");
					el.setAttribute("target", "_blank");
					el.setAttribute("rel", "noopener");

					const update = () => {
						const href = pswpInstance.currSlide?.data?.src || "";
						el.href = href;
						el.setAttribute("href", href);
					};
					pswpInstance.on("change", update);
					pswpInstance.on("afterInit", update);
				},
			});

			// Register OCR button (opens a dialog outside PhotoSwipe).
			pswp.ui.registerElement({
				name: "ocr-button",
				ariaLabel: t("pswpExtractText", "Extract text"),
				order: 6,
				isButton: true,
				html: '<span class="pswp__icn mdi mdi-text-recognition"></span>',
				appendTo: "wrapper",
				onClick: async (event) => {
					event.preventDefault();
					event.stopPropagation();

					const dialog = document.getElementById("pswpOcrDialog");
					const statusEl = document.getElementById("pswpOcrStatus");
					const loadingStatusEl = document.getElementById(
						"pswpOcrLoadingStatus",
					);
					const textEl = document.getElementById("pswpOcrText");
					const filenameEl = document.getElementById("pswpOcrFilename");
					const copyBtn = document.getElementById("pswpOcrCopy");
					const retryBtn = document.getElementById("pswpOcrRetry");
					const overlayEl = document.getElementById("pswpOcrLoadingOverlay");

					if (
						!dialog ||
						!statusEl ||
						!loadingStatusEl ||
						!textEl ||
						!filenameEl ||
						!copyBtn ||
						!retryBtn ||
						!overlayEl
					) {
						return;
					}

					const data =
						pswp.currSlide?.data || pswp.currItem?.data || pswp.currItem || {};
					const src = data?.src || data?.msrc || "";
					const alt = data?.alt || data?.title || "";

					const setLoading = (isLoading, message = "") => {
						dialog.setAttribute("aria-busy", isLoading ? "true" : "false");
						overlayEl.classList.toggle("hidden", !isLoading);
						loadingStatusEl.textContent = message;
						if (isLoading) {
							copyBtn.disabled = true;
							retryBtn.disabled = true;
						} else {
							retryBtn.disabled = false;
						}
					};

					const renderResult = (text, status) => {
						statusEl.textContent = status || "";
						textEl.textContent = text || "";
						copyBtn.disabled = !text;
					};

					const runOcr = async (force = false) => {
						if (!src) {
							renderResult(
								"",
								t(
									"pswpImageCannotBeProcessed",
									"This image cannot be processed.",
								),
							);
							return;
						}

						setLoading(true, t("pswpExtractingText", "Extracting text..."));
						renderResult("", "");

						const csrfToken = document
							.querySelector('meta[name="csrf-token"]')
							?.getAttribute("content");
						const headers = {
							"Content-Type": "application/json",
							...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
						};

						try {
							const response = await fetch(ocrEndpoint, {
								method: "POST",
								headers,
								body: JSON.stringify({ src, force }),
							});

							if (!response.ok) {
								const payload = await response.json().catch(() => ({}));
								const detail = payload?.detail || "";
								if (detail === "ocr_not_available") {
									statusEl.textContent = t(
										"pswpOcrNotAvailable",
										"OCR is not available on this server.",
									);
								} else if (detail === "invalid_src") {
									statusEl.textContent = t(
										"pswpImageCannotBeProcessed",
										"This image cannot be processed.",
									);
								} else {
									statusEl.textContent = t("pswpOcrFailed", "OCR failed.");
								}
								return;
							}

							const payload = await response.json();
							const text = (payload?.text || "").trim();
							if (!text) {
								renderResult("", t("pswpNoTextDetected", "No text detected."));
								return;
							}

							renderResult(text, t("pswpTextExtracted", "Text extracted."));
						} catch (error) {
							console.error("OCR failed", error);
							renderResult("", t("pswpOcrFailed", "OCR failed."));
						} finally {
							setLoading(false);
						}
					};

					filenameEl.textContent = alt;
					dialog.showModal();

					copyBtn.onclick = async () => {
						const fullText = textEl.textContent || "";
						const selection = window.getSelection?.();
						const selectedText = selection ? selection.toString() : "";
						const isSelectionInsideTextEl = (() => {
							if (!selection || selection.rangeCount === 0) {
								return false;
							}
							const range = selection.getRangeAt(0);
							const container = range.commonAncestorContainer;
							const node =
								container.nodeType === Node.ELEMENT_NODE
									? container
									: container.parentElement;
							return !!(node && textEl.contains(node));
						})();

						const copyUsingSelection = (range) => {
							if (!selection) {
								return false;
							}
							const previousRanges = [];
							for (let i = 0; i < selection.rangeCount; i += 1) {
								previousRanges.push(selection.getRangeAt(i).cloneRange());
							}
							selection.removeAllRanges();
							selection.addRange(range);
							const ok = document.execCommand("copy");
							selection.removeAllRanges();
							for (const previousRange of previousRanges) {
								selection.addRange(previousRange);
							}
							return ok;
						};

						// In some browsers, Clipboard API writes can fail inside <dialog>, but copying the
						// current selection via execCommand still works. Prefer selection-based copying.
						if (isSelectionInsideTextEl && selectedText) {
							const ok = document.execCommand("copy");
							if (ok) {
								window.showToast?.(
									t("copiedToClipboard", "Copied to clipboard"),
									"success",
								);
								return;
							}
							if (typeof window.copyToClipboard === "function") {
								await window.copyToClipboard(selectedText, copyBtn);
								return;
							}
							window.showToast?.(t("failedToCopy", "Failed to copy"), "error");
							return;
						}

						if (fullText) {
							const range = document.createRange();
							range.selectNodeContents(textEl);
							const ok = copyUsingSelection(range);
							if (ok) {
								window.showToast?.(
									t("copiedToClipboard", "Copied to clipboard"),
									"success",
								);
								return;
							}
						}

						if (typeof window.copyToClipboard === "function") {
							await window.copyToClipboard(fullText, copyBtn);
						} else {
							window.showToast?.(t("failedToCopy", "Failed to copy"), "error");
						}
					};

					retryBtn.onclick = async () => {
						await runOcr(true);
					};

					await runOcr(false);
				},
			});

			// Register Crop button (only for editable images on edit pages).
			pswp.ui.registerElement({
				name: "crop-button",
				ariaLabel: t("pswpCropImage", "Crop image"),
				order: 9,
				isButton: true,
				html: '<span class="pswp__icn mdi mdi-crop"></span>',
				appendTo: "bar",
				onClick: () => {
					const item = pswp.currItem;
					const element =
						item?.element ||
						item?.data?.element ||
						pswp.currSlide?.data?.element;
					if (!element) {
						return;
					}
					const customEvent = new CustomEvent("pswp:crop", {
						detail: { element, pswp },
						bubbles: true,
					});
					element.dispatchEvent(customEvent);
				},
				onInit: (el, pswpInstance) => {
					const update = () => {
						const item = pswpInstance.currItem;
						const element =
							item?.element ||
							item?.data?.element ||
							pswpInstance.currSlide?.data?.element;
						const isCroppable = element?.hasAttribute("data-pswp-crop");
						el.style.display = isCroppable ? "inline-flex" : "none";
					};
					pswpInstance.on("change", update);
					pswpInstance.on("afterInit", update);
				},
			});
		});

		lightbox.init();
		lightboxes.set(gallery, lightbox);
		return lightbox;
	};

	window.initPhotoSwipeGallery = initGallery;
	window.refreshPhotoSwipeGallery = (gallery) => {
		const instance = initGallery(gallery);
		if (instance) {
			if (typeof instance.refresh === "function") {
				instance.refresh();
			} else if (typeof instance.destroy === "function") {
				instance.destroy();
				lightboxes.delete(gallery);
				return initGallery(gallery);
			}
		}
		return instance;
	};

	const initAll = () => {
		document.querySelectorAll("[data-pswp-gallery]").forEach((gallery) => {
			initGallery(gallery);
		});
	};

	const markdownLightboxes = new Map();
	const openMarkdownLightbox = (image) => {
		if (!image) {
			return;
		}
		const group = image.getAttribute("data-lightbox-group") || "markdown";
		const images = Array.from(
			document.querySelectorAll(
				`img.markdown-inline-image[data-lightbox-group="${group}"]`,
			),
		);
		if (!images.length) {
			return;
		}

		const items = images.map((img) => {
			const src = img.getAttribute("data-lightbox-src") || img.src;
			const alt = img.getAttribute("data-lightbox-alt") || img.alt || "";
			const width = img.naturalWidth || 1200;
			const height = img.naturalHeight || 1200;
			return {
				src,
				msrc: img.src,
				width,
				height,
				alt,
				title: alt,
				element: img,
			};
		});

		const index = Math.max(0, images.indexOf(image));
		const existing = markdownLightboxes.get(group);
		if (existing) {
			existing.destroy();
			markdownLightboxes.delete(group);
		}

		const lightbox = new PhotoSwipeLightbox({
			dataSource: items,
			pswpModule: () => import(pswpModuleUrl),
		});
		lightbox.on("close", () => {
			lightbox.destroy();
			markdownLightboxes.delete(group);
		});
		lightbox.init();
		markdownLightboxes.set(group, lightbox);
		lightbox.loadAndOpen(index);
	};

	const openAtIndex = (trigger) => {
		const gallery = trigger.closest("[data-pswp-gallery]");
		if (!gallery) {
			return;
		}

		const raw = trigger.getAttribute("data-pswp-open-index") || "0";
		const index = Number.parseInt(raw, 10);
		const targetIndex = Number.isNaN(index) ? 0 : index;
		const anchors = Array.from(gallery.querySelectorAll("a[data-pswp-width]"));
		const target = anchors[targetIndex];
		if (target) {
			target.click();
			return;
		}

		const lightbox = window.refreshPhotoSwipeGallery(gallery);
		if (lightbox) {
			lightbox.loadAndOpen(0, gallery);
		}
	};

	window.openPhotoSwipeIndex = (trigger) => {
		if (!trigger) {
			return;
		}
		openAtIndex(trigger);
	};

	document.addEventListener("click", (event) => {
		const markdownImage = event.target.closest("img.markdown-inline-image");
		if (markdownImage) {
			const src =
				markdownImage.getAttribute("data-lightbox-src") ||
				markdownImage.src ||
				"";
			if (
				src.includes("/drops/symbols/") ||
				src.includes("/inline/garnstudio-symbols/")
			) {
				return;
			}
			event.preventDefault();
			event.stopPropagation();
			openMarkdownLightbox(markdownImage);
			return;
		}

		const trigger = event.target.closest("[data-pswp-open-index]");
		if (!trigger) {
			return;
		}
		if (
			event.target.closest("a,button,input,select,textarea,label,[data-call]")
		) {
			return;
		}
		event.preventDefault();
		event.stopPropagation();
		openAtIndex(trigger);
	});

	// Crop handling
	(() => {
		let cropper = null;
		let currentPswp = null;
		let currentImageElement = null;

		const dialog = document.getElementById("pswpCropDialog");
		const image = document.getElementById("pswpCropImage");
		const filenameEl = document.getElementById("pswpCropFilename");
		const saveBtn = document.getElementById("pswpCropSave");
		const resetBtn = document.getElementById("pswpCropReset");

		if (!dialog || !image || !saveBtn) {
			return;
		}

		function getCropperCtor() {
			const cropperNamespace = window.Cropper;
			const ctor = cropperNamespace?.default || cropperNamespace;
			if (typeof ctor !== "function") {
				return null;
			}
			return ctor;
		}

		function destroyCropper() {
			if (cropper) {
				cropper.destroy();
				cropper = null;
			}
		}

		function initCropper(src) {
			destroyCropper();

			const ctor = getCropperCtor();
			if (!ctor) {
				window.showToast?.(
					t("imageCropperFailedToLoad", "Image cropper failed to load"),
					"error",
				);
				dialog.close();
				return;
			}

			// Wait for image to load before initializing cropper
			image.onload = () => {
				// Reset styles to ensure clean state
				image.removeAttribute("style");
				image.classList.remove("opacity-0");
				image.style.display = "block";
				image.style.maxWidth = "100%";
				image.style.maxHeight = "100%";
				image.style.width = "auto";
				image.style.height = "auto";

				// Ensure we initialize after the crop container has a real layout size.
				const container = document.getElementById("pswpCropContainer");
				if (
					container &&
					(container.clientWidth === 0 || container.clientHeight === 0)
				) {
					requestAnimationFrame(() => initCropper(src));
					return;
				}
				cropper = new ctor(image, {
					viewMode: 1,
					dragMode: "move",
					autoCropArea: 1.0,
					minContainerWidth: 0,
					minContainerHeight: 0,
					restore: false,
					guides: true,
					center: true,
					highlight: false,
					cropBoxMovable: true,
					cropBoxResizable: true,
					toggleDragModeOnDblclick: false,
					background: false, // We provide our own background pattern
				});
			};
			image.src = src;
		}

		document.addEventListener("pswp:crop", (e) => {
			const element = e.detail?.element;
			currentPswp = e.detail?.pswp;
			currentImageElement = element;

			// Try to find the best source for the full resolution image
			let src = "";
			let alt = "";

			if (currentPswp?.currItem) {
				// PhotoSwipe item source is the most reliable for full resolution
				src = currentPswp.currItem.src || currentPswp.currItem.data?.src;
				alt = currentPswp.currItem.alt || currentPswp.currItem.data?.alt;
			}

			if (!src && element) {
				const anchor = element.closest("a[data-pswp-width]") || element;
				src = anchor.getAttribute("href") || anchor.src || "";
				alt = anchor.getAttribute("data-pswp-caption") || "";
			}

			if (!src) {
				console.error("Could not find image source for cropping");
				return;
			}

			// Hack: if the source is a thumbnail (contains 'thumb_'), try to guess original
			// This is a safety fallback if we somehow got the wrong URL
			if (src.includes("/thumbnails/") && src.includes("thumb_")) {
				src = src.replace("/thumbnails/", "/").replace("thumb_", "");
				// Handle extension fix if needed, but usually thumb matches.
				// We'll trust the primary resolution logic first.
			}

			filenameEl.textContent = alt || src.split("/").pop() || "";

			// Show modal BEFORE initializing cropper to ensure correct dimensions
			dialog.showModal();

			// Use requestAnimationFrame to ensure layout is updated
			requestAnimationFrame(() => {
				initCropper(src);
			});

			if (currentPswp) {
				currentPswp.close();
			}
		});

		resetBtn?.addEventListener("click", () => {
			if (cropper && typeof cropper.reset === "function") {
				cropper.reset();
			}
		});

		dialog?.addEventListener("close", () => {
			destroyCropper();
			image.src = "";
			image.classList.add("opacity-0");
		});

		saveBtn?.addEventListener("click", async () => {
			if (!cropper || !currentImageElement) {
				return;
			}

			const csrfToken = document
				.querySelector('meta[name="csrf-token"]')
				?.getAttribute("content");

			let canvas;
			try {
				if (
					typeof cropper.getCroppedCanvas === "function" &&
					typeof cropper.getData === "function" &&
					typeof cropper.getImageData === "function"
				) {
					// Export at natural pixel density. Without this, some cropper
					// setups return CSS-pixel-sized crops (very small output files).
					const cropData = cropper.getData();
					const imageData = cropper.getImageData();
					const scaleX = imageData?.width
						? imageData.naturalWidth / imageData.width
						: 1;
					const scaleY = imageData?.height
						? imageData.naturalHeight / imageData.height
						: 1;
					const outputWidth = Math.max(
						1,
						Math.round((cropData?.width || 1) * scaleX),
					);
					const outputHeight = Math.max(
						1,
						Math.round((cropData?.height || 1) * scaleY),
					);
					canvas = cropper.getCroppedCanvas({
						width: outputWidth,
						height: outputHeight,
					});
				} else if (typeof cropper.getCroppedCanvas === "function") {
					canvas = cropper.getCroppedCanvas();
				} else if (typeof cropper.getCropperSelection === "function") {
					const selection = cropper.getCropperSelection();
					if (selection && typeof selection.$toCanvas === "function") {
						let outWidth;
						let outHeight;
						const rect =
							typeof selection.getBoundingClientRect === "function"
								? selection.getBoundingClientRect()
								: null;
						const displayW = image.clientWidth || rect?.width || 0;
						const displayH = image.clientHeight || rect?.height || 0;
						if (rect && displayW > 0 && displayH > 0) {
							const ratioX = image.naturalWidth / displayW;
							const ratioY = image.naturalHeight / displayH;
							outWidth = Math.max(1, Math.round(rect.width * ratioX));
							outHeight = Math.max(1, Math.round(rect.height * ratioY));
						}
						canvas = await selection.$toCanvas({
							...(outWidth ? { width: outWidth } : {}),
							...(outHeight ? { height: outHeight } : {}),
						});
					}
				}
			} catch (e) {
				console.error("Failed to generate canvas", e);
			}

			if (!canvas) {
				window.showToast?.(
					t("failedToUploadImage", "Failed to process image"),
					"error",
				);
				return;
			}

			const originalText = saveBtn.innerHTML;
			saveBtn.innerHTML =
				'<span class="loading loading-spinner loading-sm"></span> ' +
				t("uploading", "Uploading...");
			saveBtn.disabled = true;

			// Show loading overlay
			const loadingOverlay = document.getElementById("pswpCropLoading");
			if (loadingOverlay) {
				loadingOverlay.classList.remove("opacity-0", "pointer-events-none");
			}

			canvas.toBlob(async (blob) => {
				if (!blob) {
					window.showToast?.(
						t("failedToUploadImage", "Failed to process image"),
						"error",
					);
					saveBtn.innerHTML = originalText;
					saveBtn.disabled = false;
					if (loadingOverlay)
						loadingOverlay.classList.add("opacity-0", "pointer-events-none");
					return;
				}

				const anchor =
					currentImageElement.closest("a[data-pswp-width]") ||
					currentImageElement;
				const originalSrc = anchor.getAttribute("href") || "";

				const formData = new FormData();
				formData.append("file", blob, "cropped.png");
				formData.append("original_src", originalSrc);

				try {
					const response = await fetch("/utils/crop-image", {
						method: "POST",
						body: formData,
						headers: csrfToken ? { "X-CSRF-Token": csrfToken } : {},
					});

					if (!response.ok) {
						throw new Error("Upload failed");
					}

					await response.json();
					window.showToast?.(
						t("imageCroppedSuccessfully", "Image cropped successfully"),
						"success",
					);
					dialog.close();

					// Refresh the page to show the new cropped image.
					// Adding a timestamp to ensure fresh load if needed, though reload() usually works.
					window.location.reload();
				} catch (err) {
					console.error(err);
					window.showToast?.(
						t("failedToUploadImage", "Failed to upload image"),
						"error",
					);
					saveBtn.innerHTML = originalText;
					saveBtn.disabled = false;
					if (loadingOverlay)
						loadingOverlay.classList.add("opacity-0", "pointer-events-none");
				}
			}, "image/png");
		});
	})();

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", () => {
			initAll();
		});
	} else {
		initAll();
	}
})();
