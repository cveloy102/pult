package ru.hl100.pult

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hosts the Compose UI and the few things that need an Activity: links, QR scanner, APK installer.
 * App logic lives in [PultController].
 */
class MainActivity : ComponentActivity() {
    private val model: PultViewModel by viewModels()
    private val controller get() = model.controller

    // APK downloaded while the "install unknown apps" permission was missing; installed on return.
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (savedInstanceState == null) handleLink(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    controller.refreshStatus()
                    delay(3000)
                }
            }
        }
        val updates = getSharedPreferences("updates", MODE_PRIVATE)
        controller.checkUpdateOnStart(updates.getLong("lastCheck", 0), System.currentTimeMillis()) { updates.edit().putLong("lastCheck", it).apply() }
        setContent {
            val state by controller.state.collectAsState()
            SideEffect {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = state.theme == "quiet"
                bars.isAppearanceLightNavigationBars = state.theme == "quiet"
            }
            AppScreen(state, controller, onScanQr = ::scanQr, onInstallUpdate = ::installUpdate)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingApk
        if (apk != null && packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            try { AndroidUpdates.install(this, apk) } catch (ex: Exception) { controller.showMessage(errorText(ex)) }
        }
    }

    /** pult://pair links from the agent QR code, opened by the system camera. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith("pult:")) return
        controller.receiveLink(data, scanned = false)
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Google code scanner: no camera permission in the app; needs Google Play services. */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> controller.receiveLink(code.rawValue, scanned = true) }
            .addOnFailureListener { controller.showMessage("Сканер недоступен. Подключи ПК через «Найти ПК в сети» или по адресу.") }
    }

    private fun installUpdate(release: AndroidRelease) = controller.installUpdate(release) {
        val apk = withContext(Dispatchers.IO) { AndroidUpdates.download(this@MainActivity, it) }
        if (packageManager.canRequestPackageInstalls()) {
            AndroidUpdates.install(this@MainActivity, apk)
        } else {
            pendingApk = apk
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }
}
