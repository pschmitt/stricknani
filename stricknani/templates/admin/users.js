    function openCreateUserDialog() {
        const dialog = document.getElementById('createUserDialog');
        const form = document.getElementById('createUserForm');

        if (!dialog || !form) {
            return;
        }

        form.reset();
        dialog.showModal();
    }

    let currentEditingUserId = null;

    function triggerEditUserImageUpload() {
        if (currentEditingUserId) {
            window.triggerProfileImageUpload(currentEditingUserId);
        }
    }

    function openEditUserDialog(button) {
        const userId = button.dataset.userId;
        const userEmail = button.dataset.userEmail || '';
        const dialog = document.getElementById('editUserDialog');
        const form = document.getElementById('editUserForm');
        const emailTitle = document.getElementById('editUserEmailTitle');
        const emailInput = document.getElementById('edit-user-email');

        if (!dialog || !form || !emailInput || !userId) {
            return;
        }

        currentEditingUserId = userId;
        form.action = `/admin/users/${userId}/edit`;
        form.setAttribute('hx-post', `/admin/users/${userId}/edit`);
        form.setAttribute('hx-target', `#user-card-${userId}`);
        emailTitle.textContent = userEmail;
        emailInput.value = userEmail;

        // Refresh HTMX to pick up the new hx-post and hx-target
        htmx.process(form);

        dialog.showModal();
    }

    function openResetPasswordDialog(button) {
        const userId = button.dataset.userId;
        const userEmail = button.dataset.userEmail || '';
        const dialog = document.getElementById('resetPasswordDialog');
        const form = document.getElementById('resetPasswordForm');
        const emailTarget = document.getElementById('resetPasswordEmail');
        const input = document.getElementById('reset-password-input');

        if (!dialog || !form || !emailTarget || !input || !userId) {
            return;
        }

        form.action = `/admin/users/${userId}/reset-password`;
        form.setAttribute('hx-post', `/admin/users/${userId}/reset-password`);
        emailTarget.textContent = userEmail;
        input.value = '';
        dialog.showModal();
    }

    function openDeleteUserDialog(button) {
        const userId = button.dataset.userId;
        const userEmail = button.dataset.userEmail || '';
        const dialog = document.getElementById('deleteUserDialog');
        const form = document.getElementById('deleteUserForm');
        const emailTarget = document.getElementById('deleteUserEmail');

        if (!dialog || !form || !emailTarget || !userId) {
            return;
        }

        form.action = `/admin/users/${userId}/delete`;
        form.setAttribute('hx-post', `/admin/users/${userId}/delete`);
        form.setAttribute('hx-target', `#user-card-${userId}`);
        form.setAttribute('hx-swap', 'outerHTML');
        emailTarget.textContent = userEmail;
        dialog.showModal();
    }

    const adminToastMessages = {
        admin_granted: { message: '{{ _("Admin access granted") }}', variant: 'success' },
        admin_revoked: { message: '{{ _("Admin access revoked") }}', variant: 'success' },
        user_activated: { message: '{{ _("User activated") }}', variant: 'success' },
        user_deactivated: { message: '{{ _("User disabled") }}', variant: 'success' },
        user_deleted: { message: '{{ _("User deleted") }}', variant: 'success' },
        user_created: { message: '{{ _("User created") }}', variant: 'success' },
        user_updated: { message: '{{ _("Profile updated") }}', variant: 'success' },
        password_reset: { message: '{{ _("Password reset") }}', variant: 'success' },
        user_not_found: { message: '{{ _("User not found") }}', variant: 'error' },
        upload_failed: { message: '{{ _("Upload failed") }}', variant: 'error' },
        cannot_remove_last_admin: { message: '{{ _("Cannot remove the last admin") }}', variant: 'error' },
        cannot_remove_own_admin: { message: '{{ _("Cannot remove your own admin access") }}', variant: 'error' },
        cannot_deactivate_self: { message: '{{ _("You cannot disable your own account") }}', variant: 'error' },
        cannot_delete_self: { message: '{{ _("You cannot delete your own account") }}', variant: 'error' },
        cannot_delete_last_admin: { message: '{{ _("Cannot delete the last admin") }}', variant: 'error' },
        email_empty: { message: '{{ _("Email cannot be empty") }}', variant: 'error' },
        password_empty: { message: '{{ _("Password cannot be empty") }}', variant: 'error' },
        email_exists: { message: '{{ _("Email already registered") }}', variant: 'error' },
    };

    document.addEventListener('DOMContentLoaded', () => {
        const toastKey = new URLSearchParams(window.location.search).get('toast');
        if (toastKey && adminToastMessages[toastKey]) {
            const toast = adminToastMessages[toastKey];
            window.showToast?.(toast.message, toast.variant);
            const url = new URL(window.location.href);
            url.searchParams.delete('toast');
            window.history.replaceState({}, '', url.toString());
        }
    });
