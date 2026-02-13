import { Editor, mergeAttributes } from "https://esm.sh/@tiptap/core@3.19.0";
import Image from "https://esm.sh/@tiptap/extension-image@3.19.0";
import Link from "https://esm.sh/@tiptap/extension-link@3.19.0";
import Underline from "https://esm.sh/@tiptap/extension-underline@3.19.0";
import { Markdown } from "https://esm.sh/@tiptap/markdown@3.19.0";
import {
	chainCommands,
	createParagraphNear,
	liftEmptyBlock,
	newlineInCode,
	splitBlock,
} from "https://esm.sh/@tiptap/pm@3.19.0/commands?target=es2022";
import StarterKit from "https://esm.sh/@tiptap/starter-kit@3.19.0";

const WYSIWYG_INSTANCES = new Map();
let currentImageAutocomplete = null;
let currentInsertMarker = null;
let currentInternalImageDrag = null;

const SizedImage = Image.extend({
	addNodeView() {
		return ({ node, editor, getPos }) => {
			const wrapper = document.createElement("span");
			wrapper.className = "wysiwyg-image-node";

			const img = document.createElement("img");
			img.className = this.options.HTMLAttributes?.class || "";
			img.draggable = true;

			const broken = document.createElement("span");
			broken.className = "wysiwyg-image-broken";

			const brokenIcon = document.createElement("span");
			brokenIcon.className = "mdi mdi-image-broken-variant";

			const brokenText = document.createElement("span");
			brokenText.className = "wysiwyg-image-broken-text";

			broken.appendChild(brokenIcon);
			broken.appendChild(brokenText);

			const del = document.createElement("button");
			del.type = "button";
			del.className = "wysiwyg-image-delete";
			del.setAttribute("aria-label", getI18n("delete", "Delete"));
			del.innerHTML = '<span class="mdi mdi-close"></span>';

			function applyAttrs() {
				// Reset broken state when content updates.
				wrapper.classList.remove("is-broken");

				img.setAttribute("src", node.attrs.src || "");
				if (node.attrs.alt) {
					img.setAttribute("alt", node.attrs.alt);
				} else {
					img.setAttribute("alt", "");
				}

				const src = String(node.attrs.src || "");
				const filename = src.split("/").filter(Boolean).pop() || "";
				brokenText.textContent =
					node.attrs.alt || filename || getI18n("image", "Image");

				const rawTitle =
					typeof node.attrs.title === "string" ? node.attrs.title : "";
				const match = rawTitle.match(/\bsn:size=(sm|md|lg|xl)\b/);
				const snSize = match ? match[1] : "sm";
				const cleanedTitle = rawTitle
					.replace(/\bsn:size=(sm|md|lg|xl)\b/g, "")
					.replace(/\s+/g, " ")
					.trim();

				if (cleanedTitle) {
					img.setAttribute("title", cleanedTitle);
				} else {
					img.removeAttribute("title");
				}
				img.setAttribute("data-sn-size", snSize);
			}

			applyAttrs();

			img.addEventListener("error", () => {
				// When an image URL no longer exists (deleted) the browser shows the alt
				// text; hide the img and show an explicit broken-image placeholder.
				wrapper.classList.add("is-broken");
			});

			img.addEventListener("load", () => {
				wrapper.classList.remove("is-broken");
			});

			wrapper.addEventListener("dragstart", (e) => {
				// Enable insertion marker for internal image moves (ProseMirror drag).
				currentInternalImageDrag = { editor };
				try {
					e.dataTransfer?.setData(
						"text/plain",
						`![${node.attrs.alt || ""}](${node.attrs.src || ""})`,
					);
					e.dataTransfer.effectAllowed = "move";
				} catch (_err) {
					// Ignore.
				}
			});

			wrapper.addEventListener("dragend", () => {
				currentInternalImageDrag = null;
				hideInsertMarker();
			});

			del.addEventListener("mousedown", (e) => {
				e.preventDefault();
				e.stopPropagation();
			});
			del.addEventListener("click", (e) => {
				e.preventDefault();
				e.stopPropagation();
				const pos = typeof getPos === "function" ? getPos() : null;
				if (typeof pos !== "number") return;
				const tr = editor.state.tr.delete(pos, pos + node.nodeSize);
				editor.view.dispatch(tr);
				editor.commands.focus();
			});

			// Touch move: long-press then drag to reposition inside the editor.
			// (Native HTML5 drag-and-drop is unreliable on mobile.)
			const isCoarsePointer =
				window.matchMedia?.("(pointer: coarse)")?.matches ||
				"ontouchstart" in window;
			if (isCoarsePointer) {
				let pressTimer = null;
				let started = false;
				let startX = 0;
				let startY = 0;
				let ghost = null;

				function clearTimer() {
					if (pressTimer) {
						clearTimeout(pressTimer);
						pressTimer = null;
					}
				}

				function cleanup() {
					clearTimer();
					started = false;
					hideInsertMarker();
					if (ghost) {
						ghost.remove();
						ghost = null;
					}
				}

				function setGhostPosition(x, y) {
					if (!ghost) return;
					ghost.style.left = `${x + 12}px`;
					ghost.style.top = `${y + 12}px`;
				}

				function beginDrag(touch) {
					started = true;
					ghost = document.createElement("img");
					ghost.src = node.attrs.src || "";
					ghost.alt = "";
					ghost.style.position = "fixed";
					ghost.style.zIndex = "9999";
					ghost.style.width = "64px";
					ghost.style.height = "64px";
					ghost.style.objectFit = "cover";
					ghost.style.borderRadius = "10px";
					ghost.style.boxShadow = "0 10px 30px rgba(0,0,0,0.25)";
					ghost.style.opacity = "0.9";
					ghost.style.pointerEvents = "none";
					(document.body || document.documentElement).appendChild(ghost);
					setGhostPosition(touch.clientX, touch.clientY);
				}

				wrapper.addEventListener(
					"touchstart",
					(e) => {
						if (e.touches.length !== 1) return;
						started = false;
						const t = e.touches[0];
						startX = t.clientX;
						startY = t.clientY;
						clearTimer();
						pressTimer = setTimeout(() => beginDrag(t), 350);
					},
					{ passive: true },
				);

				wrapper.addEventListener(
					"touchmove",
					(e) => {
						if (e.touches.length !== 1) return;
						const t = e.touches[0];
						const dx = Math.abs(t.clientX - startX);
						const dy = Math.abs(t.clientY - startY);
						if (!started && (dx > 10 || dy > 10)) {
							// User is scrolling; cancel.
							clearTimer();
							return;
						}
						if (!started) return;
						e.preventDefault();
						setGhostPosition(t.clientX, t.clientY);

						const pos = editor.view.posAtCoords({
							left: t.clientX,
							top: t.clientY,
						})?.pos;
						if (
							typeof pos !== "number" ||
							!showInsertMarkerAtPos(editor, pos)
						) {
							showInsertMarkerAtClientPoint(t.clientX, t.clientY);
						}
					},
					{ passive: false },
				);

				wrapper.addEventListener(
					"touchend",
					(e) => {
						clearTimer();
						if (!started) return;
						e.preventDefault();
						e.stopPropagation();

						const touch = e.changedTouches?.[0];
						const from = typeof getPos === "function" ? getPos() : null;
						if (!touch || typeof from !== "number") {
							cleanup();
							return;
						}

						const coords = { left: touch.clientX, top: touch.clientY };
						const found = editor.view.posAtCoords(coords);
						const to = typeof found?.pos === "number" ? found.pos : from;

						const moved = node.type.create(node.attrs);
						const size = node.nodeSize;
						let insertPos = to;
						if (from < insertPos) {
							insertPos = Math.max(0, insertPos - size);
						}

						try {
							let tr = editor.state.tr.delete(from, from + size);
							tr = tr.insert(insertPos, moved);
							editor.view.dispatch(tr.scrollIntoView());
						} catch (err) {
							console.error("WYSIWYG: Failed to move image", err);
						}

						hideInsertMarker();
						cleanup();
					},
					{ passive: false },
				);

				wrapper.addEventListener("touchcancel", cleanup, { passive: true });
			}

			wrapper.appendChild(img);
			wrapper.appendChild(broken);
			wrapper.appendChild(del);

			return {
				dom: wrapper,
				contentDOM: null,
				update(updatedNode) {
					if (updatedNode.type.name !== node.type.name) return false;
					node = updatedNode;
					applyAttrs();
					return true;
				},
			};
		};
	},
	renderHTML({ HTMLAttributes }) {
		const attrs = { ...HTMLAttributes };
		const rawTitle = typeof attrs.title === "string" ? attrs.title : "";
		const match = rawTitle.match(/\bsn:size=(sm|md|lg|xl)\b/);
		const snSize = match ? match[1] : "sm";
		const cleanedTitle = rawTitle
			.replace(/\bsn:size=(sm|md|lg|xl)\b/g, "")
			.replace(/\s+/g, " ")
			.trim();

		// Don't leak our internal marker into the browser tooltip.
		if (cleanedTitle) {
			attrs.title = cleanedTitle;
		} else {
			delete attrs.title;
		}
		attrs["data-sn-size"] = snSize;

		return ["img", mergeAttributes(this.options.HTMLAttributes, attrs)];
	},
});

