(() => {
  document.addEventListener("DOMContentLoaded", () => {
    let cropper;

    const input = document.getElementById("profile-image-input");
    const modal = document.getElementById("crop-modal");
    const cropContainer = document.getElementById("avatar-crop-container");
    const cropOverlay = document.getElementById("avatar-crop-overlay");
    const image = document.getElementById("crop-image");
    const saveBtn = document.getElementById("crop-save");
    const cancelBtn = document.getElementById("crop-cancel");

    const cfg = window.STRICKNANI?.profileCropper || {};
    const i18n = window.STRICKNANI?.i18n || {};
    const currentUserId = Number.parseInt(cfg.currentUserId ?? "0", 10) || 0;

    let targetUserId = currentUserId;

    if (
      !input ||
      !modal ||
      !cropContainer ||
      !cropOverlay ||
      !image ||
      !saveBtn ||
      !cancelBtn ||
      !currentUserId
    ) {
      return;
    }

    window.triggerProfileImageUpload = (userId) => {
      const parsed = Number.parseInt(String(userId || ""), 10);
      targetUserId =
        Number.isFinite(parsed) && parsed > 0 ? parsed : currentUserId;
      input.click();
    };

    function getCropperCtor() {
      // CropperJS v2 UMD exposes `window.Cropper.default`.
      // CropperJS v1 exposes `window.Cropper` directly.
      const cropperNamespace = window.Cropper;
      const ctor = cropperNamespace?.default || cropperNamespace;

      if (typeof ctor !== "function") {
        return null;
      }

      return ctor;
    }

    function configureCropperSelection() {
      if (!cropper || typeof cropper.getCropperSelection !== "function") {
        return;
      }

      const selection = cropper.getCropperSelection();
      if (!selection) {
        return;
      }

      // Fixed circular crop: keep selection size fixed and let the user move/zoom the image.
      selection.setAttribute("aspect-ratio", "1");
      selection.setAttribute("initial-coverage", "1");
      selection.removeAttribute("movable");
      selection.removeAttribute("resizable");
    }

    function updateAvatarCropOverlay() {
      const w = cropContainer.clientWidth;
      const h = cropContainer.clientHeight;
      const size = Math.max(0, Math.floor(Math.min(w, h)));
      const radius = Math.max(0, Math.floor(size / 2));

      cropOverlay.style.setProperty("--avatar-crop-size", `${size}px`);
      cropOverlay.style.setProperty("--avatar-crop-radius", `${radius}px`);
    }

    function getCroppedCanvas() {
      if (!cropper) {
        return Promise.reject(new Error("Cropper not initialized"));
      }

      // CropperJS v2: export from the selection element.
      if (typeof cropper.getCropperSelection === "function") {
        const selection = cropper.getCropperSelection();
        if (selection && typeof selection.$toCanvas === "function") {
          return selection.$toCanvas({
            width: 400,
            height: 400,
          });
        }
      }

      // CropperJS v1 fallback.
      if (typeof cropper.getCroppedCanvas === "function") {
        return Promise.resolve(
          cropper.getCroppedCanvas({
            width: 400,
            height: 400,
          }),
        );
      }

      return Promise.reject(new Error("Unsupported CropperJS API"));
    }

    input.addEventListener("change", (e) => {
      const files = e.target.files;
      if (!files || !files.length) {
        return;
      }

      const file = files[0];
      const reader = new FileReader();
      reader.onload = (readerEvent) => {
        image.src = readerEvent.target.result;
        modal.showModal();

        if (cropper) {
          cropper.destroy();
        }

        const ctor = getCropperCtor();
        if (!ctor) {
          window.showToast?.(i18n.imageCropperFailedToLoad, "error");
          modal.close();
          return;
        }

        cropper = new ctor(image, {});
        window.requestAnimationFrame(() => {
          configureCropperSelection();
          updateAvatarCropOverlay();
          window.requestAnimationFrame(updateAvatarCropOverlay);
        });
      };
      reader.readAsDataURL(file);
    });

    window.addEventListener("resize", () => {
      if (!modal.open) {
        return;
      }
      updateAvatarCropOverlay();
    });

    cancelBtn.addEventListener("click", () => {
      modal.close();
      input.value = "";
      if (cropper) {
        cropper.destroy();
        cropper = null;
      }
    });

    saveBtn.addEventListener("click", () => {
      if (!cropper) {
        return;
      }

      const csrfToken = document
        .querySelector('meta[name="csrf-token"]')
        ?.getAttribute("content");

      getCroppedCanvas()
        .then((canvas) => {
          return new Promise((resolve) => {
            canvas.toBlob((blob) => resolve(blob), "image/png");
          });
        })
        .then((blob) => {
          if (!blob) {
            window.showToast?.(i18n.failedToUploadImage, "error");
            return;
          }

          const formData = new FormData();
          formData.append("file", blob, "avatar.png");

          const originalText = saveBtn.textContent;
          saveBtn.textContent = i18n.uploading;
          saveBtn.disabled = true;

          const url =
            targetUserId === currentUserId
              ? "/user/profile-image"
              : `/admin/users/${targetUserId}/profile-image`;

          fetch(url, {
            method: "POST",
            body: formData,
            headers: {
              "HX-Request": "true",
              ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
            },
          })
            .then((response) => {
              if (!response.ok) {
                if (response.status === 401 || response.status === 403) {
                  window.showToast?.(i18n.sessionExpiredMessage, "error");
                  window.confirmAction?.(
                    i18n.sessionExpiredTitle,
                    i18n.sessionExpiredMessage,
                    () => window.location.reload(),
                    null,
                    {
                      confirmText: i18n.reloadPage,
                      cancelText: i18n.cancel,
                    },
                  );
                  throw new Error("Upload failed (CSRF)");
                }
                throw new Error("Upload failed");
              }
              return response.text();
            })
            .then((html) => {
              if (targetUserId === currentUserId) {
                window.location.reload();
                return;
              }

              const card = document.getElementById(`user-card-${targetUserId}`);
              if (card) {
                const parser = new DOMParser();
                const doc = parser.parseFromString(html, "text/html");

                const newCard = doc.getElementById(`user-card-${targetUserId}`);
                if (newCard) {
                  card.replaceWith(newCard);
                  window.htmx?.process?.(newCard);
                }

                const newCount = doc.getElementById("user-count");
                const currentCount = document.getElementById("user-count");
                if (newCount && currentCount) {
                  currentCount.replaceWith(newCount);
                }
              }

              modal.close();

              const editUserDialog = document.getElementById("editUserDialog");
              if (editUserDialog && editUserDialog.open) {
                editUserDialog.close();
              }

              window.showToast?.(
                i18n.profilePictureUpdatedSuccessfully,
                "success",
              );
            })
            .catch((err) => {
              console.error(err);
              window.showToast?.(i18n.failedToUploadImage, "error");
            })
            .finally(() => {
              saveBtn.textContent = originalText;
              saveBtn.disabled = false;
            });
        })
        .catch((err) => {
          console.error(err);
          window.showToast?.(i18n.failedToUploadImage, "error");
        });
    });
  });
})();
