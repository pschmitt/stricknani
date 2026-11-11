package blue.anika.wolle.ui.trash

import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef

/**
 * What a project save moved to the trash: the steps that were there when the form opened and are
 * gone now (the server trashes steps a project update leaves out), then the images removed in the
 * form. Steps come first so that a restored image finds its step again.
 */
fun trashedByProjectSave(
    initialStepIds: List<Int?>,
    currentStepIds: List<Int?>,
    removedImageIds: List<Int>,
): List<TrashRef> {
    val kept = currentStepIds.filterNotNull().toSet()
    val steps = initialStepIds.filterNotNull().distinct().filter { it !in kept }
    return steps.map { TrashRef(TrashKind.Step, it) } +
        removedImageIds.distinct().map { TrashRef(TrashKind.Image, it) }
}

/** What a yarn save moved to the trash: the photos removed in the form. */
fun trashedByYarnSave(removedPhotoIds: List<Int>): List<TrashRef> =
    removedPhotoIds.distinct().map { TrashRef(TrashKind.Photo, it) }
