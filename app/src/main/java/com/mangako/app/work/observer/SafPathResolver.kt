package com.mangako.app.work.observer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * True when the app holds the runtime "All files access" permission AND the
 * platform is new enough for the check itself to exist. The permission only
 * matters under scoped storage (API 30+); on older Android the legacy
 * READ_EXTERNAL_STORAGE permission with maxSdkVersion=32 from the manifest
 * gives equivalent access without an extra opt-in, but we keep the
 * real-time path gated to API 30+ to avoid having to maintain two distinct
 * permission flows.
 */
fun canUseFileObserver(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        Environment.isExternalStorageManager()

/**
 * Best-effort conversion of a SAF tree URI to a real filesystem path.
 *
 * Only `com.android.externalstorage.documents` URIs map cleanly:
 *   primary:Manga/Downloads → /storage/emulated/0/Manga/Downloads
 *   1FF4-AC1B:Comics        → /storage/1FF4-AC1B/Comics  (SD card)
 *
 * Every other DocumentsProvider (Drive, OneDrive, FTP shares, in-app
 * providers, etc.) is opaque — there's no exposed file path, so the
 * caller falls back to the SAF observer + scan-on-resume path.
 *
 * The returned [File] is NOT verified to exist or be readable. The caller
 * is responsible for checking access (which usually means
 * `Environment.isExternalStorageManager()` first).
 */
object SafPathResolver {

    fun toFilePath(@Suppress("unused") context: Context, uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull() ?: return null
        val (storageId, relative) = treeDocId.split(':', limit = 2)
            .takeIf { it.size == 2 } ?: return null

        val root = if (storageId == "primary") {
            // Environment.getExternalStorageDirectory() resolves to the
            // primary external storage even on multi-user devices.
            Environment.getExternalStorageDirectory()
        } else {
            // Removable storage (SD card, USB OTG). The /storage/<id>
            // mapping is a stable convention across Android versions.
            File("/storage", storageId)
        }
        return File(root, relative)
    }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