function getI18n(key, fallback) {
	return window.STRICKNANI?.i18n?.[key] || fallback;
}

function showInsertMarkerAtPos(editor, pos) {
	if (!editor || typeof pos !== "number") return false;
	let coords;
	try {
		coords = editor.view.coordsAtPos(pos);
	} catch (_err) {
		return false;
	}

	if (!coords) return false;
	if (!currentInsertMarker) {
		const el = document.createElement("div");
		el.className = "wysiwyg-insert-marker hidden";
		(document.body || document.documentElement).appendChild(el);
		currentInsertMarker = el;
	}

	const height = Math.max(12, (coords.bottom || coords.top + 16) - coords.top);
	// Shift slightly left so it's easier to see under a finger.
	currentInsertMarker.style.left = `${Math.max(0, coords.left - 2)}px`;
	currentInsertMarker.style.top = `${coords.top}px`;
	currentInsertMarker.style.height = `${height}px`;
	currentInsertMarker.classList.remove("hidden");
	return true;
}

function showInsertMarkerAtClientPoint(clientX, clientY) {
	if (typeof clientX !== "number" || typeof clientY !== "number") return;
	if (!currentInsertMarker) {
		const el = document.createElement("div");
		el.className = "wysiwyg-insert-marker hidden";
		(document.body || document.documentElement).appendChild(el);
		currentInsertMarker = el;
	}

	// Offset away from the finger/cursor so it's actually visible.
	const x = Math.max(0, clientX - 18);
	const y = Math.max(0, clientY - 12);
	currentInsertMarker.style.left = `${x}px`;
	currentInsertMarker.style.top = `${y}px`;
	currentInsertMarker.style.height = "24px";
	currentInsertMarker.classList.remove("hidden");
}

function hideInsertMarker() {
	if (currentInsertMarker) {
		currentInsertMarker.classList.add("hidden");
	}
}

function isImageFile(file) {
	return Boolean(
		file && typeof file.type === "string" && file.type.startsWith("image/"),
	);
}

function dataTransferHasFileDrag(dt) {
	if (!dt) return false;
	if (Array.isArray(dt.types) && dt.types.includes("Files")) return true;
	try {
		return Array.from(dt.items || []).some((item) => item.kind === "file");
	} catch (_err) {
		return false;
	}
}

function dataTransferHasImageFileDrag(dt) {
	if (!dt) return false;
	try {
		// In some browsers `dt.files` is empty during dragover; `dt.items` is reliable.
		if (Array.from(dt.files || []).some(isImageFile)) return true;
		return Array.from(dt.items || []).some(
			(item) => item.kind === "file" && item.type?.startsWith?.("image/"),
		);
	} catch (_err) {
		return false;
	}
}

function autoResizeTextarea(textarea) {
	if (!textarea) return;
	textarea.style.height = "auto";
	textarea.style.overflowY = "hidden";
	textarea.style.height = `${textarea.scrollHeight}px`;
}

function getDropInsertPos(view, event, fallbackPos) {
	try {
		const coords = { left: event.clientX, top: event.clientY };
		const result = view.posAtCoords(coords);
		if (result && typeof result.pos === "number") {
			return result.pos;
		}
	} catch (_err) {
		// Ignore and fall back.
	}
	return fallbackPos;
}

