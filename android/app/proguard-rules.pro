# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# PDFBox Android references this optional JPEG2000 decoder when JPX images are present.
-dontwarn com.gemalto.jp2.JP2Decoder

# Keep Kaivor skill manifests (used in reflection/serialization)
-keep class com.kaivor.agent.skills.** { *; }
-keep class com.kaivor.agent.AgentConfig { *; }
