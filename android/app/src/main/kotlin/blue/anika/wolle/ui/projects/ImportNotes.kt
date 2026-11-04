package blue.anika.wolle.ui.projects

import android.content.Context
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.ImportDetail
import blue.anika.wolle.data.repository.YarnImportNote
import blue.anika.wolle.data.repository.YarnImportNoteKind

/**
 * Appends the imported facts that have no field of their own to [existing] as `Label: value` lines.
 */
internal fun projectImportNotes(
    context: Context,
    existing: String?,
    details: List<ImportDetail>,
): String? {
    val lines = details.map {
        context.getString(
            R.string.import_detail_line,
            context.getString(importDetailLabel(it.kind)),
            it.value,
        )
    }
    return (listOfNotNull(existing?.takeIf { it.isNotBlank() }) + lines)
        .joinToString("\n")
        .ifBlank { null }
}

internal fun yarnImportNotes(context: Context, notes: List<YarnImportNote>): String? =
    notes
        .map {
            context.getString(
                R.string.import_detail_line,
                context.getString(
                    when (it.kind) {
                        YarnImportNoteKind.Gauge -> R.string.import_note_gauge
                        YarnImportNoteKind.Care -> R.string.import_note_care
                        YarnImportNoteKind.Price -> R.string.import_note_price
                    }
                ),
                it.value,
            )
        }
        .joinToString("\n")
        .ifBlank { null }
