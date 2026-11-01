"use strict";

document.querySelectorAll("[data-ocr-widget]").forEach((widget) => {
    const button = widget.querySelector("[data-ocr-run]");
    const language = widget.querySelector("[data-ocr-language]");
    const output = widget.querySelector("[data-ocr-output]");
    const status = widget.querySelector("[data-ocr-status]");
    if (!button || !language || !output || !status) return;

    button.addEventListener("click", async () => {
        button.disabled = true;
        status.textContent = widget.dataset.busy || "";
        output.value = "";
        output.hidden = true;
        try {
            const response = await fetch("/utils/ocr", {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/json",
                    "X-CSRF-Token": widget.dataset.csrf || "",
                },
                body: JSON.stringify({
                    src: widget.dataset.src || "",
                    lang: language.value,
                }),
            });
            if (!response.ok) throw new Error("ocr_request_failed");
            const result = await response.json();
            if (typeof result.text !== "string") throw new Error("ocr_invalid_response");
            output.value = result.text;
            output.hidden = false;
            status.textContent = "";
        } catch (_error) {
            status.textContent = widget.dataset.error || "";
        } finally {
            button.disabled = false;
        }
    });
});
