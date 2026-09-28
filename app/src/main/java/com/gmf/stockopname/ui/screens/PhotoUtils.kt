package com.gmf.stockopname.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Two actions a screen can trigger to get a new photo. */
class PhotoPickers(
    val takePhoto: () -> Unit,
    val pickFromGallery: () -> Unit
)

/**
 * Sets up camera + gallery launchers. [onPhoto] receives the chosen photo, already downscaled.
 * The camera action asks for the CAMERA permission first if it isn't granted yet
 * (the manifest declares it for barcode scanning, so launching the camera without it would crash).
 */
@Composable
fun rememberPhotoPickers(onPhoto: (Bitmap) -> Unit): PhotoPickers {
    val context = LocalContext.current

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap -> if (bitmap != null) onPhoto(scaleDown(bitmap)) }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) uriToBitmap(context, uri)?.let { onPhoto(scaleDown(it)) } }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) camera.launch(null) }

    return remember(camera, gallery, permission) {
        PhotoPickers(
            takePhoto = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) camera.launch(null) else permission.launch(Manifest.permission.CAMERA)
            },
            pickFromGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }
}

/** Decodes a picked gallery image (content Uri) into a reasonably small Bitmap, respecting rotation. */
fun uriToBitmap(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > maxDim) {
                val scale = maxDim.toFloat() / largest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
} catch (_: Exception) {
    null
}

/** Downscales a bitmap so uploads to Firestore stay small and fast. */
fun scaleDown(bitmap: Bitmap, maxDim: Int = 1024): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val largest = maxOf(w, h)
    if (largest <= maxDim) return bitmap
    val scale = maxDim.toFloat() / largest
    return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
}

/** Firestore documents max out at 1 MiB, so keep the photo well under that (target <= ~600 KB). */
fun compressForFirestore(bitmap: Bitmap): ByteArray {
    var quality = 80
    var bytes: ByteArray
    do {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        quality -= 10
    } while (bytes.size > 600_000 && quality >= 30)
    return bytes
}
