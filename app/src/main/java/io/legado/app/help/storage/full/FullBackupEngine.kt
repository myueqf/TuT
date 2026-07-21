package io.legado.app.help.storage.full

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.data.AppDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FullBackupEngine(private val context: Context) {

    data class Progress(val stage: String, val files: Int, val bytes: Long)
    data class BackupResult(val skippedBooks: List<SkippedLocalBook>)

    private data class Source(
        val archivePath: String,
        val lastModified: Long,
        val open: () -> InputStream,
    )

    private data class LocalBookRef(val url: String, val fileName: String, val name: String)

    private data class Inspection(
        val manifest: FullBackupManifest,
        val records: Map<String, FullBackupFileRecord>,
    )

    private val gson = Gson()
    private val databaseFile get() = context.getDatabasePath(DATABASE_NAME)
    private val externalFiles get() = context.getExternalFilesDir(null)

    fun backup(uri: Uri, onProgress: (Progress) -> Unit = {}): BackupResult {
        requireUriOutsideRestoreRoots(uri)
        val localBooks = checkpointAndReadLocalBooks()
        val sources = arrayListOf<Source>()
        addRootSources(sources, File(context.applicationInfo.dataDir, "databases"), "internal/databases")
        addRootSources(sources, File(context.applicationInfo.dataDir, "shared_prefs"), "internal/shared_prefs")
        addRootSources(sources, context.filesDir, "internal/files")
        addRootSources(sources, File(context.applicationInfo.dataDir, "no_backup"), "internal/no_backup")
        externalFiles?.let { addRootSources(sources, it, "external/files") }

        val existingPaths = sources.mapTo(hashSetOf()) { it.archivePath }
        val oldPortableMap = readPortableMap()
        val portableBooks = arrayListOf<PortableBookRecord>()
        val portableFonts = arrayListOf<PortableFontRecord>()
        val skippedBooks = arrayListOf<SkippedLocalBook>()

        localBooks.forEach { book ->
            val resolved = resolveReadable(book.url, oldPortableMap.books.associate {
                it.bookUrl to it.relativePath
            })
            if (resolved == null) {
                skippedBooks += SkippedLocalBook(book.name, book.url, "文件不存在或没有读取权限")
                return@forEach
            }
            val relativePath = relativeToExternal(resolved.first) ?: portableRelativePath(
                "full_backup_books", book.url, book.fileName
            ).also { target ->
                val archivePath = "external/files/$target"
                if (existingPaths.add(archivePath)) {
                    sources += Source(archivePath, resolved.second) { openUri(resolved.first) }
                }
            }
            portableBooks += PortableBookRecord(book.url, relativePath)
        }

        val oldFontMap = oldPortableMap.fonts.associate { it.source to it.relativePath }
        configuredFontPaths().forEach { fontPath ->
            val resolved = resolveReadable(fontPath, oldFontMap) ?: return@forEach
            val name = displayName(resolved.first, "font.ttf")
            val relativePath = relativeToExternal(resolved.first) ?: portableRelativePath(
                "full_backup_fonts", fontPath, name
            ).also { target ->
                val archivePath = "external/files/$target"
                if (existingPaths.add(archivePath)) {
                    sources += Source(archivePath, resolved.second) { openUri(resolved.first) }
                }
            }
            portableFonts += PortableFontRecord(fontPath, relativePath)
        }

        val portableBytes = gson.toJson(
            PortableFileMap(books = portableBooks, fonts = portableFonts)
        ).toByteArray()
        sources.removeAll { it.archivePath == FullBackupArchive.PORTABLE_MAP_PATH }
        sources += Source(FullBackupArchive.PORTABLE_MAP_PATH, System.currentTimeMillis()) {
            ByteArrayInputStream(portableBytes)
        }

        val records = arrayListOf<FullBackupFileRecord>()
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.buffered().use { output ->
                ZipOutputStream(output).use { zip ->
                    var files = 0
                    var bytes = 0L
                    sources.sortedBy { it.archivePath }.forEach { source ->
                        FullBackupArchive.requireSafeEntryPath(source.archivePath)
                        val result = writeEntry(zip, source)
                        records += result
                        files++
                        bytes += result.size
                        onProgress(Progress("正在备份", files, bytes))
                    }
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    @Suppress("DEPRECATION")
                    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        packageInfo.longVersionCode
                    } else {
                        packageInfo.versionCode.toLong()
                    }
                    val manifest = FullBackupManifest(
                        formatVersion = FullBackupArchive.FORMAT_VERSION,
                        packageName = context.packageName,
                        createdAt = System.currentTimeMillis(),
                        appVersionCode = versionCode,
                        appVersionName = packageInfo.versionName.orEmpty(),
                        databaseVersion = readDatabaseVersion(databaseFile),
                        files = records,
                        skippedLocalBooks = skippedBooks,
                    )
                    zip.putNextEntry(ZipEntry(FullBackupArchive.MANIFEST_PATH))
                    zip.write(gson.toJson(manifest).toByteArray())
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            DocumentFile.fromSingleUri(context, uri)?.delete()
            throw e
        }
        return BackupResult(skippedBooks)
    }

    fun restore(uri: Uri, onProgress: (Progress) -> Unit = {}) {
        requireUriOutsideRestoreRoots(uri)
        val inspection = inspect(uri, onProgress)
        checkAvailableSpace(inspection.manifest)
        FullBackupControl.markRestoreActive(context, uri.toString())
        clearRestoreRoots()
        extract(uri, inspection, onProgress)
        validateDatabase(databaseFile, allowOlder = true)
        PortableFileResolver.invalidate()
        FullBackupControl.clearRestore(context)
    }

    private fun inspect(uri: Uri, onProgress: (Progress) -> Unit): Inspection {
        val observed = linkedMapOf<String, FullBackupFileRecord>()
        var manifest: FullBackupManifest? = null
        var files = 0
        var bytes = 0L
        val preflightDb = File(context.cacheDir, PREFLIGHT_DATABASE_NAME).apply { delete() }
        openZip(uri).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                FullBackupArchive.requireSafeEntryPath(path)
                if (entry.isDirectory) continue
                if (path == FullBackupArchive.MANIFEST_PATH) {
                    check(manifest == null) { "备份中存在重复清单" }
                    val text = zip.readBytesLimited(MAX_MANIFEST_SIZE).toString(Charsets.UTF_8)
                    manifest = gson.fromJson(text, FullBackupManifest::class.java)
                } else {
                    check(path !in observed) { "备份中存在重复路径: $path" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val dbOutput = if (path == DATABASE_ARCHIVE_PATH) FileOutputStream(preflightDb) else null
                    var entrySize = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    dbOutput.use { db ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            digest.update(buffer, 0, read)
                            db?.write(buffer, 0, read)
                            entrySize += read
                        }
                    }
                    observed[path] = FullBackupFileRecord(
                        path, entrySize, digest.digest().hex(), 0L
                    )
                    files++
                    bytes += entrySize
                    onProgress(Progress("正在校验", files, bytes))
                }
                zip.closeEntry()
            }
        }
        val checkedManifest = requireNotNull(manifest) { "不是阅读全量备份文件" }
        require(checkedManifest.formatVersion == FullBackupArchive.FORMAT_VERSION) {
            "不支持的全量备份格式: ${checkedManifest.formatVersion}"
        }
        require(checkedManifest.packageName == context.packageName) {
            "备份属于 ${checkedManifest.packageName}，当前应用是 ${context.packageName}"
        }
        val declared = checkedManifest.files.associateBy { it.path }
        require(declared.size == checkedManifest.files.size && declared.keys == observed.keys) {
            "备份文件清单不完整"
        }
        declared.forEach { (path, expected) ->
            FullBackupArchive.requireSafeEntryPath(path)
            val actual = observed.getValue(path)
            require(expected.size == actual.size && expected.sha256.equals(actual.sha256, true)) {
                "文件校验失败: $path"
            }
        }
        if (preflightDb.isFile) {
            val actualVersion = validateDatabase(preflightDb, allowOlder = true)
            require(checkedManifest.databaseVersion == actualVersion) {
                "数据库版本与备份清单不一致"
            }
            preflightDb.delete()
        } else {
            require(checkedManifest.databaseVersion == 0) { "备份缺少数据库文件" }
        }
        return Inspection(checkedManifest, declared)
    }

    private fun extract(uri: Uri, inspection: Inspection, onProgress: (Progress) -> Unit) {
        var files = 0
        var bytes = 0L
        val seen = hashSetOf<String>()
        openZip(uri).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                FullBackupArchive.requireSafeEntryPath(entry.name)
                if (entry.isDirectory || entry.name == FullBackupArchive.MANIFEST_PATH) continue
                val record = inspection.records[entry.name]
                    ?: error("文件不在清单中: ${entry.name}")
                check(seen.add(entry.name)) { "备份中存在重复路径: ${entry.name}" }
                val target = targetFor(entry.name)
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var entrySize = 0L
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        entrySize += read
                    }
                }
                check(entrySize == record.size && digest.digest().hex() == record.sha256.lowercase()) {
                    "恢复时文件校验失败: ${entry.name}"
                }
                if (record.lastModified > 0) target.setLastModified(record.lastModified)
                files++
                bytes += entrySize
                onProgress(Progress("正在恢复", files, bytes))
                zip.closeEntry()
            }
        }
        check(seen == inspection.records.keys) { "恢复文件数量不完整" }
    }

    private fun checkpointAndReadLocalBooks(): List<LocalBookRef> {
        if (!databaseFile.isFile) return emptyList()
        val database = openDatabaseForCheckpoint(databaseFile)
        return database.use { db ->
            val books = arrayListOf<LocalBookRef>()
            db.rawQuery(
                "SELECT bookUrl, originName, name FROM books " +
                    "WHERE (type & 256) > 0 OR (type = 0 AND (origin = 'loc_book' OR origin LIKE 'webDav::%'))",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    books += LocalBookRef(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            books
        }
    }

    private fun validateDatabase(file: File, allowOlder: Boolean): Int {
        if (!file.isFile) return 0
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return database.use { db ->
            val version = db.version
            require(version <= AppDatabase.DATABASE_VERSION) {
                "数据库版本 $version 高于当前支持的 ${AppDatabase.DATABASE_VERSION}"
            }
            if (allowOlder) {
                require(version == 0 || version >= MIN_MIGRATABLE_DATABASE_VERSION) {
                    "数据库版本 $version 只能破坏性升级，已拒绝恢复"
                }
            }
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                    "数据库完整性检查失败"
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(cursor.count == 0) { "数据库外键检查失败" }
            }
            version
        }
    }

    private fun addRootSources(sources: MutableList<Source>, root: File, archiveRoot: String) {
        if (!root.isDirectory) return
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        root.walkTopDown().filter {
            it.isFile && it.canonicalPath.startsWith(rootPath)
        }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (shouldExclude(archiveRoot, relative)) return@forEach
            sources += Source("$archiveRoot/$relative", file.lastModified()) { FileInputStream(file) }
        }
    }

    private fun shouldExclude(archiveRoot: String, relative: String): Boolean {
        if (archiveRoot == "internal/databases" &&
            (relative.endsWith("-wal") || relative.endsWith("-shm") || relative.endsWith("-journal"))
        ) {
            return true
        }
        if (archiveRoot == "internal/files") {
            if (relative == "backup" || relative.startsWith("backup/")) return true
            if (relative == "full_backup/portable_files.json") return true
        }
        if (archiveRoot == "external/files") {
            val name = relative.substringAfterLast('/')
            if (relative == "tmp_backup.zip" || name.matches(Regex("backup.*\\.zip", RegexOption.IGNORE_CASE))) {
                return true
            }
        }
        return false
    }

    private fun writeEntry(zip: ZipOutputStream, source: Source): FullBackupFileRecord {
        zip.putNextEntry(ZipEntry(source.archivePath).apply { time = source.lastModified })
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.open().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                zip.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                size += read
            }
        }
        zip.closeEntry()
        return FullBackupFileRecord(
            source.archivePath, size, digest.digest().hex(), source.lastModified
        )
    }

    private fun resolveReadable(source: String, oldMap: Map<String, String>): Pair<Uri, Long>? {
        val direct = parseSourceUri(source)
        readableMetadata(direct)?.let { return direct to it }
        val relative = oldMap[source] ?: return null
        val file = externalFiles?.let { File(it, relative) } ?: return null
        val uri = Uri.fromFile(file)
        return readableMetadata(uri)?.let { uri to it }
    }

    private fun parseSourceUri(source: String): Uri {
        val uri = Uri.parse(source)
        return if (uri.scheme.isNullOrBlank()) Uri.fromFile(File(source)) else uri
    }

    private fun readableMetadata(uri: Uri): Long? = runCatching {
        openUri(uri).use { it.read() }
        if (uri.scheme == "content") {
            DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
        } else {
            File(requireNotNull(uri.path)).lastModified()
        }
    }.getOrNull()

    private fun openUri(uri: Uri): InputStream = if (uri.scheme == "content") {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取 $uri" }
    } else {
        FileInputStream(File(requireNotNull(uri.path)))
    }

    private fun relativeToExternal(uri: Uri): String? {
        if (uri.scheme == "content") return null
        val root = externalFiles ?: return null
        val file = File(uri.path ?: return null).canonicalFile
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return if (file.path.startsWith(rootPath)) {
            file.path.removePrefix(rootPath).replace(File.separatorChar, '/')
        } else null
    }

    private fun portableRelativePath(folder: String, key: String, fileName: String): String {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).hex().take(24)
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "book" }
        return "$folder/$id/$safeName"
    }

    private fun displayName(uri: Uri, fallback: String): String = if (uri.scheme == "content") {
        DocumentFile.fromSingleUri(context, uri)?.name ?: fallback
    } else {
        File(uri.path ?: fallback).name.ifBlank { fallback }
    }

    private fun configuredFontPaths(): Set<String> {
        val result = linkedSetOf<String>()
        listOf("readConfig.json", "shareReadConfig.json").forEach { name ->
            val file = File(context.filesDir, name)
            if (!file.isFile) return@forEach
            runCatching { collectJsonValues(JsonParser.parseString(file.readText()), "textFont", result) }
        }
        return result.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun collectJsonValues(element: JsonElement, key: String, result: MutableSet<String>) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { collectJsonValues(it, key, result) }
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (name, value) ->
                if (name == key && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    result += value.asString
                } else {
                    collectJsonValues(value, key, result)
                }
            }
        }
    }

    private fun readPortableMap(): PortableFileMap {
        val file = File(context.filesDir, "full_backup/portable_files.json")
        return runCatching { gson.fromJson(file.readText(), PortableFileMap::class.java) }
            .getOrNull() ?: PortableFileMap()
    }

    private fun openZip(uri: Uri): ZipInputStream = ZipInputStream(
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开备份文件" }.buffered()
    )

    private fun checkAvailableSpace(manifest: FullBackupManifest) {
        val internalRequired = manifest.files.filter { it.path.startsWith("internal/") }.sumOf { it.size }
        val externalRequired = manifest.files.filter { it.path.startsWith("external/") }.sumOf { it.size }
        val internalRoot = File(context.applicationInfo.dataDir)
        require(internalRequired <= internalRoot.usableSpace + restoreRootSize(internal = true)) {
            "内部存储空间不足"
        }
        if (externalRequired > 0) {
            val externalRoot = requireNotNull(externalFiles) { "外部应用目录不可用" }
            require(externalRequired <= externalRoot.usableSpace + directorySize(externalRoot)) {
                "外部存储空间不足"
            }
        }
    }

    private fun requireUriOutsideRestoreRoots(uri: Uri) {
        require(uri.authority != "${context.packageName}.fileProvider") {
            "全量备份文件不能放在 APP 专属目录中"
        }
        val resolvedPath = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")
            }
        }.getOrNull() ?: return
        if (!resolvedPath.startsWith(File.separator)) return
        val roots = buildList {
            add(File(context.applicationInfo.dataDir).canonicalPath)
            externalFiles?.canonicalPath?.let { add(it) }
        }
        require(roots.none { root ->
            resolvedPath == root || resolvedPath.startsWith(root.trimEnd(File.separatorChar) + File.separator)
        }) {
            "全量备份文件不能放在 APP 专属目录中"
        }
    }

    private fun restoreRootSize(internal: Boolean): Long {
        if (!internal) return 0
        return listOf("databases", "shared_prefs", "files", "no_backup")
            .sumOf { directorySize(File(context.applicationInfo.dataDir, it)) }
    }

    private fun directorySize(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf(::directorySize) ?: 0L
        else -> 0L
    }

    private fun clearRestoreRoots() {
        listOf("databases", "shared_prefs", "files", "no_backup").forEach { name ->
            clearDirectory(File(context.applicationInfo.dataDir, name))
        }
        externalFiles?.let(::clearDirectory)
    }

    private fun clearDirectory(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
            return
        }
        directory.listFiles()?.forEach { child ->
            check(child.deleteRecursively()) { "无法删除 ${child.path}" }
        }
    }

    private fun targetFor(archivePath: String): File {
        FullBackupArchive.requireSafeEntryPath(archivePath)
        val pair = when {
            archivePath.startsWith("internal/databases/") ->
                File(context.applicationInfo.dataDir, "databases") to archivePath.removePrefix("internal/databases/")
            archivePath.startsWith("internal/shared_prefs/") ->
                File(context.applicationInfo.dataDir, "shared_prefs") to archivePath.removePrefix("internal/shared_prefs/")
            archivePath.startsWith("internal/files/") ->
                context.filesDir to archivePath.removePrefix("internal/files/")
            archivePath.startsWith("internal/no_backup/") ->
                File(context.applicationInfo.dataDir, "no_backup") to archivePath.removePrefix("internal/no_backup/")
            archivePath.startsWith("external/files/") ->
                requireNotNull(externalFiles) to archivePath.removePrefix("external/files/")
            else -> error("不支持的恢复路径: $archivePath")
        }
        val target = File(pair.first, pair.second)
        val rootPath = pair.first.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(target.canonicalPath.startsWith(rootPath)) { "恢复路径越界: $archivePath" }
        return target
    }

    private fun readDatabaseVersion(file: File): Int {
        if (!file.isFile) return 0
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    }

    private fun openDatabaseForCheckpoint(file: File): SQLiteDatabase {
        var lastError: Throwable? = null
        repeat(20) {
            try {
                return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
            }
        }
        throw IllegalStateException("数据库仍被其他进程占用", lastError)
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) { "备份清单过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val DATABASE_NAME = "legado.db"
        private const val DATABASE_ARCHIVE_PATH = "internal/databases/legado.db"
        private const val PREFLIGHT_DATABASE_NAME = "full_restore_preflight.db"
        private const val MIN_MIGRATABLE_DATABASE_VERSION = 10
        private const val MAX_MANIFEST_SIZE = 16 * 1024 * 1024
    }
}
