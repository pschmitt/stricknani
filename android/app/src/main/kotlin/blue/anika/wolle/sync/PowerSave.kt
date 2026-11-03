package blue.anika.wolle.sync

import android.content.Context
import android.os.PowerManager

/** Whether the system battery saver is on. */
fun Context.isPowerSaveMode(): Boolean =
    getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

/**
 * Background (periodic) sync and replay sit out while the battery saver is on; anything the user
 * triggers, and the replay right after a local edit, still runs. Queued edits stay queued and go
 * out on the next foreground sync or once the saver is off.
 */
fun shouldSkipBackgroundWork(background: Boolean, powerSaveMode: Boolean): Boolean =
    background && powerSaveMode
