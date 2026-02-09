(() => {
	document.addEventListener("DOMContentLoaded", () => {
		// Swipe-to-navigate between the primary navbar destinations.
		// Disabled on primary form pages to avoid conflicts with horizontal gestures while editing.
		const isPrimaryFormPage = !!document.querySelector(
			'main form[data-primary-form="true"]',
		);
		if (isPrimaryFormPage) {
			return;
		}

		const mainEl = document.querySelector("main");
		const detailPrevHref = mainEl?.dataset?.swipePrevHref || "";
		const detailNextHref = mainEl?.dataset?.swipeNextHref || "";
		const hasDetailNav = !!(detailPrevHref || detailNextHref);

		const navMenu = document.querySelector(".navbar-dropdown-nav");
		const navLinks = navMenu
			? Array.from(navMenu.querySelectorAll("a[href]"))
					.map((a) => a.getAttribute("href") || "")
					.filter((href) => href.startsWith("/"))
			: [];

		const leftIndicator = document.getElementById("swipe-indicator-left");
		const rightIndicator = document.getElementById("swipe-indicator-right");

		const isSwipeEligibleTarget = (target) => {
			if (!target) {
				return false;
			}
			if (document.querySelector("dialog[open]")) {
				return false;
			}
			if (document.querySelector(".pswp.pswp--open")) {
				return false;
			}
			if (
				target.closest(
					'input, textarea, select, button, a, label, [contenteditable="true"]',
				)
			) {
				return false;
			}
			if (target.closest("[data-swipe-nav-ignore]")) {
				return false;
			}
			return true;
		};

		const findCurrentIndex = () => {
			if (navLinks.length < 2) {
				return -1;
			}
			const pathname = window.location.pathname || "/";
			let best = { index: -1, length: -1 };

			navLinks.forEach((href, index) => {
				if (!href) {
					return;
				}
				const isMatch = pathname === href || pathname.startsWith(`${href}/`);
				if (!isMatch) {
					return;
				}
				if (href.length > best.length) {
					best = { index, length: href.length };
				}
			});

			return best.index;
		};

		const getNextHref = (dx) => {
			if (dx === 0) {
				return null;
			}
			const isNext = dx < 0; // Swipe left -> Next

			if (hasDetailNav) {
				const detailHref = isNext ? detailNextHref : detailPrevHref;
				if (detailHref) {
					return detailHref;
				}
			}

			const currentIndex = findCurrentIndex();
			if (currentIndex === -1) {
				return null;
			}

			const nextIndex = isNext ? currentIndex + 1 : currentIndex - 1;
			if (nextIndex >= 0 && nextIndex < navLinks.length) {
				return navLinks[nextIndex];
			}
			return null;
		};

		const updateIndicators = (dx, opacity) => {
			if (!leftIndicator || !rightIndicator) {
				return;
			}

			// Reset
			leftIndicator.style.opacity = "0";
			leftIndicator.style.transform = "translate(0, -50%) scale(0.75)";
			rightIndicator.style.opacity = "0";
			rightIndicator.style.transform = "translate(0, -50%) scale(0.75)";

			if (Math.abs(dx) < 10) {
				return;
			}

			const href = getNextHref(dx);
			if (!href) {
				return;
			}

			const target = dx > 0 ? leftIndicator : rightIndicator;
			target.style.opacity = String(opacity);
			// Slide in slightly
			const slide = Math.min(20, Math.abs(dx) * 0.1);
			const scale = 0.75 + opacity * 0.25;
			const translate = dx > 0 ? `${slide}px` : `-${slide}px`;
			target.style.transform = `translate(${translate}, -50%) scale(${scale})`;
		};

		const hideIndicators = () => {
			if (leftIndicator) {
				leftIndicator.style.opacity = "0";
			}
			if (rightIndicator) {
				rightIndicator.style.opacity = "0";
			}
		};

		let startX = 0;
		let startY = 0;
		let touchTarget = null;
		let cancelled = false;

		const thresholdX = 60; // px
		const thresholdY = 70; // px

		document.addEventListener(
			"touchstart",
			(event) => {
				if (!event.touches || event.touches.length !== 1) {
					return;
				}
				const target = event.target;
				if (!isSwipeEligibleTarget(target)) {
					touchTarget = null;
					return;
				}

				const touch = event.touches[0];
				startX = touch.clientX;
				startY = touch.clientY;
				touchTarget = target;
				cancelled = false;
				hideIndicators();
			},
			{ passive: true },
		);

		document.addEventListener(
			"touchmove",
			(event) => {
				if (!touchTarget || !event.touches || event.touches.length !== 1) {
					return;
				}
				const touch = event.touches[0];
				const dx = touch.clientX - startX;
				const dy = touch.clientY - startY;

				if (Math.abs(dy) > thresholdY) {
					cancelled = true;
					hideIndicators();
					return;
				}

				if (cancelled) {
					return;
				}

				// Calculate opacity based on progress to threshold
				const progress = Math.min(1, Math.abs(dx) / (thresholdX * 1.5));
				updateIndicators(dx, progress);
			},
			{ passive: true },
		);

		document.addEventListener(
			"touchend",
			(event) => {
				hideIndicators();

				if (
					!touchTarget ||
					cancelled ||
					!event.changedTouches ||
					event.changedTouches.length !== 1
				) {
					touchTarget = null;
					return;
				}

				if (!isSwipeEligibleTarget(touchTarget)) {
					touchTarget = null;
					return;
				}

				const touch = event.changedTouches[0];
				const dx = touch.clientX - startX;
				const dy = touch.clientY - startY;

				// Basic horizontal swipe detection.
				if (Math.abs(dx) < thresholdX) {
					touchTarget = null;
					return;
				}
				if (Math.abs(dy) > thresholdY) {
					touchTarget = null;
					return;
				}

				const href = getNextHref(dx);
				if (href) {
					window.location.href = href;
				}
				touchTarget = null;
			},
			{ passive: true },
		);
	});
})();