function extractDroppedText(event) {
	const dt = event.dataTransfer;
	if (!dt) return "";

	// Prefer markdown inserted by our own drag handlers.
	const textPlain = dt.getData("text/plain");
	if (textPlain) return textPlain;

	const uriList = dt.getData("text/uri-list");
	if (uriList) {
		// text/uri-list can contain multiple lines and comments starting with '#'
		const firstUrl = uriList
			.split("\n")
			.map((l) => l.trim())
			.find((l) => l && !l.startsWith("#"));
		return firstUrl || "";
	}

	return "";
}

function parseMarkdownImage(text) {
	// Conservative match for our own drag payload: ![alt](url)
	const re = /!\[([^\]]*)\]\(\s*([^\s)]+)(?:\s+"([^"]*)")?\s*\)/;
	const m = String(text || "").match(re);
	if (!m) return null;
	return {
		alt: m[1] || "",
		src: m[2] || "",
		title: m[3] || "",
	};
}

function isLikelyImageUrl(url) {
	const u = String(url || "").trim();
	if (!u) return false;
	if (u.startsWith("/media/")) return true;
	return /\.(png|jpe?g|gif|webp|avif|svg)(\?.*)?$/i.test(u);
}

function insertImageAt(editor, pos, { src, alt }) {
	const content = [
		{
			type: "image",
			attrs: {
				src,
				alt: alt || "",
			},
		},
		{ type: "paragraph" },
	];

	try {
		editor.commands.insertContentAt(pos, content, {
			updateSelection: true,
		});
		return true;
	} catch (err) {
		// If drop coords resolve to a non-text position, fallback to current selection.
		try {
			editor.chain().focus().insertContent(content).run();
			return true;
		} catch (_err2) {
			console.error("WYSIWYG: Failed to insert dropped image", err);
			return false;
		}
	}
}

function extractThumbnailPayload(tile) {
	if (!tile) return null;

	const pendingUrl = tile.getAttribute("data-pending-url");
	if (pendingUrl) {
		const img = tile.querySelector("img");
		return { url: pendingUrl, altText: img?.alt || "" };
	}

	const anchor = tile.querySelector("a[data-pswp-width]");
	const img = tile.querySelector("img");
	const url = anchor?.getAttribute("href") || "";
	if (!url) return null;
	return {
		url,
		altText: img?.alt || anchor?.getAttribute("data-pswp-caption") || "",
	};
}

function findWysiwygAtPoint(x, y) {
	const els = document.elementsFromPoint?.(x, y) || [];
	for (const el of els) {
		const container = el.closest?.("[data-wysiwyg]");
		if (container && WYSIWYG_INSTANCES.has(container)) {
			return { container, editor: WYSIWYG_INSTANCES.get(container) };
		}
	}
	return null;
}

function installThumbnailLongPressDrag() {
	// Mobile/touch: emulate "drag thumbnail into editor" with long-press + move.
	const isCoarsePointer =
		window.matchMedia?.("(pointer: coarse)")?.matches ||
		"ontouchstart" in window;
	if (!isCoarsePointer) return;

	const tiles = Array.from(
		document.querySelectorAll(
			"#titleImagesContainer [data-image-id], #stitchSampleImagesContainer [data-image-id], .step-images [data-image-id], #existing-photos-grid [id^='photo-card-'], [data-pending-url]",
		),
	);

	tiles.forEach((tile) => {
		if (tile.dataset.wysiwygTouchDragInstalled === "true") return;
		tile.dataset.wysiwygTouchDragInstalled = "true";

		const payload = extractThumbnailPayload(tile);
		if (!payload?.url) return;

		let pressTimer = null;
		let started = false;
		let startX = 0;
		let startY = 0;
		let ghost = null;
		let lastTarget = null;

		function clearTimer() {
			if (pressTimer) {
				clearTimeout(pressTimer);
				pressTimer = null;
			}
		}

		function cleanup() {
			clearTimer();
			started = false;
			if (ghost) {
				ghost.remove();
				ghost = null;
			}
			if (lastTarget?.container) {
				lastTarget.container.classList.remove("ring-2", "ring-primary");
			}
			lastTarget = null;
		}

		function setGhostPosition(x, y) {
			if (!ghost) return;
			ghost.style.left = `${x + 12}px`;
			ghost.style.top = `${y + 12}px`;
		}

		function beginDrag(touch) {
			started = true;

			ghost = document.createElement("img");
			ghost.src = payload.url;
			ghost.alt = "";
			ghost.style.position = "fixed";
			ghost.style.zIndex = "9999";
			ghost.style.width = "64px";
			ghost.style.height = "64px";
			ghost.style.objectFit = "cover";
			ghost.style.borderRadius = "10px";
			ghost.style.boxShadow = "0 10px 30px rgba(0,0,0,0.25)";
			ghost.style.opacity = "0.9";
			ghost.style.pointerEvents = "none";
			(document.body || document.documentElement).appendChild(ghost);
			setGhostPosition(touch.clientX, touch.clientY);
		}

		tile.addEventListener(
			"touchstart",
			(e) => {
				if (e.touches.length !== 1) return;
				started = false;

				const t = e.touches[0];
				startX = t.clientX;
				startY = t.clientY;

				clearTimer();
				pressTimer = setTimeout(() => beginDrag(t), 350);
			},
			{ passive: true },
		);

		tile.addEventListener(
			"touchmove",
			(e) => {
				if (e.touches.length !== 1) return;
				const t = e.touches[0];
				const dx = Math.abs(t.clientX - startX);
				const dy = Math.abs(t.clientY - startY);

				// If user is scrolling before long-press activates, cancel.
				if (!started && (dx > 10 || dy > 10)) {
					clearTimer();
					return;
				}

				if (!started) return;

				// We're dragging; prevent page scroll.
				e.preventDefault();
				setGhostPosition(t.clientX, t.clientY);

				const target = findWysiwygAtPoint(t.clientX, t.clientY);
				if (
					lastTarget?.container &&
					lastTarget.container !== target?.container
				) {
					lastTarget.container.classList.remove("ring-2", "ring-primary");
				}
				if (target?.container) {
					target.container.classList.add("ring-2", "ring-primary");
				}
				lastTarget = target;

				if (target?.editor) {
					const pos = target.editor.view.posAtCoords({
						left: t.clientX,
						top: t.clientY,
					})?.pos;
					if (
						typeof pos !== "number" ||
						!showInsertMarkerAtPos(target.editor, pos)
					) {
						showInsertMarkerAtClientPoint(t.clientX, t.clientY);
					}
				} else {
					hideInsertMarker();
				}
			},
			{ passive: false },
		);

		tile.addEventListener(
			"touchend",
			(e) => {
				clearTimer();
				if (!started) {
					// Let normal click behavior happen (Photoswipe open).
					return;
				}
				e.preventDefault();
				e.stopPropagation();

				const touch = e.changedTouches?.[0];
				if (touch) {
					const target = findWysiwygAtPoint(touch.clientX, touch.clientY);
					if (target?.editor) {
						target.editor.commands.focus();
						const fallbackPos = target.editor.state.selection.from;
						const pos = target.editor.view.posAtCoords({
							left: touch.clientX,
							top: touch.clientY,
						})?.pos;
						insertImageAt(
							target.editor,
							typeof pos === "number" ? pos : fallbackPos,
							{
								src: payload.url,
								alt: payload.altText || "",
							},
						);
					}
				}

				cleanup();
			},
			{ passive: false },
		);

		tile.addEventListener("touchcancel", cleanup, { passive: true });
	});
}

