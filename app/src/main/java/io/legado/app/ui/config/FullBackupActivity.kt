package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.databinding.ActivityFullBackupBinding
import io.legado.app.help.storage.full.FullBackupControl
import io.legado.app.help.storage.full.FullBackupEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FullBackupActivity : ComponentActivity() {

    private lateinit var binding: ActivityFullBackupBinding
    private var running = false
    private var retryRestore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.button.setOnClickListener {
            if (retryRestore) runTransfer() else returnToApp()
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!running) returnToApp()
        }
        runTransfer()
    }

    private fun runTransfer() {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RESTORE
        val uri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_URI)))
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        running = true
        retryRestore = false
        binding.button.isEnabled = false
        binding.progress.isIndeterminate = true
        binding.title.setText(if (mode == MODE_BACKUP) R.string.full_backup else R.string.full_restore)
        binding.status.setText(R.string.full_transfer_stopping)
        binding.detail.text = ""
        lifecycleScope.launch {
            delay(350)
            if (mainPid > 0 && mainPid != Process.myPid()) {
                Process.killProcess(mainPid)
                delay(350)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val engine = FullBackupEngine(this@FullBackupActivity)
                    if (mode == MODE_BACKUP) {
                        engine.backup(uri, ::postProgress)
                    } else {
                        engine.restore(uri, ::postProgress)
                        null
                    }
                }
            }
            running = false
            binding.progress.isIndeterminate = false
            binding.progress.progress = 100
            result.onSuccess { backupResult ->
                binding.status.setText(R.string.full_transfer_success)
                binding.detail.text = backupResult?.skippedBooks
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { skipped ->
                        skipped.joinToString(
                            prefix = getString(R.string.full_backup_skipped, skipped.size) + "\n",
                            separator = "\n",
                        ) { "${it.name}: ${it.reason}" }
                    }
                    .orEmpty()
                binding.button.setText(R.string.return_to_app)
                binding.button.isEnabled = true
            }.onFailure { error ->
                binding.status.setText(R.string.full_transfer_failed)
                binding.detail.text = error.localizedMessage ?: error.javaClass.simpleName
                retryRestore = mode == MODE_RESTORE &&
                    FullBackupControl.pendingRestoreUri(this@FullBackupActivity) != null
                binding.button.setText(if (retryRestore) R.string.retry else R.string.return_to_app)
                binding.button.isEnabled = true
            }
        }
    }

    private fun postProgress(progress: FullBackupEngine.Progress) {
        runOnUiThread {
            binding.status.text = progress.stage
            binding.detail.text = getString(
                R.string.full_transfer_progress,
                progress.files,
                Formatter.formatFileSize(this, progress.bytes),
            )
        }
    }

    private fun returnToApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finishAndRemoveTask()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MAIN_PID = "main_pid"
        const val MODE_BACKUP = "backup"
        const val MODE_RESTORE = "restore"

        fun intent(context: Context, mode: String, uri: Uri, mainPid: Int): Intent =
            Intent(context, FullBackupActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_MAIN_PID, mainPid)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
    }
}
