/* Stricknani global browser utilities.
 *
 * This file intentionally defines a few functions on `window` so templates can
 * call them without inline JS blocks. Keep logic here generic and drive UI
 * strings via `window.STRICKNANI.i18n`.
 */

(() => {
  const config = window.STRICKNANI || {};
  const i18n = config.i18n || {};

  const getI18n = (key, fallback) => {
    const value = i18n[key];
    return typeof value === "string" && value.trim() ? value : fallback;
  };

  const getCsrfToken = () =>
    document
      .querySelector('meta[name="csrf-token"]')
      ?.getAttribute("content") || "";

  const toastVariants = {
    success:
      "bg-emerald-500/90 text-white border border-emerald-200/30 shadow-[0_14px_30px_-18px_rgba(16,185,129,0.8)]",
    error:
      "bg-red-600/90 text-white border border-red-200/30 shadow-[0_14px_30px_-18px_rgba(239,68,68,0.8)]",
    info: "bg-slate-900/90 text-white border border-slate-500/20 shadow-[0_14px_30px_-18px_rgba(15,23,42,0.8)]",
  };
  const toastAccents = {
    success: "bg-emerald-200/70",
    error: "bg-rose-200/70",
    info: "bg-slate-200/50",
  };
  const toastDurations = {
    success: 2400,
    error: 6200,
    info: 3600,
  };

  const getBaseToastContainer = () => document.getElementById("toastContainer");

  const getToastContainer = () => {
    const openDialogs = Array.from(document.querySelectorAll("dialog[open]"));
    const activeDialog = openDialogs.length
      ? openDialogs[openDialogs.length - 1]
      : null;
    if (!activeDialog) {
      return getBaseToastContainer();
    }

    let container = activeDialog.querySelector('[data-toast-container="1"]');
    if (!container) {
      container = document.createElement("div");
      container.setAttribute("data-toast-container", "1");
      container.className =
        "fixed inset-x-4 top-[4.5rem] z-[2147483647] mx-auto flex max-w-sm flex-col gap-3 sm:inset-auto sm:right-4 sm:top-[4.5rem] sm:w-80";
      container.setAttribute("aria-live", "polite");
      container.setAttribute("aria-atomic", "true");
      activeDialog.appendChild(container);
    }
    return container;
  };

  window.showToast = (message, variant = "info") => {
    const container = getToastContainer();
    if (!container || !message) {
      return;
    }

    container.replaceChildren();
    const toast = document.createElement("div");
    toast.className = `relative overflow-hidden rounded-2xl px-4 py-3 shadow-lg ring-1 ring-black/10 backdrop-blur transition duration-200 ease-out opacity-0 translate-y-2 translate-x-4 hover:translate-y-1 cursor-pointer ${
      toastVariants[variant] || toastVariants.info
    }`;
    toast.setAttribute("role", "status");
    const accentClass = toastAccents[variant] || toastAccents.info;

    const dismissLabel = getI18n("dismissMessage", "Dismiss message");
    const iconClass =
      variant === "success"
        ? "mdi-check-circle"
        : variant === "error"
          ? "mdi-alert-circle"
          : "mdi-information";

    toast.innerHTML = `
      <span class="pointer-events-none absolute inset-y-0 left-0 w-1 ${accentClass}"></span>
      <div class="flex items-center gap-3">
        <span class="mdi ${iconClass} text-lg"></span>
        <p class="text-sm leading-snug"></p>
        <button type="button" class="ml-auto text-white/80 transition hover:text-white" aria-label="${dismissLabel}">
          <span class="mdi mdi-close"></span>
        </button>
      </div>
    `;
    toast.querySelector("p")?.append(document.createTextNode(String(message)));

    const dismiss = () => {
      toast.classList.add("opacity-0", "translate-y-2", "translate-x-4");
      setTimeout(() => toast.remove(), 200);
    };

    toast.addEventListener("click", dismiss);
    container.appendChild(toast);

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        toast.classList.remove("opacity-0", "translate-y-2", "translate-x-4");
      });
    });

    const duration = toastDurations[variant] || toastDurations.info;
    setTimeout(() => {
      if (!toast.isConnected) {
        return;
      }
      dismiss();
    }, duration);
  };

  const execCommandCopyFallback = (text) => {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.style.position = "fixed";
    textArea.style.left = "-9999px";
    textArea.style.top = "0";
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
      const successful = document.execCommand("copy");
      if (!successful) {
        throw new Error("execCommand copy failed");
      }
    } finally {
      document.body.removeChild(textArea);
    }
  };

  window.copyToClipboard = async (text, btn) => {
    if (typeof text !== "string" || !text) {
      console.warn("copyToClipboard called with invalid text:", text);
      return;
    }
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
          await navigator.clipboard.writeText(text);
        } catch {
          execCommandCopyFallback(text);
        }
      } else {
        execCommandCopyFallback(text);
      }

      const originalContent = btn ? btn.innerHTML : "";
      if (btn) {
        btn.innerHTML = '<span class="mdi mdi-check text-success"></span>';
        btn.classList.add("btn-success", "bg-success/10");
      }

      window.showToast?.(
        getI18n("copiedToClipboard", "Copied to clipboard"),
        "success",
      );

      setTimeout(() => {
        if (!btn) {
          return;
        }
        btn.innerHTML = originalContent;
        btn.classList.remove("btn-success", "bg-success/10");
      }, 2000);
    } catch (err) {
      console.error("Failed to copy:", err);
      window.showToast?.(getI18n("failedToCopy", "Failed to copy"), "error");
    }
  };

  const extractErrorMessage = async (response, fallback) => {
    const defaultMessage =
      fallback ||
      getI18n("somethingWentWrong", "Something went wrong. Please try again");
    if (!response) {
      return defaultMessage;
    }

    const isFetchResponse = typeof response.clone === "function";
    const contentType = isFetchResponse
      ? response.headers?.get("content-type") || ""
      : typeof response.getResponseHeader === "function"
        ? response.getResponseHeader("content-type") || ""
        : "";

    try {
      if (contentType.includes("application/json")) {
        if (isFetchResponse) {
          const data = await response.clone().json();
          if (typeof data?.detail === "string" && data.detail.trim()) {
            return data.detail;
          }
          if (typeof data?.message === "string" && data.message.trim()) {
            return data.message;
          }
        } else if (response.responseText) {
          const data = JSON.parse(response.responseText);
          if (typeof data?.detail === "string" && data.detail.trim()) {
            return data.detail;
          }
          if (typeof data?.message === "string" && data.message.trim()) {
            return data.message;
          }
        }
      } else if (isFetchResponse) {
        const text = await response.clone().text();
        if (text.trim()) {
          return text.trim();
        }
      } else if (response.responseText) {
        const text = response.responseText;
        if (text && text.trim()) {
          return text.trim();
        }
      }
    } catch (error) {
      console.error("Failed to parse error response", error);
    }

    return defaultMessage;
  };

  const resolveDialog = (dialogOrId) => {
    if (typeof dialogOrId === "string") {
      return document.getElementById(dialogOrId);
    }
    return dialogOrId;
  };

  window.openDialog = (dialogOrId) => {
    const dialog = resolveDialog(dialogOrId);
    if (!dialog) {
      return false;
    }
    if (typeof dialog.showModal === "function") {
      if (!dialog.open) {
        dialog.showModal();
      }
      return true;
    }
    dialog.setAttribute("open", "");
    dialog.style.display = "block";
    dialog.setAttribute("aria-modal", "true");
    return true;
  };

  window.closeDialog = (dialogOrId) => {
    const dialog = resolveDialog(dialogOrId);
    if (!dialog) {
      return false;
    }
    if (typeof dialog.close === "function") {
      dialog.close();
      return true;
    }
    dialog.removeAttribute("open");
    dialog.style.display = "none";
    return true;
  };

  window.printProject = (id) => {
    if (!id) {
      window.print();
      return;
    }
    const printUrl = `/projects/${id}#print`;
    const win = window.open(printUrl, "_blank");
    win?.focus();
  };

  const setButtonTextPreservingIcon = (button, text) => {
    if (!button) {
      return;
    }
    const icon = button.querySelector(".mdi");
    button.replaceChildren();
    if (icon) {
      button.appendChild(icon);
      button.appendChild(document.createTextNode(" "));
    }
    button.appendChild(document.createTextNode(text));
  };

  window.confirmAction = (
    title,
    message,
    onConfirm,
    onCancel = null,
    options = {},
  ) => {
    const dialog = document.getElementById("confirmationDialog");
    const titleEl = document.getElementById("confirmationTitle");
    const messageEl = document.getElementById("confirmationMessage");
    const confirmBtn = document.getElementById("confirmationConfirm");
    const cancelBtn = document.getElementById("confirmationCancel");

    if (!dialog || !titleEl || !messageEl || !confirmBtn || !cancelBtn) {
      return;
    }

    titleEl.textContent = title;
    messageEl.textContent = message;

    const confirmText = options.confirmText || getI18n("confirm", "Confirm");
    const cancelText = options.cancelText || getI18n("cancel", "Cancel");

    setButtonTextPreservingIcon(confirmBtn, confirmText);
    setButtonTextPreservingIcon(cancelBtn, cancelText);

    // Remove old listeners to avoid stacking.
    const newConfirmBtn = confirmBtn.cloneNode(true);
    confirmBtn.parentNode.replaceChild(newConfirmBtn, confirmBtn);
    const newCancelBtn = cancelBtn.cloneNode(true);
    cancelBtn.parentNode.replaceChild(newCancelBtn, cancelBtn);

    newConfirmBtn.addEventListener("click", () => {
      if (onConfirm) {
        onConfirm();
      }
      dialog.close();
    });

    newCancelBtn.addEventListener("click", () => {
      if (onCancel) {
        onCancel();
      }
    });

    dialog.showModal();
  };

  window.openPdfViewer = (url, filename) => {
    const dialog = document.getElementById("pdfViewerDialog");
    const frame = document.getElementById("pdfViewerFrame");
    const download = document.getElementById("pdfViewerDownload");
    const nameEl = document.getElementById("pdfViewerFilename");

    if (!dialog || !frame || !download || !nameEl || !url) {
      return;
    }

    nameEl.textContent = filename || "";
    download.href = url;
    frame.src = url;
    dialog.showModal();
  };

  window.openImageViewer = (url, filename) => {
    const dialog = document.getElementById("imageViewerDialog");
    const image = document.getElementById("imageViewerImage");
    const download = document.getElementById("imageViewerDownload");
    const nameEl = document.getElementById("imageViewerFilename");

    if (!dialog || !image || !download || !nameEl || !url) {
      return;
    }

    nameEl.textContent = filename || "";
    download.href = url;
    image.src = url;
    image.alt = filename || "";
    dialog.showModal();
  };

  window.openPhotoSwipeIndex = (trigger) => {
    if (!trigger) {
      return;
    }
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
    }
  };

  window.setupImageUploadWidget = (input, dropzone, onUpload) => {
    if (!input || !dropzone || dropzone.dataset.initialized === "true") {
      return;
    }

    dropzone.dataset.initialized = "true";
    const instructions = dropzone.querySelector(".upload-instructions");
    const getEnabledText = () =>
      instructions?.dataset.enabledText || instructions?.textContent || "";
    const uploadingMessage = getI18n("uploading", "Uploading...");

    const setUploadingState = (isUploading) => {
      dropzone.classList.toggle("opacity-70", isUploading);
      dropzone.classList.toggle("pointer-events-none", isUploading);
      dropzone.classList.toggle("cursor-wait", isUploading);
      dropzone.setAttribute("aria-busy", String(Boolean(isUploading)));
      if (instructions) {
        instructions.textContent = isUploading
          ? uploadingMessage
          : getEnabledText();
      }
    };

    const handleFiles = async (files) => {
      const fileList = Array.from(files || []);
      if (!fileList.length) {
        return;
      }

      const accept = (input.getAttribute("accept") || "").trim();
      const requiresImages =
        accept.includes("image/") &&
        !accept.includes("*/*") &&
        !accept.split(",").some((item) => item.trim() === "*/*");
      const acceptedFiles = requiresImages
        ? fileList.filter((file) => file.type.startsWith("image/"))
        : fileList;
      if (!acceptedFiles.length) {
        window.showToast?.(
          getI18n("onlyImages", "Only image files are supported"),
          "error",
        );
        return;
      }

      setUploadingState(true);
      try {
        for (const file of acceptedFiles) {
          await onUpload(file);
        }
      } finally {
        setUploadingState(false);
      }
    };

    input.addEventListener("change", async (e) => {
      await handleFiles(e.target.files);
      input.value = "";
    });

    ["dragover", "dragleave", "drop"].forEach((name) => {
      dropzone.addEventListener(name, (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (name === "dragover") {
          dropzone.classList.add("border-primary");
        } else {
          dropzone.classList.remove("border-primary");
        }
      });
    });

    dropzone.addEventListener("drop", async (e) => {
      const files = e.dataTransfer?.files;
      if (files && files.length > 0) {
        await handleFiles(files);
      }
    });
  };

  window.previewMarkdown = async (source) => {
    const textarea =
      typeof source === "string" ? document.getElementById(source) : source;
    if (!textarea) {
      return;
    }

    const content = textarea.value;
    if (!content.trim()) {
      window.showToast?.(
        getI18n("nothingToPreview", "Nothing to preview"),
        "info",
      );
      return;
    }

    const formData = new FormData();
    formData.append("content", content);

    try {
      const csrfToken = getCsrfToken();
      const response = await fetch("/utils/preview/markdown", {
        method: "POST",
        headers: csrfToken ? { "X-CSRF-Token": csrfToken } : {},
        body: formData,
      });

      if (response.ok) {
        const html = await response.text();
        const dialog = document.getElementById("previewDialog");
        const contentDiv = document.getElementById("previewContent");
        if (dialog && contentDiv) {
          contentDiv.innerHTML = html;
          dialog.showModal();
        }
      }
    } catch (error) {
      console.error("Preview failed", error);
    }
  };

  window.setTheme = (newTheme) => {
    const html = document.documentElement;
    html.setAttribute("data-theme", newTheme);
    localStorage.setItem("theme", newTheme);

    if (newTheme === "dark") {
      html.classList.add("dark");
    } else {
      html.classList.remove("dark");
    }

    window.updateThemeUI?.(newTheme);
  };

  window.toggleTheme = () => {
    const currentTheme =
      document.documentElement.getAttribute("data-theme") || "light";
    const newTheme = currentTheme === "dark" ? "light" : "dark";
    window.setTheme(newTheme);
  };

  window.updateThemeUI = (theme) => {
    const isDark = theme === "dark";
    const label = document.getElementById("theme-label");
    const icon = document.getElementById("theme-icon");
    const iconPublic = document.getElementById("theme-icon-public");

    const lightBtn = document.getElementById("theme-btn-light");
    const darkBtn = document.getElementById("theme-btn-dark");

    if (label) {
      label.textContent = isDark
        ? getI18n("darkMode", "Dark Mode")
        : getI18n("lightMode", "Light Mode");
    }

    const iconClass = isDark ? "mdi-weather-night" : "mdi-weather-sunny";
    const iconColor = isDark ? "text-blue-400" : "text-amber-500";

    if (icon) {
      icon.className = `mdi ${iconClass} text-xl transition-all duration-300 ${iconColor}`;
    }

    if (iconPublic) {
      iconPublic.className = `mdi ${iconClass} text-xl transition-all duration-300 ${iconColor}`;
    }

    if (lightBtn && darkBtn) {
      if (isDark) {
        darkBtn.classList.add("bg-base-100", "shadow-sm", "text-primary");
        darkBtn.classList.remove("btn-ghost", "opacity-60");
        lightBtn.classList.remove("bg-base-100", "shadow-sm", "text-primary");
        lightBtn.classList.add("btn-ghost", "opacity-60");
      } else {
        lightBtn.classList.add("bg-base-100", "shadow-sm", "text-primary");
        lightBtn.classList.remove("btn-ghost", "opacity-60");
        darkBtn.classList.remove("bg-base-100", "shadow-sm", "text-primary");
        darkBtn.classList.add("btn-ghost", "opacity-60");
      }
    }
  };

  window.toggleAuthForms = (showId, hideId) => {
    const showEl = showId ? document.getElementById(showId) : null;
    const hideEl = hideId ? document.getElementById(hideId) : null;
    hideEl?.classList.add("hidden");
    showEl?.classList.remove("hidden");
  };

  document.addEventListener("DOMContentLoaded", () => {
    // Theme UI init.
    const currentTheme =
      document.documentElement.getAttribute("data-theme") || "light";
    window.updateThemeUI?.(currentTheme);

    // Toast via query param.
    const urlParams = new URLSearchParams(window.location.search);
    const toastKey = urlParams.get("toast");
    if (toastKey) {
      const toastMessages = config.toastMessages || {};
      const entry = toastMessages[toastKey];
      const message = typeof entry === "string" ? entry : entry?.message;
      if (message) {
        const variant =
          typeof entry === "string" ? "success" : entry?.variant || "success";
        window.showToast?.(message, variant);

        const url = new URL(window.location.href);
        url.searchParams.delete("toast");
        window.history.replaceState({}, "", url.toString());
      }
    }

    // Attachment open handlers.
    document.addEventListener("click", (event) => {
      const row = event.target.closest('[data-action="open-attachment"]');
      if (!row) {
        return;
      }
      if (event.target.closest("a,button,input,select,textarea")) {
        return;
      }
      event.preventDefault();

      const kind = row.dataset.attachmentKind;
      const url = row.dataset.attachmentUrl;
      const name = row.dataset.attachmentName;

      if (kind === "pdf") {
        window.openPdfViewer(url, name);
      } else if (kind === "image") {
        if (
          row.hasAttribute("data-pswp-open-index") &&
          window.openPhotoSwipeIndex
        ) {
          window.openPhotoSwipeIndex(row);
        } else {
          window.openImageViewer(url, name);
        }
      } else if (url) {
        window.open(url, "_blank", "noopener,noreferrer");
      }
    });

    // Generic actions: keep templates free of inline event handlers.
    document.addEventListener("click", (event) => {
      const actionEl = event.target.closest("[data-action]");
      if (!actionEl) {
        return;
      }

      const action = actionEl.getAttribute("data-action") || "";
      if (action === "open-dialog") {
        const dialogId = actionEl.getAttribute("data-dialog-id") || "";
        if (dialogId) {
          event.preventDefault();
          window.openDialog?.(dialogId);
        }
      } else if (action === "close-dialog") {
        const dialogId = actionEl.getAttribute("data-dialog-id") || "";
        if (dialogId) {
          event.preventDefault();
          window.closeDialog?.(dialogId);
        }
      } else if (action === "reload") {
        event.preventDefault();
        window.location.reload();
      } else if (action === "click-target") {
        const targetId = actionEl.getAttribute("data-target-id") || "";
        if (targetId) {
          const target = document.getElementById(targetId);
          if (target && typeof target.click === "function") {
            event.preventDefault();
            target.click();
          }
        }
      }
    });

    const resolveCallArg = (arg, element, eventObj) => {
      if (arg === "$this") {
        return element;
      }
      if (arg === "$event") {
        return eventObj;
      }
      if (arg === "$value") {
        return element?.value;
      }
      if (arg === "$checked") {
        return element?.checked;
      }
      if (typeof arg === "string" && arg.startsWith("$value:")) {
        const selector = arg.slice("$value:".length);
        const target = selector ? document.querySelector(selector) : null;
        return target?.value;
      }
      if (typeof arg === "string" && arg.startsWith("$text:")) {
        const selector = arg.slice("$text:".length);
        const target = selector ? document.querySelector(selector) : null;
        return target?.textContent || "";
      }
      if (typeof arg === "string" && arg.startsWith("$dataset:")) {
        const key = arg.slice("$dataset:".length);
        return key ? element?.dataset?.[key] : undefined;
      }
      return arg;
    };

    const parseCallArgs = (raw) => {
      if (!raw) {
        return null;
      }
      try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : null;
      } catch {
        return null;
      }
    };

    const invokeCall = (element, eventObj, fnName, rawArgs) => {
      if (!fnName) {
        return;
      }
      const fn = window[fnName];
      if (typeof fn !== "function") {
        return;
      }

      const args = parseCallArgs(rawArgs);
      if (args) {
        const resolvedArgs = args.map((arg) =>
          resolveCallArg(arg, element, eventObj),
        );
        fn(...resolvedArgs);
      } else {
        if (rawArgs) {
          console.warn(`Failed to parse call args for ${fnName}:`, rawArgs);
        }
        fn(element);
      }
    };

    // Generic function calls for click/input/change.
    document.addEventListener("click", (event) => {
      const el = event.target.closest("[data-call]");
      if (!el) {
        return;
      }
      event.preventDefault();
      invokeCall(
        el,
        event,
        el.getAttribute("data-call"),
        el.getAttribute("data-call-args"),
      );
    });

    document.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") {
        return;
      }
      const el = event.target.closest?.("[data-call]");
      if (!el) {
        return;
      }

      // Avoid hijacking typing in form controls.
      if (event.target.closest("input,textarea,select")) {
        return;
      }

      // Let native elements handle their own key behavior.
      const tag = el.tagName.toLowerCase();
      if (tag === "button" || tag === "a") {
        return;
      }

      event.preventDefault();
      invokeCall(
        el,
        event,
        el.getAttribute("data-call"),
        el.getAttribute("data-call-args"),
      );
    });

    document.addEventListener("input", (event) => {
      const el = event.target.closest("[data-call-input]");
      if (!el) {
        return;
      }
      invokeCall(
        el,
        event,
        el.getAttribute("data-call-input"),
        el.getAttribute("data-call-input-args"),
      );
    });

    document.addEventListener("change", (event) => {
      const el = event.target.closest("[data-call-change]");
      if (!el) {
        return;
      }
      invokeCall(
        el,
        event,
        el.getAttribute("data-call-change"),
        el.getAttribute("data-call-change-args"),
      );
    });

    document.addEventListener("keydown", (event) => {
      const row = document.activeElement?.closest?.(
        '[data-action="open-attachment"]',
      );
      if (!row) {
        return;
      }
      if (event.key !== "Enter" && event.key !== " ") {
        return;
      }
      event.preventDefault();

      const kind = row.dataset.attachmentKind;
      const url = row.dataset.attachmentUrl;
      const name = row.dataset.attachmentName;

      if (kind === "pdf") {
        window.openPdfViewer(url, name);
      } else if (kind === "image") {
        if (
          row.hasAttribute("data-pswp-open-index") &&
          window.openPhotoSwipeIndex
        ) {
          window.openPhotoSwipeIndex(row);
        } else {
          window.openImageViewer(url, name);
        }
      } else if (url) {
        window.open(url, "_blank", "noopener,noreferrer");
      }
    });

    document
      .getElementById("pdfViewerDialog")
      ?.addEventListener("close", () => {
        const frame = document.getElementById("pdfViewerFrame");
        if (frame) {
          frame.src = "about:blank";
        }
      });

    document
      .getElementById("imageViewerDialog")
      ?.addEventListener("close", () => {
        const image = document.getElementById("imageViewerImage");
        if (image) {
          image.src = "about:blank";
          image.alt = "";
        }
      });

    // Close daisyUI dropdowns on Escape and on outside click.
    const closeDropdowns = () => {
      document.querySelectorAll(".dropdown").forEach((d) => {
        d.blur();
      });

      if (document.activeElement instanceof HTMLElement) {
        const activeDropdown = document.activeElement.closest(".dropdown");
        if (activeDropdown) {
          document.activeElement.blur();
        }
      }
    };

    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        closeDropdowns();
      }
    });

    document.addEventListener("click", (e) => {
      if (!e.target.closest(".dropdown")) {
        closeDropdowns();
      }
    });

    const isTypingTarget = (target) => {
      if (!(target instanceof Element)) {
        return false;
      }
      if (target.closest('input, textarea, select, [contenteditable="true"]')) {
        return true;
      }
      return target.closest("[data-shortcuts-ignore]") !== null;
    };

    const hasOpenBlockingUi = () => {
      if (document.querySelector("dialog[open]")) {
        return true;
      }
      return !!document.querySelector(".pswp.pswp--open");
    };

    const navigateTo = (href) => {
      if (!href) {
        return false;
      }
      window.location.href = href;
      return true;
    };

    const getMainShortcutConfig = () => {
      const main = document.querySelector("main");
      if (!main) {
        return null;
      }
      return {
        scope: main.dataset.shortcutsScope || "",
        createHref: main.dataset.shortcutCreateHref || "",
        editHref: main.dataset.shortcutEditHref || "",
        importDialogId: main.dataset.shortcutImportDialog || "",
        deleteDialogId: main.dataset.shortcutDeleteDialog || "",
        prevHref: main.dataset.swipePrevHref || "",
        nextHref: main.dataset.swipeNextHref || "",
      };
    };

    const openDeleteDialog = (dialogId) => {
      const trigger = dialogId
        ? document.querySelector(
            `[data-action="open-dialog"][data-dialog-id="${dialogId}"]`,
          )
        : document.querySelector(
            '[data-action="open-dialog"][data-dialog-id^="delete"]',
          );

      if (trigger && typeof trigger.click === "function") {
        trigger.click();
        return true;
      }

      if (dialogId) {
        return window.openDialog?.(dialogId) || false;
      }

      return false;
    };

    document.addEventListener("keydown", (event) => {
      if (event.defaultPrevented) {
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }
      if (isTypingTarget(event.target)) {
        return;
      }
      if (hasOpenBlockingUi()) {
        return;
      }

      const shortcutConfig = getMainShortcutConfig();
      if (!shortcutConfig?.scope) {
        return;
      }

      if (shortcutConfig.scope === "list") {
        if (event.key === "c") {
          if (navigateTo(shortcutConfig.createHref)) {
            event.preventDefault();
          }
          return;
        }
        if (event.key === "i") {
          if (
            shortcutConfig.importDialogId &&
            window.openDialog?.(shortcutConfig.importDialogId)
          ) {
            event.preventDefault();
          }
        }
        return;
      }

      if (shortcutConfig.scope !== "detail") {
        return;
      }

      if (event.key === "D") {
        if (openDeleteDialog(shortcutConfig.deleteDialogId)) {
          event.preventDefault();
        }
        return;
      }

      if (event.key === "e") {
        if (navigateTo(shortcutConfig.editHref)) {
          event.preventDefault();
        }
        return;
      }

      if (event.key === "n") {
        if (navigateTo(shortcutConfig.nextHref)) {
          event.preventDefault();
        }
        return;
      }

      if (event.key === "p") {
        if (navigateTo(shortcutConfig.prevHref)) {
          event.preventDefault();
        }
      }
    });

    // HTMX toast integration (errors + declarative success toasts).
    document.body.addEventListener("htmx:responseError", async (event) => {
      const message = await extractErrorMessage(event.detail.xhr, null);
      window.showToast?.(message, "error");
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
      const element = event.detail?.requestConfig?.elt || event.detail?.elt;
      if (!element) {
        return;
      }
      const status = event.detail?.xhr?.status ?? 200;
      if (status >= 400) {
        return;
      }

      const toastElement =
        element.getAttribute("data-toast-message") !== null
          ? element
          : element.querySelector?.("[data-toast-message]");
      if (!toastElement) {
        return;
      }
      const message = toastElement.getAttribute("data-toast-message");
      if (!message) {
        return;
      }
      const variant = toastElement.getAttribute("data-toast-variant") || "info";
      window.showToast?.(message, variant);

      const dialogId = element.getAttribute("data-dialog-close");
      if (dialogId) {
        window.closeDialog?.(dialogId);
      }
    });
  });
})();
