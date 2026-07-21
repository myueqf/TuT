package io.legado.app.help.storage.full

import android.content.Context
import android.os.Build

object FullBackupControl {

    private const val PREFS_NAME = "full_backup_control"
    private const val KEY_RESTORE_URI = "restore_uri"
    private const val KEY_RESTORE_ACTIVE = "restore_active"
    const val PROCESS_SUFFIX = ":full_data_transfer"

    fun isTransferProcess(): Boolean = processName().endsWith(PROCESS_SUFFIX)

    fun markRestoreActive(context: Context, uri: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            controlFile(context).writeText(uri)
            return
        }
        preferences(context).edit()
            .putBoolean(KEY_RESTORE_ACTIVE, true)
            .putString(KEY_RESTORE_URI, uri)
            .commit()
    }

    fun clearRestore(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            controlFile(context).delete()
            return
        }
        preferences(context).edit().clear().commit()
    }

    fun pendingRestoreUri(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return controlFile(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        }
        val preferences = preferences(context)
        if (!preferences.getBoolean(KEY_RESTORE_ACTIVE, false)) return null
        return preferences.getString(KEY_RESTORE_URI, null)
    }

    private fun preferences(context: Context) = protectedContext(context)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun protectedContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

    private fun controlFile(context: Context) = java.io.File(
        context.cacheDir, "full_backup_restore_active"
    )

    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName()
        }
        return runCatching {
            java.io.File("/proc/self/cmdline").inputStream().buffered().use {
                val bytes = it.readBytes()
                val end = bytes.indexOf(0).let { index -> if (index < 0) bytes.size else index }
                String(bytes, 0, end, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }
}
