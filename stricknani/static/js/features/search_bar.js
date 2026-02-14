(() => {
	function initSearchBar(wrapper) {
		if (!wrapper || wrapper.dataset.searchBarInitialized === "1") {
			return;
		}
		wrapper.dataset.searchBarInitialized = "1";

		const searchInput =
			wrapper.querySelector("[data-search-input]") ||
			wrapper.querySelector('input[type="text"]');
		const suggestionContainer =
			wrapper.querySelector("[data-search-suggestions]") ||
			wrapper.querySelector('[id$="-suggestions"]');
		const suggestionList =
			wrapper.querySelector("[data-search-suggestion-list]") ||
			wrapper.querySelector('[id$="-suggestion-list"]');
		const clearButton = wrapper.querySelector("[data-search-clear]");

		if (!searchInput || !suggestionContainer || !suggestionList) {
			return;
		}

		let activeIndex = -1;
		let lastSuggestions = [];
		let lastMatch = null;

		const prefixes = [
			{ prefix: "cat:", type: "cat", icon: "mdi-tag-outline" },
			{ prefix: "tag:", type: "tag", icon: "mdi-tag-multiple-outline" },
			{ prefix: "#", type: "tag", icon: "mdi-tag-multiple-outline" },
			{ prefix: "brand:", type: "brand", icon: "mdi-label-outline" },
		];
		const activeClass = "bg-base-200";

		const formatSuggestionValue = (prefixConfig, suggestion) => {
			if (prefixConfig.type === "brand" && /\s/.test(suggestion)) {
				return `${prefixConfig.prefix}"${suggestion}"`;
			}
			return `${prefixConfig.prefix}${suggestion}`;
		};

		const clearActiveState = () => {
			activeIndex = -1;
			suggestionList
				.querySelectorAll("[data-suggestion-index]")
				.forEach((item) => {
					item.classList.remove(activeClass);
				});
		};

			const updateActiveItem = (nextIndex) => {
				const items = suggestionList.querySelectorAll("[data-suggestion-index]");
				if (!items.length) {
					clearActiveState();
					return;
				}
				const clampedIndex = Math.max(0, Math.min(nextIndex, items.length - 1));
				items.forEach((item) => {
					item.classList.remove(activeClass);
				});
				activeIndex = clampedIndex;
				const activeItem = items[activeIndex];
				activeItem.classList.add(activeClass);
				activeItem.scrollIntoView({ block: "nearest" });
			};

		const closeSuggestions = () => {
			suggestionContainer.classList.add("hidden");
			clearActiveState();
		};

		const openSuggestions = () => {
			suggestionContainer.classList.remove("hidden");
			updateActiveItem(activeIndex >= 0 ? activeIndex : 0);
		};

		const updateClearButton = () => {
			if (!clearButton) {
				return;
			}
			if (searchInput.value.trim()) {
				clearButton.classList.remove("hidden");
			} else {
				clearButton.classList.add("hidden");
			}
		};

		const triggerSearch = () => {
			// htmx listens to keyup / changed events configured in hx-trigger.
			searchInput.dispatchEvent(new Event("input", { bubbles: true }));
			searchInput.dispatchEvent(new KeyboardEvent("keyup", { bubbles: true }));
		};

		searchInput.addEventListener("input", async (e) => {
			const val = e.target.value || "";
			updateClearButton();
			const match = prefixes.find((p) =>
				val.toLowerCase().startsWith(p.prefix),
			);

			if (match) {
				const q = val.substring(match.prefix.length).trim();
				const type = match.type;
				const baseUrl = String(searchInput.getAttribute("hx-get") || "").replace(
					/\/+$/,
					"",
				);
				if (!baseUrl) {
					closeSuggestions();
					return;
				}

				try {
					const response = await fetch(
						`${baseUrl}/search-suggestions?type=${type}&q=${encodeURIComponent(q)}`,
					);
					const suggestions = await response.json();
					lastSuggestions = suggestions;
					lastMatch = match;

					if (suggestions.length > 0) {
						const trimmedValue = val.trim();
						const normalizedInput = trimmedValue.toLowerCase();
						const matchedExact = suggestions.some((s) => {
							const formattedValue = formatSuggestionValue(
								match,
								s,
							).toLowerCase();
							return formattedValue === normalizedInput;
						});
						if (matchedExact) {
							closeSuggestions();
							return;
						}
						suggestionList.innerHTML = suggestions
							.map((s, index) => {
								return `
	                                <li>
	                                    <button type="button" data-suggestion-index="${index}" class="py-2 px-4 hover:bg-base-200 text-left w-full transition-colors flex items-center gap-2">
	                                        <span class="mdi ${match.icon} text-base-content/50"></span>
	                                        <span>${s}</span>
	                                    </button>
	                                </li>
	                            `;
							})
							.join("");
						openSuggestions();
						clearActiveState();
					} else {
						closeSuggestions();
					}
				} catch (err) {
					console.error("Failed to fetch suggestions:", err);
					closeSuggestions();
				}
			} else {
				closeSuggestions();
			}
		});

		searchInput.addEventListener("keydown", (e) => {
			if (suggestionContainer.classList.contains("hidden")) {
				return;
			}
			const items = suggestionList.querySelectorAll("[data-suggestion-index]");
			if (!items.length) {
				return;
			}
			if (e.key === "ArrowDown") {
				e.preventDefault();
				const nextIndex = activeIndex === -1 ? 0 : activeIndex + 1;
				updateActiveItem(nextIndex);
			} else if (e.key === "ArrowUp") {
				e.preventDefault();
				const nextIndex = activeIndex <= 0 ? 0 : activeIndex - 1;
				updateActiveItem(nextIndex);
			} else if (e.key === "Enter") {
				e.preventDefault();
				const index = activeIndex >= 0 ? activeIndex : 0;
				const activeItem = items[index];
				activeItem.click();
			} else if (e.key === "Escape") {
				closeSuggestions();
			}
		});

		document.addEventListener("click", (e) => {
			if (!wrapper.contains(e.target)) {
				closeSuggestions();
			}
		});

		suggestionList.addEventListener("click", (e) => {
			const button = e.target.closest("[data-suggestion-index]");
			if (!button) {
				return;
			}
			const index = Number.parseInt(
				button.getAttribute("data-suggestion-index") || "0",
				10,
			);
			const suggestion = lastSuggestions[index];
			if (!suggestion || !lastMatch) {
				return;
			}
			const formattedValue = formatSuggestionValue(lastMatch, suggestion);
			searchInput.value = formattedValue;
			closeSuggestions();
			updateClearButton();
			searchInput.focus();
			triggerSearch();
		});

		if (clearButton) {
			clearButton.addEventListener("click", () => {
				searchInput.value = "";
				closeSuggestions();
				updateClearButton();
				searchInput.focus();
				triggerSearch();
			});
		}

		updateClearButton();
	}

	function initAll() {
		document.querySelectorAll("[data-search-bar]").forEach(initSearchBar);
	}

	document.addEventListener("DOMContentLoaded", initAll);
	if (typeof htmx !== "undefined") {
		htmx.on("htmx:afterSettle", initAll);
	}
})();
