package com.shilapi.xcertplay.compat

import android.content.Context
import android.content.pm.PackageManager

/** API 19 grants all permissions at install time; runtime checks only matter on API 23+. */
fun hasPermission(c: Context, p: String): Boolean =
    realSdkInt() >= 23 && c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
