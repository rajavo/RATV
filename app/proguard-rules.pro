# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer ship their own consumer rules; nothing extra needed.

# Picture enhancement: ExoPlayer creates the Media3 effect pipeline by reflection
# (PlaybackVideoGraphWrapper), including the builders' no-argument constructors.
-keep class androidx.media3.effect.PreviewingSingleInputVideoGraph$Factory { <init>(...); }
-keep class androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder { <init>(); *** build(); }
