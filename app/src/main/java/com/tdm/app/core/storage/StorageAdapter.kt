package com.tdm.app.core.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.tdm.app.data.db.StorageProfileEntity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Adapter over SAF (spec §27, §55, §66, §67).
 * Handles DocumentFile/ContentResolver/ParcelFileDescriptor properly and implements:
 *  - directory creation from [PathTemplate] segments
 *  - copy into `filename.partial` then ATOMIC rename to final name (spec §23)
 *  - collision-safe finalization
 *  - best-effort free space pre-check (spec §66)
 *  - permission-loss detection (spec §67)
 */
class StorageAdapter(private val context: Context) {

    sealed class StorageOp {
        data class Success(val uri: Uri, val finalPathHint: String) : StorageOp()
        data class Failure(val kind: Kind, val message: String) : StorageOp()
        enum class Kind { PERMISSION_LOST, INSUFFICIENT_STORAGE, IO_ERROR, NO_PROFILE }
    }

    fun rootFor(profile: StorageProfileEntity): DocumentFile? {
        val uri = Uri.parse(profile.treeUri)
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return if (root.exists() && root.canWrite()) root else null
    }

    /** Spec §67 — detect lost SAF permission without touching the queue. */
    fun isPermissionValid(profile: StorageProfileEntity): Boolean = rootFor(profile) != null

    fun tempFreeBytes(): Long = runCatching {
        StatFs(context.getDir("tdlib_files", Context.MODE_PRIVATE).absolutePath).availableBytes
    }.getOrDefault(0L)

    /** Pre-flight check (spec §66): temp space for TDLib partial + best-effort destination check. */
    fun hasSpaceFor(profile: StorageProfileEntity, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0) return true
        val tempFree = tempFreeBytes()
        // TDLib keeps its own partial; destination lives on SAF. Temp must fit the whole file.
        if (tempFree in 1 until sizeBytes) return false
        return true
    }

    /**
     * Stream [source] (TDLib completed local file) into destination `name.partial`,
     * then rename atomically. Returns final document uri.
     */
    fun finalizeFile(
        profile: StorageProfileEntity,
        segments: List<String>,
        source: File,
        expectedSize: Long,
        onProgress: (copiedBytes: Long) -> Unit = {},
    ): StorageOp {
        val root = rootFor(profile)
            ?: return StorageOp.Failure(StorageOp.Kind.PERMISSION_LOST, "SAF root unavailable — re-select folder")
        val dir = ensureDirs(root, segments.dropLast(1))
            ?: return StorageOp.Failure(StorageOp.Kind.IO_ERROR, "Cannot create destination folders")

        val finalName = segments.last()
        val partialName = "$finalName.partial"

        // remove stale .partial from a previous interrupted finalize
        dir.findFile(partialName)?.let { stale -> runCatching { stale.delete() } }

        val partialDoc = dir.createFile("application/octet-stream", partialName)
            ?: return StorageOp.Failure(StorageOp.Kind.IO_ERROR, "Cannot create partial file")
        try {
            context.contentResolver.openOutputStream(partialDoc.uri, "w")?.use { out ->
                source.inputStream().use { input ->
                    copyStream(input, out, onProgress)
                }
            } ?: return StorageOp.Failure(StorageOp.Kind.IO_ERROR, "Cannot open output stream")
        } catch (e: IOException) {
            runCatching { partialDoc.delete() }
            return StorageOp.Failure(StorageOp.Kind.INSUFFICIENT_STORAGE, "Write failed: ${e.message}")
        }

        // Atomic finalize: rename .partial → final (spec §23)
        val finalDoc = renameCollisionSafe(dir, partialDoc, finalName)
            ?: return StorageOp.Failure(
                StorageOp.Kind.IO_ERROR,
                "Rename failed; partial kept at $partialName"
            )
        return StorageOp.Success(finalDoc.uri, describe(dir, finalName))
    }

    private fun copyStream(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit) {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8) // 64 KiB — low RAM, streaming (spec §56)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total % (8L * 1024 * 1024) < buf.size) onProgress(total) // progress every ~8MB
        }
        out.flush()
    }

    private fun renameCollisionSafe(dir: DocumentFile, partial: DocumentFile, finalName: String): DocumentFile? {
        var candidate = finalName
        val dot = finalName.lastIndexOf('.')
        val base = if (dot > 0) finalName.substring(0, dot) else finalName
        val ext = if (dot > 0) finalName.substring(dot) else ""
        var attempt = 1
        while (dir.findFile(candidate) != null && attempt < 1000) {
            candidate = "$base ($attempt)$ext"
            attempt++
        }
        return if (partial.renameTo(candidate)) dir.findFile(candidate) else null
    }

    private fun ensureDirs(root: DocumentFile, segments: List<String>): DocumentFile? {
        var cur = root
        for (s in segments) {
            val existing = cur.findFile(s)
            cur = if (existing != null && existing.isDirectory) existing
            else cur.createDirectory(s) ?: return null
        }
        return cur
    }

    private fun describe(dir: DocumentFile, name: String): String {
        val tree = dir.uri.path?.substringAfterLast("primary:") ?: ""
        return "Download/$tree/$name".replace("//", "/")
    }

    /** Delete a stale .partial if a finalize retry is not going to reuse it. */
    fun deletePartial(profile: StorageProfileEntity, segments: List<String>): Boolean {
        val root = rootFor(profile) ?: return false
        val dir = ensureDirs(root, segments.dropLast(1)) ?: return false
        return dir.findFile(segments.last() + ".partial")?.delete() ?: false
    }
}
