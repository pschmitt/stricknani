(() => {
	document.addEventListener("DOMContentLoaded", () => {
		const navDropdowns = document.querySelectorAll(".navbar-nav-dropdown");
		if (!navDropdowns.length) {
			return;
		}

		navDropdowns.forEach((dropdown) => {
			let closeTimer;

			const openDropdown = () => {
				if (closeTimer) {
					clearTimeout(closeTimer);
					closeTimer = null;
				}
				dropdown.classList.add("dropdown-open");
			};

			const scheduleClose = () => {
				if (closeTimer) {
					clearTimeout(closeTimer);
				}
				closeTimer = setTimeout(() => {
					if (dropdown.matches(":hover")) {
						return;
					}
					if (dropdown.contains(document.activeElement)) {
						return;
					}
					dropdown.classList.remove("dropdown-open");
				}, 250);
			};

			dropdown.addEventListener("mouseenter", openDropdown);
			dropdown.addEventListener("mouseleave", scheduleClose);
			dropdown.addEventListener("focusin", openDropdown);
			dropdown.addEventListener("focusout", scheduleClose);
		});
	});
})();
