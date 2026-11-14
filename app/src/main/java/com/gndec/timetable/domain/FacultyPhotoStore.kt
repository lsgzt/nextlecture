package com.gndec.timetable.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gndec.timetable.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Loads faculty profile photos from the college website.
 *
 * The bundled directory carries each teacher's absolute `image_url` (scraped
 * from their profile page — the site exposes photos like
 * `https://gndec.ac.in/images/drhkgrewal%20pic.jpg`, typically ~1000px JPEGs,
 * big enough to zoom into). Photos stream through a small disk cache under
 * `cacheDir/faculty_photos` and an in-memory LruCache, so a photo loads over
 * the network exactly once and then renders instantly from disk while the
 * list scrolls.
 *
 * Decode is bounds-first with `inSampleSize`, so a 1200×900 source renders as
 * a ~256px thumbnail in rows without ever allocating the full bitmap. The
 * zoomable viewer asks for the larger [MAX_DETAIL_DIM] decode.
 */
class FacultyPhotoStore(
    context: Context,
    private val client: OkHttpClient = Net.client
) {
    companion object {
        /** Row thumbnails never need more than this on the longest side. */
        const val THUMB_MAX_DIM = 256

        /** The fullscreen zoomable viewer's decode budget (longest side). */
        const val MAX_DETAIL_DIM = 2048

        /** Cached photos older than this are refreshed from the network. */
        private const val DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    private val diskDir = File(context.cacheDir, "faculty_photos").apply { mkdirs() }

    /** Keyed by "url|maxDim" so the thumbnail and the full decode coexist. */
    private val memory = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** One in-flight load per key: rows in a scrolling list share the request. */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    /** Photo bitmap for [url] at up to [maxDim] on the longest side, or null. */
    suspend fun load(url: String, maxDim: Int = THUMB_MAX_DIM): Bitmap? {
        if (url.isBlank()) return null
        val key = "$url|$maxDim"
        memory.get(key)?.let { return it }
        val mutex = inFlightGuard.withLock {
            inFlight.getOrPut(key) { Mutex() }
        }
        // Fast path for whoever already holds the lock: they are loading it.
        if (!mutex.tryLock()) {
            mutex.lock()
            mutex.unlock()
            memory.get(key)?.let { return it }
            return diskBitmap(url, maxDim)?.also { memory.put(key, it) }
        }
        try {
            val cached = diskBitmap(url, maxDim)
            if (cached != null) {
                memory.put(key, cached)
                return cached
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.isSuccessful) response.body?.bytes() else null
                    }
                }.getOrNull()
            } ?: return null
            val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxDim) } ?: return null
            withContext(Dispatchers.IO) {
                runCatching { diskFile(url).writeBytes(bytes) }
            }
            memory.put(key, bitmap)
            return bitmap
        } finally {
            mutex.unlock()
            inFlightGuard.withLock { if (inFlight[key] == mutex) inFlight.remove(key) }
        }
    }

    private fun diskFile(url: String): File = File(diskDir, sha256(url) + ".img")

    private fun diskBitmap(url: String, maxDim: Int): Bitmap? {
        val file = diskFile(url)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > DISK_TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        return runCatching { decode(file.readBytes(), maxDim) }.getOrNull()
    }

    /** Bounds-first decode with power-of-two sampling down to [maxDim]. */
    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxDim) {
            sample *= 2
            longest /= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
