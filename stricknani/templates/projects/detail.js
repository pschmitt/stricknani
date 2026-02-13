document.addEventListener("DOMContentLoaded", () => {
	setupExclusiveYarnDeleteSelection();

	function updateDetailImageVisibility() {
		// Check stitch sample
		const stitchSampleCard = document.getElementById("stitch-sample-card");
		if (stitchSampleCard) {
			const textSection = document.getElementById("stitch-sample-text");
			const photoSection = document.getElementById("stitch-sample-photos");
			if (textSection && photoSection) {
				processSection(textSection, photoSection);
			}
		}

		// Check steps
		document.querySelectorAll(".step-card").forEach((card) => {
			const textSection = card.querySelector(".step-description");
			const photoSection = card.querySelector(".step-photos-section");
			if (textSection && photoSection) {
				processSection(textSection, photoSection);
			}
		});
	}

	function processSection(textContainer, photoContainer) {
		const imagesInText = Array.from(
			textContainer.querySelectorAll("img.markdown-inline-image"),
		).map((img) => img.getAttribute("data-lightbox-src") || img.src);

		const galleryLinks = photoContainer.querySelectorAll(".pswp-gallery a");
		let visibleCount = 0;

		galleryLinks.forEach((link) => {
			const url = link.getAttribute("href");
			const isUsed = imagesInText.some((u) => {
				try {
					return u === url || decodeURI(u) === url || u.endsWith(url);
				} catch (e) {
					return u === url || u.endsWith(url);
				}
			});

			if (isUsed) {
				link.classList.add("hidden");
			} else {
				link.classList.remove("hidden");
				visibleCount++;
			}
		});

		if (visibleCount === 0) {
			photoContainer.classList.add("hidden");
		} else {
			photoContainer.classList.remove("hidden");
		}
	}

	function setupInstructionsCollapsePersistence() {
		const instructionsToggle = document.getElementById("instructions-toggle");
		const storageKey = "stricknani.project.instructions.collapsed";

		if (!instructionsToggle) {
			return;
		}

		try {
			const isCollapsed = window.localStorage.getItem(storageKey) === "1";
			instructionsToggle.checked = !isCollapsed;
		} catch (e) {
			// Ignore storage access errors and keep the default expanded state.
		}

		instructionsToggle.addEventListener("change", () => {
			try {
				window.localStorage.setItem(
					storageKey,
					instructionsToggle.checked ? "0" : "1",
				);
			} catch (e) {
				// Ignore storage write errors.
			}
		});
	}

	updateDetailImageVisibility();
	setupInstructionsCollapsePersistence();

	if (window.location.hash === "#print") {
		setTimeout(() => {
			window.print();
		}, 1000); // Give it a second to render
	}
});

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
