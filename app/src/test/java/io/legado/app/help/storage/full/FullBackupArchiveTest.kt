package io.legado.app.help.storage.full

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FullBackupArchiveTest {

    @Test
    fun acceptsOnlyDeclaredRootsAndManifest() {
        FullBackupArchive.requireSafeEntryPath("internal/databases/legado.db")
        FullBackupArchive.requireSafeEntryPath("external/files/book_cache/a/1.txt")
        FullBackupArchive.requireSafeEntryPath(FullBackupArchive.MANIFEST_PATH)

        listOf(
            "../shared_prefs/config.xml",
            "/internal/files/a",
            "internal/files/../../escape",
            "internal\\files\\a",
            "cache/a",
            "internal/files/",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                FullBackupArchive.requireSafeEntryPath(path)
            }
        }
    }

    @Test
    fun sha256CountsAndHashesStream() {
        val (hash, size) = FullBackupArchive.sha256(
            ByteArrayInputStream("legado".toByteArray())
        )

        assertEquals(6L, size)
        assertEquals("c848c83a14853821592f9ec571c3ee23caa985a2ebe93b8d3185be3e9d650051", hash)
    }

    @Test
    fun manifestUsesStableFieldNames() {
        val json = Gson().toJson(
            FullBackupManifest(
                formatVersion = 1,
                packageName = "io.legado.app",
                createdAt = 1,
                appVersionCode = 2,
                appVersionName = "3.0",
                databaseVersion = 75,
                files = listOf(FullBackupFileRecord("internal/files/a", 1, "00", 3)),
                skippedLocalBooks = emptyList(),
            )
        )

        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"databaseVersion\":75"))
        assertTrue(json.contains("\"lastModified\":3"))
    }
}
