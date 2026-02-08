/**
 * Image cropping functionality for PhotoSwipe.
 * Uses Cropper.js for the cropping interface.
 */
(() => {
    const i18n = window.STRICKNANI?.i18n || {};

    const t = (key, fallback = "") => {
        const value = i18n?.[key];
        if (typeof value === "string" && value.length) {
            return value;
        }
        return fallback;
    };

    let cropperInstance = null;
    let currentImageSrc = null;
    let currentImageElement = null;

    /**
     * Initialize the crop dialog and its event handlers.
     */
    const initCropDialog = () => {
        const dialog = document.getElementById('pswpCropDialog');
        if (!dialog) {
            console.warn('Crop dialog not found');
            return;
        }

        const cropImage = document.getElementById('pswpCropImage');
        const saveBtn = document.getElementById('pswpCropSave');
        const cancelBtn = document.getElementById('pswpCropCancel');
        const resetBtn = document.getElementById('pswpCropReset');
        const loadingOverlay = document.getElementById('pswpCropLoadingOverlay');
        const loadingStatus = document.getElementById('pswpCropLoadingStatus');

        if (!cropImage || !saveBtn || !cancelBtn || !resetBtn) {
            console.warn('Crop dialog elements not found');
            return;
        }

        const setLoading = (isLoading, message = '') => {
            if (loadingOverlay) {
                loadingOverlay.classList.toggle('hidden', !isLoading);
            }
            if (loadingStatus) {
                loadingStatus.textContent = message;
            }
            saveBtn.disabled = isLoading;
            cancelBtn.disabled = isLoading;
            resetBtn.disabled = isLoading;
        };

        const destroyCropper = () => {
            if (cropperInstance) {
                cropperInstance.destroy();
                cropperInstance = null;
            }
        };

        const closeCropDialog = () => {
            destroyCropper();
            dialog.close();
            currentImageSrc = null;
            currentImageElement = null;
        };

        cancelBtn.addEventListener('click', () => {
            closeCropDialog();
        });

        resetBtn.addEventListener('click', () => {
            if (cropperInstance) {
                cropperInstance.reset();
            }
        });

        saveBtn.addEventListener('click', async () => {
            if (!cropperInstance || !currentImageElement) {
                return;
            }

            setLoading(true, t('pswpSavingCrop', 'Saving cropped image...'));

            try {
                // Get the cropped canvas
                const canvas = cropperInstance.getCroppedCanvas({
                    maxWidth: 4096,
                    maxHeight: 4096,
                    imageSmoothingEnabled: true,
                    imageSmoothingQuality: 'high',
                });

                if (!canvas) {
                    throw new Error('Failed to get cropped canvas');
                }

                // Convert canvas to blob
                const blob = await new Promise((resolve, reject) => {
                    canvas.toBlob((b) => {
                        if (b) resolve(b);
                        else reject(new Error('Failed to create blob'));
                    }, 'image/jpeg', 0.95);
                });

                // Get the image ID from the element
                const imageId = currentImageElement.getAttribute('data-image-id');
                const attachmentId = currentImageElement.getAttribute('data-attachment-id');

                if (!imageId && !attachmentId) {
                    throw new Error('No image ID or attachment ID found');
                }

                // Prepare form data
                const formData = new FormData();
                formData.append('cropped_image', blob, 'cropped.jpg');
                formData.append('src', currentImageSrc);
                if (imageId) {
                    formData.append('image_id', imageId);
                }
                if (attachmentId) {
                    formData.append('attachment_id', attachmentId);
                }

                // Get CSRF token
                const csrfToken = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content');
                const headers = {};
                if (csrfToken) {
                    headers['X-CSRF-Token'] = csrfToken;
                }

                // Send to backend
                const response = await fetch('/utils/crop-image', {
                    method: 'POST',
                    headers,
                    body: formData,
                });

                if (!response.ok) {
                    const error = await response.json().catch(() => ({}));
                    throw new Error(error.detail || 'Failed to save cropped image');
                }

                const result = await response.json();

                // Update the image in the page
                if (result.url) {
                    // Add cache buster to force reload
                    const newUrl = result.url + '?t=' + Date.now();

                    // Update all instances of this image
                    document.querySelectorAll(`[data-image-id="${imageId}"], [data-attachment-id="${attachmentId}"]`).forEach(el => {
                        if (el.tagName === 'IMG') {
                            el.src = newUrl;
                        } else if (el.tagName === 'A') {
                            el.href = newUrl;
                            const img = el.querySelector('img');
                            if (img) {
                                img.src = newUrl;
                            }
                        }
                    });
                }

                window.showToast?.(t('pswpCropSaved', 'Image cropped successfully'), 'success');
                closeCropDialog();

                // Refresh PhotoSwipe if it's open
                if (window.pswpLightboxes) {
                    window.pswpLightboxes.forEach(lightbox => {
                        if (lightbox.pswp) {
                            lightbox.pswp.close();
                        }
                    });
                }

            } catch (error) {
                console.error('Failed to save cropped image:', error);
                window.showToast?.(t('pswpCropFailed', 'Failed to save cropped image'), 'error');
            } finally {
                setLoading(false);
            }
        });
    };

    /**
     * Open the crop dialog with the given image.
     */
    window.openCropDialog = (imageSrc, imageElement) => {
        const dialog = document.getElementById('pswpCropDialog');
        const cropImage = document.getElementById('pswpCropImage');

        if (!dialog || !cropImage) {
            console.warn('Crop dialog elements not found');
            return;
        }

        currentImageSrc = imageSrc;
        currentImageElement = imageElement;

        // Destroy existing cropper if any
        if (cropperInstance) {
            cropperInstance.destroy();
            cropperInstance = null;
        }

        // Load the image
        cropImage.src = imageSrc;

        // Show dialog
        dialog.showModal();

        // Initialize Cropper.js when image loads
        cropImage.onload = () => {
            if (typeof Cropper === 'undefined') {
                console.error('Cropper.js not loaded');
                window.showToast?.(t('pswpCropperNotAvailable', 'Image cropper not available'), 'error');
                dialog.close();
                return;
            }

            cropperInstance = new Cropper(cropImage, {
                viewMode: 1,
                dragMode: 'move',
                aspectRatio: NaN, // Free aspect ratio
                autoCropArea: 1,
                restore: false,
                guides: true,
                center: true,
                highlight: false,
                cropBoxMovable: true,
                cropBoxResizable: true,
                toggleDragModeOnDblclick: false,
            });
        };
    };

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initCropDialog);
    } else {
        initCropDialog();
    }
})();