async function uploadDroppedImage(container, hiddenInput, file) {
	const projectApi = window.STRICKNANI?.projectUploads;
	const yarnApi = window.STRICKNANI?.yarnUploads;

	const stepItem = container?.closest?.(".step-item");
	if (projectApi) {
		if (stepItem) {
			let stepId = stepItem.getAttribute("data-step-id");
			if (!stepId) {
				stepId = await projectApi.ensureStepId(stepItem);
			}
			if (!stepId) {
				return null;
			}

			const data = await projectApi.uploadStepImage(stepId, file);
			if (data) {
				projectApi.addStepImagePreview?.(stepItem, data);
			}
			return data;
		}

		const inputId = hiddenInput?.id || "";
		if (inputId === "stitch_sample") {
			return await projectApi.uploadStitchSampleImageData(file);
		}

		return await projectApi.uploadTitleImageData(file);
	}

	if (yarnApi) {
		return await yarnApi.uploadYarnImageData(file);
	}

	return null;
}

function collectAvailableImages() {
	const images = [];

	document
		.querySelectorAll("#titleImagesContainer [data-image-id]")
		.forEach((el) => {
			const anchor = el.querySelector("a[data-pswp-width]");
			const img = el.querySelector("img");
			if (anchor && img) {
				images.push({
					url: anchor.getAttribute("href"),
					thumbnail_url: img.src,
					alt_text: img.alt || anchor.getAttribute("data-pswp-caption") || "",
				});
			}
		});

	document
		.querySelectorAll("#stitchSampleImagesContainer [data-image-id]")
		.forEach((el) => {
			const anchor = el.querySelector("a[data-pswp-width]");
			const img = el.querySelector("img");
			if (anchor && img) {
				images.push({
					url: anchor.getAttribute("href"),
					thumbnail_url: img.src,
					alt_text: img.alt || anchor.getAttribute("data-pswp-caption") || "",
				});
			}
		});

	document.querySelectorAll(".step-images [data-image-id]").forEach((el) => {
		const anchor = el.querySelector("a[data-pswp-width]");
		const img = el.querySelector("img");
		if (anchor && img) {
			images.push({
				url: anchor.getAttribute("href"),
				thumbnail_url: img.src,
				alt_text: img.alt || anchor.getAttribute("data-pswp-caption") || "",
			});
		}
	});

	document
		.querySelectorAll("#existing-photos-grid [id^='photo-card-']")
		.forEach((el) => {
			const anchor = el.querySelector("a[data-pswp-width]");
			const img = el.querySelector("img");
			if (anchor && img) {
				images.push({
					url: anchor.getAttribute("href"),
					thumbnail_url: img.src,
					alt_text: img.alt || anchor.getAttribute("data-pswp-caption") || "",
				});
			}
		});

	document.querySelectorAll("[data-pending-url]").forEach((el) => {
		const url = el.getAttribute("data-pending-url");
		const img = el.querySelector("img");
		if (url) {
			images.push({
				url: url,
				thumbnail_url: img?.src || url,
				alt_text: img?.alt || getI18n("pendingImage", "Pending image"),
			});
		}
	});

	const seen = new Set();
	return images.filter((img) => {
		if (seen.has(img.url)) return false;
		seen.add(img.url);
		return true;
	});
}

function isCoarsePointer() {
	return (
		window.matchMedia?.("(pointer: coarse)")?.matches ||
		"ontouchstart" in window
	);
}

