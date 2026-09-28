package com.gmf.stockopname.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.border
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.gmf.stockopname.data.AppViewModel
import com.gmf.stockopname.data.ScanLookup
import java.util.concurrent.Executors

private class ScanNotice(val title: String, val message: String)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    vm: AppViewModel,
    storeId: String,
    location: String,
    onBack: () -> Unit,
    onToolFound: () -> Unit
) {
    val stats = vm.store.stats(storeId, location, vm.toolsFor(storeId, location).size)
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    var paused by remember { mutableStateOf(false) }
    var resumeAt by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf<ScanNotice?>(null) }
    var showManual by remember { mutableStateOf(false) }

    // Compares the code with the tools database: found -> Detail Tool, otherwise a message.
    fun handleCode(code: String, force: Boolean = false) {
        if (!force && (paused || SystemClock.elapsedRealtime() < resumeAt)) return
        paused = true
        when (val r = vm.lookupScanned(code)) {
            is ScanLookup.InLocation -> {
                vm.scanCursor = r.index
                onToolFound()
            }
            is ScanLookup.OtherLocation -> notice = ScanNotice(
                "Tool tidak ada di lokasi ini",
                "Tool ${r.tool.toolNumber} (${r.tool.description}) ada di database, tetapi terdaftar di " +
                    "${r.tool.store} / ${r.tool.location}, bukan di $location."
            )
            ScanLookup.NotFound -> notice = ScanNotice(
                "Tool tidak ditemukan",
                "Kode \"$code\" tidak ada di database tools."
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopHeader("Scan Barcode", "$location \u00B7 ${stats.checked}/${stats.total}", onBack)
        Column(Modifier.padding(16.dp)) {
            LinearProgressIndicator(
                progress = stats.percent,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF0A1220), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (cameraPermission.status.isGranted) {
                    CameraPreviewWithBarcodeScanner(onDetected = { code -> handleCode(code) })
                    // viewfinder guide overlay
                    Box(
                        Modifier
                            .size(190.dp, 130.dp)
                            .border(2.5.dp, Color(0xFF4C9BFF), RoundedCornerShape(10.dp))
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Kamera belum aktif", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("\uD83C\uDF9E\uFE0F Aktifkan Kamera")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Arahkan kamera ke barcode pada tool. Kodenya dicocokkan dengan Tool Number di database.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                Text("\u2328\uFE0F Input Tool Number manual")
            }
            val denied = cameraPermission.status as? PermissionStatus.Denied
            if (denied != null && denied.shouldShowRationale) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Izin kamera ditolak. Buka Setelan aplikasi > Izin > Kamera untuk mengaktifkan, atau gunakan input manual di atas.",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    val n = notice
    if (n != null) {
        val dismiss = {
            notice = null
            paused = false
            resumeAt = SystemClock.elapsedRealtime() + 2000 // beri jeda agar barcode yang sama tidak langsung terbaca lagi
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(n.title) },
            text = { Text(n.message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("Scan Lagi") } }
        )
    }

    if (showManual) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Input Tool Number") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Tool Number") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showManual = false
                    if (text.isNotBlank()) handleCode(text, force = true)
                }) { Text("Cek") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Batal") } }
        )
    }
}

/**
 * Live CameraX preview piped through ML Kit's on-device barcode scanner.
 * Calls [onDetected] with the raw barcode value the moment one is read.
 */
@Composable
private fun CameraPreviewWithBarcodeScanner(onDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDetected by rememberUpdatedState(onDetected)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODABAR
                )
                .build()
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull()?.rawValue
                                if (value != null) currentOnDetected(value)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (_: Exception) {
                    // binding failed (e.g. no camera hardware) — screen falls back to the manual button
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}
