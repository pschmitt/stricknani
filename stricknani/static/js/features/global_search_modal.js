(function () {
  function openModal(modal, input) {
    if (!modal || !input) {
      return;
    }
    if (!modal.open) {
      modal.showModal();
      window.setTimeout(() => input.focus(), 50);
      return;
    }
    modal.close();
  }

  document.addEventListener("keydown", (e) => {
    // Ctrl+K or Cmd+K
    if ((e.ctrlKey || e.metaKey) && e.key === "k") {
      e.preventDefault();
      const modal = document.getElementById("globalSearchModal");
      const input = document.getElementById("globalSearchInput");
      openModal(modal, input);
    }
  });

  // 2-finger swipe down to open search on mobile
  (function () {
    let touchStartY = 0;
    let isTwoFinger = false;

    document.addEventListener(
      "touchstart",
      (e) => {
        if (e.touches.length === 2) {
          isTwoFinger = true;
          touchStartY = (e.touches[0].pageY + e.touches[1].pageY) / 2;
        } else {
          isTwoFinger = false;
        }
      },
      { passive: true },
    );

    document.addEventListener(
      "touchend",
      (e) => {
        if (isTwoFinger) {
          const touchEndY =
            (e.changedTouches[0].pageY +
              (e.changedTouches[1]
                ? e.changedTouches[1].pageY
                : e.changedTouches[0].pageY)) /
            2;
          const swipeDistance = touchEndY - touchStartY;

          // If swiped down more than 100px
          if (swipeDistance > 100) {
            const modal = document.getElementById("globalSearchModal");
            const input = document.getElementById("globalSearchInput");
            if (modal && !modal.open) {
              modal.showModal();
              window.setTimeout(() => input?.focus(), 50);
            }
          }
        }
        isTwoFinger = false;
      },
      { passive: true },
    );
  })();

  // Navigation within search results using arrow keys
  function handleResultsNav(e) {
    if (!["ArrowDown", "ArrowUp", "Enter"].includes(e.key)) {
      return;
    }
    const results = document.querySelectorAll("#globalSearchResults a");
    if (!results.length) {
      return;
    }

    const list = Array.from(results);
    let activeIndex = list.findIndex((el) =>
      el.classList.contains("bg-primary/10"),
    );

    if (e.key === "ArrowDown") {
      e.preventDefault();
      list.forEach((el) => {
        el.classList.remove("bg-primary/10");
      });
      activeIndex = (activeIndex + 1) % list.length;
      list[activeIndex].classList.add("bg-primary/10");
      list[activeIndex].scrollIntoView({ block: "nearest" });
      return;
    }

    if (e.key === "ArrowUp") {
      e.preventDefault();
      list.forEach((el) => {
        el.classList.remove("bg-primary/10");
      });
      activeIndex = (activeIndex - 1 + list.length) % list.length;
      list[activeIndex].classList.add("bg-primary/10");
      list[activeIndex].scrollIntoView({ block: "nearest" });
      return;
    }

    if (e.key === "Enter" && activeIndex >= 0) {
      e.preventDefault();
      list[activeIndex].click();
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("globalSearchModal");
    const input = document.getElementById("globalSearchInput");
    input?.addEventListener("keydown", handleResultsNav);

    // Focus search input when modal is opened via other means (if any)
    modal?.addEventListener("show", () => {
      window.setTimeout(() => input?.focus(), 50);
    });
  });
})();
