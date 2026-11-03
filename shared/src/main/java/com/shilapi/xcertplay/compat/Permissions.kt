package com.shilapi.xcertplay.compat

import android.content.Context

/**
 * API 19（Android 4.4）在安装时授予全部权限，不存在运行时权限机制，因此恒为已授权。
 */
fun hasPermission(c: Context, p: String): Boolean = true
