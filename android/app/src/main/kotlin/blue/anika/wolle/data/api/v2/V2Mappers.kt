package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.ProjectPageDto
import blue.anika.wolle.data.api.dto.StepDto
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnListItemDto
import blue.anika.wolle.data.api.dto.YarnPageDto
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.repository.toEntity
import kotlinx.serialization.json.Json

/*
 * v2 -> the app's current domain. The app's domain types are the v1 DTOs (Room's `detailJson` holds
 * a serialized `ProjectDto`/`YarnDto`), so mapping v2 into them keeps Room, the repositories and
 * the UI unchanged. The `*_html` fields are dropped: the app renders Markdown itself.
 */

fun StepV2Dto.toV1(): StepDto =
    StepDto(id = id, title = title, description = description, stepNumber = stepNumber)

fun ProjectV2Dto.toV1(): ProjectDto =
    ProjectDto(
        id = id,
        name = name,
        category = category,
        yarn = yarn,
        needles = needles,
        stitchSample = stitchSample,
        description = description,
        notes = notes,
        otherMaterials = otherMaterials,
        tags = tags,
        link = link,
        linkArchive = linkArchive,
        isAiEnhanced = isAiEnhanced,
        isFavorite = isFavorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
        yarnIds = yarnIds,
        steps = steps.map { it.toV1() },
        images = images,
        attachments = attachments,
    )

fun YarnV2Dto.toV1(): YarnDto =
    YarnDto(
        id = id,
        name = name,
        description = description,
        brand = brand,
        colorway = colorway,
        dyeLot = dyeLot,
        fiberContent = fiberContent,
        weightCategory = weightCategory,
        recommendedNeedles = recommendedNeedles,
        weightGrams = weightGrams,
        lengthMeters = lengthMeters,
        notes = notes,
        link = link,
        linkArchive = linkArchive,
        isAiEnhanced = isAiEnhanced,
        isFavorite = isFavorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
        projectIds = projectIds,
        photos = photos,
    )

/** `{data, meta}` -> v1's flat `{items, page, per_page, has_more}`. */
fun V2ListEnvelope<ProjectListItemDto>.toV1Page(): ProjectPageDto =
    ProjectPageDto(
        items = data,
        page = meta.page,
        perPage = meta.perPage,
        hasMore = meta.hasMore,
    )

fun V2ListEnvelope<YarnListItemDto>.toV1Page(): YarnPageDto =
    YarnPageDto(items = data, page = meta.page, perPage = meta.perPage, hasMore = meta.hasMore)

/** Room row for a v2 project - same columns and `detailJson` format as the v1 path. */
fun ProjectV2Dto.toRoomEntity(json: Json): ProjectEntity = toV1().toEntity(json)

fun YarnV2Dto.toRoomEntity(json: Json): YarnEntity = toV1().toEntity(json)
