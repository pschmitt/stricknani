<!DOCTYPE html>
<html lang="en" class="h-full antialiased transition-colors duration-200">

<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Edit Project - Stricknani</title>
    <meta name="csrf-token" content="facd35f37e917124753039c2e39e2f26e142ba6c">
    
    <link rel="icon" type="image/svg+xml"
        href="/static/favicon-dev.svg">
    <link rel="stylesheet" href="http://localhost:7674/static/vendor/mdi/css/materialdesignicons.min.css">
    <link rel="stylesheet" href="http://localhost:7674/static/vendor/photoswipe/photoswipe.css">
    <link rel="stylesheet" href="http://localhost:7674/static/css/app.css">
    <script>
        window.STRICKNANI = window.STRICKNANI || {};
        window.STRICKNANI.i18n = {"cancel": "Cancel", "confirm": "Confirm", "copiedToClipboard": "Copied to clipboard", "darkMode": "Dark Mode", "dismissMessage": "Dismiss message", "failedToCopy": "Failed to copy", "failedToUploadImage": "Failed to upload image", "imageCropperFailedToLoad": "Image cropper failed to load", "lightMode": "Light Mode", "nothingToPreview": "Nothing to preview", "onlyImages": "Only image files are supported", "profilePictureUpdatedSuccessfully": "Profile picture updated successfully", "pswpDeleteImage": "Delete image", "pswpDownloadImage": "Download image", "pswpExtractText": "Extract text", "pswpExtractingText": "Extracting text...", "pswpGalleryThumbnails": "Gallery thumbnails", "pswpImageCannotBeProcessed": "This image cannot be processed.", "pswpNoTextDetected": "No text detected.", "pswpOcrFailed": "OCR failed.", "pswpOcrNotAvailable": "OCR is not available on this server.", "pswpOpenImage": "Open image", "pswpSetAsPrimary": "Set as primary", "pswpTextExtracted": "Text extracted.", "reloadPage": "Reload page", "sessionExpiredMessage": "Your session has expired. Reload the page to continue.", "sessionExpiredTitle": "Session expired", "somethingWentWrong": "Something went wrong. Please try again", "uploading": "Uploading...", "wysiwygLinkUrl": "Enter URL:"};
        window.STRICKNANI.toastMessages = {"archive_request_unavailable": {"message": "Archive snapshot request unavailable", "variant": "info"}, "archive_requested": {"message": "Archive snapshot request queued", "variant": "success"}, "category_created": "Category created", "category_deleted": "Category deleted", "category_updated": "Category updated", "profile_updated": "Profile updated successfully", "project_created": "Project created successfully", "project_deleted": "Project deleted", "project_updated": "Project updated successfully", "yarn_created": "Yarn created successfully", "yarn_deleted": "Yarn deleted", "yarn_updated": "Yarn updated successfully"};
    </script>
    <script src="http://localhost:7674/static/js/app.js"></script>
    <script src="http://localhost:7674/static/vendor/cropperjs/cropper.min.js"></script>
    <script>
        // Theme initialization - MUST run before page renders
        (function () {
            let theme = localStorage.getItem('theme');
            if (!theme) {
                if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                    theme = 'dark';
                } else {
                    theme = 'light';
                }
            }
            document.documentElement.setAttribute('data-theme', theme);
            // Also set dark class for Tailwind dark mode
            if (theme === 'dark') {
                document.documentElement.classList.add('dark');
            }
        })();
    </script>


    <link href="http://localhost:7674/static/vendor/daisyui/daisyui.css" rel="stylesheet" type="text/css" />
    <link href="http://localhost:7674/static/vendor/daisyui/themes.css" rel="stylesheet" type="text/css" />
    <script src="http://localhost:7674/static/vendor/tailwindcss-browser/index.global.js"></script>
    <script src="http://localhost:7674/static/vendor/htmx/htmx.min.js"></script>
    <script src="http://localhost:7674/static/js/htmx/csrf.js"></script>
    
</head>

<body class="min-h-screen bg-base-300 flex flex-col">
    

    







<nav class="navbar bg-base-100 shadow-sm border-b border-base-300 sticky top-0 z-[80] min-h-12">
    <div class="max-w-none mx-auto px-4 md:px-8 lg:px-10 w-full">
        <div class="flex items-center justify-between h-12 w-full gap-4">
            
            <div class="flex items-center gap-3 md:gap-8 min-w-0 flex-1">
                
                
                <div class="w-8 h-8 flex items-center justify-center shrink-0">
                    <a href="/projects/2" class="btn btn-circle btn-ghost btn-sm text-base-content/60"
                        aria-label="Back to projects" data-unsaved-confirm="true">
                        <span class="mdi mdi-arrow-left text-xl"></span>
                    </a>
                </div>
                

                
                <div class="dropdown dropdown-start md:dropdown-start navbar-nav-dropdown">
                    <button tabindex="0" role="button"
                        class="flex items-center gap-2 text-xl md:text-2xl font-extrabold text-base-content hover:text-primary transition-colors cursor-pointer group">
                        
                            <span class="mdi mdi-folder-outline text-primary hidden md:inline-block"></span>
                        
                        <span class="truncate hidden md:inline">
                            Commuter Rib Scarf
                        </span>
                        <span
                            class="mdi mdi-chevron-down text-base opacity-60 group-hover:opacity-100 transition-opacity hidden md:inline"></span>
                    </button>
                    <ul tabindex="0"
                        class="dropdown-content navbar-dropdown-content navbar-dropdown-nav z-[90] menu p-2 shadow-lg bg-base-100 rounded-box w-56 border border-base-200 gap-1 mt-2">
                        
                        <li>
                            <a href="/projects"
                                class="bg-primary/10 text-primary font-medium">
                                <span class="mdi mdi-folder-outline text-lg"></span>
                                Projects
                            </a>
                        </li>
                        <li>
                            <a href="/yarn"
                                class="">
                                <span class="mdi mdi-sheep text-lg"></span>
                                Yarns
                            </a>
                        </li>
                        
                        <li>
                            <a href="/gauge"
                                class="">
                                <span class="mdi mdi-ruler text-lg"></span>
                                Gauge Calculator
                            </a>
                        </li>
                        
                        <li>
                            <a href="/admin/users"
                                class="">
                                <span class="mdi mdi-shield-account text-lg"></span>
                                Admin
                            </a>
                        </li>
                        
                    </ul>
                </div>
                
            </div>

            <div class="flex items-center gap-2 shrink-0">
                
                
                
                
<div class="flex items-center gap-1">
    <button type="submit" form="projectForm" class="btn btn-primary gap-2 h-10 min-h-0 px-3 md:px-4">
        <span class="mdi mdi-content-save text-xl"></span>
        <span>Save</span>
    </button>
    
</div>

                
                
            </div>

            
            <div class="flex items-center shrink-0">
                
                <div class="dropdown dropdown-end">
                    <button tabindex="0" role="button" class="btn btn-ghost btn-circle avatar">
                        
                        <div class="w-10 rounded-full">
                            <img src="/media/thumbnails/users/1/thumb_20260210_153146_a6139a25.jpg"
                                alt="Profile picture for demo@stricknani.local" />
                        </div>
                        
                    </button>
                    <div tabindex="0" class="dropdown-content navbar-dropdown-content menu bg-base-100 rounded-box z-[90] w-72 p-0 shadow-xl border border-base-300 mt-2">
                        <div class="flex items-center gap-3 border-b border-base-300 px-4 py-3">
                            <div class="avatar">
	                                <button type="button"
	                                    class="w-12 rounded-full cursor-pointer hover:ring-2 hover:ring-primary transition-all group relative overflow-hidden"
	                                    data-action="click-target"
	                                    data-target-id="profile-image-input"
	                                    aria-label="Change profile picture">
                                    
                                    <img src="/media/thumbnails/users/1/thumb_20260210_153146_a6139a25.jpg"
                                        alt="Profile picture for demo@stricknani.local" />
                                    
                                    <div
                                        class="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity">
                                        <span class="mdi mdi-camera text-white text-lg"></span>
                                    </div>
                                </button>
                            </div>
                            <div>
                                <p class="text-sm font-semibold truncate max-w-[12rem]">demo@stricknani.local</p>
                                <p class="text-xs opacity-60">Signed in</p>
                            </div>
                        </div>
                        <div class="space-y-4 border-b border-base-300 px-4 py-4">
                            <div class="space-y-2">
                                <label class="block mb-1 px-1">
                                    <span
                                        class="text-[10px] font-bold uppercase tracking-[0.1em] opacity-50 flex items-center gap-2">
                                        <span class="mdi mdi-translate text-xs"></span>
                                        Language
                                    </span>
                                </label>
                                <div class="join w-full bg-base-200 p-1 rounded-xl ">
    <form action="/auth/set-language" method="post" class="flex-1">
        <input type="hidden" name="csrf_token" value="facd35f37e917124753039c2e39e2f26e142ba6c">
        <input type="hidden" name="next"
            value="/projects/2/edit">
        <input type="hidden" name="language" value="en">
        <button type="submit"
            class="btn btn-sm w-full border-none join-item rounded-lg transition-all h-8 min-h-8 text-xs font-bold bg-base-100 shadow-sm text-primary">
            🇬🇧 EN
        </button>
    </form>
    <form action="/auth/set-language" method="post" class="flex-1">
        <input type="hidden" name="csrf_token" value="facd35f37e917124753039c2e39e2f26e142ba6c">
        <input type="hidden" name="next"
            value="/projects/2/edit">
        <input type="hidden" name="language" value="de">
        <button type="submit"
            class="btn btn-sm w-full border-none join-item rounded-lg transition-all h-8 min-h-8 text-xs font-bold btn-ghost opacity-60 hover:opacity-100">
            🇩🇪 DE
        </button>
    </form>
</div>
                            </div>
                            <div class="space-y-2">
                                <label class="block mb-1 px-1">
                                    <span
                                        class="text-[10px] font-bold uppercase tracking-[0.1em] opacity-50 flex items-center gap-2">
                                        <span class="mdi mdi-palette-swatch text-xs"></span>
                                        Appearance
                                    </span>
                                </label>
	                                <div class="join w-full bg-base-200 p-1 rounded-xl">
	                                    <button type="button" id="theme-btn-light" data-call="setTheme" data-call-args='["light"]'
	                                        class="btn btn-sm flex-1 border-none join-item rounded-lg transition-all h-8 min-h-8 text-xs font-bold gap-2">
	                                        <span class="mdi mdi-weather-sunny"></span>
	                                        Light
	                                    </button>
	                                    <button type="button" id="theme-btn-dark" data-call="setTheme" data-call-args='["dark"]'
	                                        class="btn btn-sm flex-1 border-none join-item rounded-lg transition-all h-8 min-h-8 text-xs font-bold gap-2">
	                                        <span class="mdi mdi-weather-night"></span>
	                                        Dark
	                                    </button>
	                                </div>
                            </div>
                        </div>
                        <div class="space-y-2 px-4 py-4">
                            <form action="/auth/logout" method="post">
                                <input type="hidden" name="csrf_token" value="facd35f37e917124753039c2e39e2f26e142ba6c">
                                <button type="submit" class="btn btn-ghost w-full justify-start text-error">
                                    <span class="mdi mdi-logout text-lg"></span>
                                    Logout
                                </button>
                            </form>
                        </div>
                    </div>
                </div>
                
            </div>
        </div>
    </div>
</nav>


    <main class="flex-grow w-full" >
        



<dialog id="previewDialog"
    class="p-0 rounded-lg shadow-xl backdrop:bg-black/50 dark:bg-slate-900 dark:text-slate-100 w-full max-w-2xl">
    <div class="flex items-center justify-between p-4 border-b border-slate-200 dark:border-slate-700">
        <h3 class="text-lg font-semibold">Preview</h3>
        <button data-action="close-dialog" data-dialog-id="previewDialog"
            class="text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200">
            <span class="mdi mdi-close text-xl"></span>
        </button>
    </div>
    <div id="previewContent" class="p-6 prose max-w-none dark:prose-invert overflow-y-auto max-h-[70vh]"></div>
</dialog>


















<dialog id="importDialog" class="modal modal-top sm:modal-middle " data-import-dialog>
    <div class="modal-box max-w-xl">
        <form method="dialog">
            <button class="btn btn-sm btn-circle btn-ghost absolute right-2 top-2">
                <span class="mdi mdi-close text-xl"></span>
            </button>
        </form>
        
        <h3 class="font-bold text-lg mb-4">Import</h3>
        
        
<form id="importForm" action="/projects/import" method="POST" enctype="multipart/form-data">
    <input type="hidden" name="csrf_token" value="facd35f37e917124753039c2e39e2f26e142ba6c">
    <input type="hidden" name="type" value="url" data-import-type>

    
    <input type="hidden" name="project_id" value="2">
    
    
    <input type="hidden" name="use_ai" value="true">
    

    <div class="space-y-6 py-2" data-import-content>
        <div class="flex items-center gap-4 p-4 bg-primary/5 rounded-2xl border border-primary/10">
            <div class="flex-shrink-0 w-12 h-12 flex items-center justify-center bg-primary/10 text-primary rounded-xl">
                <span class="mdi mdi-auto-fix text-2xl"></span>
            </div>
            <div>
                <h4 class="font-bold text-base">Magic Import</h4>
                <p class="text-xs opacity-70 leading-relaxed">Paste a URL from Ravelry, a blog, or any knitting site. We&#39;ll try to extract all the
details for you.</p>
            </div>
        </div>

        <div class="form-control w-full">
            <label class="label pt-0">
                <span class="label-text font-semibold">Pattern URL</span>
            </label>
            <div class="relative group">
                <div
                    class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/40 group-focus-within:text-primary transition-colors">
                    <span class="mdi mdi-link-variant text-xl"></span>
                </div>
                <input type="url" id="importUrl" name="url" placeholder="e.g. https://www.garnstudio.com/pattern.php?id=..."
                    value="https://example.com/patterns/commuter-rib-scarf"
                    class="input input-bordered w-full pl-10 bg-base-200/50 focus:bg-base-100 transition-all border-base-content/10 focus:border-primary" />
            </div>
        </div>

        <div class="divider text-xs opacity-50 m-0">OR</div>

        <div class="form-control w-full space-y-4">
            <div id="importFileList"
                class="space-y-2 max-h-60 overflow-y-auto pr-2 hidden">
                <label class="label pt-0 pb-1">
                    <span class="label-text font-semibold">Select File</span>
                </label>

                
            </div>

            <div id="fileDropZone"
                class="relative border-2 border-dashed border-base-content/20 rounded-2xl p-6 text-center transition-all hover:border-primary/50 hover:bg-primary/5 cursor-pointer"
                data-import-file-dropzone>

                <input type="file" id="importFile" name="files" multiple class="hidden" data-import-file-input
                    accept=".jpg,.jpeg,.png,.gif,.webp,.pdf,.txt,.md,.html,.htm" />

                <div class="space-y-2">
                    <div class="w-10 h-10 mx-auto flex items-center justify-center bg-base-200 rounded-full">
                        <span class="mdi mdi-plus text-xl text-primary"></span>
                    </div>
                    <div>
                        <p class="font-medium text-sm">Upload New File</p>
                        <p class="text-xs opacity-60">
                            Drag &amp; drop or click to browse
                        </p>
                    </div>
                </div>
            </div>
        </div>

        
        <div class="flex items-center gap-2 px-3 py-2 bg-primary/5 rounded-xl border border-primary/10">
            <span class="mdi mdi-information-outline text-primary text-sm"></span>
            <span class="text-[10px] uppercase font-bold tracking-wider text-primary/70">AI-powered extraction enabled</span>
        </div>
        
    </div>

    <div id="importLoading" class="text-center py-12 space-y-4" hidden>
        <div class="relative inline-block">
            <div class="w-16 h-16 border-4 border-primary/20 border-t-primary rounded-full animate-spin"></div>
            <div class="absolute inset-0 flex items-center justify-center">
                <span class="mdi mdi-auto-fix text-primary text-xl animate-pulse"></span>
            </div>
        </div>
        <div class="space-y-1">
            <p class="text-lg font-bold">Working some magic...</p>
            <p class="text-sm opacity-60 max-w-xs mx-auto">We are analyzing the pattern and extracting details. This usually takes 10-20
seconds.</p>
        </div>
    </div>

    <div class="modal-action mt-8">
        
<button type="button" class="btn btn-ghost gap-2 "  data-action="close-dialog" data-dialog-id="importDialog">
    <span class="mdi mdi-close"></span>
    Cancel
</button>

        <button type="submit" class="btn btn-primary sm:btn-wide" data-import-submit>
            <span class="mdi mdi-auto-fix mr-2"></span>
            Analyze &amp; Import
        </button>
    </div>
</form>

<script>
(function () {
  const formId = "importForm";
  const storageKey = "importedData";
  const redirectUrl = "/projects/new";
  const populateFnName = "populateProjectForm";

  const dialog = document.getElementById("importDialog");
  if (!dialog) {
    return;
  }

  const form = document.getElementById(formId);
  if (!form) {
    return;
  }

  const loading = dialog.querySelector("#importLoading");
  const content = dialog.querySelector("[data-import-content]");
  const importTypeInput = form.querySelector("[data-import-type]");
  const urlInput = form.querySelector("#importUrl");

  const dropZone = dialog.querySelector("[data-import-file-dropzone]");
  const fileInput = dialog.querySelector("[data-import-file-input]");
  const submitBtn = dialog.querySelector("[data-import-submit]");

  const fileListEl = dialog.querySelector("#importFileList");
  const filesToUpload = new Map();

  function getCheckedItems() {
    if (!fileListEl) {
      return [];
    }
    return Array.from(
      fileListEl.querySelectorAll('input[type="checkbox"]:checked'),
    );
  }

  function getCheckedNewFileNames() {
    if (!fileListEl) {
      return [];
    }
    return Array.from(
      fileListEl.querySelectorAll('input[name="new_files_checked"]:checked'),
    ).map((input) => input.value);
  }

  function formatFileSize(bytes) {
    if (!bytes) {
      return "0 Bytes";
    }
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
  }

  function updateSubmitButton() {
    if (!submitBtn) {
      return;
    }

    const hasUrl = Boolean(urlInput?.value.trim());
    const fileCount = getCheckedItems().length;

    if (hasUrl && fileCount === 0) {
      submitBtn.innerHTML = '<span class="mdi mdi-auto-fix mr-2"></span>Analyze URL';
      return;
    }

    if (fileCount > 0) {
      submitBtn.innerHTML = `<span class="mdi mdi-auto-fix mr-2"></span>Analyze Files <span class="badge badge-sm badge-ghost ml-2">${fileCount}</span>`;
      return;
    }

    submitBtn.innerHTML =
      '<span class="mdi mdi-auto-fix mr-2"></span>Analyze & Import';
  }

  function handleNewFiles(fileList) {
    if (!fileListEl) {
      return;
    }

    fileListEl.classList.remove("hidden");

    Array.from(fileList).forEach((file) => {
      const key = `${file.name}:${file.size}:${file.lastModified}`;
      if (filesToUpload.has(key)) {
        return;
      }
      filesToUpload.set(key, file);

      const label = document.createElement("label");
      label.className =
        "flex items-center gap-4 p-3 rounded-xl border border-base-200 cursor-pointer hover:bg-base-200/50 transition-colors group has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:checked]:ring-1 has-[:checked]:ring-primary relative overflow-hidden";

      let icon = "mdi-file-outline";
      if (file.type.includes("image")) {
        icon = "mdi-file-image-outline";
      } else if (file.type.includes("pdf")) {
        icon = "mdi-file-pdf-outline";
      }

      label.innerHTML = `
        <input type="checkbox" name="new_files_checked" value="${key}" class="checkbox checkbox-primary checkbox-sm" checked>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-medium truncate" title="${file.name}">${file.name}</span>
            <span class="badge badge-sm badge-secondary font-bold text-xs">NEW</span>
          </div>
          <div class="text-xs opacity-60 flex items-center gap-1 min-w-fit">
            <span class="mdi ${icon}"></span> ${formatFileSize(file.size)}
          </div>
        </div>
      `;

      fileListEl.appendChild(label);
    });

    updateSubmitButton();
  }

  if (fileListEl) {
    fileListEl.addEventListener("change", (e) => {
      if (e.target.matches('input[type="checkbox"]')) {
        updateSubmitButton();
      }
    });
  }

  if (urlInput) {
    urlInput.addEventListener("input", updateSubmitButton);
  }

  function handleDrop(files) {
    if (!files || files.length === 0) {
      return;
    }
    handleNewFiles(files);
  }

  if (dialog) {
    const handleDrag = (e, add) => {
      e.preventDefault();
      if (dropZone) {
        if (add) {
          dropZone.classList.add("border-primary", "bg-primary/5");
        } else {
          dropZone.classList.remove("border-primary", "bg-primary/5");
        }
      }
    };

    dialog.addEventListener("dragenter", (e) => handleDrag(e, true));
    dialog.addEventListener("dragover", (e) => handleDrag(e, true));
    dialog.addEventListener("dragleave", (e) => {
      if (e.relatedTarget === null || !dialog.contains(e.relatedTarget)) {
        handleDrag(e, false);
      }
    });
    dialog.addEventListener("drop", (e) => {
      handleDrag(e, false);
      handleDrop(e.dataTransfer?.files);
    });
  }

  if (dropZone && !dropZone.dataset.importDropInitialized) {
    dropZone.dataset.importDropInitialized = "1";
    dropZone.addEventListener("click", () => fileInput?.click());
    dropZone.addEventListener("dragenter", (e) => e.preventDefault());
    dropZone.addEventListener("dragover", (e) => e.preventDefault());
    dropZone.addEventListener("dragleave", (e) => e.preventDefault());
    dropZone.addEventListener("drop", (e) => {
      e.preventDefault();
      handleDrop(e.dataTransfer?.files);
    });
  }

  if (fileInput && !fileInput.dataset.importInputInitialized) {
    fileInput.dataset.importInputInitialized = "1";
    fileInput.addEventListener("change", (e) => {
      handleNewFiles(e.target.files);
      e.target.value = "";
    });
  }

  if (!form.dataset.importSubmitInitialized) {
    form.dataset.importSubmitInitialized = "1";
    form.addEventListener("submit", async (e) => {
      e.preventDefault();

      const formData = new FormData(form);
      const hasUrl = Boolean(urlInput?.value.trim());
      const checkedItems = getCheckedItems();
      const checkedNewFiles = getCheckedNewFileNames();
      const hasFiles = checkedItems.length > 0;

      formData.delete("files");
      checkedNewFiles.forEach((key) => {
        const file = filesToUpload.get(key);
        if (file) {
          if (form.action.endsWith("/yarn/import")) {
            formData.append("file", file);
          } else {
            formData.append("files", file);
          }
        }
      });

      if (!hasUrl && !hasFiles) {
        alert("Please provide a URL or select at least one file.");
        return;
      }

      if (hasFiles) {
        formData.set("type", "file");
      } else {
        formData.set("type", "url");
      }

      if (content) {
        content.hidden = true;
      }
      if (loading) {
        loading.hidden = false;
      }

      try {
        const response = await fetch(form.action, {
          method: "POST",
          body: formData,
        });

        if (!response.ok) {
          const error = await response.json().catch(() => ({}));
          throw new Error(error.detail || "Unknown error");
        }

        const data = await response.json();
        const populate = populateFnName ? window[populateFnName] : null;

        if (typeof populate === "function") {
          populate(data);
          dialog?.close();
          return;
        }

        sessionStorage.setItem(storageKey, JSON.stringify(data));
        window.location.href = redirectUrl;
      } catch (err) {
        console.error(err);
        alert(`Import failed: ${err?.message || "Unknown error"}`);
      } finally {
        if (loading) {
          loading.hidden = true;
        }
        if (content) {
          content.hidden = false;
        }
      }
    });
  }

  updateSubmitButton();
})();
</script>

    </div>
    
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

