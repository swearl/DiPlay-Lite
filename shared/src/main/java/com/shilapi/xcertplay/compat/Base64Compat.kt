package com.shilapi.xcertplay.compat

import android.util.Base64

/**
 * java.util.Base64 is API 26+; Android 4.4 only has android.util.Base64, which lacks
 * the MIME variants, so they are emulated here.
 */
object Base64Compat {
    /** Standard alphabet, no line breaks (java.util.Base64.getEncoder()). */
    fun encodeToString(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    /** Standard alphabet in 64-character LF-terminated lines (getMimeEncoder(64, '\n')). */
    fun encodeToMimeString(data: ByteArray): String =
        encodeToString(data).chunked(64).joinToString("\n")

    /** Accepts plain and whitespace-wrapped input (getDecoder()/getMimeDecoder()). */
    fun decode(encoded: String): ByteArray {
        val compact = encoded.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        return Base64.decode(compact, Base64.NO_WRAP)
    }

    /** Same as [decode] for ASCII input held in a byte array (java MIME decoder form). */
    fun decode(encoded: ByteArray): ByteArray = decode(String(encoded, Charsets.US_ASCII))
}
