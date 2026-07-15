# MediaPipe + LiteRT use JNI; keep their surfaces intact.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Serialized pipeline payloads (World-State ledger, dispatch alerts, manifests).
-keep,includedescriptorclasses class com.aegisedge.os.**$$serializer { *; }
-keepclassmembers class com.aegisedge.os.** { *** Companion; }
