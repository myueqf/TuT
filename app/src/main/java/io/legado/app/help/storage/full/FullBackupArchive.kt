package io.legado.app.help.storage.full

import com.google.gson.annotations.SerializedName
import java.io.InputStream
import java.security.MessageDigest

object FullBackupArchive {

    const val FORMAT_VERSION = 1
    const val MANIFEST_PATH = "META-INF/legado-full-backup.json"
    const val PORTABLE_MAP_PATH = "internal/files/full_backup/portable_files.json"

    val allowedPrefixes = listOf(
        "internal/databases/",
        "internal/shared_prefs/",
        "internal/files/",
        "internal/no_backup/",
        "external/files/",
    )

    fun requireSafeEntryPath(path: String) {
        require(path.isNotBlank()) { "Empty ZIP entry path" }
        require('\\' !in path && !path.startsWith('/')) { "Invalid ZIP entry path: $path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid ZIP entry path: $path"
        }
        require(path == MANIFEST_PATH || allowedPrefixes.any(path::startsWith)) {
            "Unsupported ZIP entry path: $path"
        }
    }

    fun sha256(input: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            size += read
        }
        return digest.digest().toHex() to size
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

data class FullBackupManifest(
    @SerializedName("formatVersion") val formatVersion: Int,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("appVersionCode") val appVersionCode: Long,
    @SerializedName("appVersionName") val appVersionName: String,
    @SerializedName("databaseVersion") val databaseVersion: Int,
    @SerializedName("files") val files: List<FullBackupFileRecord>,
    @SerializedName("skippedLocalBooks") val skippedLocalBooks: List<SkippedLocalBook>,
)

data class FullBackupFileRecord(
    @SerializedName("path") val path: String,
    @SerializedName("size") val size: Long,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("lastModified") val lastModified: Long,
)

data class PortableFileMap(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("books") val books: List<PortableBookRecord> = emptyList(),
    @SerializedName("fonts") val fonts: List<PortableFontRecord> = emptyList(),
)

data class PortableBookRecord(
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("relativePath") val relativePath: String,
)

data class PortableFontRecord(
    @SerializedName("source") val source: String,
    @SerializedName("relativePath") val relativePath: String,
)

data class SkippedLocalBook(
    @SerializedName("name") val name: String,
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("reason") val reason: String,
)
