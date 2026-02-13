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

const SizedImage = Image.extend({
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

function isImageFile(file) {
	return Boolean(
		file && typeof file.type === "string" && file.type.startsWith("image/"),
	);
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

function createImageAutocompleteDropdown(editor, images, position) {
	const dropdown = document.createElement("div");
	dropdown.className =
		"markdown-image-autocomplete fixed z-[9999] bg-base-100 border border-base-300 rounded-lg shadow-lg max-h-64 overflow-y-auto w-72";
	dropdown.setAttribute("role", "listbox");
	dropdown.setAttribute("aria-label", getI18n("selectImage", "Select image"));

	if (images.length === 0) {
		const emptyItem = document.createElement("div");
		emptyItem.className = "p-3 text-sm text-base-content/60 italic";
		emptyItem.textContent = getI18n("noImagesAvailable", "No images available");
		dropdown.appendChild(emptyItem);
	} else {
		images.forEach((image, index) => {
			const item = document.createElement("div");
			item.className =
				"markdown-image-item flex items-center gap-3 p-2 hover:bg-base-200 cursor-pointer transition-colors";
			item.setAttribute("role", "option");
			item.setAttribute("data-index", index);
			item.setAttribute("data-url", image.url);
			item.setAttribute("data-alt", image.alt_text || "");

			const img = document.createElement("img");
			img.src = image.thumbnail_url || image.url;
			img.alt = "";
			img.className = "w-12 h-12 object-cover rounded flex-shrink-0";

			const info = document.createElement("div");
			info.className = "flex-1 min-w-0";

			const altText = document.createElement("div");
			altText.className = "text-sm font-medium truncate";
			altText.textContent =
				image.alt_text || getI18n("untitledImage", "Untitled image");

			const urlText = document.createElement("div");
			urlText.className = "text-xs text-base-content/50 truncate";
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
	function runToolbarAction(action, value) {
		switch (action) {
			case "bold":
				editor.chain().focus().toggleBold().run();
				return;
			case "italic":
				editor.chain().focus().toggleItalic().run();
				return;
			case "underline":
				editor.chain().focus().toggleUnderline().run();
				return;
			case "strike":
				editor.chain().focus().toggleStrike().run();
				return;
			case "code":
				editor.chain().focus().toggleCode().run();
				return;
			case "codeBlock":
				editor.chain().focus().toggleCodeBlock().run();
				return;
			case "heading": {
				const level = Number.parseInt(value, 10) || 1;
				editor.chain().focus().toggleHeading({ level }).run();
				return;
			}
			case "paragraph":
				editor.chain().focus().setParagraph().run();
				return;
			case "bulletList":
				editor.chain().focus().toggleBulletList().run();
				return;
			case "orderedList":
				editor.chain().focus().toggleOrderedList().run();
				return;
			case "blockquote":
				editor.chain().focus().toggleBlockquote().run();
				return;
			case "horizontalRule":
				editor.chain().focus().setHorizontalRule().run();
				return;
			case "link": {
				const url = prompt(
					options.i18n?.wysiwygLinkUrl ||
						getI18n("wysiwygLinkUrl", "Enter URL:"),
				);
				if (url) {
					editor.chain().focus().setLink({ href: url }).run();
				}
				return;
			}
			case "unlink":
				editor.chain().focus().unsetLink().run();
				return;
			case "undo":
				editor.chain().focus().undo().run();
				return;
			case "redo":
				editor.chain().focus().redo().run();
				return;
			default:
				return;
		}
	}

	function createBubbleMenu() {
		const bubble = document.createElement("div");
		bubble.className = "wysiwyg-bubble hidden";
		bubble.setAttribute("role", "toolbar");
		const actions = ["bold", "italic", "underline", "link", "unlink"];
		actions.forEach((action, idx) => {
			if (idx === 3) {
				const sep = document.createElement("span");
				sep.className = "wysiwyg-bubble-sep";
				sep.setAttribute("aria-hidden", "true");
				bubble.appendChild(sep);
			}

			const srcBtn = toolbar.querySelector(`button[data-action="${action}"]`);
			if (srcBtn) {
				const cloned = srcBtn.cloneNode(true);
				cloned.removeAttribute("disabled");
				bubble.appendChild(cloned);
			}
		});

		// Keep it near the editor in the DOM, but position with `fixed`.
		container.appendChild(bubble);

		bubble.addEventListener("mousedown", (e) => {
			// Prevent editor blur when clicking the bubble buttons.
			e.preventDefault();
		});

		bubble.addEventListener("click", (e) => {
			const btn = e.target.closest("button[data-action]");
			if (!btn) return;
			e.preventDefault();
			runToolbarAction(btn.dataset.action, btn.dataset.value);
			updateToolbarState(toolbar, editor);
			updateImageSizeLabel();
			updateBubbleMenu();
		});

		function hide() {
			bubble.classList.add("hidden");
		}

		function showAt({ x, y }) {
			bubble.style.left = `${x}px`;
			bubble.style.top = `${y}px`;
			bubble.classList.remove("hidden");
		}

		function updateBubbleMenu() {
			// Only for non-empty text selections in rich mode.
			if (container.dataset.wysiwygRaw === "true") {
				hide();
				return;
			}
			const focused =
				typeof editor.isFocused === "function"
					? editor.isFocused()
					: editor.isFocused;
			if (!focused) {
				hide();
				return;
			}
			const { from, to, empty } = editor.state.selection;
			if (empty || from === to) {
				hide();
				return;
			}

			// Only show for selections that include inline content.
			const $from = editor.state.doc.resolve(from);
			if (!$from.parent.isTextblock) {
				hide();
				return;
			}

			const start = editor.view.coordsAtPos(from);
			const end = editor.view.coordsAtPos(to);
			const centerX = (start.left + end.right) / 2;
			const top = Math.min(start.top, end.top);

			// Measure after it's visible to center properly.
			bubble.classList.remove("hidden");
			const rect = bubble.getBoundingClientRect();
			const x = Math.max(
				8,
				Math.min(window.innerWidth - rect.width - 8, centerX - rect.width / 2),
			);
			const y = Math.max(8, top - rect.height - 8);
			showAt({ x, y });
		}

		editor.on("selectionUpdate", updateBubbleMenu);
		// Some selection changes (especially mouse-driven) can be missed depending on
		// how transactions are dispatched; keep it in sync on any transaction too.
		editor.on("transaction", updateBubbleMenu);
		editor.on("focus", updateBubbleMenu);
		editor.on("blur", hide);
		editor.view.dom.addEventListener("mouseup", () => {
			// Ensure the bubble closes when selection collapses on mouseup.
			setTimeout(updateBubbleMenu, 0);
		});
		editor.view.dom.addEventListener("keyup", () => {
			// Ensure the bubble closes when selection collapses via keyboard.
			setTimeout(updateBubbleMenu, 0);
		});
		window.addEventListener("scroll", hide, true);
		window.addEventListener("resize", hide, true);

		return { update: updateBubbleMenu, hide };
	}

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
			bubbleMenu.hide();
			return;
		}

		switch (action) {
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
			default:
				runToolbarAction(action, value);
				break;
		}

		updateToolbarState(toolbar, editor);
		updateImageSizeLabel();
		bubbleMenu.update();
	});

	const bubbleMenu = createBubbleMenu();

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

document.addEventListener("scroll", closeImageAutocomplete, true);
