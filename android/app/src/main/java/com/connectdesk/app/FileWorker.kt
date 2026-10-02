package com.connectdesk.app

import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device-side file support.
 *
 *  - [scanAndSync] walks shared storage and pushes a flat index (path, name,
 *    kind, size, mime, modified) so the dashboard can browse it. Paths are
 *    relative to external storage, never absolute — the dashboard never sees
 *    the device's private directories.
 *  - [sendFile] streams one file back in base64 chunks when the dashboard asks
 *    for it, so downloads start without opening the app.
 *
 * Consent: both paths run only when the dashboard has enabled the `files`
 * capability, which the server also re-checks on every chunk.
 */
object FileWorker {

    private const val CHUNK = 300_000
    private const val MAX_ENTRIES = 2000
    private const val MAX_DEPTH = 8

    /** Folders worth indexing — app-private data stays on the phone. */
    private val interestingRoots = listOf(
        "Download",
        "Documents",
        "DCIM",
        "Pictures",
        "Movies",
        "Music",
        "Podcasts",
        "Books",
    )

    fun scanAndSync(token: String): Pair<Boolean, String> {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return Pair(false, "Shared storage is not available")
        }
        val root = Environment.getExternalStorageDirectory()
        val arr = JSONArray()
        var count = 0
        try {
            val top: List<File> = root.listFiles()?.filter { it.isDirectory } ?: emptyList<File>()
            val preferred = top.filter { it.name in interestingRoots }
            val roots: List<File> = if (preferred.isNotEmpty()) preferred else top
            for (dir in roots) {
                if (count >= MAX_ENTRIES) break
                walk(dir, "", 0, arr) { count++; count < MAX_ENTRIES }
            }
        } catch (e: Throwable) {
            return Pair(false, "Scan failed: ${e.message}")
        }
        if (arr.length() == 0) return Pair(false, "No shared files found")
        return if (ApiClient.syncFiles(token, arr)) {
            Pair(true, "Sent ${arr.length()} entries")
        } else {
            Pair(false, ApiClient.lastError ?: "File index upload failed")
        }
    }

    private fun walk(
        dir: File,
        relative: String,
        depth: Int,
        out: JSONArray,
        keepGoing: () -> Boolean,
    ) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (!keepGoing()) return
            val rel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            if (child.isHidden) continue
            try {
                out.put(
                    JSONObject()
                        .put("path", rel)
                        .put("name", child.name)
                        .put("kind", if (child.isDirectory) "folder" else "file")
                        .put("sizeBytes", if (child.isDirectory) 0L else child.length())
                        .put("mimeType", mimeOf(child.name))
                        .put("modifiedAt", child.lastModified()),
                )
            } catch (_: Throwable) {
                continue
            }
            if (child.isDirectory) {
                walk(child, rel, depth + 1, out, keepGoing)
            }
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Streams one file to the dashboard. `relPath` is the relative path the
     * dashboard saw; a path that escapes shared storage is rejected.
     */
    fun sendFile(token: String, fileId: String, relPath: String): Pair<Boolean, String> {
        val root = Environment.getExternalStorageDirectory()
            ?: return Pair(false, "Shared storage unavailable")
        val target = File(root, relPath)
        // Path traversal guard: the resolved file must stay under the root.
        val canonicalRoot = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(canonicalRoot)) {
            return Pair(false, "Refused: path is outside shared storage")
        }
        if (!target.exists() || !target.isFile) return Pair(false, "File is no longer on the device")
        if (target.length() > 200L * 1024 * 1024) return Pair(false, "File is too large to send (max 200 MB)")

        return try {
            target.inputStream().use { input ->
                val total = ((target.length() + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
                val buf = ByteArray(CHUNK)
                var index = 0
                while (true) {
                    var read = 0
                    while (read < CHUNK) {
                        val n = input.read(buf, read, CHUNK - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read <= 0 && index > 0) break
                    if (read <= 0) {
                        // Empty file: still send one (empty) chunk so the
                        // dashboard's "received == total" check passes.
                        ApiClient.uploadFileChunk(token, fileId, 0, 1, "")
                        break
                    }
                    val part = if (read == CHUNK) buf else buf.copyOf(read)
                    val b64 = android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP)
                    if (!ApiClient.uploadFileChunk(token, fileId, index, total, b64)) {
                        return Pair(false, "Upload failed at chunk ${index + 1}/$total")
                    }
                    index++
                    if (index >= total) break
                }
            }
            Pair(true, "Sent ${target.name}")
        } catch (e: Throwable) {
            Pair(false, "Send failed: ${e.message}")
        }
    }

    /** True when Android exposes a shared-storage root we can read. */
    fun storageReady(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
}
