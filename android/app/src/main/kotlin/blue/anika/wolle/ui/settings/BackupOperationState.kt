package blue.anika.wolle.ui.settings

/** Result of a manual export/restore (SNA-15), surfaced by [SettingsViewModel.backupState]. */
sealed interface BackupOperationState {
    data object Idle : BackupOperationState

    data object InProgress : BackupOperationState

    data class Success(val message: String) : BackupOperationState

    data class Error(val message: String) : BackupOperationState

    /**
     * The picked file is password-protected; retry via [SettingsViewModel.retryImportWithPassword].
     */
    data object PasswordRequired : BackupOperationState
}
