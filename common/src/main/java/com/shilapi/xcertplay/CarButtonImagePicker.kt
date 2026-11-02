package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Prefer file locations; older head units may only provide a content picker. Head units without a
 * system document picker may still have an app that answers every intent, so there the content
 * picker goes first.
 */
internal fun launchCarButtonImagePicker(
    openDocument: () -> Unit,
    getContent: () -> Unit,
    documentPickerIsSystem: Boolean = true,
): Result<Unit> {
    val (first, second) = if (documentPickerIsSystem) openDocument to getContent else getContent to openDocument
    return runCatching {
        try {
            first()
        } catch (_: ActivityNotFoundException) {
            second()
        }
    }
}

/** False only when apps answer the image document intent and none of them is part of the system image. */
internal fun Context.documentPickerIsSystem(): Boolean {
    val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
    }
    val handlers = packageManager.queryIntentActivities(
        documentIntent,
        PackageManager.MATCH_DEFAULT_ONLY,
    )
    return handlers.isEmpty() ||
        handlers.any { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
}
