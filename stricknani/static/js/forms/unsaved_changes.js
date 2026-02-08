/* Unsaved changes guard for form pages.
 *
 * Templates provide:
 * - a `<dialog id="unsavedChangesDialog">` with buttons using data attributes:
 *   `data-unsaved-cancel`, `data-unsaved-leave`, `data-unsaved-save`.
 * - links/buttons that should be guarded include `data-unsaved-confirm`.
 *
 * This script exposes `window.unsavedChanges` with an optional `onBeforeDiscard`
 * hook that can be set by page scripts.
 */

(function () {
  "use strict";

  const install = () => {
    if (window.__stricknaniUnsavedChangesInstalled) {
      return;
    }
    window.__stricknaniUnsavedChangesInstalled = true;

    // Initialize as soon as possible; preserve existing properties (e.g. hooks).
    window.unsavedChanges = {
      _isDirty: false,
      setDirty: function (value = true) {
        this._isDirty = value;
      },
      isDirty: function () {
        return this._isDirty;
      },
      onBeforeDiscard: null, // async hook
      ...window.unsavedChanges,
    };

    const dialog = document.getElementById("unsavedChangesDialog");
    const cancelButton = dialog?.querySelector("[data-unsaved-cancel]");
    const leaveButton = dialog?.querySelector("[data-unsaved-leave]");
    const saveButton = dialog?.querySelector("[data-unsaved-save]");
    let pendingNavigation = null;
    let ignoreNextPopstate = false;
    let hasHistoryGuard = false;

    // Track changes on any form element within main.
    document.addEventListener("input", (e) => {
      if (e.target.closest("main form")) {
        window.unsavedChanges.setDirty(true);
      }
    });

    document.addEventListener("change", (e) => {
      if (e.target.closest("main form")) {
        window.unsavedChanges.setDirty(true);
      }
    });

    // Allow submission without warning.
    document.addEventListener("submit", (e) => {
      if (e.target.closest("main form")) {
        window.unsavedChanges.setDirty(false);
      }
    });

    const ensureHistoryGuard = () => {
      if (window.history.state?.unsavedGuard) {
        hasHistoryGuard = true;
        return;
      }
      window.history.pushState({ unsavedGuard: true }, "");
      hasHistoryGuard = true;
    };

    const showUnsavedDialog = (href) => {
      if (!dialog) {
        return;
      }
      pendingNavigation = href || null;
      dialog.showModal();
    };

    const handleConfirmNav = (event) => {
      if (!window.unsavedChanges.isDirty()) {
        return;
      }
      event.preventDefault();
      const target =
        event.currentTarget.getAttribute("href") ||
        event.currentTarget.dataset.href;
      showUnsavedDialog(target);
    };

    // Delegate click listener for all elements with data-unsaved-confirm.
    document.addEventListener("click", (e) => {
      const confirmElem = e.target.closest("[data-unsaved-confirm]");
      if (
        confirmElem &&
        (confirmElem.tagName.toLowerCase() === "a" || confirmElem.dataset.href)
      ) {
        handleConfirmNav({
          preventDefault: () => e.preventDefault(),
          currentTarget: confirmElem,
        });
      }
    });

    ensureHistoryGuard();

    window.addEventListener("popstate", () => {
      if (!hasHistoryGuard) {
        return;
      }
      if (ignoreNextPopstate) {
        ignoreNextPopstate = false;
        return;
      }
      if (window.unsavedChanges.isDirty()) {
        pendingNavigation = "history-back";
        showUnsavedDialog(null);
        window.history.pushState({ unsavedGuard: true }, "");
        return;
      }
      ignoreNextPopstate = true;
      window.history.back();
    });

    cancelButton?.addEventListener("click", () => {
      dialog?.close();
    });

    leaveButton?.addEventListener("click", async () => {
      if (window.unsavedChanges.onBeforeDiscard) {
        try {
          await window.unsavedChanges.onBeforeDiscard();
        } catch (err) {
          console.error("Error in onBeforeDiscard hook:", err);
        }
      }
      window.unsavedChanges.setDirty(false);
      dialog?.close();
      const target = pendingNavigation;
      pendingNavigation = null;
      if (target) {
        if (target === "history-back") {
          ignoreNextPopstate = true;
          window.history.go(-2);
        } else {
          window.location.href = target;
        }
      } else {
        window.history.back();
      }
    });

    saveButton?.addEventListener("click", () => {
      const form =
        document.querySelector('main form[data-primary-form="true"]') ||
        document.querySelector("main form");
      if (!form) {
        return;
      }
      if (form.requestSubmit) {
        form.requestSubmit();
      } else {
        form.submit();
      }
    });

    dialog?.addEventListener("close", () => {
      pendingNavigation = null;
    });

    // Warn before leaving.
    window.addEventListener("beforeunload", (e) => {
      if (window.unsavedChanges.isDirty()) {
        e.preventDefault();
        e.returnValue = "";
      }
    });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", install);
  } else {
    install();
  }
})();