function createImageAutocompleteDropdown(editor, images, position) {
	if (isCoarsePointer()) {
		const overlay = document.createElement("div");
		overlay.className = "markdown-image-overlay";

		const sheet = document.createElement("div");
		sheet.className = "markdown-image-sheet";
		sheet.setAttribute("role", "dialog");
		sheet.setAttribute("aria-label", getI18n("selectImage", "Select image"));

		const header = document.createElement("div");
		header.className = "markdown-image-sheet-header";
		header.innerHTML = `<div class="markdown-image-sheet-title">${getI18n(
			"selectImage",
			"Select image",
		)}</div>`;

		const closeBtn = document.createElement("button");
		closeBtn.type = "button";
		closeBtn.className = "markdown-image-sheet-close";
		closeBtn.innerHTML = '<span class="mdi mdi-close"></span>';
		closeBtn.addEventListener("click", (e) => {
			e.preventDefault();
			closeImageAutocomplete();
		});
		header.appendChild(closeBtn);

		const list = document.createElement("div");
		list.className = "markdown-image-sheet-list";
		list.setAttribute("role", "listbox");

		if (images.length === 0) {
			const emptyItem = document.createElement("div");
			emptyItem.className = "markdown-image-empty";
			emptyItem.textContent = getI18n(
				"noImagesAvailable",
				"No images available",
			);
			list.appendChild(emptyItem);
		} else {
			images.forEach((image, index) => {
				const item = document.createElement("button");
				item.type = "button";
				item.className = "markdown-image-sheet-item";
				item.setAttribute("role", "option");
				item.setAttribute("data-index", index);
				item.setAttribute("data-url", image.url);
				item.setAttribute("data-alt", image.alt_text || "");

				const thumb = document.createElement("img");
				thumb.className = "markdown-image-thumb";
				thumb.alt = "";
				thumb.src = image.thumbnail_url || image.url;

				const meta = document.createElement("div");
				meta.className = "markdown-image-meta";

				const alt = document.createElement("div");
				alt.className = "markdown-image-alt";
				alt.textContent =
					image.alt_text || getI18n("untitledImage", "Untitled image");

				const url = document.createElement("div");
				url.className = "markdown-image-url";
				url.textContent = image.url.split("/").pop() || image.url;

				meta.appendChild(alt);
				meta.appendChild(url);

				item.appendChild(thumb);
				item.appendChild(meta);

				item.addEventListener("click", (e) => {
					e.preventDefault();
					insertImageFromAutocomplete(editor, image);
					closeImageAutocomplete();
				});

				list.appendChild(item);
			});
		}

		overlay.addEventListener("click", (e) => {
			if (e.target === overlay) {
				closeImageAutocomplete();
			}
		});

		sheet.appendChild(header);
		sheet.appendChild(list);
		overlay.appendChild(sheet);

		return overlay;
	}

	const dropdown = document.createElement("div");
	dropdown.className = "markdown-image-autocomplete";
	dropdown.setAttribute("role", "listbox");
	dropdown.setAttribute("aria-label", getI18n("selectImage", "Select image"));

	if (images.length === 0) {
		const emptyItem = document.createElement("div");
		emptyItem.className = "markdown-image-empty";
		emptyItem.textContent = getI18n("noImagesAvailable", "No images available");
		dropdown.appendChild(emptyItem);
	} else {
		images.forEach((image, index) => {
			const item = document.createElement("div");
			item.className = "markdown-image-item";
			item.setAttribute("role", "option");
			item.setAttribute("data-index", index);
			item.setAttribute("data-url", image.url);
			item.setAttribute("data-alt", image.alt_text || "");

			const img = document.createElement("img");
			img.src = image.thumbnail_url || image.url;
			img.alt = "";
			img.className = "markdown-image-thumb";

			const info = document.createElement("div");
			info.className = "markdown-image-meta";

			const altText = document.createElement("div");
			altText.className = "markdown-image-alt";
			altText.textContent =
				image.alt_text || getI18n("untitledImage", "Untitled image");

			const urlText = document.createElement("div");
			urlText.className = "markdown-image-url";
			urlText.textContent = image.url.split("/").pop() || image.url;

			info.appendChild(altText);
			info.appendChild(urlText);
			item.appendChild(img);
			item.appendChild(info);

			item.addEventListener("click", (e) => {
				e.preventDefault();
				e.stopPropagation();
				insertImageFromAutocomplete(editor, image);
				closeImageAutocomplete();
			});

			item.addEventListener("mouseenter", () => {
				setAutocompleteSelectedIndex(dropdown, index);
			});

			dropdown.appendChild(item);
		});
	}

	dropdown.style.left = `${position.x}px`;
	dropdown.style.top = `${position.y}px`;

	return dropdown;
}

function setAutocompleteSelectedIndex(dropdown, index) {
	const items = dropdown.querySelectorAll(".markdown-image-item");
	items.forEach((item, i) => {
		if (i === index) {
			item.classList.add("bg-base-200");
			item.setAttribute("aria-selected", "true");
		} else {
			item.classList.remove("bg-base-200");
			item.setAttribute("aria-selected", "false");
		}
	});
	if (currentImageAutocomplete) {
		currentImageAutocomplete.selectedIndex = index;
	}
}

function insertImageFromAutocomplete(editor, image) {
	const altText = image.alt_text || getI18n("image", "Image");
	const range = currentImageAutocomplete?.range;

	closeImageAutocomplete();

	editor.commands.focus();

	setTimeout(() => {
		const docSize = editor.state.doc.content.size;
		if (range && range.from >= 0 && range.to <= docSize) {
			editor
				.chain()
				.deleteRange({ from: range.from, to: range.to })
				.setImage({
					src: image.url,
					alt: altText,
				})
				.run();
		} else {
			// Toolbar-based insertion should not delete content around the cursor.
			editor
				.chain()
				.setImage({
					src: image.url,
					alt: altText,
				})
				.run();
		}
	}, 10);
}

function closeImageAutocomplete() {
	if (currentImageAutocomplete) {
		currentImageAutocomplete.dropdown?.remove();
		currentImageAutocomplete = null;
	}
}

function handleImageAutocomplete(editor, event) {
	const { from, to, $from } = editor.state.selection;
	const textBefore = $from.parent.textBetween(
		Math.max(0, $from.parentOffset - 1),
		$from.parentOffset,
	);

	if (textBefore === "!") {
		const textBeforeTrigger = $from.parent.textBetween(
			0,
			Math.max(0, $from.parentOffset - 1),
		);
		if (textBeforeTrigger.length === 0 || /\s$/.test(textBeforeTrigger)) {
			event.preventDefault();

			const images = collectAvailableImages();

			let coords = editor.view.coordsAtPos(to);
			if (!coords || coords.left === 0) {
				const selection = window.getSelection();
				if (selection && selection.rangeCount > 0) {
					const range = selection.getRangeAt(0);
					const rect = range.getBoundingClientRect();
					coords = { left: rect.left, bottom: rect.bottom };
				} else {
					coords = { left: 100, bottom: 100 };
				}
			}

			const dropdown = createImageAutocompleteDropdown(editor, images, {
				x: coords.left,
				y: coords.bottom + 4,
			});

			closeImageAutocomplete();
			document.body.appendChild(dropdown);

			currentImageAutocomplete = {
				editor,
				dropdown,
				selectedIndex: 0,
				range: { from: from - 1, to },
			};

			if (images.length > 0) {
				setAutocompleteSelectedIndex(dropdown, 0);
			}

			return true;
		}
	}
	return false;
}

