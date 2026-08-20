(() => {
	function toggleSidebarExpansion(checkbox) {
		const main = document.getElementById("main-column");
		const sidebar = document.getElementById("sidebar-column");
		const restoreButton = document.getElementById("details-restore-button");
		if (!main || !sidebar || !checkbox) {
			return;
		}

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

	function restoreSidebarDetails() {
		const sidebarToggle = document.getElementById("details-toggle-sidebar");
		if (!sidebarToggle) {
			return;
		}
		sidebarToggle.checked = true;
		toggleSidebarExpansion(sidebarToggle);
	}

	window.toggleSidebarExpansion = toggleSidebarExpansion;
	window.restoreSidebarDetails = restoreSidebarDetails;

	const init = () => {
		const sidebarToggle = document.getElementById("details-toggle-sidebar");
		if (sidebarToggle) {
			toggleSidebarExpansion(sidebarToggle);
		}
	};

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", init);
	} else {
		init();
	}
})();
