async function deleteProject(id) {
	if (!id) return;

	const btn = document.querySelector("#deleteProjectDialog .btn-error");
	if (btn) {
		btn.disabled = true;
		const originalHtml = btn.innerHTML;
		btn.innerHTML = `<span class="loading loading-spinner loading-sm"></span> ${btn.textContent.trim()}`;
		btn.dataset.originalHtml = originalHtml;
	}

	try {
		const response = await fetch(`/projects/${id}`, {
			method: "DELETE",
			headers: {
				"X-CSRF-Token": document.querySelector('meta[name="csrf-token"]')
					.content,
			},
		});

		if (response.ok) {
			window.location.href = "/projects?toast=project_deleted";
			return;
		}

		window.showToast?.("{{ _("Failed to delete project") }}", "error");
		if (btn) {
			btn.disabled = false;
			btn.innerHTML = btn.dataset.originalHtml;
		}
	} catch (error) {
		console.error("Delete project failed", error);
		window.showToast?.("{{ _("Failed to delete project") }}", "error");
		if (btn) {
			btn.disabled = false;
			btn.innerHTML = btn.dataset.originalHtml;
		}
	}
}
