package blue.anika.wolle.data.imports

import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ImportedYarnPreview
import blue.anika.wolle.data.repository.ProjectImporter
import blue.anika.wolle.data.repository.YarnImporter

/**
 * The importer the screens use: it runs the import in the background ([BackgroundImports]) and
 * waits for it, or picks up the result of the same link that already finished. A failure is raised
 * as the network importer raised it, so the dialogs classify it as before.
 */
class BackgroundProjectImporter(private val imports: BackgroundImports) : ProjectImporter {
    override suspend fun importFromUrl(url: String): ImportedProjectPreview =
        when (val result = imports.run(ImportKind.Project, url)) {
            is PendingImport.ProjectReady -> result.preview
            is PendingImport.Failed -> throw result.toThrowable()
            else -> throw IllegalStateException("unexpected import result")
        }
}

class BackgroundYarnImporter(private val imports: BackgroundImports) : YarnImporter {
    override suspend fun importFromUrl(url: String): ImportedYarnPreview =
        when (val result = imports.run(ImportKind.Yarn, url)) {
            is PendingImport.YarnReady -> result.preview
            is PendingImport.Failed -> throw result.toThrowable()
            else -> throw IllegalStateException("unexpected import result")
        }
}