</dialog>


<div
    class="max-w-7xl mx-auto px-0 sm:px-6 lg:px-8 py-2 md:py-8 space-y-4 md:space-y-6">
    <!-- Form Content -->
    
<form id="projectForm" action="/projects/2" method="post"
    data-primary-form="true" class="space-y-6">
    <input type="hidden" name="csrf_token" value="facd35f37e917124753039c2e39e2f26e142ba6c">
    <input type="hidden" id="import_image_urls" name="import_image_urls" value="">
    <input type="hidden" id="import_title_image_url" name="import_title_image_url" value="">
    <input type="hidden" id="import_attachment_tokens" name="import_attachment_tokens" value="">
    <input type="hidden" id="archive_on_save" name="archive_on_save" value="">
    <input type="hidden" id="is_ai_enhanced" name="is_ai_enhanced"
        value="">
    <input type="hidden" id="yarn_brand" name="yarn_brand" value="">
    <input type="hidden" id="yarn_details" name="yarn_details" value="">
    <input type="hidden" id="stepsData" name="steps_data">


    <div id="importWarning"
        class="hidden mb-6 bg-yellow-50 dark:bg-yellow-900/20 border-l-4 border-yellow-400 dark:border-yellow-600 p-4 rounded">
        <div class="flex items-start gap-3">
            <span class="mdi mdi-alert text-yellow-600 dark:text-yellow-500 text-xl mt-0.5"></span>
            <div class="flex-1">
                <h3 class="font-semibold text-yellow-800 dark:text-yellow-400 mb-1">Imported Data - Not Saved Yet</h3>
                <p class="text-sm text-yellow-700 dark:text-yellow-300">
                    This project was imported from an external source. Please review all fields carefully and click &#34;Save&#34; to keep it.
                </p>
            </div>
        </div>
    </div>

    <div class="relative grid grid-cols-1 lg:grid-cols-3 gap-6 lg:gap-8">
        <!-- Main Content Column -->
        <div id="main-column" class="lg:col-span-2 space-y-6">
            <!-- Basic Info Card -->
            <div
                class="collapse collapse-arrow bg-base-100 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-base-200 overflow-visible">
                <input type="checkbox" checked />
                <div class="collapse-title p-3 sm:p-6 pb-0">
                    <h3
                        class="text-sm font-bold uppercase tracking-widest text-base-content/40 flex items-center gap-2">
                        <span class="mdi mdi-information-outline"></span>
                        Basic Information
                    </h3>
                </div>
                <div class="collapse-content p-3 sm:p-6 pt-0">
                    <div class="space-y-4 sm:space-y-6 pt-4">
                        <div>
                            <label for="name" class=" text-sm font-medium mb-1 inline-flex items-center gap-2">
                                <span class="mdi mdi-notebook-outline text-base-content/70"></span>
                                Project Name <span class="text-red-500 dark:text-red-400">*</span>
                            </label>
                            <input type="text" id="name" name="name" required
                                value="Commuter Rib Scarf"
                                class="input input-bordered w-full" placeholder="e.g. My Amazing Project">
                        </div>

                        <div>
                            <div class="flex items-center justify-between mb-1">
                                <label for="description" class=" text-sm font-medium inline-flex items-center gap-2">
                                    <span class="mdi mdi-text-box-outline text-base-content/70"></span>
                                    Description <span class="text-base-content/60 text-xs">Optional</span>
                                </label>
                            </div>
                            
<div data-wysiwyg data-wysiwyg-input="description" class="wysiwyg-container">
    <div class="wysiwyg-toolbar">
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bold" title="Bold" aria-label="Bold">
                <span class="mdi mdi-format-bold"></span>
            </button>
            <button type="button" data-action="italic" title="Italic" aria-label="Italic">
                <span class="mdi mdi-format-italic"></span>
            </button>
            <button type="button" data-action="underline" title="Underline" aria-label="Underline">
                <span class="mdi mdi-format-underline"></span>
            </button>
            <button type="button" data-action="strike" title="Strikethrough" aria-label="Strikethrough">
                <span class="mdi mdi-format-strikethrough"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="heading" data-value="1" title="Heading 1" aria-label="Heading 1">
                <span class="mdi mdi-format-header-1"></span>
            </button>
            <button type="button" data-action="heading" data-value="2" title="Heading 2" aria-label="Heading 2">
                <span class="mdi mdi-format-header-2"></span>
            </button>
            <button type="button" data-action="heading" data-value="3" title="Heading 3" aria-label="Heading 3">
                <span class="mdi mdi-format-header-3"></span>
            </button>
            <button type="button" data-action="paragraph" title="Paragraph" aria-label="Paragraph">
                <span class="mdi mdi-format-paragraph"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bulletList" title="Bullet list" aria-label="Bullet list">
                <span class="mdi mdi-format-list-bulleted"></span>
            </button>
            <button type="button" data-action="orderedList" title="Numbered list" aria-label="Numbered list">
                <span class="mdi mdi-format-list-numbered"></span>
            </button>
            <button type="button" data-action="blockquote" title="Quote" aria-label="Quote">
                <span class="mdi mdi-format-quote-close"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="code" title="Code" aria-label="Code">
                <span class="mdi mdi-code-tags"></span>
            </button>
            <button type="button" data-action="codeBlock" title="Code block" aria-label="Code block">
                <span class="mdi mdi-code-braces"></span>
            </button>
            <button type="button" data-action="horizontalRule" title="Horizontal rule" aria-label="Horizontal rule">
                <span class="mdi mdi-minus"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="link" title="Add link" aria-label="Add link">
                <span class="mdi mdi-link"></span>
            </button>
            <button type="button" data-action="unlink" title="Remove link" aria-label="Remove link">
                <span class="mdi mdi-link-off"></span>
            </button>
            <button type="button" data-action="image" title="Insert image" aria-label="Insert image" >
                <span class="mdi mdi-image"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="undo" title="Undo" aria-label="Undo">
                <span class="mdi mdi-undo"></span>
            </button>
            <button type="button" data-action="redo" title="Redo" aria-label="Redo">
                <span class="mdi mdi-redo"></span>
            </button>
        </div>
    </div>
    <div class="wysiwyg-content"></div>
</div>
<textarea id="description" name="description" rows="4" class="hidden" placeholder="Brief description of this project..."  data-markdown-images="true">Vibrant rainbow ribbed scarf to **brighten** up winter days.

sdsdssdsdsd

---

`sdsds`

[sdsdsd](httsd)

&amp;nbsp;</textarea>

                        </div>

                        <div>
                            <div class="flex items-center justify-between mb-1">
                                <label for="notes" class=" text-sm font-medium inline-flex items-center gap-2">
                                    <span class="mdi mdi-notebook-outline text-base-content/70"></span>
                                    Notes <span class="text-base-content/60 text-xs">Optional</span>
                                </label>
                            </div>
                            
<div data-wysiwyg data-wysiwyg-input="notes" class="wysiwyg-container">
    <div class="wysiwyg-toolbar">
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bold" title="Bold" aria-label="Bold">
                <span class="mdi mdi-format-bold"></span>
            </button>
            <button type="button" data-action="italic" title="Italic" aria-label="Italic">
                <span class="mdi mdi-format-italic"></span>
            </button>
            <button type="button" data-action="underline" title="Underline" aria-label="Underline">
                <span class="mdi mdi-format-underline"></span>
            </button>
            <button type="button" data-action="strike" title="Strikethrough" aria-label="Strikethrough">
                <span class="mdi mdi-format-strikethrough"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="heading" data-value="1" title="Heading 1" aria-label="Heading 1">
                <span class="mdi mdi-format-header-1"></span>
            </button>
            <button type="button" data-action="heading" data-value="2" title="Heading 2" aria-label="Heading 2">
                <span class="mdi mdi-format-header-2"></span>
            </button>
            <button type="button" data-action="heading" data-value="3" title="Heading 3" aria-label="Heading 3">
                <span class="mdi mdi-format-header-3"></span>
            </button>
            <button type="button" data-action="paragraph" title="Paragraph" aria-label="Paragraph">
                <span class="mdi mdi-format-paragraph"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bulletList" title="Bullet list" aria-label="Bullet list">
                <span class="mdi mdi-format-list-bulleted"></span>
            </button>
            <button type="button" data-action="orderedList" title="Numbered list" aria-label="Numbered list">
                <span class="mdi mdi-format-list-numbered"></span>
            </button>
            <button type="button" data-action="blockquote" title="Quote" aria-label="Quote">
                <span class="mdi mdi-format-quote-close"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="code" title="Code" aria-label="Code">
                <span class="mdi mdi-code-tags"></span>
            </button>
            <button type="button" data-action="codeBlock" title="Code block" aria-label="Code block">
                <span class="mdi mdi-code-braces"></span>
            </button>
            <button type="button" data-action="horizontalRule" title="Horizontal rule" aria-label="Horizontal rule">
                <span class="mdi mdi-minus"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="link" title="Add link" aria-label="Add link">
                <span class="mdi mdi-link"></span>
            </button>
            <button type="button" data-action="unlink" title="Remove link" aria-label="Remove link">
                <span class="mdi mdi-link-off"></span>
            </button>
            <button type="button" data-action="image" title="Insert image" aria-label="Insert image" >
                <span class="mdi mdi-image"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="undo" title="Undo" aria-label="Undo">
                <span class="mdi mdi-undo"></span>
            </button>
            <button type="button" data-action="redo" title="Redo" aria-label="Redo">
                <span class="mdi mdi-redo"></span>
            </button>
        </div>
    </div>
    <div class="wysiwyg-content"></div>
</div>
<textarea id="notes" name="notes" rows="4" class="hidden" placeholder="Personal notes about this project..."  data-markdown-images="true">The variegation pools nicely in the 2x2 ribbing.

&amp;nbsp;</textarea>

                        </div>
                    </div>
                </div>
            </div>

            <!-- Gallery Card -->
            <div
                class="collapse collapse-arrow bg-base-100 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-base-200 overflow-visible">
                <input type="checkbox" checked />
                <div class="collapse-title p-3 sm:p-6 pb-0">
                    <h3
                        class="text-sm font-bold uppercase tracking-widest text-base-content/40 flex items-center gap-2">
                        <span class="mdi mdi-image-multiple"></span>
                        Gallery
                    </h3>
                </div>
                <div class="collapse-content p-3 sm:p-6 pt-0">
                    <div class="pt-4">
                        <div id="titleImagesContainer" class="grid grid-cols-2 md:grid-cols-3 gap-4 mb-4 pswp-gallery"
                            data-pswp-gallery>
                            
                            
                            <div class="relative group " data-image-id="6">
    <a href="/media/projects/2/demo_image_19.jpg" data-pswp-width="1600" data-pswp-height="1000"
        data-pswp-caption="Commuter rib scarf in deep red laid out on a neutral backdrop" data-pswp-promote="true"  data-pswp-delete="true"  data-pswp-crop="true" 
        data-pswp-is-primary="true" class="block" draggable="true" ondragstart="handleImageDragStart(event)" >
        <img src="/media/thumbnails/projects/2/thumb_demo_image_19.jpg" alt="Commuter rib scarf in deep red laid out on a neutral backdrop"
            class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
    </a>
    
    <button type="button" data-call="promoteImage" data-call-args='[2,6]'
        class="title-promote-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-amber-400 text-white opacity-100 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
        title="Make title image">
        <span class="mdi mdi-star"></span>
    </button>
    
    
    <button type="button" data-call="deleteImage" data-call-args='[6]'
        class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
        <span class="mdi mdi-delete"></span>
    </button>
    
</div>
                            
                            <div class="relative group " data-image-id="7">
    <a href="/media/projects/2/demo_image_4.jpg" data-pswp-width="1024" data-pswp-height="1024"
        data-pswp-caption="Bright neon rainbow hand-dyed sock yarn skeins" data-pswp-promote="true"  data-pswp-delete="true"  data-pswp-crop="true" 
        data-pswp-is-primary="false" class="block" draggable="true" ondragstart="handleImageDragStart(event)" >
        <img src="/media/thumbnails/projects/2/thumb_demo_image_4.jpg" alt="Bright neon rainbow hand-dyed sock yarn skeins"
            class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
    </a>
    
    <button type="button" data-call="promoteImage" data-call-args='[2,7]'
        class="title-promote-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
        title="Make title image">
        <span class="mdi mdi-star-outline"></span>
    </button>
    
    
    <button type="button" data-call="deleteImage" data-call-args='[7]'
        class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
        <span class="mdi mdi-delete"></span>
    </button>
    
</div>
                            
                            <div class="relative group " data-image-id="8">
    <a href="/media/projects/2/demo_image_5.jpg" data-pswp-width="1024" data-pswp-height="1024"
        data-pswp-caption="Winding a ball of colorful variegated yarn" data-pswp-promote="true"  data-pswp-delete="true"  data-pswp-crop="true" 
        data-pswp-is-primary="false" class="block" draggable="true" ondragstart="handleImageDragStart(event)" >
        <img src="/media/thumbnails/projects/2/thumb_demo_image_5.jpg" alt="Winding a ball of colorful variegated yarn"
            class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
    </a>
    
    <button type="button" data-call="promoteImage" data-call-args='[2,8]'
        class="title-promote-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
        title="Make title image">
        <span class="mdi mdi-star-outline"></span>
    </button>
    
    
    <button type="button" data-call="deleteImage" data-call-args='[8]'
        class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
        <span class="mdi mdi-delete"></span>
    </button>
    
</div>
                            
                            
                        </div>

                        <div id="titleImageUploadContainer">
                            

<div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg p-8 text-center hover:border-blue-500 transition-colors dark:border-slate-600 dark:hover:border-blue-400 "
     id="titleImageDropZone" >
    <input type="file" id="titleImageInput" 
        accept="image/*"  multiple class="hidden" >
    <label for="titleImageInput" class="cursor-pointer block">
        <span class="mdi mdi-image-plus mx-auto text-4xl text-slate-400 dark:text-slate-500"></span>
        <p class="upload-instructions mt-2 text-sm text-base-content/70" >
            Drag and drop images here or click to upload
        </p>
    </label>
</div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Attachments Card -->
            <div
                class="collapse collapse-arrow bg-base-100 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-base-200 overflow-visible">
                <input type="checkbox" checked />
                <div class="collapse-title p-3 sm:p-6 pb-0">
                    <h3
                        class="text-sm font-bold uppercase tracking-widest text-base-content/40 flex items-center gap-2">
                        <span class="mdi mdi-paperclip"></span>
                        Attachments
                    </h3>
                </div>
                <div class="collapse-content p-3 sm:p-6 pt-0">
                    <div class="pt-4">
                        <div id="attachmentsContainer" class="space-y-2 mb-4 pswp-gallery" data-pswp-gallery>
                            
                        </div>

                        

<div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg p-8 text-center hover:border-blue-500 transition-colors dark:border-slate-600 dark:hover:border-blue-400 "
     id="attachmentDropZone" >
    <input type="file" id="attachmentInput" 
        accept="*/*"  multiple class="hidden" >
    <label for="attachmentInput" class="cursor-pointer block">
        <span class="mdi mdi-paperclip mx-auto text-4xl text-slate-400 dark:text-slate-500"></span>
        <p class="upload-instructions mt-2 text-sm text-base-content/70" 
            data-enabled-text="Click or drag files here to attach (PDF, etc.)" >
            Click or drag files here to attach (PDF, etc.)
        </p>
    </label>
