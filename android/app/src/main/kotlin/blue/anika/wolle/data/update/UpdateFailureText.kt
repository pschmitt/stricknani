package blue.anika.wolle.data.update

import androidx.annotation.StringRes
import blue.anika.wolle.R

@StringRes
fun UpdateFailure.messageRes(): Int =
    when (this) {
        UpdateFailure.NO_APK -> R.string.update_failure_no_apk
        UpdateFailure.NOT_TRUSTED -> R.string.update_failure_not_trusted
        UpdateFailure.DOWNLOAD_FAILED -> R.string.update_failure_download
        UpdateFailure.INSUFFICIENT_STORAGE -> R.string.update_failure_storage
        UpdateFailure.CHECKSUM_UNAVAILABLE -> R.string.update_failure_checksum_unavailable
        UpdateFailure.CHECKSUM_MISMATCH -> R.string.update_failure_checksum_mismatch
        UpdateFailure.UNREADABLE_APK -> R.string.update_failure_unreadable
        UpdateFailure.WRONG_PACKAGE -> R.string.update_failure_wrong_package
        UpdateFailure.WRONG_VERSION -> R.string.update_failure_wrong_version
        UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_failure_signature
        UpdateFailure.DOWNGRADE -> R.string.update_failure_downgrade
        UpdateFailure.PERMISSION_NEEDED -> R.string.update_failure_permission
        UpdateFailure.INSTALL_FAILED -> R.string.update_failure_install
        UpdateFailure.INSTALL_CANCELLED -> R.string.update_failure_cancelled
    }
