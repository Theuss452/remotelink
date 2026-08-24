package app.remotelink.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class IncomingFileReceiver(private val context: Context) {
    data class SavedFile(val name: String, val location: String, val size: Long)

    private data class ActiveTransfer(
        val id: String,
        val name: String,
        val mime: String,
        val expectedSize: Long,
        val tempFile: File,
        val output: BufferedOutputStream,
        var received: Long = 0L
    )

    private var active: ActiveTransfer? = null

    @Synchronized
    fun start(id: String, rawName: String, rawMime: String, size: Long): String? {
        if (active != null) return "transfer_busy"
        if (!ID_REGEX.matches(id)) return "invalid_id"
        if (size !in 1..MAX_FILE_BYTES) return "invalid_size"

        val name = sanitizeName(rawName) ?: return "invalid_name"
        val mime = sanitizeMime(rawMime)
        val temp = File.createTempFile("remotelink-", ".part", context.cacheDir)
        val stream = BufferedOutputStream(FileOutputStream(temp), 64 * 1024)
        active = ActiveTransfer(id, name, mime, size, temp, stream)
        return null
    }

    @Synchronized
    fun append(bytes: ByteArray): String? {
        val transfer = active ?: return "no_transfer"
        if (bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES) return "invalid_chunk"
        val next = transfer.received + bytes.size
        if (next > transfer.expectedSize) return "size_overflow"
        transfer.output.write(bytes)
        transfer.received = next
        return null
    }

    @Synchronized
    fun progress(): Pair<Long, Long>? = active?.let { it.received to it.expectedSize }

    @Synchronized
    fun finish(id: String): Result<SavedFile> {
        val transfer = active ?: return Result.failure(IllegalStateException("no_transfer"))
        if (transfer.id != id) return Result.failure(IllegalArgumentException("id_mismatch"))
        if (transfer.received != transfer.expectedSize) {
            return Result.failure(IllegalStateException("size_mismatch"))
        }

        active = null
        return try {
            transfer.output.flush()
            transfer.output.close()
            val saved = persist(transfer)
            transfer.tempFile.delete()
            Result.success(saved)
        } catch (e: Exception) {
            try { transfer.output.close() } catch (_: Exception) {}
            transfer.tempFile.delete()
            Result.failure(e)
        }
    }

    @Synchronized
    fun cancel() {
        val transfer = active ?: return
        active = null
        try { transfer.output.close() } catch (_: Exception) {}
        transfer.tempFile.delete()
    }

    private fun persist(transfer: ActiveTransfer): SavedFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            persistToDownloads(transfer)
        } else {
            persistToAppDownloads(transfer)
        }
    }

    private fun persistToDownloads(transfer: ActiveTransfer): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, transfer.name)
            put(MediaStore.Downloads.MIME_TYPE, transfer.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemoteLink")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("media_store_insert_failed")

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "media_store_open_failed" }
                transfer.tempFile.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
            }
            val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            return SavedFile(transfer.name, uri.toString(), transfer.expectedSize)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    private fun persistToAppDownloads(transfer: ActiveTransfer): SavedFile {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val dir = File(base, "RemoteLink").apply { mkdirs() }
        val output = uniqueFile(dir, transfer.name)
        transfer.tempFile.inputStream().buffered().use { input ->
            FileOutputStream(output).buffered().use { destination -> input.copyTo(destination, 64 * 1024) }
        }
        return SavedFile(output.name, output.absolutePath, transfer.expectedSize)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "$stem-${System.currentTimeMillis()}$ext")
    }

    private fun sanitizeName(raw: String): String? {
        val clean = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(128)
        if (clean.isBlank() || clean == "." || clean == "..") return null
        return clean
    }

    private fun sanitizeMime(raw: String): String {
        val value = raw.trim().lowercase().take(120)
        return if (MIME_REGEX.matches(value)) value else "application/octet-stream"
    }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1024L * 1024L
        const val MAX_CHUNK_BYTES = 64 * 1024
        private val ID_REGEX = Regex("[A-Za-z0-9_-]{8,80}")
        private val MIME_REGEX = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    }
}
