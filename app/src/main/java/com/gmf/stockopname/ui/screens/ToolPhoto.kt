package com.gmf.stockopname.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmf.stockopname.data.RemoteMasterDataSource
import com.gmf.stockopname.ui.theme.Green
import com.gmf.stockopname.ui.theme.MutedLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short-lived in-memory cache so reopening a tool doesn't re-download its photo every time. */
private class CachedPhoto(val image: ImageBitmap, val at: Long)

private val photoCache = HashMap<String, CachedPhoto>()
private const val FRESH_MS = 60_000L

fun cacheToolPhoto(toolNumber: String, image: ImageBitmap) {
    photoCache[toolNumber] = CachedPhoto(image, System.currentTimeMillis())
}

private sealed interface PhotoState {
    object Loading : PhotoState
    object None : PhotoState
    class Loaded(val image: ImageBitmap) : PhotoState
}

/**
 * Shows the latest photo of a tool (loaded from Firestore by tool number).
 * Change [version] to force a reload after the photo was updated.
 */
@Composable
fun ToolPhoto(toolNumber: String, version: Int = 0, modifier: Modifier = Modifier) {
    val state by produceState<PhotoState>(
        initialValue = photoCache[toolNumber]?.let { PhotoState.Loaded(it.image) } ?: PhotoState.Loading,
        toolNumber, version
    ) {
        val cached = photoCache[toolNumber]
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MS) {
            value = PhotoState.Loaded(cached.image)
            return@produceState
        }
        val bytes = try { RemoteMasterDataSource.getPhoto(toolNumber) } catch (_: Exception) { null }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        value = when {
            bmp != null -> {
                cacheToolPhoto(toolNumber, bmp)
                PhotoState.Loaded(bmp)
            }
            cached != null -> PhotoState.Loaded(cached.image) // fetch failed / offline: keep the old one
            else -> PhotoState.None
        }
    }

    Card(
        modifier.fillMaxWidth().height(200.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        when (val s = state) {
            is PhotoState.Loaded -> Image(
                bitmap = s.image,
                contentDescription = "Foto tool",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PhotoState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Memuat foto...", color = MutedLight, fontSize = 12.sp)
            }
            PhotoState.None -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Belum ada foto", color = MutedLight, fontSize = 12.sp)
            }
        }
    }
}

/** The photo plus "Ambil Foto" / "Galeri" buttons to replace it with a newer one. */
@Composable
fun ToolPhotoSection(toolNumber: String) {
    val scope = rememberCoroutineScope()
    var version by remember(toolNumber) { mutableStateOf(0) }
    var busy by remember(toolNumber) { mutableStateOf(false) }
    var message by remember(toolNumber) { mutableStateOf<String?>(null) }
    var isError by remember(toolNumber) { mutableStateOf(false) }

    val pickers = rememberPhotoPickers { bitmap ->
        busy = true
        message = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { compressForFirestore(bitmap) }
                val synced = RemoteMasterDataSource.setPhoto(toolNumber, bytes)
                cacheToolPhoto(toolNumber, bitmap.asImageBitmap())
                version++
                isError = false
                message = if (synced) "Foto berhasil diperbarui."
                else "Koneksi lambat/offline \u2014 foto akan terkirim otomatis saat online."
            } catch (e: Exception) {
                isError = true
                message = "Gagal memperbarui foto: " + (e.message ?: "cek koneksi internet.")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ToolPhoto(toolNumber, version)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = pickers.takePhoto,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "Mengunggah..." else "\uD83D\uDCF7 Update Foto") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = pickers.pickFromGallery,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("\uD83D\uDDBC\uFE0F Dari Galeri") }
        }
        val msg = message
        if (msg != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                color = if (isError) MaterialTheme.colorScheme.error else Green,
                fontSize = 12.sp
            )
        }
    }
}