</div>
                    </div>
                </div>
            </div>

            <!-- Mobile Details Card -->
            <div class="lg:hidden p-4 bg-base-200/30 rounded-xl border border-base-200 space-y-4">
                <h3 class="font-bold text-xs uppercase tracking-widest text-base-content/40 flex items-center gap-2">
                    <span class="mdi mdi-information-outline"></span>
                    Project Details
                </h3>

                <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                        <div class="flex items-center justify-between">
                            <label for="category-mobile" class=" text-xs font-bold uppercase text-base-content/50">
                                Category
                            </label>
                            <a href="/projects/categories" class="text-[10px] text-primary hover:underline"
                                target="_blank" rel="noreferrer">
                                Manage
                            </a>
                        </div>
                        <select name="category" class="select  select-sm w-full mt-1">
                            <option value="">Select a category</option>
                            
                            <option value="Jacke" >
                                Jacke
                            </option>
                            
                            <option value="Mütze" >
                                Mütze
                            </option>
                            
                            <option value="Pullover" >
                                Pullover
                            </option>
                            
                            <option value="Schal" selected>
                                Schal
                            </option>
                            
                            <option value="Stirnband" >
                                Stirnband
                            </option>
                            
                        </select>
                    </div>
                    <div class="sm:col-span-2">
                        <label for="tags_input_mobile" class=" text-xs font-bold uppercase text-base-content/50">
                            Tags
                        </label>
                        <input type="hidden" id="tags" name="tags"
                            value="ribbing, winter, rainbow, uni, ex">
                        <div id="tags_chips_mobile" class="flex flex-wrap gap-2 mt-2"></div>
                        <div class="relative mt-2">
                            <input type="text" id="tags_input_mobile" class="input  input-sm w-full" autocomplete="off"
                                placeholder="Add a tag...">
                            <div id="tags_suggestions_mobile"
                                class="hidden absolute z-30 w-full mt-1 max-h-48 overflow-y-auto bg-base-100 border border-base-300 rounded-lg shadow-xl">
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="accessory">
                                    #accessory
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="baby">
                                    #baby
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="beginner-friendly">
                                    #beginner-friendly
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="bulky">
                                    #bulky
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="cardigan">
                                    #cardigan
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="ex">
                                    #ex
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="garter">
                                    #garter
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="gift">
                                    #gift
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="halo">
                                    #halo
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="lace">
                                    #lace
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="lightweight">
                                    #lightweight
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="one-skein">
                                    #one-skein
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="outerwear">
                                    #outerwear
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="pockets">
                                    #pockets
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="quick">
                                    #quick
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="raglan">
                                    #raglan
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="rainbow">
                                    #rainbow
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="ribbing">
                                    #ribbing
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="summer">
                                    #summer
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="top-down">
                                    #top-down
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="tweed">
                                    #tweed
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="uni">
                                    #uni
                                </button>
                                
                                <button type="button"
                                    class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                    data-tag="winter">
                                    #winter
                                </button>
                                
                            </div>
                        </div>
                        <p class="text-[10px] text-base-content/60 mt-1">Press Enter or choose a suggestion.
                        </p>
                    </div>

                    <div class="sm:col-span-2">
                        <div class="flex items-center justify-between mb-1">
                            <label class=" text-xs font-bold uppercase text-base-content/50">Pattern Source</label>
                            <button type="button" id="reimport-btn-mobile"
                                class="btn btn-ghost btn-xs gap-1 normal-case "
                                data-call="importFromUrl" data-call-args='["$value:#link"]'>
                                <span class="mdi mdi-download"></span>
                                Re-import
                            </button>
                            <a href="https://example.com/patterns/commuter-rib-scarf" target="_blank"
                                class="btn btn-ghost btn-xs gap-1 normal-case "
                                id="visit-link-btn-mobile" title="Visit Website">
                                <span class="mdi mdi-open-in-new"></span>
                                Visit
                            </a>
                            <button type="button" class="btn btn-ghost btn-xs gap-1 normal-case"
                                data-call="copyToClipboard" data-call-args='["$value:#link","$this"]'
                                title="Copy URL">
                                <span class="mdi mdi-content-copy"></span>
                            </button>
                        </div>
                        <input type="url" name="link" value="https://example.com/patterns/commuter-rib-scarf" class="input  input-sm w-full"
                            placeholder="https://..." data-call-input="syncLinkInputs">
                        <label id="wayback_mobile_container"
                            class="label cursor-pointer justify-start gap-3 px-3 py-2 bg-base-200/50 rounded-xl border border-base-300 mt-2 ">
                            <input type="checkbox" id="archive_on_save_mobile" name="archive_on_save" value="1"
                                class="checkbox checkbox-primary checkbox-sm" checked>
                            <span class="label-text text-xs font-bold uppercase text-base-content/50">Archive on Wayback Machine</span>
                        </label>
                    </div>

                    <div class="sm:col-span-2 pt-2 border-t border-base-200">
                        <label id="ai_enhanced_mobile_container"
                            class="label cursor-pointer justify-start gap-3 px-3 py-2 bg-base-200/50 rounded-xl border border-base-300">
                            <input type="checkbox" id="is_ai_enhanced_mobile_checkbox"
                                class="checkbox checkbox-primary checkbox-sm"  data-call-change="syncAiEnhanced">
                            <span
                                class="label-text text-xs font-bold uppercase text-base-content/50 flex items-center gap-2">
                                <span class="badge badge-primary font-black text-[10px] h-4 min-h-0">AI</span>
                                AI Enhanced
                            </span>
                        </label>
                    </div>
                </div>
            </div>

            <!-- Technical Specifications Group -->
            <div
                class="collapse collapse-arrow bg-base-100 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-base-200 overflow-visible">
                <input type="checkbox" checked />
                <div class="collapse-title p-3 sm:p-6 pb-0">
                    <h3
                        class="font-bold text-base text-base-content/40 uppercase tracking-widest flex items-center gap-2 text-xs">
                        <span class="mdi mdi-cog-outline"></span>
                        Technical Specifications
                    </h3>
                </div>
                <div class="collapse-content p-3 sm:p-6 pt-0">
                    <div class="space-y-6 pt-4">
                        <!-- Yarns Card -->
                        <div class="bg-base-200/30 rounded-xl border border-base-200 p-4">
                            <div class="flex items-center justify-between mb-4">
                                <h3 class="text-lg font-semibold text-base-content flex items-center gap-2">
                                    <span class="mdi mdi-sheep text-primary"></span>
                                    Yarns Used
                                </h3>
                                <a href="/yarn/new" class="btn btn-link btn-xs p-0 h-auto min-h-0" target="_blank"
                                    rel="noreferrer">
                                    Add new
                                </a>
                            </div>

                            <div class="space-y-4">
                                <input type="hidden" id="yarn_ids" name="yarn_ids" value="">
                                <input type="hidden" id="yarn_text_hidden" name="yarn_text" value="">

                                <div class="relative">
                                    <input type="text" id="yarn_search" placeholder="Search yarns..."
                                        class="input input-bordered w-full pl-10" autocomplete="off">
                                    <span
                                        class="mdi mdi-magnify absolute left-3 top-1/2 -translate-y-1/2 text-base-content/60 text-xl"></span>
                                </div>

                                <div id="yarn_dropdown"
                                    class="hidden absolute z-50 w-full max-w-md max-h-60 overflow-y-auto bg-base-100 border border-base-300 rounded-lg shadow-xl">
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="4" data-yarn-name="Coastal Linen Sport"
                                        data-yarn-brand="Drift Thread Co."
                                        data-yarn-colorway="Goldenrod"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/drift-thread-coastal-linen-sport"
                                        data-yarn-image="/media/thumbnails/yarns/4/thumb_demo_image_10.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/4/thumb_demo_image_10.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Coastal Linen Sport</div>
                                                <div class="text-[10px] opacity-60 truncate">Drift Thread Co.
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="5" data-yarn-name="Halo Alpaca Silk"
                                        data-yarn-brand="Andes Loft"
                                        data-yarn-colorway="Petal Pink"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/andes-loft-halo-alpaca-silk"
                                        data-yarn-image="/media/thumbnails/yarns/5/thumb_demo_image_14.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/5/thumb_demo_image_14.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Halo Alpaca Silk</div>
                                                <div class="text-[10px] opacity-60 truncate">Andes Loft
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="6" data-yarn-name="Highland Tweed Worsted"
                                        data-yarn-brand="North Ridge"
                                        data-yarn-colorway="Emerald Forest"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/northridge-highland-tweed-worsted"
                                        data-yarn-image="/media/thumbnails/yarns/6/thumb_demo_image_16.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/6/thumb_demo_image_16.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Highland Tweed Worsted</div>
                                                <div class="text-[10px] opacity-60 truncate">North Ridge
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="2" data-yarn-name="Laneway Sock 4ply"
                                        data-yarn-brand="Harbor Mill"
                                        data-yarn-colorway="Neon Prism"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/harbor-mill-laneway-sock-4ply"
                                        data-yarn-image="/media/thumbnails/yarns/2/thumb_demo_image_4.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/2/thumb_demo_image_4.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Laneway Sock 4ply</div>
                                                <div class="text-[10px] opacity-60 truncate">Harbor Mill
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="1" data-yarn-name="Riverbend Merino DK"
                                        data-yarn-brand="North Ridge Wool"
                                        data-yarn-colorway="Sapphire Blue"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/northridge-riverbend-merino-dk"
                                        data-yarn-image="/media/thumbnails/yarns/1/thumb_demo_image_1.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/1/thumb_demo_image_1.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Riverbend Merino DK</div>
                                                <div class="text-[10px] opacity-60 truncate">North Ridge Wool
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                    <button type="button"
                                        class="yarn-option w-full text-left px-3 py-2 hover:bg-base-200 border-b border-base-200 last:border-b-0 text-sm"
                                        data-yarn-id="3" data-yarn-name="Summit Bulky"
                                        data-yarn-brand="Timberline Fibers"
                                        data-yarn-colorway="Ruby Red"
                                        data-yarn-dye-lot=""
                                        data-yarn-link="https://example.com/yarns/timberline-summit-bulky"
                                        data-yarn-image="/media/thumbnails/yarns/3/thumb_demo_image_7.jpg">
                                        <div class="flex items-center gap-2">
                                            <div
                                                class="w-8 h-8 rounded bg-base-300 flex items-center justify-center shrink-0">
                                                
                                                <img src="/media/thumbnails/yarns/3/thumb_demo_image_7.jpg" alt=""
                                                    class="w-full h-full rounded object-cover">
                                                
                                            </div>
                                            <div class="min-w-0 flex-1">
                                                <div class="font-medium truncate">Summit Bulky</div>
                                                <div class="text-[10px] opacity-60 truncate">Timberline Fibers
                                                </div>
                                            </div>
                                        </div>
                                    </button>
                                    
                                </div>

                                <div id="selected_yarns" class="grid grid-cols-1 sm:grid-cols-2 gap-2">
                                    <!-- Chips populated by JS -->
                                </div>
                            </div>
                        </div>

                        <!-- Other Materials Card -->
                        <div class="bg-base-200/30 rounded-xl border border-base-200 p-4">
                            <h3 class="text-lg font-semibold text-base-content mb-4 flex items-center gap-2">
                                <span class="mdi mdi-package-variant-closed text-primary"></span>
                                Other Materials
                            </h3>
                            <div>
                                
<div data-wysiwyg data-wysiwyg-input="other_materials" class="wysiwyg-container">
    <div class="wysiwyg-toolbar">
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bold" title="Bold" aria-label="Bold">
                <span class="mdi mdi-format-bold"></span>
            </button>
            <button type="button" data-action="italic" title="Italic" aria-label="Italic">
                <span class="mdi mdi-format-italic"></span>
            </button>
            <button type="button" data-action="underline" title="Underline" aria-label="Underline">
                <span class="mdi mdi-format-underline"></span>
            </button>
            <button type="button" data-action="strike" title="Strikethrough" aria-label="Strikethrough">
                <span class="mdi mdi-format-strikethrough"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="heading" data-value="1" title="Heading 1" aria-label="Heading 1">
                <span class="mdi mdi-format-header-1"></span>
            </button>
            <button type="button" data-action="heading" data-value="2" title="Heading 2" aria-label="Heading 2">
                <span class="mdi mdi-format-header-2"></span>
            </button>
            <button type="button" data-action="heading" data-value="3" title="Heading 3" aria-label="Heading 3">
                <span class="mdi mdi-format-header-3"></span>
            </button>
            <button type="button" data-action="paragraph" title="Paragraph" aria-label="Paragraph">
                <span class="mdi mdi-format-paragraph"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bulletList" title="Bullet list" aria-label="Bullet list">
                <span class="mdi mdi-format-list-bulleted"></span>
            </button>
            <button type="button" data-action="orderedList" title="Numbered list" aria-label="Numbered list">
                <span class="mdi mdi-format-list-numbered"></span>
            </button>
            <button type="button" data-action="blockquote" title="Quote" aria-label="Quote">
                <span class="mdi mdi-format-quote-close"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="code" title="Code" aria-label="Code">
                <span class="mdi mdi-code-tags"></span>
            </button>
            <button type="button" data-action="codeBlock" title="Code block" aria-label="Code block">
                <span class="mdi mdi-code-braces"></span>
            </button>
            <button type="button" data-action="horizontalRule" title="Horizontal rule" aria-label="Horizontal rule">
                <span class="mdi mdi-minus"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="link" title="Add link" aria-label="Add link">
                <span class="mdi mdi-link"></span>
            </button>
            <button type="button" data-action="unlink" title="Remove link" aria-label="Remove link">
                <span class="mdi mdi-link-off"></span>
            </button>
            <button type="button" data-action="image" title="Insert image" aria-label="Insert image" disabled>
                <span class="mdi mdi-image"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="undo" title="Undo" aria-label="Undo">
                <span class="mdi mdi-undo"></span>
            </button>
            <button type="button" data-action="redo" title="Redo" aria-label="Redo">
                <span class="mdi mdi-redo"></span>
            </button>
        </div>
    </div>
    <div class="wysiwyg-content"></div>
</div>
<textarea id="other_materials" name="other_materials" rows="4" class="hidden" placeholder="e.g. Buttons, zippers, ribbon, etc."  >&amp;nbsp;</textarea>

                            </div>
                        </div>

                        <!-- Recommended Needles Card -->
                        <div class="bg-base-200/30 rounded-xl border border-base-200 p-4">
                            <h3 class="text-lg font-semibold text-base-content mb-4 flex items-center gap-2">
                                <span class="mdi mdi-needle text-primary"></span>
                                Recommended Needles
                            </h3>
                            <div>
                                <textarea id="needles" name="needles" rows="2" class="textarea textarea-bordered w-full"
                                    placeholder="e.g. 4.0mm circular">3.5mm</textarea>
                            </div>
                        </div>

                        <!-- Stitch Sample Card -->
                        <div class="bg-base-200/30 rounded-xl border border-base-200 p-4">
                            <h3 class="text-lg font-semibold text-base-content mb-4 flex items-center gap-2">
                                <span class="mdi mdi-grid text-primary"></span>
                                Stitch Sample
                            </h3>
                            <div class="space-y-4">
                                <div>
                                    
<div data-wysiwyg data-wysiwyg-input="stitch_sample" class="wysiwyg-container">
    <div class="wysiwyg-toolbar">
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bold" title="Bold" aria-label="Bold">
                <span class="mdi mdi-format-bold"></span>
            </button>
            <button type="button" data-action="italic" title="Italic" aria-label="Italic">
                <span class="mdi mdi-format-italic"></span>
            </button>
            <button type="button" data-action="underline" title="Underline" aria-label="Underline">
                <span class="mdi mdi-format-underline"></span>
            </button>
            <button type="button" data-action="strike" title="Strikethrough" aria-label="Strikethrough">
                <span class="mdi mdi-format-strikethrough"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="heading" data-value="1" title="Heading 1" aria-label="Heading 1">
                <span class="mdi mdi-format-header-1"></span>
            </button>
            <button type="button" data-action="heading" data-value="2" title="Heading 2" aria-label="Heading 2">
                <span class="mdi mdi-format-header-2"></span>
            </button>
            <button type="button" data-action="heading" data-value="3" title="Heading 3" aria-label="Heading 3">
                <span class="mdi mdi-format-header-3"></span>
            </button>
            <button type="button" data-action="paragraph" title="Paragraph" aria-label="Paragraph">
                <span class="mdi mdi-format-paragraph"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="bulletList" title="Bullet list" aria-label="Bullet list">
                <span class="mdi mdi-format-list-bulleted"></span>
            </button>
            <button type="button" data-action="orderedList" title="Numbered list" aria-label="Numbered list">
                <span class="mdi mdi-format-list-numbered"></span>
            </button>
            <button type="button" data-action="blockquote" title="Quote" aria-label="Quote">
                <span class="mdi mdi-format-quote-close"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="code" title="Code" aria-label="Code">
                <span class="mdi mdi-code-tags"></span>
            </button>
            <button type="button" data-action="codeBlock" title="Code block" aria-label="Code block">
                <span class="mdi mdi-code-braces"></span>
            </button>
            <button type="button" data-action="horizontalRule" title="Horizontal rule" aria-label="Horizontal rule">
                <span class="mdi mdi-minus"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="link" title="Add link" aria-label="Add link">
                <span class="mdi mdi-link"></span>
            </button>
            <button type="button" data-action="unlink" title="Remove link" aria-label="Remove link">
                <span class="mdi mdi-link-off"></span>
            </button>
            <button type="button" data-action="image" title="Insert image" aria-label="Insert image" >
                <span class="mdi mdi-image"></span>
            </button>
        </div>
        <div class="wysiwyg-toolbar-group">
            <button type="button" data-action="undo" title="Undo" aria-label="Undo">
                <span class="mdi mdi-undo"></span>
            </button>
            <button type="button" data-action="redo" title="Redo" aria-label="Redo">
                <span class="mdi mdi-redo"></span>
            </button>
        </div>
    </div>
    <div class="wysiwyg-content"></div>
</div>
<textarea id="stitch_sample" name="stitch_sample" rows="4" class="hidden" placeholder="Describe your stitch sample / gauge swatch..."  data-markdown-images="true"></textarea>


                                    <h4 id="stitchSamplePhotosLabel"
                                        class="text-xs font-bold text-base-content/50 uppercase tracking-wider mb-2 flex items-center gap-1 mt-4 pt-4 border-t border-base-100 hidden">
                                        <span class="mdi mdi-image-outline"></span> Stitch Sample Photos
                                    </h4>
                                    <div id="stitchSampleImagesContainer"
                                        class="grid grid-cols-2 md:grid-cols-3 gap-4 mb-4 pswp-gallery"
                                        data-pswp-gallery>
                                        
                                    </div>
                                    

<div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg p-8 text-center hover:border-blue-500 transition-colors dark:border-slate-600 dark:hover:border-blue-400 "
     id="stitchSampleDropzone" >
    <input type="file" id="stitchSampleImageInput" 
        accept="image/*"  multiple class="hidden" >
    <label for="stitchSampleImageInput" class="cursor-pointer block">
        <span class="mdi mdi-image-plus mx-auto text-4xl text-slate-400 dark:text-slate-500"></span>
        <p class="upload-instructions mt-2 text-sm text-base-content/70" >
            Drag and drop images here or click to upload
        </p>
    </label>
</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Steps Card -->
            <div
                class="collapse collapse-arrow bg-base-100 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-base-200 overflow-visible">
                <input type="checkbox" checked />
                <div class="collapse-title p-3 sm:p-6 pb-0">
                    <h3 class="text-xl font-semibold text-base-content flex items-center gap-2">
                        <span class="mdi mdi-format-list-text text-primary"></span>
                        Steps
                    </h3>
                </div>
                <div class="collapse-content p-3 sm:p-6 pt-0">
                    <div id="stepsContainer" class="space-y-4 pt-4">
                        
                        <div class="step-item border rounded-lg p-2 md:p-4 bg-base-200/50 border-base-300"
                            data-step-id="3" data-step-number="1">
                            <!-- Step Item Content -->
                            <div class="flex items-center justify-between mb-3">
                                <h4 class="text-lg font-medium text-base-content">Step <span
                                        class="step-number">1</span></h4>
                                <div class="flex flex-wrap gap-2">
                                    <button type="button" data-call="moveStepUp" class="btn btn-xs btn-ghost gap-1"
                                        title="Move Up">
                                        <span class="mdi mdi-arrow-up"></span>
                                        <span class="hidden sm:inline">Move Up</span>
                                    </button>
                                    <button type="button" data-call="moveStepDown" class="btn btn-xs btn-ghost gap-1"
                                        title="Move Down">
                                        <span class="mdi mdi-arrow-down"></span>
                                        <span class="hidden sm:inline">Move Down</span>
                                    </button>
                                    <button type="button" data-call="saveStep" class="btn btn-xs btn-primary gap-1"
                                        title="Save">
                                        <span class="mdi mdi-content-save"></span>
                                        <span class="hidden sm:inline">Save</span>
                                    </button>
                                    <button type="button" data-call="removeStep"
                                        class="btn btn-xs btn-error text-white gap-1" title="Remove Step">
                                        <span class="mdi mdi-delete"></span>
                                        <span class="hidden sm:inline">Remove</span>
                                    </button>
                                </div>
                            </div>
                            <div class="mb-2">
                                <input type="text" class="step-title input input-bordered w-full"
                                    placeholder="Step Title" value="Set Up Rib">
                            </div>
                            <div data-wysiwyg data-wysiwyg-input="step-description-3" data-wysiwyg-step="true" class="wysiwyg-container mb-2">
                                <div class="wysiwyg-toolbar">
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="bold" title="Bold"><span class="mdi mdi-format-bold"></span></button>
                                        <button type="button" data-action="italic" title="Italic"><span class="mdi mdi-format-italic"></span></button>
                                        <button type="button" data-action="underline" title="Underline"><span class="mdi mdi-format-underline"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="heading" data-value="2" title="Heading 2"><span class="mdi mdi-format-header-2"></span></button>
                                        <button type="button" data-action="heading" data-value="3" title="Heading 3"><span class="mdi mdi-format-header-3"></span></button>
                                        <button type="button" data-action="paragraph" title="Paragraph"><span class="mdi mdi-format-paragraph"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="bulletList" title="Bullet list"><span class="mdi mdi-format-list-bulleted"></span></button>
                                        <button type="button" data-action="orderedList" title="Numbered list"><span class="mdi mdi-format-list-numbered"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="link" title="Add link"><span class="mdi mdi-link"></span></button>
                                        <button type="button" data-action="image" title="Insert image"><span class="mdi mdi-image"></span></button>
                                    </div>
                                </div>
                                <div class="wysiwyg-content"></div>
                            </div>
                            <textarea id="step-description-3" name="step-description-3" class="step-description hidden" data-markdown-images="true">Work 2x2 ribbing, slipping first stitch for clean selvedges.</textarea>
                            <h4
                                class="step-photos-label text-xs font-bold text-base-content/50 uppercase tracking-wider mb-2 flex items-center gap-1 mt-4 pt-4 border-t border-base-100">
                                <span class="mdi mdi-image-outline"></span> Step Photos
                            </h4>
                            <div class="step-images grid grid-cols-3 gap-2 mb-2 pswp-gallery" data-pswp-gallery>
                                
                                <div class="relative group aspect-video overflow-hidden" data-image-id="9">
    <a href="/media/projects/2/demo_image_5.jpg" data-pswp-width="1024" data-pswp-height="1024"
        data-pswp-caption="Winding a ball of colorful variegated yarn"  data-pswp-delete="true"  data-pswp-crop="true" 
        data-pswp-is-primary="false" class="block" draggable="true" ondragstart="handleImageDragStart(event)" >
        <img src="/media/thumbnails/projects/2/thumb_demo_image_5.jpg" alt="Winding a ball of colorful variegated yarn"
            class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
    </a>
    
    
    <button type="button" data-call="deleteImage" data-call-args='[9]'
        class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
        <span class="mdi mdi-delete"></span>
    </button>
    
</div>
                                
                            </div>
                            <div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg p-8 text-center hover:border-blue-500 transition-colors step-image-dropzone dark:border-slate-600 dark:hover:border-blue-400"
     data-step-id="3" >
    <input type="file" class="step-image-input hidden" accept="image/*" multiple
        data-step-id="3"  id="stepImageInput3">
    <label for="stepImageInput3" class="cursor-pointer block">
        <svg class="mx-auto h-12 w-12 text-slate-400 dark:text-slate-500" stroke="currentColor" fill="none"
            viewBox="0 0 48 48">
            <path
                d="M28 8H12a4 4 0 00-4 4v20m32-12v8m0 0v8a4 4 0 01-4 4H12a4 4 0 01-4-4v-4m32-4l-3.172-3.172a4 4 0 00-5.656 0L28 28M8 32l9.172-9.172a4 4 0 015.656 0L28 28m0 0l4 4m4-24h8m-4-4v8m-12 4h.02"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <p class="mt-2 text-sm text-base-content/70 upload-instructions"
            data-enabled-text="Drag and drop images here or click to upload"
            data-disabled-text="Save the project before adding images to this step">
            Drag and drop images here or click to upload
        </p>
    </label>
</div>
                        </div>
                        
                        <div class="step-item border rounded-lg p-2 md:p-4 bg-base-200/50 border-base-300"
                            data-step-id="4" data-step-number="2">
                            <!-- Step Item Content -->
                            <div class="flex items-center justify-between mb-3">
                                <h4 class="text-lg font-medium text-base-content">Step <span
                                        class="step-number">2</span></h4>
                                <div class="flex flex-wrap gap-2">
                                    <button type="button" data-call="moveStepUp" class="btn btn-xs btn-ghost gap-1"
                                        title="Move Up">
                                        <span class="mdi mdi-arrow-up"></span>
                                        <span class="hidden sm:inline">Move Up</span>
                                    </button>
                                    <button type="button" data-call="moveStepDown" class="btn btn-xs btn-ghost gap-1"
                                        title="Move Down">
                                        <span class="mdi mdi-arrow-down"></span>
                                        <span class="hidden sm:inline">Move Down</span>
                                    </button>
                                    <button type="button" data-call="saveStep" class="btn btn-xs btn-primary gap-1"
                                        title="Save">
                                        <span class="mdi mdi-content-save"></span>
                                        <span class="hidden sm:inline">Save</span>
                                    </button>
                                    <button type="button" data-call="removeStep"
                                        class="btn btn-xs btn-error text-white gap-1" title="Remove Step">
                                        <span class="mdi mdi-delete"></span>
                                        <span class="hidden sm:inline">Remove</span>
                                    </button>
                                </div>
                            </div>
                            <div class="mb-2">
                                <input type="text" class="step-title input input-bordered w-full"
                                    placeholder="Step Title" value="Length and Finish">
                            </div>
                            <div data-wysiwyg data-wysiwyg-input="step-description-4" data-wysiwyg-step="true" class="wysiwyg-container mb-2">
                                <div class="wysiwyg-toolbar">
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="bold" title="Bold"><span class="mdi mdi-format-bold"></span></button>
                                        <button type="button" data-action="italic" title="Italic"><span class="mdi mdi-format-italic"></span></button>
                                        <button type="button" data-action="underline" title="Underline"><span class="mdi mdi-format-underline"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="heading" data-value="2" title="Heading 2"><span class="mdi mdi-format-header-2"></span></button>
                                        <button type="button" data-action="heading" data-value="3" title="Heading 3"><span class="mdi mdi-format-header-3"></span></button>
                                        <button type="button" data-action="paragraph" title="Paragraph"><span class="mdi mdi-format-paragraph"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="bulletList" title="Bullet list"><span class="mdi mdi-format-list-bulleted"></span></button>
                                        <button type="button" data-action="orderedList" title="Numbered list"><span class="mdi mdi-format-list-numbered"></span></button>
                                    </div>
                                    <div class="wysiwyg-toolbar-group">
                                        <button type="button" data-action="link" title="Add link"><span class="mdi mdi-link"></span></button>
                                        <button type="button" data-action="image" title="Insert image"><span class="mdi mdi-image"></span></button>
                                    </div>
                                </div>
                                <div class="wysiwyg-content"></div>
                            </div>
                            <textarea id="step-description-4" name="step-description-4" class="step-description hidden" data-markdown-images="true">Continue rib to 180 cm, then bind off in pattern and soak block.</textarea>
                            <h4
                                class="step-photos-label text-xs font-bold text-base-content/50 uppercase tracking-wider mb-2 flex items-center gap-1 mt-4 pt-4 border-t border-base-100">
                                <span class="mdi mdi-image-outline"></span> Step Photos
                            </h4>
                            <div class="step-images grid grid-cols-3 gap-2 mb-2 pswp-gallery" data-pswp-gallery>
                                
                                <div class="relative group aspect-video overflow-hidden" data-image-id="10">
    <a href="/media/projects/2/demo_image_19.jpg" data-pswp-width="1600" data-pswp-height="1000"
        data-pswp-caption="Commuter rib scarf in deep red laid out on a neutral backdrop"  data-pswp-delete="true"  data-pswp-crop="true" 
        data-pswp-is-primary="false" class="block" draggable="true" ondragstart="handleImageDragStart(event)" >
        <img src="/media/thumbnails/projects/2/thumb_demo_image_19.jpg" alt="Commuter rib scarf in deep red laid out on a neutral backdrop"
            class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
    </a>
    
    
    <button type="button" data-call="deleteImage" data-call-args='[10]'
        class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
        <span class="mdi mdi-delete"></span>
    </button>
    
