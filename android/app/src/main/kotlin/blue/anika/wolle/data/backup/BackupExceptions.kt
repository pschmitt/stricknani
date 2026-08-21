package blue.anika.wolle.data.backup

/** The backup file is password-protected; retry [BackupManager.import] with a password. */
class BackupPasswordRequiredException : Exception()

/** Bad magic header, wrong password (AEAD tag mismatch), or a truncated/corrupted file. */
class BackupDecryptionException(message: String) : Exception(message)
