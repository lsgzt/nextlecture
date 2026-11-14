package com.gndec.timetable.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One Lost & Found post, as stored in the Supabase `lost_found` table. */
@Serializable
data class LostFoundItem(
    val id: String,
    val kind: String,
    val title: String,
    val description: String = "",
    val image_path: String? = null,
    val created_at: String
) {
    companion object {
        const val KIND_LOST = "lost"
        const val KIND_FOUND = "found"
    }
}

/**
 * Thin Supabase REST client (PostgREST + Storage) for the campus Lost & Found.
 *
 * Everything runs with the project's PUBLISHABLE key only — the equivalent
 * of the legacy anon key: Row Level Security on `public.lost_found` allows
 * public reads and shape-constrained anonymous inserts, and the storage
 * bucket `lostfound` is public with an anonymous upload policy. No secret
 * key ever ships inside the app.
 *
 * Bandwidth policy: every photo is uploaded twice — a full image
 * (<=1280px, JPEG q72) and a `_t` thumbnail (<=480px, JPEG q60). List
 * scrolling only ever downloads the thumbnail, which is typically 20–30%
 * the size of the full image; the full image is fetched when the student
 * opens a post.
 */
class LostFoundClient(
    private val client: OkHttpClient = Net.client
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** New posts, newest first, optionally only one tab. */
    suspend fun list(kind: String? = null, limit: Int = LIST_LIMIT): List<LostFoundItem> =
        withContext(Dispatchers.IO) {
            val filter = if (kind == null) "" else "&kind=eq.$kind"
            val request = builder("$REST_URL?select=*&order=created_at.desc&limit=$limit$filter")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "Lost & Found feed unavailable")
                }
                val body = response.body?.string().orEmpty()
                json.decodeFromString<List<LostFoundItem>>(body)
            }
        }

    /**
     * Inserts one post. [imagePath] must already have been uploaded via
     * [uploadImage]; rows cannot be updated anonymously, so the image goes
     * up first and the row last.
     */
    suspend fun insert(
        kind: String,
        title: String,
        description: String,
        imagePath: String?
    ): LostFoundItem = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("kind", kind)
            put("title", title)
            put("description", description)
            if (imagePath != null) put("image_path", imagePath)
        }
        val request = builder(REST_URL)
            .header("Prefer", "return=representation")
            .post(payload.toString().toRequestBody(Net.JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpException(response.code, friendlyInsertError(response.code, body))
            }
            val rows = json.decodeFromString<List<LostFoundItem>>(body)
            rows.firstOrNull() ?: throw HttpException(response.code, "Empty insert response")
        }
    }

    /** Uploads one JPEG under [path] inside the public `lostfound` bucket. */
    suspend fun uploadImage(path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val request = builder("$STORAGE_OBJECT_URL/$path")
            .header("Content-Type", "image/jpeg")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(JPEG_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw HttpException(response.code, "Image upload failed: ${body.take(160)}")
            }
        }
    }

    private fun builder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $PUBLISHABLE_KEY")

    private fun friendlyInsertError(code: Int, body: String): String = when {
        code == 403 && body.contains("row-level-security", ignoreCase = true) ->
            "The post was rejected by the posting rules"
        code == 413 -> "The post is too large"
        else -> "Could not post ($code)"
    }

    companion object {
        const val PROJECT_URL = "https://kitemjxgqisxpefsqjlc.supabase.co"
        const val BUCKET = "lostfound"

        /**
         * Publishable API key — the public client key of this Supabase project,
         * safe to embed in the app exactly like a Firebase web config. It grants
         * only what Row Level Security grants: read + constrained insert.
         */
        const val PUBLISHABLE_KEY = "sb_publishable_JcLDNq6fDbrYRhc5SMwYcg_P-HariqH"

        private const val REST_URL = "$PROJECT_URL/rest/v1/lost_found"
        private const val STORAGE_OBJECT_URL = "$PROJECT_URL/storage/v1/object/$BUCKET"
        private val JPEG_MEDIA = "image/jpeg".toMediaType()

        /** Newest ~100 entries is plenty for a campus noticeboard. */
        const val LIST_LIMIT = 100

        /** Download URL of a stored image (public bucket, no auth headers). */
        fun imageUrl(path: String): String = "$PROJECT_URL/storage/v1/object/public/$BUCKET/$path"

        /**
         * Path of the small variant of a stored image: 'found/x.jpg' ->
         * 'found/x_t.jpg'. Both are uploaded together, so the derivation
         * only ever formats a name the uploader itself produced.
         */
        fun thumbPath(path: String): String {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return "$dir/${name}_t"
            return "$dir/${name.substring(0, dot)}_t${name.substring(dot)}"
        }

        /** Download URL of the small (list) variant of a stored image. */
        fun thumbUrl(path: String): String = imageUrl(thumbPath(path))
    }
}
