package blue.anika.wolle.data.api

/**
 * Whether the app offers to import a project at all: it talks to a server (not local-only mode)
 * that takes a link, or is a v2 server (file and pasted text imports need no link import, as on the
 * web).
 */
fun ApiCapabilitiesState.offersProjectImport(localMode: Boolean): Boolean =
    !localMode && (importAvailability().url || generation == ApiGeneration.V2)

/** Whether the app offers to fill a yarn in from a shop or Ravelry link. */
fun ApiCapabilitiesState.offersYarnImport(localMode: Boolean): Boolean =
    !localMode && importAvailability().url
