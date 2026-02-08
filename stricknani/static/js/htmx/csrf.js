/* global htmx */

(function () {
  document.addEventListener("htmx:configRequest", (event) => {
    const csrfToken = document
      .querySelector('meta[name="csrf-token"]')
      ?.getAttribute("content");
    if (csrfToken) {
      event.detail.headers["X-CSRF-Token"] = csrfToken;
    }
  });
})();

