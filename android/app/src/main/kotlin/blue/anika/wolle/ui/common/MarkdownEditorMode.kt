package blue.anika.wolle.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How a Markdown field is edited: as rich text, or as the Markdown source. */
internal enum class MarkdownEditorMode(val stored: String) {
    VISUAL("visual"),
    RAW("raw");

    companion object {
        /** Visual unless the user chose Raw, as on the web. */
        fun fromStored(value: String?): MarkdownEditorMode =
            entries.firstOrNull { it.stored == value } ?: VISUAL
    }
}

/**
 * The remembered Visual / Raw choice, shared by every Markdown field so that all editors on a
 * screen switch together (the web editors do the same). Kept in a small preferences file; losing it
 * only means the default (Visual) comes back.
 */
internal object MarkdownEditorModeStore {
    private const val FILE = "markdown_editor"
    private const val KEY = "mode"

    private var mode by mutableStateOf(MarkdownEditorMode.VISUAL)
    private var loaded = false

    /** The current choice; reads the stored value once. */
    fun current(context: Context): MarkdownEditorMode {
        if (!loaded) {
            loaded = true
            mode =
                runCatching {
                        MarkdownEditorMode.fromStored(
                            context.applicationContext
                                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                                .getString(KEY, null)
                        )
                    }
                    .getOrDefault(MarkdownEditorMode.VISUAL)
        }
        return mode
    }

    fun set(context: Context, next: MarkdownEditorMode) {
        loaded = true
        mode = next
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, next.stored)
                .apply()
        }
    }
}
