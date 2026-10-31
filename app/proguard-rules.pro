# Add project specific ProGuard rules here.

# Gson reflects over these DTOs; keep their names, fields and annotations.
-keep class com.example.instadl.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# OkHttp / Okio are shipped with their own R8 rules. Silence optional
# platform integrations that are not present on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