</div>
                                
                            </div>
                            <div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg p-8 text-center hover:border-blue-500 transition-colors step-image-dropzone dark:border-slate-600 dark:hover:border-blue-400"
     data-step-id="4" >
    <input type="file" class="step-image-input hidden" accept="image/*" multiple
        data-step-id="4"  id="stepImageInput4">
    <label for="stepImageInput4" class="cursor-pointer block">
        <svg class="mx-auto h-12 w-12 text-slate-400 dark:text-slate-500" stroke="currentColor" fill="none"
            viewBox="0 0 48 48">
            <path
                d="M28 8H12a4 4 0 00-4 4v20m32-12v8m0 0v8a4 4 0 01-4 4H12a4 4 0 01-4-4v-4m32-4l-3.172-3.172a4 4 0 00-5.656 0L28 28M8 32l9.172-9.172a4 4 0 015.656 0L28 28m0 0l4 4m4-24h8m-4-4v8m-12 4h.02"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <p class="mt-2 text-sm text-base-content/70 upload-instructions"
            data-enabled-text="Drag and drop images here or click to upload"
            data-disabled-text="Save the project before adding images to this step">
            Drag and drop images here or click to upload
        </p>
    </label>
</div>
                        </div>
                        
                    </div>
                    <button type="button" data-call="addStep" class="btn btn-success text-white mt-4 gap-2">
                        <span class="mdi mdi-playlist-plus text-xl"></span>
                        Add Step
                    </button>
                </div>
            </div>
        </div>

        <div class="hidden lg:block lg:absolute lg:right-0 lg:top-0 z-30">
            <button type="button" id="project-details-restore-button"
                class="flex h-16 w-8 -mr-4 items-center justify-center rounded-l-full bg-base-100/90 backdrop-blur border border-base-200 shadow-lg text-base-content/50 transition-all duration-200 opacity-0 pointer-events-none translate-x-2 scale-95 hover:text-primary hover:border-primary/40"
                data-call="restoreEditSidebarDetails" title="Project Details"
                aria-label="Project Details">
                <span class="mdi mdi-chevron-left text-lg"></span>
                <span class="sr-only">Project Details</span>
            </button>
        </div>

        <!-- Sidebar Column -->
        <div id="sidebar-column" class="space-y-6 lg:col-start-3 lg:row-start-1 lg:sticky lg:top-24 lg:self-start">
            <!-- Project Details Card -->
            <div
                class="collapse collapse-right collapse-arrow bg-base-100 rounded-2xl shadow-sm border border-base-200 hidden lg:block">
                <input type="checkbox" id="project-details-toggle-sidebar" checked
                    data-call-change="toggleEditSidebarExpansion" />
                <label for="project-details-toggle-sidebar"
                    class="collapse-title font-bold text-base text-base-content/40 uppercase tracking-widest text-xs">
                    <span class="flex items-center gap-2">
                        <span class="mdi mdi-information-outline"></span>
                        Project Details
                    </span>
                </label>

                <div class="collapse-content space-y-4">
                    <div class="pt-2">
                        <div>
                            <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 mb-1">
                                <label for="category" class=" text-xs font-bold uppercase text-base-content/50">
                                    Category
                                </label>
                                <a href="/projects/categories" class="btn btn-link btn-xs p-0 h-auto min-h-0"
                                    target="_blank" rel="noreferrer">
                                    Manage
                                </a>
                            </div>
                            <select id="category" name="category" class="select  select-sm w-full">
                                <option value="">Select a category</option>
                                
                                <option value="Jacke" >
                                    Jacke
                                </option>
                                
                                <option value="Mütze" >
                                    Mütze
                                </option>
                                
                                <option value="Pullover" >
                                    Pullover
                                </option>
                                
                                <option value="Schal" selected>
                                    Schal
                                </option>
                                
                                <option value="Stirnband" >
                                    Stirnband
                                </option>
                                
                            </select>
                        </div>

                        <div>
                            <label for="tags_input" class=" text-xs font-bold uppercase text-base-content/50">
                                Tags
                            </label>
                            <div id="tags_chips" class="flex flex-wrap gap-2 mt-2"></div>
                            <div class="relative mt-2">
                                <input type="text" id="tags_input" class="input  input-sm w-full" autocomplete="off"
                                    placeholder="Add a tag...">
                                <div id="tags_suggestions"
                                    class="hidden absolute z-30 w-full mt-1 max-h-48 overflow-y-auto bg-base-100 border border-base-300 rounded-lg shadow-xl">
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="accessory">
                                        #accessory
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="baby">
                                        #baby
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="beginner-friendly">
                                        #beginner-friendly
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="bulky">
                                        #bulky
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="cardigan">
                                        #cardigan
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="ex">
                                        #ex
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="garter">
                                        #garter
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="gift">
                                        #gift
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="halo">
                                        #halo
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="lace">
                                        #lace
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="lightweight">
                                        #lightweight
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="one-skein">
                                        #one-skein
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="outerwear">
                                        #outerwear
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="pockets">
                                        #pockets
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="quick">
                                        #quick
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="raglan">
                                        #raglan
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="rainbow">
                                        #rainbow
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="ribbing">
                                        #ribbing
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="summer">
                                        #summer
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="top-down">
                                        #top-down
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="tweed">
                                        #tweed
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="uni">
                                        #uni
                                    </button>
                                    
                                    <button type="button"
                                        class="tag-suggestion w-full text-left px-3 py-2 hover:bg-base-200 text-sm"
                                        data-tag="winter">
                                        #winter
                                    </button>
                                    
                                </div>
                            </div>
                            <p class="text-[10px] text-base-content/60 mt-1">Press Enter or choose a suggestion.
                            </p>
                        </div>

                        <div class="divider my-2"></div>

                        <div>
                            <div class="flex items-center justify-between mb-1">
                                <label for="link" class=" text-xs font-bold uppercase text-base-content/50">
                                    Pattern Source
                                </label>
                                <button type="button" id="reimport-btn"
                                    class="btn btn-ghost btn-xs gap-1 normal-case "
                                    data-call="importFromUrl" data-call-args='["$value:#link"]'>
                                    <span class="mdi mdi-download"></span>
                                    Re-import
                                </button>
                                <a href="https://example.com/patterns/commuter-rib-scarf" target="_blank"
                                    class="btn btn-ghost btn-xs gap-1 normal-case "
                                    id="visit-link-btn" title="Visit Website">
                                    <span class="mdi mdi-open-in-new"></span>
                                    Visit
                                </a>
                                <button type="button" class="btn btn-ghost btn-xs gap-1 normal-case"
                                    data-call="copyToClipboard" data-call-args='["$value:#link","$this"]'
                                    title="Copy URL">
                                    <span class="mdi mdi-content-copy"></span>
                                </button>
                            </div>
                            <input type="url" id="link" name="link" value="https://example.com/patterns/commuter-rib-scarf"
                                class="input  input-sm w-full" placeholder="https://..."
                                data-call-input="syncLinkInputs">
                            <label id="wayback_sidebar_container"
                                class="label cursor-pointer justify-start gap-3 px-3 py-2 bg-base-200/50 rounded-xl border border-base-300 mt-2 ">
                                <input type="checkbox" id="archive_on_save_sidebar" name="archive_on_save" value="1"
                                    class="checkbox checkbox-primary checkbox-sm" checked>
                                <span class="label-text text-xs font-bold uppercase text-base-content/50">Archive on Wayback Machine</span>
                            </label>
                        </div>

                        <div class="pt-2 border-t border-base-200">
                            <label id="ai_enhanced_sidebar_container"
                                class="label cursor-pointer justify-start gap-3 px-3 py-2 bg-base-200/50 rounded-xl border border-base-300">
                                <input type="checkbox" id="is_ai_enhanced_checkbox"
                                    class="checkbox checkbox-primary checkbox-sm"  data-call-change="syncAiEnhanced">
                                <span
                                    class="label-text text-xs font-bold uppercase text-base-content/50 flex items-center gap-2">
                                    <span class="badge badge-primary font-black text-[10px] h-4 min-h-0">AI</span>
                                    AI Enhanced
                                </span>
                            </label>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Danger Zone -->
            
            <div
                class="bg-red-50 dark:bg-red-900/10 rounded-none sm:rounded-2xl shadow-sm border-y sm:border border-red-100 dark:border-red-900/30 p-3 sm:p-5">
                <h3
                    class="font-bold text-base text-red-800 dark:text-red-400 uppercase tracking-widest mb-4 flex items-center gap-2 text-xs">
                    <span class="mdi mdi-alert-outline"></span>
                    Danger Zone
                </h3>
                


<button type="button" class="btn btn-error text-white w-full" data-action="open-dialog" data-dialog-id="deleteProjectDialog">
    <span class="mdi mdi-delete-outline text-xl"></span>
    <span>Delete Project</span>
</button>


            </div>
            
        </div>
    </div>
</form>

</div>

<dialog id="unsavedChangesDialog" class="modal modal-top sm:modal-middle " >
    <div class="modal-box ">
        <form method="dialog">
            <button class="btn btn-sm btn-circle btn-ghost absolute right-2 top-2">
                <span class="mdi mdi-close text-xl"></span>
            </button>
        </form>
        
        <h3 class="font-bold text-lg mb-4">Unsaved changes</h3>
        
        
<p class="py-4">You have unsaved changes. Save before leaving?</p>
<div class="modal-action">
    
<button type="button" class="btn btn-ghost gap-2 "  data-unsaved-cancel>
    <span class="mdi mdi-close"></span>
    Cancel
</button>

    
<button type="button" class="btn btn-error text-white gap-2 "  data-unsaved-leave>
    <span class="mdi mdi-trash-can-outline"></span>
    Discard
</button>

    
<button type="button" class="btn btn-primary gap-2 "  data-unsaved-save>
    <span class="mdi mdi-content-save"></span>
    Save
</button>

</div>

    </div>
    
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

</dialog>