function handleAutocompleteKeydown(event) {
	if (!currentImageAutocomplete) return false;

	const items = currentImageAutocomplete.dropdown.querySelectorAll(
		".markdown-image-item",
	);
	if (items.length === 0) {
		if (event.key === "Escape") {
			event.preventDefault();
			closeImageAutocomplete();
			return true;
		}
		return false;
	}

	switch (event.key) {
		case "ArrowDown":
			event.preventDefault();
			setAutocompleteSelectedIndex(
				currentImageAutocomplete.dropdown,
				(currentImageAutocomplete.selectedIndex + 1) % items.length,
			);
			return true;
		case "ArrowUp":
			event.preventDefault();
			setAutocompleteSelectedIndex(
				currentImageAutocomplete.dropdown,
				(currentImageAutocomplete.selectedIndex - 1 + items.length) %
					items.length,
			);
			return true;
		case "Enter":
			event.preventDefault();
			if (items[currentImageAutocomplete.selectedIndex]) {
				const item = items[currentImageAutocomplete.selectedIndex];
				const image = {
					url: item.getAttribute("data-url"),
					alt_text: item.getAttribute("data-alt"),
				};
				insertImageFromAutocomplete(currentImageAutocomplete.editor, image);
				closeImageAutocomplete();
			}
			return true;
		case "Escape":
			event.preventDefault();
			closeImageAutocomplete();
			return true;
	}

	return false;
}

function createEditor(container, hiddenInput, options = {}) {
	const toolbar = container.querySelector(".wysiwyg-toolbar");
	const editorEl = container.querySelector(".wysiwyg-content");

	if (!editorEl || !hiddenInput) {
		console.error("WYSIWYG: Missing required elements");
		return null;
	}

	const initialContent = hiddenInput.value || "";

	function setToolbarRawMode(toolbarEl, enabled) {
		if (!toolbarEl) return;
		toolbarEl.querySelectorAll("button[data-action]").forEach((btn) => {
			if (btn.dataset.action === "toggleRaw") {
				btn.classList.toggle("is-active", enabled);
				return;
			}
			if (enabled) {
				if (btn.disabled) return;
				btn.dataset.wysiwygDisabledByRaw = "true";
				btn.disabled = true;
				return;
			}
			if (btn.dataset.wysiwygDisabledByRaw === "true") {
				delete btn.dataset.wysiwygDisabledByRaw;
				btn.disabled = false;
			}
		});
	}

	function setRawMode(enabled) {
		if (!editorEl) return;
		if (enabled) {
			container.dataset.wysiwygRaw = "true";
			hiddenInput.value = editor.getMarkdown();
			editorEl.classList.add("hidden");
			hiddenInput.classList.remove("hidden");
			setToolbarRawMode(toolbar, true);
			autoResizeTextarea(hiddenInput);
			hiddenInput.focus();
			return;
		}

		container.dataset.wysiwygRaw = "false";
		const markdown = hiddenInput.value || "";
		try {
			const json = editor.markdown?.parse
				? editor.markdown.parse(markdown)
				: markdown;
			editor.commands.setContent(json);
		} catch (err) {
			console.error(
				"WYSIWYG: Failed to parse markdown, keeping raw content",
				err,
			);
			// Keep raw textarea visible if parsing fails.
			container.dataset.wysiwygRaw = "true";
			setToolbarRawMode(toolbar, true);
			return;
		}

		hiddenInput.classList.add("hidden");
		hiddenInput.style.height = "";
		hiddenInput.style.overflowY = "";
		editorEl.classList.remove("hidden");
		setToolbarRawMode(toolbar, false);
		editor.commands.focus();
	}

	hiddenInput.addEventListener("input", () => {
		if (container.dataset.wysiwygRaw === "true") {
			autoResizeTextarea(hiddenInput);
		}
	});

	const editor = new Editor({
		element: editorEl,
		extensions: [
			StarterKit.configure({
				heading: {
					levels: [1, 2, 3, 4, 5, 6],
				},
				// We implement our own drop handling; the dropcursor overlay has been
				// causing runtime errors in some browsers.
				dropcursor: false,
				gapcursor: false,
				// Trailing node enforcement can crash when the doc is momentarily
				// invalid during markdown parsing/transactions.
				trailingNode: false,
				// We include Link and Underline separately to configure them; disable the ones from StarterKit.
				link: false,
				underline: false,
			}),
			SizedImage.configure({
				allowBase64: false,
				HTMLAttributes: {
					class: "max-w-full h-auto rounded",
				},
			}),
			Underline,
			Link.configure({
				openOnClick: false,
				HTMLAttributes: {
					class: "text-primary hover:underline",
				},
			}),
			Markdown.configure({
				html: true,
				breaks: true,
			}),
		],
		content: initialContent,
		contentType: "markdown",
		editorProps: {
			attributes: {
				class:
					"prose prose-sm max-w-none dark:prose-invert focus:outline-none min-h-[120px] p-3",
			},
			handleDOMEvents: {
				dragover: (_view, event) => {
					const dt = event.dataTransfer;
					if (!dt) return false;

					const markdownImagesEnabled =
						hiddenInput?.dataset?.markdownImages === "true" ||
						container?.dataset?.wysiwygStep === "true";
					if (!markdownImagesEnabled) {
						hideInsertMarker();
						return false;
					}

					// Internal image move inside the editor: show marker regardless of dataTransfer payload.
					if (currentInternalImageDrag?.editor === editor) {
						event.preventDefault();
						const pos = editor.view.posAtCoords({
							left: event.clientX,
							top: event.clientY,
						})?.pos;
						if (typeof pos === "number" && showInsertMarkerAtPos(editor, pos)) {
							return false;
						}
						showInsertMarkerAtClientPoint(event.clientX, event.clientY);
						return false;
					}

					const files = Array.from(dt.files || []).filter(isImageFile);
					const droppedText = extractDroppedText(event);
					const mdImg = droppedText ? parseMarkdownImage(droppedText) : null;
					const hasFiles = dataTransferHasFileDrag(dt);
					const hasImageFiles =
						files.length > 0 || dataTransferHasImageFileDrag(dt);
					const isImageDrop =
						hasImageFiles ||
						Boolean(mdImg?.src) ||
						(droppedText && isLikelyImageUrl(droppedText));

					// If we're dragging files but can't detect mime type yet, still show a marker
					// to indicate where a drop would insert (upload may still be rejected later).
					if (!isImageDrop && !hasFiles) {
						hideInsertMarker();
						return false;
					}

					// Ensure drop is allowed (especially for files) and show insertion hint.
					event.preventDefault();

					const pos = editor.view.posAtCoords({
						left: event.clientX,
						top: event.clientY,
					})?.pos;
					if (typeof pos === "number" && showInsertMarkerAtPos(editor, pos)) {
						return false;
					}
					showInsertMarkerAtClientPoint(event.clientX, event.clientY);
					return false;
				},
				dragleave: () => {
					hideInsertMarker();
					return false;
				},
				drop: () => {
					currentInternalImageDrag = null;
					hideInsertMarker();
					return false;
				},
			},
			handleKeyDown: (view, event) => {
				if (handleAutocompleteKeydown(event)) {
					return true;
				}
				// Some pages can interfere with ProseMirror's default Enter handling.
				// Implement the ProseMirror base-keymap Enter chain directly to avoid
				// calling TipTap's `enter()` command (which can recurse via keyboardShortcut).
				if (event.key === "Enter" && !event.shiftKey) {
					return chainCommands(
						newlineInCode,
						createParagraphNear,
						liftEmptyBlock,
						splitBlock,
					)(view.state, view.dispatch);
				}
				return false;
			},
			handleTextInput: (_view, _from, _to, text) => {
				if (text === "!") {
					// Let ProseMirror insert the text first, then open the picker based on the actual doc state.
					setTimeout(() => {
						handleImageAutocomplete(editor, { preventDefault: () => {} });
					}, 0);
				}
				return false;
			},
			handleDrop: (view, event) => {
				hideInsertMarker();
				// Handle drops of:
				// - external files: upload to the relevant section and insert markdown
				// - internal gallery thumbnails: insert markdown from text/plain
				const dt = event.dataTransfer;
				if (!dt) {
					return false;
				}

				const markdownImagesEnabled =
					hiddenInput?.dataset?.markdownImages === "true" ||
					container?.dataset?.wysiwygStep === "true";

				const files = Array.from(dt.files || []).filter(isImageFile);
				const droppedText = extractDroppedText(event);
				if (!markdownImagesEnabled && (files.length > 0 || droppedText)) {
					return false;
				}
				if (files.length === 0 && !droppedText) {
					return false;
				}

				event.preventDefault();
				event.stopPropagation();

				editor.commands.focus();
				const fallbackPos = editor.state.selection.from;
				const insertPos = getDropInsertPos(view, event, fallbackPos);

				// If we're dropping markdown/url text, insert it directly.
				if (files.length === 0 && droppedText) {
					const mdImg = parseMarkdownImage(droppedText);
					if (mdImg?.src) {
						insertImageAt(editor, insertPos, {
							src: mdImg.src,
							alt: mdImg.alt || "",
						});
						return true;
					}

					if (isLikelyImageUrl(droppedText)) {
						insertImageAt(editor, insertPos, {
							src: droppedText.trim(),
							alt: "",
						});
						return true;
					}

					try {
						editor.commands.insertContentAt(insertPos, droppedText, {
							updateSelection: true,
						});
					} catch (_err) {
						editor.chain().insertContent(droppedText).run();
					}
					return true;
				}

				(async () => {
					for (let i = 0; i < files.length; i += 1) {
						const file = files[i];
						const uploaded = await uploadDroppedImage(
							container,
							hiddenInput,
							file,
						);
						if (!uploaded) {
							window.showToast?.(
								getI18n("failedToUploadImage", "Failed to upload image"),
								"error",
							);
							continue;
						}

						const url =
							uploaded.full_url ||
							uploaded.url ||
							uploaded.href ||
							uploaded.src ||
							"";
						const altText =
							uploaded.alt_text || uploaded.alt || getI18n("image", "Image");
						if (!url) {
							continue;
						}

						insertImageAt(editor, insertPos, { src: url, alt: altText });
					}
				})().catch((err) => {
					console.error("WYSIWYG drop upload failed", err);
				});

				return true;
			},
		},
		onUpdate: ({ editor }) => {
			if (container.dataset.wysiwygRaw === "true") {
				return;
			}
			const markdown = editor.getMarkdown();
			hiddenInput.value = markdown;
			hiddenInput.dispatchEvent(new Event("input", { bubbles: true }));
			updateToolbarState(toolbar, editor);
		},
		onFocus: () => {
			container.classList.add("ring-2", "ring-primary", "ring-offset-1");
		},
		onBlur: () => {
			container.classList.remove("ring-2", "ring-primary", "ring-offset-1");
		},
		onCreate: ({ editor }) => {
			updateToolbarState(toolbar, editor);
		},
	});

	if (toolbar) {
		setupToolbar(container, toolbar, editor, hiddenInput, options, setRawMode);
	}

	WYSIWYG_INSTANCES.set(container, editor);
	installThumbnailLongPressDrag();
	return editor;
}

