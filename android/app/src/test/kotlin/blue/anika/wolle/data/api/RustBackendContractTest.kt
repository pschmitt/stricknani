package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.media.resolveMediaUrl
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Opt-in integration contract against an isolated Rust candidate. The fixture user must be seeded
 * by the runner; all rows and media created here are deleted before the test finishes. No
 * credentials or tokens are included in assertions or logged.
 */
class RustBackendContractTest {
    @Test
    fun appApisRoundTripRustV1AndProtectUploadedMedia() =
        runBlocking<Unit> {
            val serverUrl = System.getenv("STRICKNANI_RUST_TEST_URL")?.trim()?.trimEnd('/')
            val email = System.getenv("STRICKNANI_RUST_TEST_EMAIL")
            val password = System.getenv("STRICKNANI_RUST_TEST_PASSWORD")
            assumeTrue(
                "Rust integration fixture environment is not configured",
                !serverUrl.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank(),
            )

            val baseUrl = "$serverUrl/"
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            }
            val unauthenticatedClient = OkHttpClient()
            val pat = mintToken(unauthenticatedClient, json, serverUrl!!, email!!, password!!)
            val authenticatedClient =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("Authorization", "Bearer $pat")
                                .build()
                        )
                    }
                    .build()
            val retrofit =
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(authenticatedClient)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            val categories = retrofit.create(CategoriesApi::class.java)
            val yarns = retrofit.create(YarnsApi::class.java)
            val projects = retrofit.create(ProjectsApi::class.java)
            val sync = retrofit.create(SyncApi::class.java)

            var categoryId: Int? = null
            var yarnId: Int? = null
            var projectId: Int? = null
            var uploadedImageId: Int? = null
            var uploadedAttachmentId: Int? = null
            var uploadedPhotoId: Int? = null
            try {
                val category =
                    categories.createCategory(CategoryCreateRequest("Rust contract category"))
                categoryId = category.id
                assertEquals("Rust contract category", category.name)
                val renamedCategory =
                    categories.updateCategory(
                        category.id,
                        CategoryUpdateRequest("Rust contract renamed"),
                    )
                assertEquals("Rust contract renamed", renamedCategory.name)

                val createdYarn =
                    yarns.createYarn(
                        YarnWriteRequest(
                            name = "Rust contract yarn",
                            brand = "Contract fixture",
                            colorway = "Blue",
                        )
                    )
                yarnId = createdYarn.id
                assertEquals("Rust contract yarn", createdYarn.name)
                val favoriteYarn = yarns.favoriteYarn(createdYarn.id)
                assertTrue(favoriteYarn.isFavorite)
                val updatedYarn =
                    yarns.updateYarn(
                        createdYarn.id,
                        YarnWriteRequest(
                            name = "Rust contract yarn updated",
                            brand = "Contract fixture",
                            colorway = "Blue",
                            expectedUpdatedAt = favoriteYarn.updatedAt,
                        ),
                    )
                assertEquals("Rust contract yarn updated", updatedYarn.name)
                assertTrue(yarns.listYarns(favorite = true).items.any { it.id == createdYarn.id })
                assertFalse(yarns.unfavoriteYarn(createdYarn.id).isFavorite)

                val createdProject =
                    projects.createProject(
                        ProjectWriteRequest(
                            name = "Rust contract project",
                            category = renamedCategory.name,
                            yarnIds = listOf(createdYarn.id),
                            steps =
                                listOf(StepWriteRequest(title = "Cast on", description = "CO 24")),
                        )
                    )
                projectId = createdProject.id
                assertEquals(renamedCategory.name, createdProject.category)
                assertEquals(listOf(createdYarn.id), createdProject.yarnIds)
                assertEquals("Cast on", createdProject.steps.single().title)
                val favoriteProject = projects.favoriteProject(createdProject.id)
                assertTrue(favoriteProject.isFavorite)
                val updatedProject =
                    projects.updateProject(
                        createdProject.id,
                        ProjectWriteRequest(
                            name = "Rust contract project updated",
                            category = renamedCategory.name,
                            yarnIds = listOf(createdYarn.id),
                            steps =
                                listOf(
                                    StepWriteRequest(
                                        id = createdProject.steps.single().id,
                                        title = "Cast on and knit",
                                        description = "CO 24; knit 2 rows",
                                    )
                                ),
                            expectedUpdatedAt = favoriteProject.updatedAt,
                        ),
                    )
                assertEquals("Rust contract project updated", updatedProject.name)
                assertEquals("Cast on and knit", updatedProject.steps.single().title)
                assertTrue(
                    projects.listProjects(favorite = true).items.any { it.id == createdProject.id }
                )
                assertFalse(projects.unfavoriteProject(createdProject.id).isFavorite)
                assertEquals(updatedProject.id, projects.getProject(createdProject.id).id)

                val categorySync = sync.syncCategories()
                assertTrue(categorySync.updated.any { it.id == category.id })
                assertTrue(sync.syncProjects().updated.any { it.id == createdProject.id })
                assertTrue(sync.syncYarns().updated.any { it.id == createdYarn.id })

                val image = projects.uploadTitleImage(createdProject.id, imagePart("title.png"))
                uploadedImageId = image.id
                assertTrue(image.isTitleImage)
                assertMediaRequiresBearer(
                    authenticatedClient,
                    unauthenticatedClient,
                    baseUrl,
                    image.url,
                )

                val attachment =
                    projects.uploadAttachment(createdProject.id, imagePart("notes.png"))
                uploadedAttachmentId = attachment.id
                assertTrue(attachment.sizeBytes > 0)
                assertMediaRequiresBearer(
                    authenticatedClient,
                    unauthenticatedClient,
                    baseUrl,
                    attachment.url,
                )

                val photo = yarns.uploadPhoto(createdYarn.id, imagePart("yarn.png"))
                uploadedPhotoId = photo.id
                assertTrue(yarns.getYarn(createdYarn.id).photos.any { it.id == photo.id })
                assertMediaRequiresBearer(
                    authenticatedClient,
                    unauthenticatedClient,
                    baseUrl,
                    photo.url,
                )
            } finally {
                projectId?.let { id ->
                    uploadedImageId?.let { imageId ->
                        runCatching { projects.deleteImage(id, imageId) }
                    }
                    uploadedAttachmentId?.let { attachmentId ->
                        runCatching { projects.deleteAttachment(id, attachmentId) }
                    }
                    runCatching { projects.deleteProject(id) }
                }
                yarnId?.let { id ->
                    uploadedPhotoId?.let { photoId ->
                        runCatching { yarns.deletePhoto(id, photoId) }
                    }
                    runCatching { yarns.deleteYarn(id) }
                }
                categoryId?.let { id -> runCatching { categories.deleteCategory(id) } }
            }
        }

    private fun mintToken(
        client: OkHttpClient,
        json: Json,
        serverUrl: String,
        email: String,
        password: String,
    ): String {
        val payload =
            json.encodeToString(
                PasswordLoginRequest.serializer(),
                PasswordLoginRequest(email, password),
            )
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$serverUrl/api/v1/auth/token").post(body).build()
        client.newCall(request).execute().use { response ->
            assertEquals("Rust token login failed with HTTP ${response.code}", 200, response.code)
            val responseJson = json.parseToJsonElement(response.body!!.string()).jsonObject
            val token = responseJson["token"]?.jsonPrimitive?.content
            assertNotNull("Rust token response omitted token", token)
            assertTrue("Rust returned an unexpected PAT format", token!!.startsWith("sna_"))
            return token
        }
    }

    private fun imagePart(filename: String): MultipartBody.Part {
        // A tiny valid PNG exercises the real decoder, thumbnailer, and filesystem path.
        val png =
            Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGMUSTnBwMDAxMDAwMDAAAAOdgFEnYDRzwAAAABJRU5ErkJggg=="
                )
        return MultipartBody.Part.createFormData(
            "file",
            filename,
            png.toRequestBody("image/png".toMediaType()),
        )
    }

    private fun assertMediaRequiresBearer(
        authenticatedClient: OkHttpClient,
        unauthenticatedClient: OkHttpClient,
        baseUrl: String,
        mediaPath: String,
    ) {
        val mediaUrl = resolveMediaUrl(baseUrl, mediaPath)
        assertNotNull("Rust returned an invalid media URL", mediaUrl)
        val request = Request.Builder().url(mediaUrl!!).get().build()
        unauthenticatedClient.newCall(request).execute().use { response ->
            assertEquals("Rust media was readable without a PAT", 401, response.code)
        }
        authenticatedClient.newCall(request).execute().use { response ->
            assertEquals("Rust rejected media access with a PAT", 200, response.code)
            assertTrue("Rust returned an empty media body", response.body!!.bytes().isNotEmpty())
        }
    }

    @Serializable
    private data class PasswordLoginRequest(
        val email: String,
        val password: String,
        @SerialName("token_name") val tokenName: String = "Android Rust contract test",
    )
}