<script>
    let initialLink = document.getElementById('link')?.value.trim() || '';
    let isImportPrompted = false;

    function toggleEditSidebarExpansion(checkbox) {
        const main = document.getElementById('main-column');
        const sidebar = document.getElementById('sidebar-column');
        const restoreButton = document.getElementById('project-details-restore-button');
        if (!main || !sidebar) return;

        if (checkbox.checked) {
            main.classList.replace('lg:col-span-3', 'lg:col-span-2');
            main.classList.remove('lg:pr-12');
            sidebar.classList.remove('lg:hidden', 'lg:pointer-events-none', 'lg:opacity-0');
            sidebar.classList.add('lg:block');
            restoreButton?.classList.add('opacity-0', 'pointer-events-none', 'translate-x-2', 'scale-95');
            restoreButton?.classList.remove('opacity-100', 'translate-x-0', 'scale-100');
        } else {
            main.classList.replace('lg:col-span-2', 'lg:col-span-3');
            main.classList.add('lg:pr-12');
            sidebar.classList.add('lg:hidden', 'lg:pointer-events-none', 'lg:opacity-0');
            sidebar.classList.remove('lg:block');
            restoreButton?.classList.remove('opacity-0', 'pointer-events-none', 'translate-x-2', 'scale-95');
            restoreButton?.classList.add('opacity-100', 'translate-x-0', 'scale-100');
        }
    }

    function restoreEditSidebarDetails() {
        const sidebarToggle = document.getElementById('project-details-toggle-sidebar');
        if (!sidebarToggle) return;
        sidebarToggle.checked = true;
        toggleEditSidebarExpansion(sidebarToggle);
    }

    function autoResize(el) {
        if (!el) return;
        // Use setTimeout to ensure the element is visible and has dimensions
        setTimeout(() => {
            el.style.height = 'auto';
            el.style.height = (el.scrollHeight + 2) + 'px';
        }, 0);
    }

    function syncAiEnhanced(el) {
        const isChecked = el.checked;
        const hiddenInput = document.getElementById('is_ai_enhanced');
        const desktopCheckbox = document.getElementById('is_ai_enhanced_checkbox');
        const mobileCheckbox = document.getElementById('is_ai_enhanced_mobile_checkbox');

        if (hiddenInput) hiddenInput.value = isChecked ? '1' : '';
        if (desktopCheckbox && desktopCheckbox !== el) desktopCheckbox.checked = isChecked;
        if (mobileCheckbox && mobileCheckbox !== el) mobileCheckbox.checked = isChecked;

        window.unsavedChanges?.setDirty(true);
    }

    function canonicalizeImportImageUrl(rawUrl) {
        if (!rawUrl) {
            return '';
        }

        const trimmed = String(rawUrl).trim();
        if (!trimmed) {
            return '';
        }

        // Treat protocol-relative URLs as HTTPS.
        const withProtocol = trimmed.startsWith('//') ? `https:${trimmed}` : trimmed;

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
        const container = document.getElementById('titleImagesContainer');
        const existingKeys = new Set();
        if (container) {
            container.querySelectorAll('a[href]').forEach(anchor => {
                const href = anchor.getAttribute('href');
                const key = canonicalizeImportImageUrl(href);
                if (key) existingKeys.add(key);
            });
        }

        const seenKeys = new Set();
        const result = [];
        urls.forEach(url => {
            const normalized = String(url || '').trim();
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
        const stepItem = textarea.closest('.step-item');
        const isStepDescription = Boolean(stepItem);
        if (textarea.id === 'stitch_sample') {
            container = document.getElementById('stitchSampleImagesContainer');
            label = document.getElementById('stitchSamplePhotosLabel');
        } else if (isStepDescription) {
            container = stepItem.querySelector('.step-images');
            label = stepItem.querySelector('.step-photos-label');
        }

        if (!container) return;

        const images = container.querySelectorAll('[data-image-id], [data-pending-url]');
        if (textarea.id === 'stitch_sample' || isStepDescription) {
            images.forEach(imgWrapper => {
                imgWrapper.classList.remove('hidden');
                imgWrapper.querySelectorAll('.hidden').forEach(child => {
                    child.classList.remove('hidden');
                });
            });
            if (images.length === 0) {
                container.classList.add('hidden');
                if (label) label.classList.add('hidden');
            } else {
                container.classList.remove('hidden');
                if (label) label.classList.remove('hidden');
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
        images.forEach(imgWrapper => {
            const anchor = imgWrapper.querySelector('a');
            if (!anchor) return;
            const url = anchor.getAttribute('href');

            // Check if URL (decoded or literal) is in the text
            const isUsed = urlsInText.some(u => {
                try {
                    return u === url || decodeURI(u) === url || u.endsWith(url);
                } catch (e) {
                    return u === url || u.endsWith(url);
                }
            });

            if (isUsed) {
                imgWrapper.classList.add('hidden');
            } else {
                imgWrapper.classList.remove('hidden');
                visibleCount++;
            }
        });

        // Hide section if no images or all are used in text
        if (images.length === 0 || visibleCount === 0) {
            container.classList.add('hidden');
            if (label) label.classList.add('hidden');
        } else {
            container.classList.remove('hidden');
            if (label) label.classList.remove('hidden');
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        const titleImageInput = document.getElementById('titleImageInput');
        const titleImageDropZone = document.getElementById('titleImageDropZone');
        if (titleImageInput && titleImageDropZone) {
            window.setupImageUploadWidget(titleImageInput, titleImageDropZone, uploadTitleImage);
        }

        initStepImageUploaders();
        initStitchSampleImageUploader();
        initAttachmentUploader();
        initYarnSelector();
        initTagEditor();
        const sidebarToggle = document.getElementById('project-details-toggle-sidebar');
        if (sidebarToggle) toggleEditSidebarExpansion(sidebarToggle);

        const importedData = sessionStorage.getItem('importedData');
        if (importedData) {
            try {
                const data = JSON.parse(importedData);

                // Prevent re-prompting on save
                isImportPrompted = true;

                const setFieldValue = (id, value) => {
                    const el = document.getElementById(id);
                    if (el && value !== undefined && value !== null) {
                        if (id === 'notes' && el.value.trim().length > 0) return false;
                        el.value = value;
                        if (typeof autoResize === 'function') autoResize(el);
                        return true;
                    }
                    return false;
                };

                const warningBanner = document.getElementById('importWarning');
                if (warningBanner) warningBanner.classList.remove('hidden');

                // Metadata
                setFieldValue('name', data.title || data.name);
                setFieldValue('needles', data.needles);
                setFieldValue('yarn_brand', data.brand);

                const yarnDetailsField = document.getElementById('yarn_details');
                if (yarnDetailsField && data.yarn_details) {
                    yarnDetailsField.value = JSON.stringify(data.yarn_details);
                }

                if (data.yarn || data.yarn_details) {
                    window.yarnSelector?.selectByName(data.yarn, data.yarn_details || []);
                }

                setFieldValue('stitch_sample', data.stitch_sample);
                setFieldValue('description', data.description);
                setFieldValue('category', data.category);
                setFieldValue('tags', data.tags);
                setFieldValue('notes', data.notes);
                setFieldValue('link', data.link);
                if (data.link) initialLink = data.link;

                // Set AI enhanced flag
                const isAiEnhanced = data.is_ai_enhanced === true;
                const hiddenAiInput = document.getElementById('is_ai_enhanced');
                if (hiddenAiInput) hiddenAiInput.value = isAiEnhanced ? '1' : '';
                const aiCheckbox = document.getElementById('is_ai_enhanced_checkbox');
                const aiCheckboxMobile = document.getElementById('is_ai_enhanced_mobile_checkbox');
                if (aiCheckbox) aiCheckbox.checked = isAiEnhanced;
                if (aiCheckboxMobile) aiCheckboxMobile.checked = isAiEnhanced;

                // Image URLs (Project level)
                const importImagesField = document.getElementById('import_image_urls');
                const imageUrls = Array.isArray(data.image_urls) ? data.image_urls : [];
                const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
                if (importImagesField) {
                    importImagesField.value = dedupedImageUrls.length
                        ? JSON.stringify(dedupedImageUrls)
                        : '';
                }
                const importAttachmentTokensField = document.getElementById('import_attachment_tokens');
                if (importAttachmentTokensField && Array.isArray(data.import_attachment_tokens)) {
                    let existingTokens = [];
                    try { existingTokens = JSON.parse(importAttachmentTokensField.value || '[]'); } catch (e) { existingTokens = []; }
                    const merged = [...new Set([...(Array.isArray(existingTokens) ? existingTokens : []), ...data.import_attachment_tokens])];
                    importAttachmentTokensField.value = JSON.stringify(merged);
                }
                const archiveField = document.getElementById('archive_on_save');
                if (archiveField) {
                    archiveField.value = '1';
                }

                // Render pending images for preview
                if (dedupedImageUrls.length > 0) {
                    dedupedImageUrls.forEach(url => {
                        if (typeof addPendingTitleImageToGallery === 'function') {
                            try { addPendingTitleImageToGallery(url); } catch (e) { console.error(e); }
                        }
                    });
                }

                // Steps
                if (data.steps && data.steps.length > 0) {
                    const stepsContainer = document.getElementById('stepsContainer');
                    if (stepsContainer) {
                        stepsContainer.innerHTML = '';
                        data.steps.forEach((step, index) => {
                            if (typeof addStep === 'function') {
                                try {
                                    addStep(step.title || `Step ${index + 1}`, step.description || '', step.images || []);
                                } catch (e) {
                                    console.error('Failed to add step from storage', e, step);
                                }
                            }
                        });
                    }
                }

                // Source Attachments (PDFs, etc.)
                if (Array.isArray(data.source_attachments) && data.source_attachments.length > 0) {
                    data.source_attachments.forEach(attachment => {
                        if (typeof addAttachmentToUI === 'function') {
                            try {
                                addAttachmentToUI(attachment);
                            } catch (e) {
                                console.error('Failed to add attachment from storage', e, attachment);
                            }
                        }
                    });
                }

                sessionStorage.removeItem('importedData');
                window.unsavedChanges?.setDirty(true);
                window.showToast?.('Pattern data loaded - please review and save', 'success');

                setTimeout(() => {
                    const saveButton = document.querySelector('button[type="submit"]');
                    if (saveButton) {
                        saveButton.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        saveButton.classList.add('ring-4', 'ring-primary', 'ring-opacity-50');
                        setTimeout(() => {
                            saveButton.classList.remove('ring-4', 'ring-primary', 'ring-opacity-50');
                        }, 3000);
                    }
                }, 500);

            } catch (error) {
                console.error('Error loading imported data:', error);
                sessionStorage.removeItem('importedData');
            }
        }

        document.querySelectorAll('textarea').forEach(el => {
            el.style.overflowY = 'hidden';
            autoResize(el);
            updateImageVisibility(el);
            el.addEventListener('input', () => {
                autoResize(el);
                updateImageVisibility(el);
            });

            setupTextareaDrop(el);
        });

        // Initialize checkbox states based on link presence
        const linkEl = document.getElementById('link');
        if (linkEl) syncLinkInputs(linkEl);

        const urlParams = new URLSearchParams(window.location.search);
        if (urlParams.get('import') === '1') {
            const currentUrl = document.getElementById('link')?.value;
            if (currentUrl) {
                importFromUrl(currentUrl);
            } else {
                document.getElementById('importDialog')?.showModal();
            }
        }
    });

    function initTagEditor() {
        const hiddenInput = document.getElementById('tags');
        if (!hiddenInput) return;

        const desktop = {
            input: document.getElementById('tags_input'),
            chips: document.getElementById('tags_chips'),
            suggestions: document.getElementById('tags_suggestions'),
        };
        const mobile = {
            input: document.getElementById('tags_input_mobile'),
            chips: document.getElementById('tags_chips_mobile'),
            suggestions: document.getElementById('tags_suggestions_mobile'),
        };
        const views = [desktop, mobile].filter(view => view.input && view.chips);

        const normalizeTag = (raw) => raw.replace(/^#/, '').trim();
        const splitTags = (raw) => raw.split(/[,#\\s]+/).map(normalizeTag).filter(Boolean);

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
            const index = tags.findIndex(item => item.toLowerCase() === key);
            if (index >= 0) tags.splice(index, 1);
            renderChips();
            syncHidden();
        };

        const syncHidden = () => {
            hiddenInput.value = tags.join(', ');
        };

        const renderChips = () => {
            views.forEach(view => {
                view.chips.innerHTML = '';
                tags.forEach(tag => {
                    const chip = document.createElement('button');
                    chip.type = 'button';
                    chip.className = 'badge badge-outline gap-1';
                    chip.setAttribute('data-tag', tag);
                    chip.innerHTML = `#${tag}<span class="mdi mdi-close text-[10px]"></span>`;
                    chip.addEventListener('click', () => removeTag(tag));
                    view.chips.appendChild(chip);
                });
            });
            refreshSuggestions();
        };

        const refreshSuggestions = () => {
            const selected = new Set(tags.map(tag => tag.toLowerCase()));
            views.forEach(view => {
                if (!view.suggestions) return;
                view.suggestions.querySelectorAll('.tag-suggestion').forEach(button => {
                    const tag = button.dataset.tag || '';
                    const isHidden = selected.has(tag.toLowerCase());
                    button.classList.toggle('hidden', isHidden);
                });
            });
        };

        const addFromInput = (input) => {
            const raw = input.value;
            splitTags(raw).forEach(addTag);
            input.value = '';
            refreshSuggestions();
        };

        const handleInput = (event, view) => {
            if (!view.suggestions) return;
            const query = event.target.value.trim().toLowerCase();
            const hasQuery = query.length > 0;
            let anyVisible = false;
            view.suggestions.querySelectorAll('.tag-suggestion').forEach(button => {
                const tag = (button.dataset.tag || '').toLowerCase();
                const matches = !query || tag.includes(query);
                const isSelected = tagIndex.has(tag);
                const shouldShow = matches && !isSelected;
                button.classList.toggle('hidden', !shouldShow);
                if (shouldShow) anyVisible = true;
            });
            view.suggestions.classList.toggle('hidden', !hasQuery || !anyVisible);
        };

        const handleKeydown = (event, view) => {
            if (event.key === 'Enter' || event.key === 'Tab') {
                if (event.target.value.trim()) {
                    event.preventDefault();
                    addFromInput(event.target);
                    view.suggestions?.classList.add('hidden');
                }
            }
            if (event.key === 'Escape') {
                view.suggestions?.classList.add('hidden');
            }
        };

        const handleBlur = (view) => {
            setTimeout(() => {
                view.suggestions?.classList.add('hidden');
                if (view.input && view.input.value.trim()) {
                    addFromInput(view.input);
                }
            }, 100);
        };

        const bindView = (view) => {
            if (!view.input) return;
            view.input.addEventListener('input', (event) => handleInput(event, view));
            view.input.addEventListener('keydown', (event) => handleKeydown(event, view));
            view.input.addEventListener('blur', () => handleBlur(view));
            view.suggestions?.querySelectorAll('.tag-suggestion').forEach(button => {
                button.addEventListener('click', () => {
                    addTag(button.dataset.tag || '');
                    view.suggestions?.classList.add('hidden');
                });
            });
        };

        splitTags(hiddenInput.value).forEach(addTag);
        views.forEach(bindView);
        refreshSuggestions();
    }

    function initYarnSelector() {
        const searchInput = document.getElementById('yarn_search');
        const dropdown = document.getElementById('yarn_dropdown');
        const selectedContainer = document.getElementById('selected_yarns');
        const hiddenInput = document.getElementById('yarn_ids');
        const yarnOptions = document.querySelectorAll('.yarn-option');

        const selectedYarns = new Map();
        const pendingYarns = new Map(); // Yarns that don't exist in DB yet: name -> { name, imageUrl }

        function selectYarn(id, name, brand, colorway, dyeLot, imageUrl) {
            if (selectedYarns.has(id)) return;

            selectedYarns.set(id, { name, brand, colorway, dyeLot, imageUrl });
            updateSelectedDisplay();
            updateHiddenInput();
            filterOptions();
        }

        function selectPendingYarn(name, imageUrl = '') {
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
                if (!name && details.length === 0) return { anySelected: false, remaining: '' };

                let anySelected = false;

                // If we have structured details, use them first
                if (details && details.length > 0) {
                    details.forEach(detail => {
                        const dName = detail.name || detail.yarn;
                        if (!dName) return;

                        const nLower = dName.toLowerCase();
                        const dLink = detail.link;

                        // Try to find an existing option by link OR name
                        const option = Array.from(yarnOptions).find(opt => {
                            const optLink = opt.dataset.yarnLink; // We might need to add this to the dataset
                            if (dLink && optLink === dLink) return true;

                            const optName = opt.dataset.yarnName.toLowerCase();
                            const optBrand = (opt.dataset.yarnBrand || '').toLowerCase();
                            return optName === nLower ||
                                `${optBrand} ${optName}`.toLowerCase() === nLower ||
                                (optBrand && nLower.includes(optBrand) && nLower.includes(optName));
                        });

                        if (option) {
                            selectYarn(
                                parseInt(option.dataset.yarnId),
                                option.dataset.yarnName,
                                option.dataset.yarnBrand,
                                option.dataset.yarnColorway,
                                option.dataset.yarnDyeLot,
                                option.dataset.yarnImage
                            );
                            anySelected = true;
                        } else {
                            selectPendingYarn(dName, detail.image_url || '');
                            anySelected = true;
                        }
                    });
                } else if (name) {
                    // Fallback to name parsing if no details provided
                    let rawNames = [];
                    if (name.includes('\n')) {
                        rawNames = name.split('\n').map(n => n.trim()).filter(Boolean);
                    } else {
                        if (/(?:farbe|color|colour)\s*\d+\s*,\s*/i.test(name)) {
                            rawNames = [name.trim()];
                        } else {
                            rawNames = name.split(',').map(n => n.trim()).filter(Boolean);
                        }
                    }

                    rawNames.forEach(n => {
                        const nLower = n.toLowerCase();
                        const option = Array.from(yarnOptions).find(opt => {
                            const optName = opt.dataset.yarnName.toLowerCase();
                            const optBrand = (opt.dataset.yarnBrand || '').toLowerCase();
                            return optName === nLower ||
                                `${optBrand} ${optName}`.toLowerCase() === nLower ||
                                (optBrand && nLower.includes(optBrand) && nLower.includes(optName));
                        });

                        if (option) {
                            selectYarn(
                                parseInt(option.dataset.yarnId),
                                option.dataset.yarnName,
                                option.dataset.yarnBrand,
                                option.dataset.yarnColorway,
                                option.dataset.yarnDyeLot,
                                option.dataset.yarnImage
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
                    searchInput.value = '';
                }

                return { anySelected, remaining: '' };
            }
        };

    function updateSelectedDisplay() {
        if (selectedYarns.size === 0 && pendingYarns.size === 0) {
            selectedContainer.innerHTML = '<span class="text-base-content/40 text-sm">No yarns selected</span>';
            return;
        }

        selectedContainer.innerHTML = '';

        // Render existing yarns
        selectedYarns.forEach((yarn, id) => {
            const chip = document.createElement('div');
            chip.className = 'flex items-center gap-3 py-2.5 px-3.5 bg-primary/10 text-primary border border-primary/20 rounded-xl';

            const imageHtml = yarn.imageUrl
                ? `<img src="${yarn.imageUrl}" alt="${yarn.name}" class="w-10 h-10 rounded-lg object-cover" onerror="this.replaceWith(this.parentElement.querySelector('[data-fallback-icon]').cloneNode(true)); this.parentElement.querySelector('[data-fallback-icon]').classList.remove('hidden')">`
                : '';

            chip.innerHTML = `
	                    ${imageHtml}
	                    <div class="w-10 h-10 rounded-lg bg-base-300 flex items-center justify-center text-base-content/60${yarn.imageUrl ? ' hidden' : ''}" data-fallback-icon>
	                        <span class="mdi mdi-image-off text-base" aria-hidden="true"></span>
	                    </div>
	                    <span class="text-base flex-1 min-w-0 truncate">
	                        ${yarn.name}${yarn.brand ? ` • ${yarn.brand}` : ''}
	                    </span>
	                    <button type="button" class="btn btn-ghost btn-xs btn-circle" data-remove-yarn="${id}">
	                        <span class="mdi mdi-close"></span>
	                    </button>
	                `;
            selectedContainer.appendChild(chip);

            chip.querySelector('[data-remove-yarn]').addEventListener('click', () => {
                removeYarn(id);
            });
        });

        // Render pending yarns
        pendingYarns.forEach(yarn => {
            const name = yarn.name;
            const chip = document.createElement('div');
            chip.className = 'flex items-center gap-3 py-2.5 px-3.5 bg-secondary/10 text-secondary border border-secondary/20 rounded-xl';

            const imageHtml = yarn.imageUrl
                ? `<img src="${yarn.imageUrl}" alt="${name}" class="w-10 h-10 rounded-lg object-cover" onerror="this.replaceWith(this.parentElement.querySelector('[data-fallback-icon]').cloneNode(true)); this.parentElement.querySelector('[data-fallback-icon]').classList.remove('hidden')">`
                : '';

            chip.innerHTML = `
	                    ${imageHtml}
	                    <div class="relative w-10 h-10 rounded-lg bg-base-300 flex items-center justify-center text-base-content/60${yarn.imageUrl ? ' hidden' : ''}" data-fallback-icon>
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

            chip.querySelector('[data-remove-pending]').addEventListener('click', () => {
                removePendingYarn(name);
            });
        });
    }

    function updateHiddenInput() {
        hiddenInput.value = Array.from(selectedYarns.keys()).join(',');

        const yarnTextHidden = document.getElementById('yarn_text_hidden');
        if (yarnTextHidden) {
            yarnTextHidden.value = Array.from(pendingYarns.keys()).join('\n');
        }

        // Explicitly clear yarn_search if we have pending yarns as chips
        const searchInput = document.getElementById('yarn_search');
        if (searchInput && pendingYarns.size > 0 && document.activeElement !== searchInput) {
            // If the user isn't typing, make sure it's clean
            if (searchInput.value.includes('\n') || Array.from(pendingYarns.keys()).some(y => searchInput.value.includes(y))) {
                searchInput.value = '';
            }
        }
    }

    function filterOptions() {
        const searchTerm = searchInput.value.toLowerCase();
        let visibleCount = 0;

        yarnOptions.forEach(option => {
            const yarnId = parseInt(option.dataset.yarnId);
            const isSelected = selectedYarns.has(yarnId);
            const name = option.dataset.yarnName.toLowerCase();
            const brand = (option.dataset.yarnBrand || '').toLowerCase();
            const colorway = (option.dataset.yarnColorway || '').toLowerCase();
            const dyeLot = (option.dataset.yarnDyeLot || '').toLowerCase();
            const matchesSearch =
                !searchTerm
                || name.includes(searchTerm)
                || brand.includes(searchTerm)
                || colorway.includes(searchTerm)
                || dyeLot.includes(searchTerm);

            if (!isSelected && matchesSearch) {
                option.classList.remove('hidden');
                visibleCount++;
            } else {
                option.classList.add('hidden');
            }
        });

        if (visibleCount > 0 && (searchInput === document.activeElement || searchTerm)) {
            dropdown.classList.remove('hidden');
        } else {
            dropdown.classList.add('hidden');
        }
    }

    searchInput.addEventListener('focus', () => {
        filterOptions();
    });

    searchInput.addEventListener('input', () => {
        filterOptions();
    });

    searchInput.addEventListener('blur', (e) => {
        setTimeout(() => {
            if (!dropdown.contains(document.activeElement)) {
                dropdown.classList.add('hidden');
            }
        }, 200);
    });

    yarnOptions.forEach(option => {
        option.addEventListener('click', (e) => {
            e.preventDefault();
            const yarnId = parseInt(option.dataset.yarnId);
            const yarnName = option.dataset.yarnName;
            const yarnBrand = option.dataset.yarnBrand;
            const yarnColorway = option.dataset.yarnColorway;
            const yarnDyeLot = option.dataset.yarnDyeLot;
            const yarnImage = option.dataset.yarnImage;

            selectYarn(yarnId, yarnName, yarnBrand, yarnColorway, yarnDyeLot, yarnImage);
            searchInput.value = '';
            searchInput.focus();
        });
    });

    document.addEventListener('click', (e) => {
        if (!searchInput.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('hidden');
        }
    });
}

    function setupTextareaDrop(textarea) {
        textarea.addEventListener('dragover', (e) => {
            e.preventDefault();
            textarea.classList.add('border-blue-500', 'ring-2', 'ring-blue-500');
        });

        textarea.addEventListener('dragleave', (e) => {
            e.preventDefault();
            textarea.classList.remove('border-blue-500', 'ring-2', 'ring-blue-500');
        });

        textarea.addEventListener('drop', async (e) => {
            e.preventDefault();
            textarea.classList.remove('border-blue-500', 'ring-2', 'ring-blue-500');

            const files = e.dataTransfer.files;
            if (files && files.length > 0) {
                const imageFiles = Array.from(files).filter(file => file.type.startsWith('image/'));
                if (imageFiles.length === 0) return;

                const stepItem = textarea.closest('.step-item');
                let sid = stepItem ? stepItem.getAttribute('data-step-id') : null;

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

            const text = e.dataTransfer.getData('text/plain');
            if (text) {
                insertAtCursor(textarea, text);
            }
        });
    }

    function handleImageDragStart(event) {
        // Find the anchor element (either target itself or parent)
        const anchor = event.target.tagName === 'A' ? event.target : event.target.closest('a');
        if (!anchor) return;

        const src = anchor.getAttribute('href');
        const alt = anchor.getAttribute('data-pswp-caption') || (anchor.querySelector('img')?.alt) || "";
        const markdown = `![${alt}](${src})`;

        // Debug
        console.log('Dragging image as markdown:', markdown);

        // Set data
        event.dataTransfer.setData('text/plain', markdown);
        event.dataTransfer.effectAllowed = 'copy';

        // Important: set dropEffect in dragover/drop handlers, here we just set effectAllowed
    }
    function insertAtCursor(textarea, text) {
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        const val = textarea.value;
        textarea.value = val.substring(0, start) + text + val.substring(end);
        textarea.selectionStart = textarea.selectionEnd = start + text.length;
        textarea.focus();
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
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
            imageClass = 'h-32'
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
               ${showPromote ? 'data-pswp-promote="true"' : ''}
               ${showDelete ? 'data-pswp-delete="true"' : ''}
               data-pswp-is-primary="${isPrimary ? 'true' : 'false'}"
               draggable="true" ondragstart="handleImageDragStart(event)"
               class="block">
                <img src="${thumbUrl}"
                     alt="${altText}"
                     class="${imageClass} w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
            </a>
	            ${showPromote ? `
	            <button type="button" data-call="promoteImage" data-call-args='[${projectId},${imageData.id}]'
	                class="title-promote-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all ${isPrimary ? 'bg-amber-400 text-white opacity-100' : 'bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500'} hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	                title="Make title image">
	                <span class="mdi ${isPrimary ? 'mdi-star' : 'mdi-star-outline'}"></span>
	            </button>
	            ` : ''}
	            ${showDelete ? `
	            <button type="button" data-call="deleteImage" data-call-args='[${imageData.id}]'
	                class="absolute top-1 right-1 z-10 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
	                <span class="mdi mdi-delete"></span>
	            </button>
	            ` : ''}
        </div>
        `;
    }

    async function uploadAndInsertImage(file, textarea, stepId) {
        if (!stepId && !(await ensureProjectId())) return;

        const formData = new FormData();
        formData.append('file', file);
        formData.append('alt_text', file.name);

        const url = stepId
            ? `/projects/${projectId}/steps/${stepId}/images`
            : `/projects/${projectId}/images/title`;

        try {
            window.showToast?.('Uploading image...', 'info');
            const response = await fetch(url, {
                method: 'POST',
                body: formData
            });

            if (response.ok) {
                const data = await response.json();
                const markdown = `![${data.alt_text}](${data.url})`;
                insertAtCursor(textarea, markdown);
                window.showToast?.('Image uploaded!', 'success');
                window.unsavedChanges?.setDirty(true);

                if (stepId) {
                    const stepItem = document.querySelector(`.step-item[data-step-id="${stepId}"]`);
                    if (stepItem) {
                        addStepImagePreview(stepItem, data);
                    }
                } else {
                    addTitleImageToGallery(data);
                }
            } else {
                window.showToast?.('Upload failed', 'error');
            }
        } catch (error) {
            console.error('Upload failed', error);
            window.showToast?.('Upload failed', 'error');
        }
    }

    let projectId = 2;
    let wasSilentlyCreated = false;

    // Initialize discard hook
    document.addEventListener('DOMContentLoaded', () => {
        if (window.unsavedChanges) {
            window.unsavedChanges.onBeforeDiscard = async () => {
	                if (wasSilentlyCreated && projectId) {
	                    console.log('Discarding silent project:', projectId);
	                    try {
	                        const response = await fetch(`/projects/${projectId}`, {
	                            method: 'DELETE',
	                            headers: {
	                                'HX-Request': 'true', // Trigger HTMX-like response if needed, although we are navigating away
	                                'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
	                            }
	                        });
                        if (!response.ok) {
                            console.error('Failed to delete silent project');
                        }
                    } catch (error) {
                        console.error('Error deleting silent project:', error);
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

        const nameInput = document.getElementById('name');
        if (nameInput && !nameInput.value.trim()) {
            const now = new Date();
            const dateStr = now.getFullYear() + '-' +
                String(now.getMonth() + 1).padStart(2, '0') + '-' +
                String(now.getDate()).padStart(2, '0');
            nameInput.value = `New Project ${dateStr}`;
        }

        const formData = new FormData(document.getElementById('projectForm'));

        try {
            const response = await fetch('/projects', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                },
                body: formData
            });

            if (response.ok) {
                const data = await response.json();
                projectId = data.id;
                wasSilentlyCreated = true;
                window.unsavedChanges?.setDirty(true);

                // Update form action and other links
                const form = document.getElementById('projectForm');
                if (form) {
                    form.action = `/projects/${projectId}`;
                }

                // Update back button link
                const backBtn = document.querySelector('[data-unsaved-confirm]');
                if (backBtn && backBtn.tagName.toLowerCase() === 'a') {
                    backBtn.href = '/projects';
                }

                // Update browser URL without reloading
                window.history.replaceState({}, '', `/projects/${projectId}/edit`);
                return true;
            } else {
                window.showToast?.('Failed to initialize project', 'error');
                return false;
            }
        } catch (error) {
            console.error('Project initialization failed', error);
            window.showToast?.('Network error', 'error');
            return false;
        }
    }

    const uploadInstructionsText = 'Drag and drop images here or click to upload';
    const stepUploadDisabledMessage = 'Save the project before adding images to this step';
    const uploadErrorMessage = 'Image upload failed. Please try again';
    const uploadNetworkErrorMessage = 'Network error while uploading image';
    const unsupportedFileMessage = 'Only image files are supported';
    const deleteErrorMessage = 'Failed to delete the image. Please try again';
    const deleteNetworkErrorMessage = 'Network error while deleting image';
    const uploadingMessage = 'Uploading…';

    const parseErrorMessage =
        typeof extractErrorMessage === 'function'
            ? extractErrorMessage
            : async (_response, fallback) => fallback;

    function bindPendingStepDropzone(dropzone, input, instructions) {
        if (!dropzone || dropzone.dataset.pendingBound === 'true') {
            return;
        }

        dropzone.dataset.pendingBound = 'true';
        dropzone.classList.add('opacity-60', 'cursor-not-allowed');
        dropzone.classList.remove('pointer-events-none');
        dropzone.setAttribute('aria-disabled', 'true');

        if (instructions) {
            instructions.textContent =
                instructions.dataset.disabledText || stepUploadDisabledMessage;
        }

        const showDisabledToast = () => {
            window.showToast?.(stepUploadDisabledMessage, 'info');
        };

        dropzone.addEventListener('click', (event) => {
            event.preventDefault();
            showDisabledToast();
        });

        ['dragover', 'drop'].forEach((eventName) => {
            dropzone.addEventListener(eventName, (event) => {
                event.preventDefault();
                event.stopPropagation();
                if (eventName === 'dragover') {
                    dropzone.classList.add('border-blue-500');
                } else {
                    dropzone.classList.remove('border-blue-500');
                    showDisabledToast();
                }
            });
        });

        dropzone.addEventListener('dragleave', (event) => {
            event.preventDefault();
            event.stopPropagation();
            dropzone.classList.remove('border-blue-500');
        });

        const label = dropzone.querySelector('label');
        if (label) {
            ['click', 'dragover', 'drop', 'dragleave'].forEach((eventName) => {
                label.addEventListener(eventName, (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    if (eventName === 'dragover') {
                        dropzone.classList.add('border-blue-500');
                        return;
                    }

                    if (eventName === 'dragleave') {
                        dropzone.classList.remove('border-blue-500');
                        return;
                    }

                    if (eventName === 'drop') {
                        dropzone.classList.remove('border-blue-500');
                    }

                    showDisabledToast();
                });
            });
        }

        input.addEventListener('change', (event) => {
            event.preventDefault();
            input.value = '';
            showDisabledToast();
        });
    }



    async function uploadTitleImage(file) {
        if (!(await ensureProjectId())) return false;

        const formData = new FormData();
        formData.append('file', file);
        formData.append('alt_text', file.name);

        let response;
        try {
            response = await fetch(`/projects/${projectId}/images/title`, {
                method: 'POST',
                headers: {
                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                },
                body: formData,
            });
        } catch (error) {
            console.error('Title image upload failed', error);
            window.showToast?.(uploadNetworkErrorMessage, 'error');
            return false;
        }

        if (!response.ok) {
            const message = await parseErrorMessage(response, uploadErrorMessage);
            window.showToast?.(message, 'error');
            return false;
        }

        const data = await response.json();
        addTitleImageToGallery(data);
        window.unsavedChanges?.setDirty(true);
        return true;
    }

    function addTitleImageToGallery(imageData) {
        const container = document.getElementById('titleImagesContainer');
        if (!container) return;

        container.insertAdjacentHTML('beforeend', createImagePreviewHTML(imageData, {
            showPromote: true,
            isPrimary: false
        }));
        window.refreshPhotoSwipeGallery?.(container);
    }

    function initAttachmentUploader() {
        const attachmentInput = document.getElementById('attachmentInput');
        const attachmentDropZone = document.getElementById('attachmentDropZone');
        if (attachmentInput && attachmentDropZone) {
            window.setupImageUploadWidget(attachmentInput, attachmentDropZone, uploadAttachment);
        }
    }

    async function uploadAttachment(file) {
        if (!(await ensureProjectId())) return false;

        const formData = new FormData();
        formData.append('file', file);

        try {
            window.showToast?.('Uploading attachment...', 'info');
            const response = await fetch(`/projects/${projectId}/attachments`, {
                method: 'POST',
                headers: {
                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                },
                body: formData
            });

            if (response.ok) {
                const data = await response.json();
                addAttachmentToUI(data);
                window.showToast?.('Attachment uploaded!', 'success');
                window.unsavedChanges?.setDirty(true);
                return true;
            } else {
                window.showToast?.('Upload failed', 'error');
                return false;
            }
        } catch (error) {
            console.error('Upload failed', error);
            window.showToast?.('Upload failed', 'error');
            return false;
        }
    }

    function addAttachmentToUI(data) {
        const container = document.getElementById('attachmentsContainer');
        if (!container) return;

        const escapeHtml = (value) => {
            const map = {
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#039;',
            };
            return String(value ?? '').replace(/[&<>"']/g, (m) => map[m]);
        };

        const sizeMb = (data.size_bytes / 1024 / 1024).toFixed(2);
        let icon = 'mdi-file-outline';
        if (data.content_type === 'application/pdf') icon = 'mdi-file-pdf-outline';
        else if (data.content_type.startsWith('image/')) icon = 'mdi-file-image-outline';

        const originalName = escapeHtml(data.original_filename);
        const url = escapeHtml(data.url);
        const thumbUrl = data.thumbnail_url ? escapeHtml(data.thumbnail_url) : '';
        const kind = data.content_type === 'application/pdf' ? 'pdf'
            : data.content_type.startsWith('image/') ? 'image'
                : 'other';
        const token = data.token ? escapeHtml(data.token) : '';
        const openIndex = kind === 'image'
            ? container.querySelectorAll('a[data-pswp-width]').length
            : null;

        const thumbOrIcon = thumbUrl ? `
                            <img src="${thumbUrl}" alt="" class="w-full h-full object-cover" loading="lazy">
            ` : `
                            <span class="mdi ${icon} text-2xl"></span>
            `;

        const pswpWidth = typeof data.width === 'number' ? data.width : 1200;
        const pswpHeight = typeof data.height === 'number' ? data.height : 1200;
        const html = `
                <div class="flex items-center justify-between p-3 rounded-xl border border-base-200 bg-base-200/30 group cursor-pointer"
                    role="button"
                    tabindex="0"
                    data-action="open-attachment"
                    data-attachment-kind="${kind}"
                    data-attachment-url="${url}"
                    data-attachment-name="${originalName}"
                    ${data.id ? `data-attachment-id="${data.id}"` : `data-pending-token="${data.token}"`}
                    ${kind === 'image' ? `data-pswp-open-index="${openIndex}"` : ''}>
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
                        title="Download">
                        <span class="mdi mdi-download text-xl"></span>
                    </a>
                    <button type="button" data-call="deleteAttachment"
                        data-call-args='[${data.id ? data.id : `"${token}"`}]'
                        class="btn btn-ghost btn-sm btn-circle text-error"
                        title="Delete">
                        <span class="mdi mdi-delete-outline text-xl"></span>
                    </button>
                </div>
                    ${kind === 'image' ? `
                        <a href="${url}" class="hidden" aria-hidden="true" tabindex="-1"
                            data-pswp-width="${pswpWidth}"
                            data-pswp-height="${pswpHeight}"
                            data-pswp-caption="${originalName}"></a>
                    ` : ''}
                </div>
            `;
        container.insertAdjacentHTML('beforeend', html);
        if (kind === 'image') {
            window.refreshPhotoSwipeGallery?.(container);
        }
    }

    async function deleteAttachment(idOrToken) {
        let contentHtml = '';

        if (typeof idOrToken === 'number' || (typeof idOrToken === 'string' && idOrToken.length !== 32)) {
            const id = idOrToken;
            const attachmentEl = document.querySelector(`[data-attachment-id="${id}"]`);
            const filename = attachmentEl?.dataset.attachmentName || 'Attachment';
            const kind = attachmentEl?.dataset.attachmentKind || 'other';
            const sizeBytes = parseInt(attachmentEl?.dataset.attachmentSize || '0');
            const thumbnailImg = attachmentEl?.querySelector('img');
            const thumbnailUrl = thumbnailImg?.src || '';

            const typeLabels = {
                'pdf': 'PDF',
                'image': 'Image',
                'other': 'File'
            };
            const typeLabel = typeLabels[kind] || typeLabels.other;

            const sizeText = sizeBytes > 0
                ? (sizeBytes / 1024 / 1024).toFixed(2) + ' MB'
                : '';

            contentHtml = '<div class="flex items-start gap-4 py-4">';

            contentHtml += '<div class="shrink-0 w-24 h-24 rounded-lg bg-base-200 flex items-center justify-center overflow-hidden">';
            if (thumbnailUrl) {
                contentHtml += `<img src="${thumbnailUrl}" alt="" class="w-full h-full object-cover">`;
            } else {
                let iconClass = 'mdi-file-outline';
                if (kind === 'pdf') {
                    iconClass = 'mdi-file-pdf-outline';
                } else if (kind === 'image') {
                    iconClass = 'mdi-file-image-outline';
                }
                contentHtml += `<span class="mdi ${iconClass} text-4xl text-primary/60"></span>`;
            }
            contentHtml += '</div>';

            contentHtml += '<div class="flex-1 min-w-0">';
            contentHtml += `<p class="font-medium text-base-content truncate" title="${filename}">${filename}</p>`;
            contentHtml += `<p class="text-sm text-base-content/60 mt-1">${typeLabel}</p>`;
            if (sizeText) {
                contentHtml += `<p class="text-sm text-base-content/60">${sizeText}</p>`;
            }
            contentHtml += '</div>';

            contentHtml += '</div>';
        }

        window.confirmAction(
            'Delete Attachment',
            'Are you sure you want to delete this attachment?',
            async () => {
                if (typeof idOrToken === 'string' && idOrToken.length === 32) {
                    const token = idOrToken;
                    const el = document.querySelector(`[data-pending-token="${token}"]`);
                    el?.remove();

                    const input = document.getElementById('import_attachment_tokens');
                    if (input && input.value) {
                        try {
                            const tokens = JSON.parse(input.value);
                            const updated = tokens.filter(t => t !== token);
                            input.value = JSON.stringify(updated);
                        } catch (e) { console.error(e); }
                    }
                    window.unsavedChanges?.setDirty(true);
                    return;
                }

                const id = idOrToken;
                try {
                    const response = await fetch(`/projects/${projectId}/attachments/${id}`, {
                        method: 'DELETE',
                        headers: {
                            'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                        }
                    });

                    if (response.ok) {
                        document.querySelector(`[data-attachment-id="${id}"]`)?.remove();
                        window.showToast?.('Attachment deleted', 'success');
                        window.unsavedChanges?.setDirty(true);
                    } else {
                        window.showToast?.('Delete failed', 'error');
                    }
                } catch (error) {
                    console.error('Delete failed', error);
                    window.showToast?.('Delete failed', 'error');
                }
            },
            null,
            { ...(contentHtml ? { content: contentHtml } : {}), variant: 'error' }
        );
    }

    function addPendingTitleImageToGallery(url, options = {}) {
        const container = document.getElementById('titleImagesContainer');
        if (!container) {
            return;
        }

        const skipAutoPromote = options.skipAutoPromote === true;

        // Defensive: avoid showing duplicates even if the import runs multiple times
        // or returns slightly different variants of the same URL.
        const key = canonicalizeImportImageUrl(url);
        if (key) {
            const alreadyInDom = Array.from(container.querySelectorAll('a[href]')).some(anchor => {
                return canonicalizeImportImageUrl(anchor.getAttribute('href')) === key;
            });
            if (alreadyInDom) {
                return;
            }
        }
        const div = document.createElement('div');
        div.className = 'relative group';
        div.setAttribute('data-pending-url', url);
        div.innerHTML = `
        <a href="${url}" data-pswp-width="1200" data-pswp-height="1200" data-pswp-caption=""
            data-pswp-promote="true" data-pswp-delete="true" data-pswp-is-primary="false"
            draggable="true" ondragstart="handleImageDragStart(event)"
            class="block">
            <img src="${url}" class="h-32 w-full cursor-zoom-in rounded object-cover shadow dark:shadow-slate-900/30">
        </a>
	        <button type="button" data-call="promotePendingImage" data-call-args='["$this","$dataset:url"]' data-url="${url}"
	            class="promote-pending-btn absolute top-1 left-1 z-10 rounded-full p-1 shadow-sm transition-all bg-white/90 text-slate-400 opacity-0 group-hover:opacity-100 dark:bg-slate-900/90 dark:text-slate-500 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/50 dark:hover:text-amber-400"
	            title="Make title image">
	            <span class="mdi mdi-star-outline"></span>
	        </button>
	        <button type="button" data-call="deletePendingImage" data-call-args='["$this","$dataset:url"]' data-url="${url}" class="absolute top-1 right-1 bg-red-600 text-white rounded-full p-1 hover:bg-red-700 dark:hover:bg-red-500 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity">
	            <span class="mdi mdi-delete"></span>
	        </button>
    `;
        container.appendChild(div);

        // If this is the first image, automatically promote it
        if (!skipAutoPromote) {
            const currentTitleUrl = document.getElementById('import_title_image_url').value;
            if (!currentTitleUrl) {
                promotePendingImage(div.querySelector('.promote-pending-btn'), url);
            }
        }

        window.refreshPhotoSwipeGallery?.(container);
    }

    function promotePendingImage(btn, url) {
        // Update hidden field
        const input = document.getElementById('import_title_image_url');
        if (input) input.value = url;

        // Visual update
        document.querySelectorAll('.promote-pending-btn').forEach(b => {
            b.classList.remove('bg-amber-400', 'text-white', 'opacity-100');
            b.classList.add('bg-white/90', 'text-slate-400', 'opacity-0');
            const star = b.querySelector('span');
            if (star) {
                star.classList.remove('mdi-star');
                star.classList.add('mdi-star-outline');
            }
            const anchor = b.closest('[data-pending-url]')?.querySelector('a[data-pswp-promote]');
            if (anchor) {
                anchor.setAttribute('data-pswp-is-primary', 'false');
            }
        });

        if (btn) {
            btn.classList.add('bg-amber-400', 'text-white', 'opacity-100');
            btn.classList.remove('bg-white/90', 'text-slate-400', 'opacity-0');
            const star = btn.querySelector('span');
            if (star) {
                star.classList.remove('mdi-star-outline');
                star.classList.add('mdi-star');
            }
            const anchor = btn.closest('[data-pending-url]')?.querySelector('a[data-pswp-promote]');
            if (anchor) {
                anchor.setAttribute('data-pswp-is-primary', 'true');
            }
        }
        window.unsavedChanges?.setDirty(true);
    }

    function deletePendingImage(button, url) {
        window.confirmAction(
            'Remove Image',
            'Remove this image from the import list?',
            () => {
                // Remove visual element
                const container = button.closest('.relative');
                const wasPrimary = document.getElementById('import_title_image_url')?.value === url;
                container?.remove();

                // Update hidden input
                const importInput = document.getElementById('import_image_urls');
                if (importInput && importInput.value) {
                    try {
                        let urls = JSON.parse(importInput.value);
                        urls = urls.filter(u => u !== url);
                        importInput.value = JSON.stringify(urls);
                        if (wasPrimary) {
                            if (urls.length > 0) {
                                const nextUrl = urls[0];
                                const nextBtn = document.querySelector(`[data-pending-url="${CSS.escape(nextUrl)}"] .promote-pending-btn`);
                                promotePendingImage(nextBtn, nextUrl);
                            } else {
                                const titleInput = document.getElementById('import_title_image_url');
                                if (titleInput) {
                                    titleInput.value = '';
                                }
                            }
                        }
                    } catch (e) {
                        console.error('Error updating import_image_urls:', e);
                    }
                } else if (wasPrimary) {
                    const titleInput = document.getElementById('import_title_image_url');
                    if (titleInput) {
                        titleInput.value = '';
                    }
                }
                window.unsavedChanges?.setDirty(true);
            }
        );
    }

    async function deleteImage(imageId) {
        const imageEl = document.querySelector(`[data-image-id="${imageId}"]`);
        const imgTag = imageEl?.querySelector('img');
        const linkTag = imageEl?.querySelector('a[data-pswp-width]');

        const thumbnailUrl = imgTag?.src || '';
        const filename = imgTag?.alt || linkTag?.dataset.pswpCaption || 'Image';
        const width = linkTag?.dataset.pswpWidth || '';
        const height = linkTag?.dataset.pswpHeight || '';

        let contentHtml = '<div class="flex flex-col items-center gap-4 py-4">';
        if (thumbnailUrl) {
            contentHtml += `<img src="${thumbnailUrl}" alt="" class="max-h-48 rounded-lg shadow-md object-contain">`;
        }
        contentHtml += '<div class="text-center">';
        contentHtml += `<p class="font-medium text-base-content">${filename}</p>`;
        if (width && height) {
            contentHtml += `<p class="text-sm text-base-content/60 mt-1">${width} × ${height} px</p>`;
        }
        contentHtml += '</div></div>';

        window.confirmAction(
            'Delete Image',
            'Are you sure you want to delete this image? This action cannot be undone.',
            async () => {
                let response;
	                try {
	                    response = await fetch(`/projects/${projectId}/images/${imageId}`, {
	                        method: 'DELETE',
	                        headers: {
	                            'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
	                        }
	                    });
	                } catch (error) {
                    console.error('Image deletion failed', error);
                    window.showToast?.(deleteNetworkErrorMessage, 'error');
                    return;
                }

                if (!response.ok) {
                    const message = await parseErrorMessage(response, deleteErrorMessage);
                    window.showToast?.(message, 'error');
                    return;
                }

                const element = document.querySelector(`[data-image-id="${imageId}"]`);
                if (element) {
                    element.remove();
                }
                window.unsavedChanges?.setDirty(true);
            },
            null,
            { content: contentHtml }
        );
    }

    async function promoteImage(pId, imageId) {
	        try {
	            const response = await fetch(`/projects/${pId}/images/${imageId}/promote`, {
	                method: 'POST',
	                headers: {
	                    'X-Requested-With': 'XMLHttpRequest',
	                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
	                }
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
                console.error('Failed to promote image');
            }
        } catch (error) {
            console.error('Error promoting image:', error);
        }
    }

    function setPrimaryTitleImage(imageId) {
        const container = document.getElementById('titleImagesContainer');
        if (!container) {
            return;
        }
        container.querySelectorAll('[data-image-id]').forEach((item) => {
            const anchor = item.querySelector('a[data-pswp-promote]');
            if (anchor) {
                anchor.setAttribute('data-pswp-is-primary', 'false');
            }
            const button = item.querySelector('.title-promote-btn');
            if (button) {
                button.classList.remove('bg-amber-400', 'text-white', 'opacity-100');
                button.classList.add('bg-white/90', 'text-slate-400', 'opacity-0');
                const icon = button.querySelector('span');
                if (icon) {
                    icon.classList.remove('mdi-star');
                    icon.classList.add('mdi-star-outline');
                }
            }
        });

        const target = container.querySelector(`[data-image-id="${imageId}"]`);
        if (!target) {
            return;
        }
        const targetAnchor = target.querySelector('a[data-pswp-promote]');
        if (targetAnchor) {
            targetAnchor.setAttribute('data-pswp-is-primary', 'true');
        }
        const targetButton = target.querySelector('.title-promote-btn');
        if (targetButton) {
            targetButton.classList.add('bg-amber-400', 'text-white', 'opacity-100');
            targetButton.classList.remove('bg-white/90', 'text-slate-400', 'opacity-0');
            const icon = targetButton.querySelector('span');
            if (icon) {
                icon.classList.add('mdi-star');
                icon.classList.remove('mdi-star-outline');
            }
        }
        window.unsavedChanges?.setDirty(true);
    }

    // Add event listeners for PhotoSwipe managed actions
    document.addEventListener('pswp:promote', (e) => {
        const anchor = e.detail.element;
        const container = anchor.closest('[data-image-id]');
        if (container) {
            const imageId = container.dataset.imageId;
            if (imageId && projectId) {
                promoteImage(projectId, imageId);
            }
        } else {
            // Handle pending images
            const pendingContainer = anchor.closest('[data-pending-url]');
            if (pendingContainer) {
                const btn = pendingContainer.querySelector('.promote-pending-btn');
                const url = pendingContainer.dataset.pendingUrl;
                if (btn && url) promotePendingImage(btn, url);
            }
        }
    });

    document.addEventListener('pswp:delete', (e) => {
        const anchor = e.detail.element;
        const container = anchor.closest('[data-image-id]');
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
            const pendingContainer = anchor.closest('[data-pending-url]');
            if (pendingContainer) {
                const url = pendingContainer.dataset.pendingUrl;
                const deleteBtn = pendingContainer.querySelector('button[onclick*="deletePendingImage"]');
                const removeBtn = pendingContainer.querySelector('button[onclick*="removePendingStepImage"]');
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
        document.querySelectorAll('.step-item').forEach((stepItem) => {
            const dropzone = stepItem.querySelector('.step-image-dropzone');
            const input = stepItem.querySelector('.step-image-input');
            const instructions = dropzone?.querySelector('.upload-instructions');
            const stepId = stepItem.getAttribute('data-step-id');

            if (!dropzone || !input || dropzone.dataset.initialized === 'true') {
                return;
            }

            dropzone.classList.remove(
                'opacity-60',
                'opacity-50',
                'cursor-not-allowed',
                'pointer-events-none',
            );
            dropzone.removeAttribute('aria-disabled');
            if (instructions) {
                instructions.textContent = instructions.dataset.enabledText || uploadInstructionsText;
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
        const stepId = stepItem.getAttribute('data-step-id');
        if (stepId) return stepId;

        return await saveStepInternal(stepItem);
    }

    async function uploadStepImage(stepId, file) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('alt_text', file.name);

        let response;
        try {
            response = await fetch(`/projects/${projectId}/steps/${stepId}/images`, {
                method: 'POST',
                headers: {
                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                },
                body: formData,
            });
        } catch (error) {
            console.error('Step image upload failed', error);
            window.showToast?.(uploadNetworkErrorMessage, 'error');
            return null;
        }

        if (!response.ok) {
            const message = await parseErrorMessage(response, uploadErrorMessage);
            window.showToast?.(message, 'error');
            return null;
        }

        const data = await response.json();
        window.unsavedChanges?.setDirty(true);
        return data;
    }

    function addStepImagePreview(stepItem, imageData) {
        const imagesContainer = stepItem.querySelector('.step-images');
        if (!imagesContainer) return;

        imagesContainer.insertAdjacentHTML('beforeend', createImagePreviewHTML(imageData, {
            imageClass: 'h-20'
        }));
        window.refreshPhotoSwipeGallery?.(imagesContainer);
        const textarea = stepItem.querySelector('.step-description');
        if (textarea) updateImageVisibility(textarea);
    }

    function initStitchSampleImageUploader() {
        const dropzone = document.getElementById('stitchSampleDropzone');
        const input = document.getElementById('stitchSampleImageInput');

        if (!dropzone || !input || dropzone.dataset.initialized === 'true') {
            return;
        }

        window.setupImageUploadWidget(input, dropzone, async (file) => {
            if (!(await ensureProjectId())) return;

            const formData = new FormData();
            formData.append('file', file);
            formData.append('alt_text', file.name);

            try {
                const response = await fetch(`/projects/${projectId}/images/stitch-sample`, {
                    method: 'POST',
                    headers: {
                        'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                    },
                    body: formData,
                });

                if (!response.ok) {
                    const message = await parseErrorMessage(response, uploadErrorMessage);
                    window.showToast?.(message, 'error');
                    return;
                }

                const data = await response.json();
                addStitchSampleImagePreview(data);
                window.unsavedChanges?.setDirty(true);
            } catch (error) {
                console.error('Stitch sample image upload failed', error);
                window.showToast?.(uploadNetworkErrorMessage, 'error');
            }
        });
    }

    function addStitchSampleImagePreview(imageData) {
        const container = document.getElementById('stitchSampleImagesContainer');
        if (!container) return;

        container.insertAdjacentHTML('beforeend', createImagePreviewHTML(imageData));
        if (window.refreshPhotoSwipeGallery) {
            window.refreshPhotoSwipeGallery(container);
        }
        const textarea = document.getElementById('stitch_sample');
        if (textarea) updateImageVisibility(textarea);
    }

	    function addStep(elOrTitle = '', description = '', stepImages = []) {
	        // `data-call="addStep"` invokes this with the clicked element as the first arg.
	        // Programmatic calls pass (title, description, stepImages).
	        let title = '';
	        if (elOrTitle && (elOrTitle instanceof Element || elOrTitle?.nodeType === 1)) {
	            title = '';
	            description = '';
	            stepImages = [];
	        } else {
	            title = elOrTitle;
	        }

	        title = title == null ? '' : (typeof title === 'string' ? title : String(title));
	        description = description == null ? '' : (typeof description === 'string' ? description : String(description));
	        if (!Array.isArray(stepImages)) stepImages = [];

	        const escapeAttr = (s) =>
	            String(s)
	                .replace(/&/g, '&amp;')
	                .replace(/"/g, '&quot;')
	                .replace(/</g, '&lt;')
	                .replace(/>/g, '&gt;');
	        const escapeHtml = (s) =>
	            String(s)
	                .replace(/&/g, '&amp;')
	                .replace(/</g, '&lt;')
	                .replace(/>/g, '&gt;');

	        const container = document.getElementById('stepsContainer');
	        const stepNumber = container.children.length + 1;
	        const div = document.createElement('div');
	        div.className = 'step-item border rounded-lg p-2 md:p-4 bg-base-200 border-base-300 dark:bg-base-300/50 dark:border-base-700';
	        div.setAttribute('data-step-number', stepNumber);
        const inputId = `newStepImageInput${Date.now()}`;
        const textareaId = `step-description-new-${Date.now()}`;
        div.innerHTML = `
        <div class="mb-3 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
            <h4 class="text-lg font-medium text-base-content">Step <span class="step-number">${stepNumber}</span></h4>
            <div class="flex flex-wrap gap-2">
	                <button type="button" data-call="moveStepUp" class="btn btn-xs btn-ghost gap-1" title="Move Up">
	                    <span class="mdi mdi-arrow-up"></span><span class="hidden sm:inline">Move Up</span>
	                </button>
	                <button type="button" data-call="moveStepDown" class="btn btn-xs btn-ghost gap-1" title="Move Down">
	                    <span class="mdi mdi-arrow-down"></span><span class="hidden sm:inline">Move Down</span>
	                </button>
	                <button type="button" data-call="saveStep" class="btn btn-xs btn-primary gap-1" title="Save">
	                    <span class="mdi mdi-content-save"></span><span class="hidden sm:inline">Save</span>
	                </button>
	                <button type="button" data-call="removeStep" class="btn btn-xs btn-error text-white gap-1" title="Remove Step">
	                    <span class="mdi mdi-delete"></span><span class="hidden sm:inline">Remove</span>
	                </button>
            </div>
	        </div>
	        <div class="mb-2">
	            <input type="text" class="step-title input  w-full" placeholder="Step Title" value="${escapeAttr(title)}">
	        </div>
            <div data-wysiwyg data-wysiwyg-input="${textareaId}" data-wysiwyg-step="true" class="wysiwyg-container mb-2">
                <div class="wysiwyg-toolbar">
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="bold" title="Bold"><span class="mdi mdi-format-bold"></span></button>
                        <button type="button" data-action="italic" title="Italic"><span class="mdi mdi-format-italic"></span></button>
                        <button type="button" data-action="underline" title="Underline"><span class="mdi mdi-format-underline"></span></button>
                    </div>
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="heading" data-value="2" title="Heading 2"><span class="mdi mdi-format-header-2"></span></button>
                        <button type="button" data-action="heading" data-value="3" title="Heading 3"><span class="mdi mdi-format-header-3"></span></button>
                        <button type="button" data-action="paragraph" title="Paragraph"><span class="mdi mdi-format-paragraph"></span></button>
                    </div>
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="bulletList" title="Bullet list"><span class="mdi mdi-format-list-bulleted"></span></button>
                        <button type="button" data-action="orderedList" title="Numbered list"><span class="mdi mdi-format-list-numbered"></span></button>
                    </div>
                    <div class="wysiwyg-toolbar-group">
                        <button type="button" data-action="link" title="Add link"><span class="mdi mdi-link"></span></button>
                        <button type="button" data-action="image" title="Insert image"><span class="mdi mdi-image"></span></button>
                    </div>
                </div>
                <div class="wysiwyg-content"></div>
            </div>
            <textarea id="${textareaId}" class="step-description hidden" data-markdown-images="true">${escapeHtml(description)}</textarea>
	        <h4 class="step-photos-label text-xs font-bold text-base-content/50 uppercase tracking-wider mb-2 flex items-center gap-1 mt-4 pt-4 border-t border-base-100 ${stepImages.length > 0 ? '' : 'hidden'}">
	            <span class="mdi mdi-image-outline"></span> Step Photos
	        </h4>
        <div class="step-images mb-2 grid grid-cols-3 gap-2 pswp-gallery ${stepImages.length > 0 ? '' : 'hidden'}" data-pswp-gallery>
            ${stepImages.map(img => {
            const url = typeof img === 'string' ? img : img.url;
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
        }).join('')}
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
        const newStepGallery = div.querySelector('.step-images');
        if (newStepGallery) {
            window.refreshPhotoSwipeGallery?.(newStepGallery);
        }

        if (window.STRICKNANI?.wysiwyg?.init) {
            window.STRICKNANI.wysiwyg.init({ i18n: window.STRICKNANI.i18n || {} });
        }
        window.unsavedChanges?.setDirty(true);
    }

        const newTextarea = div.querySelector('textarea');
        if (newTextarea) {
            newTextarea.style.overflowY = 'hidden';
            autoResize(newTextarea);
            updateImageVisibility(newTextarea);
            newTextarea.addEventListener('input', () => {
                autoResize(newTextarea);
                updateImageVisibility(newTextarea);
            });
            setupTextareaDrop(newTextarea);
        }
        window.unsavedChanges?.setDirty(true);
    }

    function removeStep(button) {
        button.closest('.step-item').remove();
        updateStepNumbers();
        window.unsavedChanges?.setDirty(true);
    }

    function removePendingStepImage(button) {
        const container = button?.closest('[data-pending-url]');
        if (!container) {
            return;
        }
        container.remove();
        window.unsavedChanges?.setDirty(true);
    }

    function moveStepUp(button) {
        const item = button.closest('.step-item');
        const prev = item.previousElementSibling;
        if (prev) {
            item.parentNode.insertBefore(item, prev);
            updateStepNumbers();
            window.unsavedChanges?.setDirty(true);
        }
    }

    function moveStepDown(button) {
        const item = button.closest('.step-item');
        const next = item.nextElementSibling;
        if (next) {
            item.parentNode.insertBefore(next, item);
            updateStepNumbers();
            window.unsavedChanges?.setDirty(true);
        }
    }

    function updateStepNumbers() {
        document.querySelectorAll('.step-item').forEach((item, index) => {
            const number = index + 1;
            item.setAttribute('data-step-number', number);
            item.querySelector('.step-number').textContent = number;
        });
    }

    async function saveStep(button) {
        const stepItem = button.closest('.step-item');
        button.disabled = true;
        const originalText = button.innerHTML;
        button.innerHTML = '<span class="mdi mdi-loading mdi-spin"></span> Saving...';

        try {
            await saveStepInternal(stepItem);
        } finally {
            button.disabled = false;
            button.innerHTML = originalText;
        }
    }

    async function saveStepInternal(stepItem) {
        const stepId = stepItem.getAttribute('data-step-id');
        const title = stepItem.querySelector('.step-title').value;
        const description = stepItem.querySelector('.step-description').value;
        const stepNumber = parseInt(stepItem.getAttribute('data-step-number')) || 1;

        if (!(await ensureProjectId())) return null;

        try {
            const url = stepId
                ? `/projects/${projectId}/steps/${stepId}`
                : `/projects/${projectId}/steps`;
            const method = stepId ? 'PUT' : 'POST';

            const response = await fetch(url, {
                method: method,
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
                },
                body: JSON.stringify({
                    title: title,
                    description: description,
                    step_number: stepNumber
                })
            });

            if (!response.ok) {
                throw new Error('Failed to save step');
            }

            const data = await response.json();

            if (!stepId && data.id) {
                stepItem.setAttribute('data-step-id', data.id);
                const dropzone = stepItem.querySelector('.step-image-dropzone');
                const fileInput = stepItem.querySelector('.step-image-input');
                if (dropzone) dropzone.setAttribute('data-step-id', data.id);
                if (fileInput) fileInput.setAttribute('data-step-id', data.id);

                // Also add visual confirmation of "Save" button if it exists
                const saveBtn = stepItem.querySelector('button[data-call="saveStep"]');
                if (!saveBtn && !projectId) {
                    // Logic to add save button if it was hidden?
                }
            }

            window.showToast?.('Step saved successfully', 'success');
            window.unsavedChanges?.setDirty(true);
            return data.id;
        } catch (error) {
            console.error('Save step failed', error);
            window.showToast?.('Failed to save step', 'error');
            return null;
        }
    }

    function syncLinkInputs(el) {
        const value = el.value.trim();
        document.querySelectorAll('input[name="link"]').forEach(input => {
            if (input !== el) input.value = value;
        });
        document.getElementById('reimport-btn')?.classList.toggle('hidden', !value);
        document.getElementById('reimport-btn-mobile')?.classList.toggle('hidden', !value);

        const visitBtnMobile = document.getElementById('visit-link-btn-mobile');
        if (visitBtnMobile) {
            visitBtnMobile.classList.toggle('hidden', !value);
            visitBtnMobile.href = value;
        }

        const visitBtn = document.getElementById('visit-link-btn');
        if (visitBtn) {
            visitBtn.classList.toggle('hidden', !value);
            visitBtn.href = value;
        }

        // Enable/disable AI and Wayback checkboxes
        const waybackCheckboxes = [
            document.getElementById('archive_on_save_mobile'),
            document.getElementById('archive_on_save_sidebar')
        ];
        const aiCheckboxes = [
            document.getElementById('is_ai_enhanced_checkbox'),
            document.getElementById('is_ai_enhanced_mobile_checkbox')
        ];
        const containers = [
            document.getElementById('wayback_mobile_container'),
            document.getElementById('wayback_sidebar_container'),
            document.getElementById('ai_enhanced_mobile_container'),
            document.getElementById('ai_enhanced_sidebar_container')
        ];

        const isEnabled = value.length > 0;

        [...waybackCheckboxes, ...aiCheckboxes].forEach(cb => {
            if (cb) {
                const wasDisabled = cb.disabled;
                cb.disabled = !isEnabled;
                if (!isEnabled) {
                    cb.checked = false;
                    // Trigger sync for AI hidden field if it's an AI checkbox
                    if (aiCheckboxes.includes(cb)) {
                        const hiddenInput = document.getElementById('is_ai_enhanced');
                        if (hiddenInput) hiddenInput.value = '';
                    }
                } else if (wasDisabled && waybackCheckboxes.includes(cb)) {
                    // Auto-set wayback to true when it becomes enabled
                    cb.checked = true;
                }
            }
        });

        containers.forEach(container => {
            if (container) {
                if (isEnabled) {
                    container.classList.remove('opacity-50', 'grayscale', 'pointer-events-none');
                } else {
                    container.classList.add('opacity-50', 'grayscale', 'pointer-events-none');
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

        const existingProjectId = 2;
    const importDialog = document.getElementById('importDialog');
    const importForm = document.getElementById('importForm');
    const importLoading = document.getElementById('importLoading');

    if (!importDialog || !importForm || !importLoading) return;

    // Reset and show loading state
    importDialog.showModal();
    importForm.classList.add('hidden');
    importLoading.classList.remove('hidden');

    const formData = new FormData();
    formData.append('url', url);
    formData.append('type', 'url');
    if (existingProjectId) {
        formData.append('project_id', String(existingProjectId));
    }

    try {
        const response = await fetch('/projects/import', {
            method: 'POST',
            headers: {
                'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
            },
            body: formData
        });

        if (!response.ok) {
            throw new Error('Import failed');
        }

        const data = await response.json();
        populateProjectForm(data);

        importDialog.close();
    } catch (error) {
        console.error('Import failed:', error);
        window.showToast?.('Failed to import pattern', 'error');
        importDialog.close();
    } finally {
        importForm.classList.remove('hidden');
        importLoading.classList.add('hidden');
    }
    }

    function populateProjectForm(data) {
        // Prevent re-prompting on save since we just imported
        isImportPrompted = true;

        // Map fields helper
        const setFieldValue = (id, value) => {
            const el = document.getElementById(id);
            if (el && value) {
                if (id === 'notes' && el.value.trim().length > 0) return false;
                el.value = value;
                if (typeof autoResize === 'function') autoResize(el);
                if (typeof updateImageVisibility === 'function') updateImageVisibility(el);
                return true;
            }
            return false;
        };

        // Metadata
        setFieldValue('name', data.title || data.name);
        setFieldValue('needles', data.needles);
        setFieldValue('recommended_needles', data.recommended_needles);
        setFieldValue('yarn_brand', data.brand);

        const yarnDetailsField = document.getElementById('yarn_details');
        if (yarnDetailsField && data.yarn_details) {
            yarnDetailsField.value = JSON.stringify(data.yarn_details);
        }

        if (data.yarn || data.yarn_details) {
            window.yarnSelector?.selectByName(data.yarn, data.yarn_details || []);
        }
        setFieldValue('gauge_stitches', data.gauge_stitches);
        setFieldValue('gauge_rows', data.gauge_rows);
        setFieldValue('stitch_sample', data.stitch_sample);
        setFieldValue('other_materials', data.other_materials);
        setFieldValue('description', data.description);
        setFieldValue('notes', data.notes);
        setFieldValue('link', data.link);
        if (data.link) initialLink = data.link;

        // AI Enhanced flag
        const isAiEnhanced = data.is_ai_enhanced === true;
        const hiddenAiInput = document.getElementById('is_ai_enhanced');
        if (hiddenAiInput) hiddenAiInput.value = isAiEnhanced ? '1' : '';
        const aiCheckbox = document.getElementById('is_ai_enhanced_checkbox');
        const aiCheckboxMobile = document.getElementById('is_ai_enhanced_mobile_checkbox');
        if (aiCheckbox) aiCheckbox.checked = isAiEnhanced;
        if (aiCheckboxMobile) aiCheckboxMobile.checked = isAiEnhanced;

        // Project Image Previews
        const importImagesField = document.getElementById('import_image_urls');
        const imageUrls = Array.isArray(data.image_urls) ? data.image_urls : [];
        const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
        if (importImagesField) {
            importImagesField.value = dedupedImageUrls.length
                ? JSON.stringify(dedupedImageUrls)
                : '';
        }
        const importAttachmentTokensField = document.getElementById('import_attachment_tokens');
        if (importAttachmentTokensField && Array.isArray(data.import_attachment_tokens)) {
            let existingTokens = [];
            try { existingTokens = JSON.parse(importAttachmentTokensField.value || '[]'); } catch (e) { existingTokens = []; }
            const merged = [...new Set([...(Array.isArray(existingTokens) ? existingTokens : []), ...data.import_attachment_tokens])];
            importAttachmentTokensField.value = JSON.stringify(merged);
        }

        if (dedupedImageUrls.length > 0) {
            // Find the best title image: prefer regular URLs over /media/imports/
            const bestTitleUrl = dedupedImageUrls.find(u => !u.includes('/media/imports/')) || dedupedImageUrls[0];

            dedupedImageUrls.forEach(url => {
                if (typeof addPendingTitleImageToGallery === 'function') {
                    addPendingTitleImageToGallery(url, {
                        skipAutoPromote: true
                    });
                }
            });

            // Explicitly promote the best one
            const bestImgBtn = document.querySelector(`.promote-pending-btn[data-url="${bestTitleUrl}"]`);
            if (bestImgBtn) {
                promotePendingImage(bestImgBtn, bestTitleUrl);
            }
        }

        // Steps
        if (data.steps && data.steps.length > 0) {
            const stepsContainer = document.getElementById('stepsContainer');
            if (stepsContainer) {
                stepsContainer.innerHTML = '';
                data.steps.forEach((step, index) => {
                    if (typeof addStep === 'function') {
                        const stepImages = step.images || step.image_urls || [];
                        addStep(step.title || `Step ${index + 1}`, step.description || '', stepImages);
                    }
                });
            }
        }

        // Source Attachments (PDFs, etc.)
        if (Array.isArray(data.source_attachments) && data.source_attachments.length > 0) {
            data.source_attachments.forEach(attachment => {
                if (typeof addAttachmentToUI === 'function') {
                    addAttachmentToUI(attachment);
                }
            });
        }

        window.showToast?.('Pattern imported successfully', 'success');
        window.unsavedChanges?.setDirty(true);
        document.getElementById('name').scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    document.getElementById('projectForm').addEventListener('submit', function (e) {
        const currentLink = document.getElementById('link')?.value.trim() || '';
        const needlesInput = document.getElementById('recommended_needles');
        if (needlesInput && typeof needlesInput.value === 'string') {
            needlesInput.value = needlesInput.value.trim();
        }

        // If a link was added to a project that didn't have one, prompt for import
        if (!initialLink && currentLink && !isImportPrompted) {
            e.preventDefault();

            window.confirmAction(
                'Import Data?',
                'You added a pattern source. Would you like to import details (notes, steps, etc.) from this URL before saving?',
                () => {
                    isImportPrompted = true;
                    importFromUrl(currentLink);
                },
                () => {
                    isImportPrompted = true;
                    // Gather steps data manually since we are bypassing the submit event
                    const steps = [];
                    const container = document.getElementById('stepsContainer');
                    const items = container ? container.querySelectorAll('.step-item') : [];

                    items.forEach((item, index) => {
                        const pendingImages = Array.from(item.querySelectorAll('[data-pending-url]')).map(img => img.dataset.pendingUrl);
                        steps.push({
                            id: item.getAttribute('data-step-id') || null,
                            title: item.querySelector('.step-title').value,
                            description: item.querySelector('.step-description').value,
                            step_number: index + 1,
                            image_urls: pendingImages
                        });
                    });
                    document.getElementById('stepsData').value = JSON.stringify(steps);
                    this.submit();
                },
                {
                    confirmText: 'Import',
                    cancelText: 'Just Save'
                }
            );
            return;
        }

        const steps = [];
        const container = document.getElementById('stepsContainer');
        const items = container ? container.querySelectorAll('.step-item') : [];

        if (items.length > 0) {
            items.forEach((item, index) => {
                const pendingImages = Array.from(item.querySelectorAll('[data-pending-url]')).map(img => img.dataset.pendingUrl);
                steps.push({
                    id: item.getAttribute('data-step-id') || null,
                    title: item.querySelector('.step-title').value,
                    description: item.querySelector('.step-description').value,
                    step_number: index + 1,
                    image_urls: pendingImages
                });
            });
        }
        document.getElementById('stepsData').value = JSON.stringify(steps);
    });

    document.addEventListener('DOMContentLoaded', () => {
        const importDialog = document.getElementById('importDialog');
        const importForm = document.getElementById('importForm');
        const importLoading = document.getElementById('importLoading');

        let currentTab = 'url';
        const tabs = document.querySelectorAll('.tabs .tab');
        const tabContents = document.querySelectorAll('.tab-content');

        tabs.forEach(tab => {
            tab.addEventListener('click', (e) => {
                e.preventDefault();
                const tabName = tab.dataset.tab;

                tabs.forEach(t => t.classList.remove('tab-active'));
                tab.classList.add('tab-active');

                tabContents.forEach(content => {
                    if (content.id === `tab-${tabName}`) {
                        content.classList.remove('hidden');
                    } else {
                        content.classList.add('hidden');
                    }
                });

                currentTab = tabName;
            });
        });

        const dropZone = document.getElementById('dropZone');
        const fileInput = document.getElementById('importFile');
        const dropZoneContent = document.getElementById('dropZoneContent');
        const filePreview = document.getElementById('filePreview');
        const fileName = document.getElementById('fileName');
        const removeFileBtn = document.getElementById('removeFile');

        if (dropZone && fileInput) {
            dropZone.addEventListener('click', (e) => {
                if (e.target !== removeFileBtn && !removeFileBtn.contains(e.target)) {
                    fileInput.click();
                }
            });

            fileInput.addEventListener('change', (e) => {
                const file = e.target.files[0];
                if (file) {
                    showFilePreview(file);
                }
            });

            dropZone.addEventListener('dragover', (e) => {
                e.preventDefault();
                dropZone.classList.add('border-primary', 'bg-base-200');
            });

            dropZone.addEventListener('dragleave', (e) => {
                e.preventDefault();
                dropZone.classList.remove('border-primary', 'bg-base-200');
            });

            dropZone.addEventListener('drop', (e) => {
                e.preventDefault();
                dropZone.classList.remove('border-primary', 'bg-base-200');

                const files = e.dataTransfer.files;
                if (files.length > 0) {
                    const dataTransfer = new DataTransfer();
                    dataTransfer.items.add(files[0]);
                    fileInput.files = dataTransfer.files;
                    showFilePreview(files[0]);
                }
            });

            if (removeFileBtn) {
                removeFileBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    fileInput.value = '';
                    hideFilePreview();
                });
            }

            function showFilePreview(file) {
                fileName.textContent = file.name;
                dropZoneContent.classList.add('hidden');
                filePreview.classList.remove('hidden');
            }

            function hideFilePreview() {
                dropZoneContent.classList.remove('hidden');
                filePreview.classList.add('hidden');
                fileName.textContent = '';
            }
        }

        if (importForm) {
            importForm.addEventListener('submit', async (e) => {
                e.preventDefault();

                const formData = new FormData();

                if (currentTab === 'url') {
                    const url = document.getElementById('importUrl').value.trim();
                    if (!url) {
                        window.showToast?.("Please enter a URL", 'error');
                        return;
                    }
                    formData.append('url', url);
                    formData.append('type', 'url');
                    const existingProjectId = 2;
                    if (existingProjectId) {
                        formData.append('project_id', String(existingProjectId));
                    }

	                } else if (currentTab === 'file') {
        const fileInput = document.getElementById('importFile');
        const file = fileInput.files[0];
        if (!file) {
            window.showToast?.("Please select a file", 'error');
            return;
        }
        formData.append('file', file);
        formData.append('type', 'file');

    } else if (currentTab === 'text') {
        const text = document.getElementById('importText').value.trim();
        if (!text) {
            window.showToast?.("Please enter some text", 'error');
            return;
        }
        formData.append('text', text);
        formData.append('type', 'text');
    }

    importForm.classList.add('hidden');
    importLoading.classList.remove('hidden');

    try {
        console.log('Importing with type:', currentTab);

        const response = await fetch('/projects/import', {
            method: 'POST',
            headers: {
                'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
            },
            body: formData
        });

        if (!response.ok) {
            let errorMsg = 'Failed to import';
            try {
                const error = await response.json();
                errorMsg = error.detail || errorMsg;
            } catch (e) {
                errorMsg = `HTTP ${response.status}: ${response.statusText}`;
            }
            throw new Error(errorMsg);
        }

        const data = await response.json();
        console.log('Import successful, data:', data);

        // Prevent re-prompting on save
        isImportPrompted = true;

        if (data.ai_fallback) {
            window.showToast?.("AI extraction failed - using basic parser", 'warning');
        }

        // Map fields helper
        const setFieldValue = (id, value) => {
            const el = document.getElementById(id);
            if (el && value) {
                if (id === 'notes' && el.value.trim().length > 0) return false;
                el.value = value;
                if (typeof autoResize === 'function') autoResize(el);
                return true;
            }
            return false;
        };

        // Metadata
        try {
            setFieldValue('name', data.title || data.name);
            setFieldValue('needles', data.needles);
            setFieldValue('recommended_needles', data.recommended_needles);
            setFieldValue('yarn_brand', data.brand);

            if (data.yarn || data.yarn_details) {
                window.yarnSelector?.selectByName(data.yarn, data.yarn_details || []);
            }
            setFieldValue('gauge_stitches', data.gauge_stitches);
            setFieldValue('gauge_rows', data.gauge_rows);
            setFieldValue('stitch_sample', data.stitch_sample);
            setFieldValue('description', data.description);
            setFieldValue('notes', data.notes);

            if (currentTab === 'url' && !data.link) {
                data.link = document.getElementById('importUrl').value.trim();
            }
            setFieldValue('link', data.link);
            if (data.link) initialLink = data.link;
        } catch (e) {
            console.error('Error mapping metadata', e);
        }

        // Project Image Previews
        try {
            const importImagesField = document.getElementById('import_image_urls');
            const imageUrls = Array.isArray(data.image_urls) ? data.image_urls : [];
            const dedupedImageUrls = dedupeImportImageUrls(imageUrls);
            if (importImagesField) {
                importImagesField.value = dedupedImageUrls.length
                    ? JSON.stringify(dedupedImageUrls)
                    : '';
            }
            const archiveField = document.getElementById('archive_on_save');
            if (archiveField) {
                archiveField.value = '1';
            }

            if (dedupedImageUrls.length > 0) {
                window.showToast?.('Imported images will be previewed in the gallery', 'info');
                dedupedImageUrls.forEach(url => {
                    if (typeof addPendingTitleImageToGallery === 'function') {
                        try { addPendingTitleImageToGallery(url); } catch (e) { console.error(e); }
                    }
                });
            }
        } catch (e) {
            console.error('Error mapping images', e);
        }

        // Steps
        try {
            if (data.steps && data.steps.length > 0) {
                const stepsContainer = document.getElementById('stepsContainer');
                if (stepsContainer) {
                    stepsContainer.innerHTML = '';
                    data.steps.forEach((step, index) => {
                        if (typeof addStep === 'function') {
                            try {
                                addStep(step.title || `Step ${index + 1}`, step.description || '', step.images || []);
                            } catch (e) {
                                console.error('Failed to add step:', e, step);
                            }
                        }
                    });
                }
            }
        } catch (e) {
            console.error('Error processing steps', e);
        }

        importDialog.close();

        importForm.reset();
        importForm.classList.remove('hidden');
        importLoading.classList.add('hidden');

        if (fileInput) fileInput.value = '';
        if (typeof hideFilePreview === 'function') hideFilePreview();

        window.showToast?.('Pattern imported successfully', 'success');
        window.unsavedChanges?.setDirty(true);

        document.getElementById('name').scrollIntoView({ behavior: 'smooth', block: 'center' });

    } catch (error) {
        console.error('Import failed:', error);
        window.showToast?.(error.message || 'Failed to import pattern', 'error');

        importForm.classList.remove('hidden');
        importLoading.classList.add('hidden');
    }
            });
        }
    });

    async function deleteProject() {
        const url = new URL('/projects/2', window.location.origin);
        const yarnIds = Array.from(
            document.querySelectorAll('#deleteProjectDialog .exclusive-yarn-checkbox:checked')
        ).map(el => el.value);
        yarnIds.forEach(id => url.searchParams.append('delete_yarn_ids', id));

	        try {
	            const response = await fetch(url, {
	                method: 'DELETE',
	                headers: {
	                    'X-CSRF-Token': 'facd35f37e917124753039c2e39e2f26e142ba6c'
	                }
	            });

            if (response.ok) {
                window.location.href = '/projects';
                return;
            }

            if (response.status === 401 || response.status === 403) {
                window.showToast?.('Your session has expired. Reload the page to continue.', 'error');
                document.getElementById('csrfErrorDialog')?.showModal?.();
                return;
            }

            window.showToast?.('Failed to delete project', 'error');
        } catch (error) {
            window.showToast?.('Failed to delete project', 'error');
        }
    }

    function setupExclusiveYarnDeleteSelection() {
        const dialog = document.getElementById('deleteProjectDialog');
        if (!dialog) return;

        const selectAll = dialog.querySelector('#select_all_exclusive_yarns');
        const yarnCheckboxes = Array.from(dialog.querySelectorAll('.exclusive-yarn-checkbox'));
        if (!selectAll || yarnCheckboxes.length === 0) return;

        function updateSelectAllState() {
            const checkedCount = yarnCheckboxes.filter(cb => cb.checked).length;
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

        selectAll.addEventListener('change', () => {
            yarnCheckboxes.forEach(cb => {
                cb.checked = selectAll.checked;
            });
            selectAll.indeterminate = false;
        });

        yarnCheckboxes.forEach(cb => {
            cb.addEventListener('change', updateSelectAllState);
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        setupExclusiveYarnDeleteSelection();
    });
</script>

<script type="module">
    import '/static/js/features/wysiwyg_editor.js';
</script>



<dialog id="deleteProjectDialog" class="modal modal-bottom sm:modal-middle">
    <div class="modal-box">
        <h3 class="font-bold text-lg">Delete Project</h3>
        <div class="py-4 space-y-4">
            <p class="text-base-content/80">
                Are you sure you want to delete this project?
                <br>
                <strong class="text-base-content">Commuter Rib Scarf</strong>
            </p>

            
        </div>
        <div class="modal-action">
            
<button type="button" class="btn btn-ghost gap-2 "  data-action="close-dialog" data-dialog-id="deleteProjectDialog">
    <span class="mdi mdi-close"></span>
    Cancel
</button>

            
<button type="button" class="btn btn-error text-white gap-2 "  data-call="deleteProject" data-call-args="[]">
    <span class="mdi mdi-delete-forever"></span>
    Delete
</button>

        </div>
    </div>
    
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

</dialog>

<dialog id="csrfErrorDialog" class="modal modal-bottom sm:modal-middle">
    <div class="modal-box">
        <h3 class="font-bold text-lg">Session expired</h3>
        <div class="py-4 space-y-4">
            <p class="text-base-content/80">Your session has expired. Reload the page to continue.</p>
            <p class="text-base-content/70 text-sm">This can happen after restarting the server.</p>
        </div>
        <div class="modal-action">
            
<button type="button" class="btn btn-ghost gap-2 "  data-action="close-dialog" data-dialog-id="csrfErrorDialog">
    <span class="mdi mdi-close"></span>
    Cancel
</button>

            <button type="button" class="btn btn-primary" data-action="reload">
                <span class="mdi mdi-refresh"></span>
                Reload page
            </button>
        </div>
    </div>
    
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

</dialog>




<script src="http://localhost:7674/static/js/forms/unsaved_changes.js"></script>

    </main>

    <footer class="mt-8 py-4 text-center text-sm text-base-content/70 bg-base-100 border-t border-base-300">
        <span class="mdi mdi-yarn mr-1 align-middle"></span>Stricknani v0.1.0 | GPL-3.0-only
        <a href="https://github.com/pschmitt/stricknani" target="_blank" rel="noopener noreferrer"
            class="ml-2 text-base-content/70 hover:text-base-content" aria-label="GitHub">
            <span class="mdi mdi-github text-lg align-middle"></span>
        </a>
    </footer>

    
    <!-- Profile Image Upload Tools -->
    <input type="file" id="profile-image-input" class="hidden" accept="image/*">
    <dialog id="crop-modal" class="modal modal-bottom sm:modal-middle">
        <div class="modal-box max-w-lg">
            <h3 class="font-bold text-lg mb-4">Crop Profile Picture</h3>
            <div id="avatar-crop-container" class="relative h-96 w-full rounded-lg bg-base-300 overflow-hidden">
                <img id="crop-image" src="" alt="Image to crop" class="max-h-full max-w-full">
                <div id="avatar-crop-overlay" class="avatar-crop-overlay" aria-hidden="true"></div>
            </div>
            <div class="modal-action">
                
<button type="button" class="btn btn-ghost gap-2 " id="crop-cancel"  >
    <span class="mdi mdi-close"></span>
    Cancel
</button>

                
<button type="button" class="btn btn-primary gap-2 " id="crop-save"  >
    <span class="mdi mdi-cloud-upload"></span>
    Save &amp; Upload
</button>

            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <script>
        window.STRICKNANI = window.STRICKNANI || {};
        window.STRICKNANI.profileCropper = {
            currentUserId: 1,
        };
    </script>
    <script src="http://localhost:7674/static/js/features/profile_cropper.js"></script>
    

    
    <dialog id="globalSearchModal"
        class="modal modal-top sm:modal-middle backdrop:bg-base-300/80 backdrop:backdrop-blur-sm">
        <div
            class="modal-box max-w-2xl p-0 overflow-hidden shadow-2xl border border-base-300 bg-base-100 rounded-2xl sm:mt-0 mt-4 mx-2 sm:mx-auto">
            <div class="relative flex items-center border-b border-base-300 px-4 py-2 bg-base-200/50">
                <span class="mdi mdi-magnify text-2xl opacity-40 mr-3"></span>
                <input type="text" id="globalSearchInput" name="q" placeholder="Search projects, yarns..."
                    class="input border-none bg-transparent w-full focus:outline-none focus:ring-0 px-0 h-12 text-lg font-medium"
                    hx-get="/search/global" hx-trigger="keyup changed delay:300ms" hx-target="#globalSearchResults"
                    hx-indicator="#globalSearchIndicator" autocomplete="off">
                <div id="globalSearchIndicator" class="htmx-indicator ml-2">
                    <span class="loading loading-spinner loading-sm opacity-40"></span>
                </div>
                <div class="flex items-center gap-1 ml-auto shrink-0 pl-4">
                    <kbd class="kbd kbd-sm bg-base-300 border-base-content/10 font-bold opacity-60">ESC</kbd>
                </div>
            </div>
            <div id="globalSearchResults" class="max-h-[60vh] overflow-y-auto">
                <div class="py-12 text-center text-base-content/30 italic">
                    <span class="mdi mdi-keyboard-outline text-4xl block mb-2"></span>
                    <p>Start typing to search...</p>
                </div>
            </div>
            <div
                class="bg-base-200/50 px-4 py-2 text-[10px] font-bold uppercase tracking-widest opacity-40 flex justify-between border-t border-base-300">
                <span>↑↓ to navigate</span>
                <span>Enter to select</span>
            </div>
        </div>
        <form method="dialog" class="modal-backdrop">
            <button>close</button>
        </form>
    </dialog>

    <script src="http://localhost:7674/static/js/features/global_search_modal.js"></script>
    

    <div id="toastContainer"
        class="fixed inset-x-4 top-[4.5rem] z-[2147483647] mx-auto flex max-w-sm flex-col gap-3 sm:inset-auto sm:right-4 sm:top-[4.5rem] sm:w-80"
        aria-live="polite" aria-atomic="true"></div>

    <dialog id="confirmationDialog" class="modal modal-bottom sm:modal-middle backdrop:bg-slate-900/50">
        <div class="modal-box p-0 overflow-hidden bg-base-100 shadow-2xl rounded-2xl w-full max-w-sm sm:max-w-md">
            <div class="bg-warning/10 p-6 flex items-center gap-4 text-warning" id="confirmationHeader">
                <span class="mdi mdi-alert-circle-outline text-3xl"></span>
                <div>
                    <h3 class="font-bold text-lg" id="confirmationTitle">Confirm Action</h3>
                </div>
            </div>

            <div class="p-6">
                <p class="text-base-content/80 text-sm leading-relaxed" id="confirmationMessage">
                    Are you sure you want to proceed?
                </p>
                <div id="confirmationContent" class="hidden mt-4 text-left bg-base-200 p-3 rounded text-xs opacity-70">
                </div>
            </div>

            <div class="p-4 bg-base-200/30 border-t border-base-200 flex justify-end gap-3">
                <form method="dialog" class="contents">
                    <button type="submit" id="confirmationCancel" class="btn btn-ghost">
                        Cancel
                    </button>
                    <button type="button" id="confirmationConfirm" class="btn btn-warning gap-2">
                        <span class="mdi mdi-check"></span>
                        Confirm
                    </button>
                </form>
            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <dialog id="pdfViewerDialog" class="modal">
        <div class="modal-box w-screen h-screen max-w-none p-0 overflow-hidden rounded-none flex flex-col">
            <div class="flex items-center justify-between gap-4 p-4 border-b border-base-200 bg-base-100">
                <div class="min-w-0">
                    <h3 class="font-bold text-lg" id="pdfViewerTitle">Preview</h3>
                    <p class="text-xs text-base-content/60 truncate" id="pdfViewerFilename"></p>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                    <a id="pdfViewerDownload" class="btn btn-ghost btn-sm btn-circle" target="_blank" rel="noreferrer"
                        title="Download">
                        <span class="mdi mdi-download text-xl"></span>
                    </a>
                    <form method="dialog">
                        <button class="btn btn-ghost btn-sm btn-circle" aria-label="close">
                            <span class="mdi mdi-close text-xl"></span>
                        </button>
                    </form>
                </div>
            </div>
            <div class="flex-1 bg-base-200/30">
                <iframe id="pdfViewerFrame" class="w-full h-full" src="about:blank" title="Preview"></iframe>
            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <dialog id="imageViewerDialog" class="modal">
        <div class="modal-box w-screen h-screen max-w-none p-0 overflow-hidden rounded-none flex flex-col">
            <div class="flex items-center justify-between gap-4 p-4 border-b border-base-200 bg-base-100">
                <div class="min-w-0">
                    <h3 class="font-bold text-lg" id="imageViewerTitle">Preview</h3>
                    <p class="text-xs text-base-content/60 truncate" id="imageViewerFilename"></p>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                    <a id="imageViewerDownload" class="btn btn-ghost btn-sm btn-circle" target="_blank" rel="noreferrer"
                        title="Download">
                        <span class="mdi mdi-download text-xl"></span>
                    </a>
                    <form method="dialog">
                        <button class="btn btn-ghost btn-sm btn-circle" aria-label="close">
                            <span class="mdi mdi-close text-xl"></span>
                        </button>
                    </form>
                </div>
            </div>
            <div class="flex-1 bg-black/90 p-3 sm:p-4 overflow-auto">
                <div class="min-h-full flex items-center justify-center">
                    <img id="imageViewerImage" class="block max-w-full max-h-full object-contain" src="about:blank"
                        alt="">
                </div>
            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <dialog id="pswpOcrDialog" class="modal modal-bottom sm:modal-middle">
        <div
            class="modal-box w-screen h-screen max-w-none p-0 overflow-hidden rounded-none flex flex-col sm:w-[min(1100px,calc(100vw-4rem))] sm:h-[min(85vh,900px)] sm:rounded-box">
            <div class="flex items-center justify-between gap-4 p-4 border-b border-base-200 bg-base-100">
                <div class="min-w-0">
                    <h3 class="font-bold text-lg">OCR</h3>
                    <p class="text-xs text-base-content/60 truncate" id="pswpOcrFilename"></p>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                    <button id="pswpOcrCopy" type="button" class="btn btn-ghost btn-sm btn-circle"
                        title="Copy text" aria-label="Copy text" disabled>
                        <span class="mdi mdi-content-copy text-xl"></span>
                    </button>
                    <button id="pswpOcrRetry" type="button" class="btn btn-ghost btn-sm btn-circle"
                        title="Retry" aria-label="Retry">
                        <span class="mdi mdi-refresh text-xl"></span>
                    </button>
                    <form method="dialog">
                        <button class="btn btn-ghost btn-sm btn-circle" aria-label="close">
                            <span class="mdi mdi-close text-xl"></span>
                        </button>
                    </form>
                </div>
            </div>
            <div class="flex-1 min-h-0 bg-base-100 p-4 overflow-auto">
                <div class="flex min-h-0 flex-col">
                    <div id="pswpOcrStatus" class="text-sm text-base-content/70"></div>
                    <div class="relative mt-3 flex-1 min-h-0">
                        <div id="pswpOcrLoadingOverlay"
                            class="absolute inset-0 hidden rounded-lg border border-base-300 bg-base-100/95 backdrop-blur-sm">
                            <div class="flex h-full w-full items-center justify-center p-4">
                                <div class="flex items-center gap-3 text-base-content/80">
                                    <span id="pswpOcrSpinner" class="loading loading-spinner loading-sm text-primary"
                                        aria-hidden="true"></span>
                                    <div id="pswpOcrLoadingStatus" class="text-sm"></div>
                                </div>
                            </div>
                        </div>
                        <pre id="pswpOcrText"
                            class="h-full min-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg border border-base-300 bg-base-100 p-3 text-sm text-base-content/90"></pre>
                    </div>
                </div>
            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <dialog id="pswpCropDialog" class="modal modal-bottom sm:modal-middle">
        <div
            class="modal-box w-full max-w-5xl h-[90vh] p-0 overflow-hidden flex flex-col bg-base-100 rounded-box shadow-xl">
            <!-- Header -->
            <div class="flex items-center justify-between px-6 py-4 border-b border-base-200 bg-base-100 z-10">
                <div class="min-w-0 flex items-center gap-3">
                    <div class="p-2 rounded-lg bg-primary/10 text-primary">
                        <span class="mdi mdi-crop text-xl"></span>
                    </div>
                    <div>
                        <h3 class="font-bold text-lg leading-tight">Crop Image</h3>
                        <p class="text-xs text-base-content/60 truncate max-w-[200px] sm:max-w-md"
                            id="pswpCropFilename"></p>
                    </div>
                </div>
                <div class="flex items-center gap-2">
                    <button id="pswpCropReset" type="button" class="btn btn-ghost btn-sm gap-2 normal-case"
                        title="Reset crop">
                        <span class="mdi mdi-refresh text-lg"></span>
                        <span class="hidden sm:inline">Reset</span>
                    </button>
                    <form method="dialog">
                        <button class="btn btn-ghost btn-sm btn-circle" aria-label="Close">
                            <span class="mdi mdi-close text-xl"></span>
                        </button>
                    </form>
                </div>
            </div>

            <!-- Crop Area -->
            <div class="flex-1 min-h-0 bg-base-300 relative group overflow-hidden">
                <!-- Checkerboard pattern for transparency -->
                <div class="absolute inset-0 opacity-20 pointer-events-none"
                    style="background-image: radial-gradient(#000 1px, transparent 1px); background-size: 20px 20px;">
                </div>

                <div class="w-full h-full p-4 sm:p-8" id="pswpCropContainer">
                    <img id="pswpCropImage" class="max-w-full block opacity-0 transition-opacity duration-300" src=""
                        alt="">
                </div>

                <!-- Loading Overlay -->
                <div id="pswpCropLoading"
                    class="absolute inset-0 z-20 flex items-center justify-center bg-base-100/50 backdrop-blur-sm transition-opacity duration-200 opacity-0 pointer-events-none">
                    <span class="loading loading-spinner loading-lg text-primary"></span>
                </div>
            </div>

            <!-- Footer -->
            <div class="p-4 sm:px-6 border-t border-base-200 bg-base-100 z-10">
                <div class="flex items-center justify-between gap-4">
                    <div class="text-xs text-base-content/50 hidden sm:block">
                        <span class="mdi mdi-information-outline mr-1"></span>
                        Drag to crop, scroll to zoom
                    </div>
                    <div class="flex justify-end gap-3 flex-1">
                        <form method="dialog">
                            
<button type="submit" class="btn btn-ghost gap-2 "  >
    <span class="mdi mdi-close"></span>
    Cancel
</button>

                        </form>
                        
<button type="button" class="btn btn-primary gap-2 " id="pswpCropSave"  >
    <span class="mdi mdi-content-save"></span>
    Save Crop
</button>

                    </div>
                </div>
            </div>
        </div>
        
<form method="dialog" class="modal-backdrop">
    <button type="submit" aria-label="Close dialog"></button>
</form>

    </dialog>

    <div id="swipe-indicator-left"
        class="fixed left-4 top-1/2 -translate-y-1/2 z-[100] transition-all duration-200 opacity-0 pointer-events-none scale-75">
        <div
            class="bg-base-100/90 backdrop-blur-sm rounded-full p-4 shadow-xl border border-base-content/10 text-primary">
            <span class="mdi mdi-arrow-left text-4xl"></span>
        </div>
    </div>
    <div id="swipe-indicator-right"
        class="fixed right-4 top-1/2 -translate-y-1/2 z-[100] transition-all duration-200 opacity-0 pointer-events-none scale-75">
        <div
            class="bg-base-100/90 backdrop-blur-sm rounded-full p-4 shadow-xl border border-base-content/10 text-primary">
            <span class="mdi mdi-arrow-right text-4xl"></span>
        </div>
    </div>

    <script>
        window.STRICKNANI = window.STRICKNANI || {};
        window.STRICKNANI.photoswipe = {
            ocrEndpoint: "/utils/ocr",
            pswpModuleUrl: "http://localhost:7674/static/vendor/photoswipe/photoswipe.esm.min.js",
        };
    </script>
    <script type="module">
        import PhotoSwipeLightbox from "http://localhost:7674/static/vendor/photoswipe/photoswipe-lightbox.esm.min.js";
        window.STRICKNANI.photoswipe.PhotoSwipeLightbox = PhotoSwipeLightbox;
        import("http://localhost:7674/static/js/features/photoswipe.js");
    </script>
    <script src="http://localhost:7674/static/js/features/navbar_dropdowns.js"></script>
    <script src="http://localhost:7674/static/js/features/swipe_nav.js"></script>
    
</body>

</html>