function setupToolbar(
	container,
	toolbar,
	editor,
	_hiddenInput,
	options,
	setRawMode,
) {
	function updateImageSizeLabel() {
		const btn = toolbar.querySelector('button[data-action="imageSize"]');
		const label = btn?.querySelector("[data-image-size-label]");
		if (!label) return;

		if (!editor.isActive("image")) {
			label.textContent = "S";
			return;
		}

		const attrs = editor.getAttributes("image") || {};
		const title = String(attrs.title || "");
		const match = title.match(/\bsn:size=(sm|md|lg|xl)\b/);
		const size = match ? match[1] : "sm";
		label.textContent = size === "xl" ? "XL" : size.toUpperCase();
	}

	function cycleSelectedImageSize() {
		if (!editor.isActive("image")) {
			return;
		}

		const attrs = editor.getAttributes("image") || {};
		const prevTitle = String(attrs.title || "");
		const match = prevTitle.match(/\bsn:size=(sm|md|lg|xl)\b/);
		const prevSize = match ? match[1] : "sm";
		const sizes = ["sm", "md", "lg", "xl"];
		const nextSize = sizes[(sizes.indexOf(prevSize) + 1) % sizes.length];

		// Preserve any user-provided title and just update our marker.
		const cleanedTitle = prevTitle
			.replace(/\bsn:size=(sm|md|lg|xl)\b/g, "")
			.replace(/\s+/g, " ")
			.trim();
		const nextTitle = cleanedTitle
			? `${cleanedTitle} sn:size=${nextSize}`
			: `sn:size=${nextSize}`;

		editor
			.chain()
			.focus()
			.updateAttributes("image", { title: nextTitle })
			.run();

		updateImageSizeLabel();
	}

	toolbar.addEventListener("click", (e) => {
		const btn = e.target.closest("button[data-action]");
		if (!btn) return;

		const action = btn.dataset.action;
		const value = btn.dataset.value;

		if (action === "toggleRaw") {
			e.preventDefault();
			const enabled = container.dataset.wysiwygRaw !== "true";
			setRawMode(enabled);
			return;
		}

		switch (action) {
			case "bold":
				editor.chain().focus().toggleBold().run();
				break;
			case "italic":
				editor.chain().focus().toggleItalic().run();
				break;
			case "underline":
				editor.chain().focus().toggleUnderline().run();
				break;
			case "strike":
				editor.chain().focus().toggleStrike().run();
				break;
			case "code":
				editor.chain().focus().toggleCode().run();
				break;
			case "codeBlock":
				editor.chain().focus().toggleCodeBlock().run();
				break;
			case "heading": {
				const level = parseInt(value, 10) || 1;
				editor.chain().focus().toggleHeading({ level }).run();
				break;
			}
			case "paragraph":
				editor.chain().focus().setParagraph().run();
				break;
			case "bulletList":
				editor.chain().focus().toggleBulletList().run();
				break;
			case "orderedList":
				editor.chain().focus().toggleOrderedList().run();
				break;
			case "blockquote":
				editor.chain().focus().toggleBlockquote().run();
				break;
			case "horizontalRule":
				editor.chain().focus().setHorizontalRule().run();
				break;
			case "link": {
				const url = prompt(
					options.i18n?.wysiwygLinkUrl ||
						getI18n("wysiwygLinkUrl", "Enter URL:"),
				);
				if (url) {
					editor.chain().focus().setLink({ href: url }).run();
				}
				break;
			}
			case "unlink":
				editor.chain().focus().unsetLink().run();
				break;
			case "image": {
				const images = collectAvailableImages();
				let coords = editor.view.coordsAtPos(editor.state.selection.to);
				if (!coords || coords.left === 0) {
					const selection = window.getSelection();
					if (selection && selection.rangeCount > 0) {
						const range = selection.getRangeAt(0);
						const rect = range.getBoundingClientRect();
						coords = { left: rect.left, bottom: rect.bottom };
					} else {
						coords = { left: 100, bottom: 100 };
					}
				}
				const dropdown = createImageAutocompleteDropdown(editor, images, {
					x: coords.left,
					y: coords.bottom + 4,
				});
				closeImageAutocomplete();
				document.body.appendChild(dropdown);
				currentImageAutocomplete = {
					editor,
					dropdown,
					selectedIndex: 0,
					range: null,
				};
				if (images.length > 0) {
					setAutocompleteSelectedIndex(dropdown, 0);
				}
				break;
			}
			case "imageSize":
				cycleSelectedImageSize();
				break;
			case "undo":
				editor.chain().focus().undo().run();
				break;
			case "redo":
				editor.chain().focus().redo().run();
				break;
		}

		updateToolbarState(toolbar, editor);
		updateImageSizeLabel();
	});

	// Keep label in sync with selection changes that don't go through toolbar clicks.
	editor.on("selectionUpdate", updateImageSizeLabel);
	updateImageSizeLabel();
}

