    async function deleteYarn(id) {
        if (!id) return;

        const btn = document.querySelector('#deleteYarnDialog .btn-error');
        if (btn) {
            btn.disabled = true;
            const originalHtml = btn.innerHTML;
            btn.innerHTML = `<span class="loading loading-spinner loading-sm"></span> ${btn.textContent.trim()}`;
            btn.dataset.originalHtml = originalHtml;
        }

        try {
            const response = await fetch(`/yarn/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-Token': document.querySelector('meta[name="csrf-token"]').content
                }
            });

            if (response.ok) {
                window.location.href = '/yarn?toast=yarn_deleted';
                return;
            }

            window.showToast?.('{{ _("Failed to delete yarn") }}', 'error');
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = btn.dataset.originalHtml;
            }
        } catch (error) {
            console.error('Delete yarn failed', error);
            window.showToast?.('{{ _("Failed to delete yarn") }}', 'error');
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = btn.dataset.originalHtml;
            }
        }
    }
