# Project specific ProGuard/R8 rules
# =====================================================================

# ── Aggressive obfuscation settings ──────────────────────────────────
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-dontpreverify

# Remove all Log calls in release builds (security: no debug info leaks)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# ── Keep rules for libraries and framework classes ───────────────────

# Gson (used for JSON serialization of Event objects)
-keepattributes Signature
-keepattributes *Annotation*
-keep class systems.sieber.fsclock.Event { *; }
-keep class systems.sieber.fsclock.WallpaperItem { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# NanoHTTPD (UploadServer)
-keep class org.nanohttpd.** { *; }
-keep class fi.iki.elonen.** { *; }

# ZXing QR code
-keep class com.google.zxing.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# Google Play Billing (google flavor)
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# Amazon IAP (amazon flavor)
-keep class com.amazon.** { *; }
-dontwarn com.amazon.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Material Components
-keep class com.google.android.material.** { *; }

# Keep custom views referenced in XML layouts
-keep class systems.sieber.fsclock.FsClockView { *; }
-keep class systems.sieber.fsclock.DateView { *; }
-keep class systems.sieber.fsclock.DigitalClockView { *; }
-keep class systems.sieber.fsclock.WallpaperView { *; }

# Keep Activities, Services, and Receivers (referenced in AndroidManifest)
-keep class systems.sieber.fsclock.FullscreenActivity { *; }
-keep class systems.sieber.fsclock.FullscreenDream { *; }
-keep class systems.sieber.fsclock.SettingsActivity { *; }
-keep class systems.sieber.fsclock.BaseSettingsActivity { *; }
-keep class systems.sieber.fsclock.FsClockWidgetAnalogProvider { *; }
-keep class systems.sieber.fsclock.FsClockWidgetDigitalProvider { *; }
-keep class systems.sieber.fsclock.FsClockWidgetAnalogConfigActivity { *; }
-keep class systems.sieber.fsclock.FsClockWidgetDigitalConfigActivity { *; }
-keep class systems.sieber.fsclock.NotificationListener { *; }
-keep class systems.sieber.fsclock.PowerReceiver { *; }
-keep class systems.sieber.fsclock.FsClockApp { *; }

# Keep the security classes but obfuscate their internals
-keep class systems.sieber.fsclock.IntegrityGuard {
    static void init(android.content.Context);
    static boolean isEnvironmentTrusted(android.content.Context);
    static boolean isSignatureValid(android.content.Context);
}
-keep class systems.sieber.fsclock.SecurePrefs {
    <init>(android.content.Context, android.content.SharedPreferences);
}

# Keep reflection-accessed system properties method
-keep class systems.sieber.fsclock.WallpaperRepo {
    public static java.lang.String getSystemProperty(java.lang.String);
    public static java.lang.String getHardwareId(android.content.Context);
}

# ── Remove debugging info ────────────────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ── Suppress common harmless warnings ────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
