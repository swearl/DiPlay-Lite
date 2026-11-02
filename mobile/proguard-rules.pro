# Keep all first-party code; R8 only shrinks third-party libraries.
-keep class com.shilapi.xcertplay.** { *; }
# Vendor AIDL/Binder stubs use reflection.
-keep class com.anwbtsdk.** { *; }
-keep class com.hsae.** { *; }
-keep class com.anwsdk.** { *; }
# autosdk.jar is compileOnly — the head unit provides com.hsae.autosdk at runtime.
-dontwarn com.hsae.autosdk.**
# JNI native methods.
-keepclasseswithmembernames class * { native <methods>; }
# Kotlin metadata / coroutines-free defaults
-dontwarn kotlin.**
# bcprov registers algorithm SPIs by name via Class.forName
# (e.g. KeyFactory EC / NONEwithECDSA for MFi signing); R8 would strip the
# unreferenced SPI classes and KeyFactory.getInstance("EC", bc) fails on API 19.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.jmdns.**
-dontwarn javax.naming.**
-dontwarn java.beans.**
