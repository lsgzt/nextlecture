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
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Loads faculty profile photos from the college website with durable local cache.
 *
 * Strategy (data-saving):
 * - List / avatar rows request [THUMB_MAX_DIM]: a small JPEG is kept under
 *   [filesDir] (survives cache clears) and is what scrolling lists always use.
 * - Fullscreen viewer requests [MAX_DETAIL_DIM]: the original bytes are kept
 *   under [filesDir] as well and only downloaded when the user opens the photo
 *   (or when a missing thumb forces a one-time fetch that also seeds the thumb).
 * - Network is used at most once per URL for the original; after that list and
 *   viewer both hit disk. No bulk prefetch of every teacher on open.
 *
 * Legacy files under cacheDir/faculty_photos are still read once and migrated
 * into the permanent dirs so existing installs do not re-download everything.
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

        /** JPEG quality for permanent list thumbs (~5–20 KB typical). */
        private const val THUMB_JPEG_QUALITY = 80

        /**
         * Full originals stay valid this long before a quiet re-fetch is allowed.
         * Thumbs have no expiry — they are cheap and stable enough for icons.
         */
        private const val FULL_TTL_MS = 180L * 24 * 60 * 60 * 1000 // 180 days
    }

    /** Permanent small avatars — not under cacheDir so the OS will not wipe them. */
    private val thumbDir = File(context.filesDir, "faculty_photo_thumbs").apply { mkdirs() }

    /** Permanent full originals for the zoom viewer. */
    private val fullDir = File(context.filesDir, "faculty_photo_full").apply { mkdirs() }

    /** Pre-v2 cache location; read-only migration source. */
    private val legacyDir = File(context.cacheDir, "faculty_photos")

    /** Keyed by "url|thumb" or "url|full" so both sizes coexist in memory. */
    private val memory = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** One in-flight load per key: rows in a scrolling list share the request. */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightGuard = Mutex()

    /**
     * Photo bitmap for [url] at up to [maxDim] on the longest side, or null.
     * Callers pass [THUMB_MAX_DIM] for list avatars and [MAX_DETAIL_DIM] for
     * the fullscreen viewer.
     */
    suspend fun load(url: String, maxDim: Int = THUMB_MAX_DIM): Bitmap? {
        if (url.isBlank()) return null
        return if (maxDim <= THUMB_MAX_DIM) loadThumb(url) else loadFull(url, maxDim)
    }

    private suspend fun loadThumb(url: String): Bitmap? {
        val key = "$url|thumb"
        memory.get(key)?.let { return it }
        return withInFlight(key) {
            memory.get(key)?.let { return@withInFlight it }

            readThumbFile(url)?.let { bmp ->
                memory.put(key, bmp)
                return@withInFlight bmp
            }

            // Prefer a durable/legacy full file so we never hit the network
            // just to rebuild a missing thumb after a cache clear.
            val fullBytes = readFullBytes(url)
            if (fullBytes != null) {
                val thumb = withContext(Dispatchers.Default) { decode(fullBytes, THUMB_MAX_DIM) }
                    ?: return@withInFlight null
                withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
                memory.put(key, thumb)
                return@withInFlight thumb
            }

            val bytes = download(url) ?: return@withInFlight null
            withContext(Dispatchers.IO) {
                writeFullBytes(url, bytes)
            }
            val thumb = withContext(Dispatchers.Default) { decode(bytes, THUMB_MAX_DIM) }
                ?: return@withInFlight null
            withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
            memory.put(key, thumb)
            thumb
        }
    }

    private suspend fun loadFull(url: String, maxDim: Int): Bitmap? {
        val key = "$url|full"
        memory.get(key)?.let { return it }
        return withInFlight(key) {
            memory.get(key)?.let { return@withInFlight it }

            val cachedBytes = readFullBytes(url)
            if (cachedBytes != null) {
                val bmp = withContext(Dispatchers.Default) { decode(cachedBytes, maxDim) }
                    ?: return@withInFlight null
                // Ensure a list thumb exists so later scrolls stay offline.
                ensureThumbFromBytes(url, cachedBytes)
                memory.put(key, bmp)
                return@withInFlight bmp
            }

            val bytes = download(url) ?: return@withInFlight null
            withContext(Dispatchers.IO) { writeFullBytes(url, bytes) }
            val bmp = withContext(Dispatchers.Default) { decode(bytes, maxDim) }
                ?: return@withInFlight null
            ensureThumbFromBytes(url, bytes)
            memory.put(key, bmp)
            bmp
        }
    }

    private suspend fun ensureThumbFromBytes(url: String, bytes: ByteArray) {
        if (thumbFile(url).isFile) return
        val thumb = withContext(Dispatchers.Default) { decode(bytes, THUMB_MAX_DIM) } ?: return
        withContext(Dispatchers.IO) { writeThumbFile(url, thumb) }
        memory.put("$url|thumb", thumb)
    }

    private suspend fun <T> withInFlight(key: String, block: suspend () -> T): T {
        val mutex = inFlightGuard.withLock {
            inFlight.getOrPut(key) { Mutex() }
        }
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
            inFlightGuard.withLock { if (inFlight[key] == mutex) inFlight.remove(key) }
        }
    }

    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun thumbFile(url: String): File = File(thumbDir, sha256(url) + ".jpg")
    private fun fullFile(url: String): File = File(fullDir, sha256(url) + ".img")
    private fun legacyFile(url: String): File = File(legacyDir, sha256(url) + ".img")

    private fun readThumbFile(url: String): Bitmap? {
        val file = thumbFile(url)
        if (!file.isFile) return null
        return runCatching { decode(file.readBytes(), THUMB_MAX_DIM) }.getOrNull()
    }

    private fun writeThumbFile(url: String, bitmap: Bitmap) {
        runCatching {
            val target = thumbFile(url)
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun readFullBytes(url: String): ByteArray? {
        val primary = fullFile(url)
        if (primary.isFile) {
            if (System.currentTimeMillis() - primary.lastModified() > FULL_TTL_MS) {
                // Soft expiry: still serve the file, but mark for refresh by
                // touching is skipped — next successful download overwrites.
                // Returning bytes keeps the UI offline; a background refresh
                // is intentionally not forced here to avoid surprise data use.
            }
            return runCatching { primary.readBytes() }.getOrNull()
        }
        val legacy = legacyFile(url)
        if (legacy.isFile) {
            val bytes = runCatching { legacy.readBytes() }.getOrNull() ?: return null
            // Migrate off disposable cacheDir so the next OS clean does not
            // force a re-download.
            runCatching { writeFullBytes(url, bytes) }
            return bytes
        }
        return null
    }

    private fun writeFullBytes(url: String, bytes: ByteArray) {
        runCatching {
            val target = fullFile(url)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
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
