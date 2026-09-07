# Add project specific ProGuard rules here.
# Keep Gson model fields.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