function updateToolbarState(toolbar, editor) {
	if (!toolbar) return;

	toolbar.querySelectorAll("button[data-action]").forEach((btn) => {
		const action = btn.dataset.action;
		const value = btn.dataset.value;
		let isActive = false;

		switch (action) {
			case "bold":
				isActive = editor.isActive("bold");
				break;
			case "italic":
				isActive = editor.isActive("italic");
				break;
			case "underline":
				isActive = editor.isActive("underline");
				break;
			case "strike":
				isActive = editor.isActive("strike");
				break;
			case "code":
				isActive = editor.isActive("code");
				break;
			case "codeBlock":
				isActive = editor.isActive("codeBlock");
				break;
			case "heading": {
				const level = parseInt(value, 10) || 1;
				isActive = editor.isActive("heading", { level });
				break;
			}
			case "bulletList":
				isActive = editor.isActive("bulletList");
				break;
			case "orderedList":
				isActive = editor.isActive("orderedList");
				break;
			case "blockquote":
				isActive = editor.isActive("blockquote");
				break;
			case "link":
				isActive = editor.isActive("link");
				break;
			case "imageSize":
				isActive = editor.isActive("image");
				break;
		}

		if (isActive) {
			btn.classList.add("bg-primary/20", "text-primary");
		} else {
			btn.classList.remove("bg-primary/20", "text-primary");
		}
	});
}

function destroyEditor(container) {
	const editor = WYSIWYG_INSTANCES.get(container);
	if (editor) {
		editor.destroy();
		WYSIWYG_INSTANCES.delete(container);
	}
}

function getEditor(container) {
	return WYSIWYG_INSTANCES.get(container);
}

function initWysiwygEditors(options = {}) {
	document.querySelectorAll("[data-wysiwyg]").forEach((container) => {
		const inputId = container.dataset.wysiwygInput;
		const hiddenInput = inputId
			? document.getElementById(inputId)
			: container.querySelector('input[type="hidden"], textarea');

		if (hiddenInput && !WYSIWYG_INSTANCES.has(container)) {
			createEditor(container, hiddenInput, options);
		}
	});

	installThumbnailLongPressDrag();
}

window.STRICKNANI = window.STRICKNANI || {};
window.STRICKNANI.wysiwyg = {
	init: initWysiwygEditors,
	create: createEditor,
	destroy: destroyEditor,
	get: getEditor,
};

document.addEventListener("DOMContentLoaded", () => {
	initWysiwygEditors({
		i18n: window.STRICKNANI.i18n || {},
	});
});

if (typeof htmx !== "undefined") {
	htmx.on("htmx:afterSettle", () => {
		initWysiwygEditors({
			i18n: window.STRICKNANI.i18n || {},
		});
	});
}

document.addEventListener("click", (event) => {
	if (!currentImageAutocomplete) return;
	if (currentImageAutocomplete.dropdown.contains(event.target)) return;

	// Don't immediately close the picker when it was opened via toolbar click.
	if (event.target.closest?.(".wysiwyg-toolbar")) return;

	closeImageAutocomplete();
});

document.addEventListener(
	"scroll",
	(event) => {
		// Allow scrolling inside the mobile picker sheet without closing it.
		if (currentImageAutocomplete?.dropdown?.contains(event.target)) {
			return;
		}
		closeImageAutocomplete();
	},
	true,
);
