# Add project specific ProGuard rules here.
# Keep Ktor classes
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

# Keep LiteRT-LM classes
-keep class com.google.ai.edge.litertlm.** { *; }
