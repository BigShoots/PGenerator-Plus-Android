# PGenerator+ Android ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Gson serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
