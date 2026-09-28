package com.gmf.stockopname.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Base64

/**
 * Talks to Firebase Firestore (free Spark plan is enough — no Cloud Storage).
 * A tool added from the Master Data "+" button, and its photo, becomes visible
 * to every phone on the team in real time.
 *
 * Layout:
 *  - collection "tools":       one document per added tool (light, no photo bytes)
 *  - collection "tool_photos": one document per tool number (field "data" = JPEG bytes).
 *                              Works for ANY tool, including the ones bundled in masterdata.json.
 *                              Saving a new photo for the same tool replaces the old one.
 * Photos live in their own collection so the tool list stays small and fast;
 * a photo is only downloaded when a detail screen actually shows it.
 *
 * Firestore also caches the last-synced data on-device automatically.
 */
object RemoteMasterDataSource {

    private const val TOOLS = "tools"
    private const val PHOTOS = "tool_photos"
    private val db by lazy { FirebaseFirestore.getInstance() }

    /** Emits the full shared-tool list, and again every time it changes on the server. */
    fun observeAll(): Flow<List<Tool>> = callbackFlow {
        val registration = db.collection(TOOLS).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            trySend(snapshot.documents.mapNotNull { it.toTool() })
        }
        awaitClose { registration.remove() }
    }

    /** Photo document id = tool number encoded (tool numbers may contain "/" etc., which Firestore ids can't). */
    private fun photoDocId(toolNumber: String): String =
        Base64.encodeToString(toolNumber.trim().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Saves the photo (if any) first, then the tool. */
    suspend fun addTool(tool: Tool, photoJpeg: ByteArray?): Tool {
        if (photoJpeg != null) setPhoto(tool.toolNumber, photoJpeg)
        val data = hashMapOf(
            "toolNumber" to tool.toolNumber,
            "partNumber" to tool.partNumber,
            "keyNumber" to tool.keyNumber,
            "serialNumber" to tool.serialNumber,
            "description" to tool.description,
            "manufacture" to tool.manufacture,
            "model" to tool.model,
            "status" to tool.status,
            "store" to tool.store,
            "location" to tool.location,
            "addedAt" to Timestamp.now()
        )
        db.collection(TOOLS).add(data).await()
        return tool
    }

    /**
     * Saves/replaces the latest photo for a tool. Returns true when the server confirmed the write,
     * false when it is still pending (offline / slow) — Firestore keeps it queued and sends it later.
     */
    suspend fun setPhoto(toolNumber: String, jpeg: ByteArray): Boolean {
        val task = db.collection(PHOTOS).document(photoDocId(toolNumber))
            .set(hashMapOf("data" to Blob.fromBytes(jpeg), "updatedAt" to Timestamp.now()))
        return withTimeoutOrNull(30_000) { task.await(); true } ?: false
    }

    /** Downloads a tool's latest photo (JPEG bytes), or null if it has none. */
    suspend fun getPhoto(toolNumber: String): ByteArray? =
        db.collection(PHOTOS).document(photoDocId(toolNumber)).get().await().getBlob("data")?.toBytes()

    private fun DocumentSnapshot.toTool(): Tool? {
        val toolNumber = getString("toolNumber") ?: return null
        return Tool(
            toolNumber = toolNumber,
            partNumber = getString("partNumber") ?: "",
            keyNumber = getString("keyNumber") ?: "",
            serialNumber = getString("serialNumber") ?: "",
            description = getString("description") ?: "",
            manufacture = getString("manufacture") ?: "",
            model = getString("model") ?: "",
            status = getString("status") ?: "available",
            store = getString("store") ?: "",
            location = getString("location") ?: ""
        )
    }
}
