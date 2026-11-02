package com.shilapi.xcertplay

import android.content.Context

/** Historical process exits need API 30 (getHistoricalProcessExitReasons); unavailable on 4.4. */
internal object ProcessExitDiagnostics {
    fun report(@Suppress("UNUSED_PARAMETER") context: Context): String =
        "Process exits available=false requiresApi=30"
}
