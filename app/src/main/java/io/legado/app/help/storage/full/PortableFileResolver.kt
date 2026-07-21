package io.legado.app.help.storage.full

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import java.io.File

object PortableFileResolver {

    @Volatile
    private var cachedMap: PortableFileMap? = null

    fun resolveBook(context: Context, bookUrl: String): Uri? {
        val relativePath = load(context).books.firstOrNull { it.bookUrl == bookUrl }?.relativePath
            ?: return null
        return existingUri(context, relativePath)
    }

    fun resolveFont(context: Context, source: String): String? {
        val relativePath = load(context).fonts.firstOrNull { it.source == source }?.relativePath
            ?: return null
        return existingUri(context, relativePath)?.path
    }

    fun invalidate() {
        cachedMap = null
    }

    private fun existingUri(context: Context, relativePath: String): Uri? {
        val root = context.getExternalFilesDir(null) ?: return null
        val file = File(root, relativePath)
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        if (!file.canonicalPath.startsWith(rootPath) || !file.isFile) return null
        return Uri.fromFile(file)
    }

    private fun load(context: Context): PortableFileMap {
        cachedMap?.let { return it }
        return synchronized(this) {
            cachedMap ?: run {
                val mapFile = File(context.filesDir, "full_backup/portable_files.json")
                runCatching {
                    Gson().fromJson(mapFile.readText(), PortableFileMap::class.java)
                }.getOrNull() ?: PortableFileMap()
            }.also { cachedMap = it }
        }
    }
}
