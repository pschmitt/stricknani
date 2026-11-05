package blue.anika.wolle.data.api.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit

/** Exercises typed media-order responses. Set `instrumentationBuildType=release` to verify R8. */
@RunWith(AndroidJUnit4::class)
class MediaOrderReleaseTest {
    @Test
    fun mediaOrderEndpointsDecodeTheirTypedEnvelope() = runBlocking {
        val retrofit =
            Retrofit.Builder()
                .baseUrl("https://serialization-test.invalid/")
                .client(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(RESPONSE.toResponseBody(JSON_MEDIA_TYPE))
                                .build()
                        }
                        .build()
                )
                .addConverterFactory(JSON.asConverterFactory(JSON_MEDIA_TYPE))
                .build()

        val expectedIds = listOf(17, 23, 42)
        val projects = retrofit.create(ProjectsV2Api::class.java)
        val yarns = retrofit.create(YarnsV2Api::class.java)

        assertEquals(
            expectedIds,
            projects.reorderTitleImages(1, MediaOrderRequest(expectedIds)).data.ids,
        )
        assertEquals(
            expectedIds,
            projects.reorderStepImages(1, 2, MediaOrderRequest(expectedIds)).data.ids,
        )
        assertEquals(
            expectedIds,
            yarns.reorderPhotos(1, MediaOrderRequest(expectedIds)).data.ids,
        )
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val RESPONSE = """{"data":{"ids":[17,23,42],"updated_at":"2026-01-02T03:04:05Z"}}"""
    }
}
