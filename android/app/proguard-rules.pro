# Vosk calls its native library through JNA, which finds classes, methods and fields by name at runtime.
# Without these the release build's speech-to-text failed with "org.vosk.LibVosk" (2026-11-05).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
