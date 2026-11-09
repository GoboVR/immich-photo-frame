# ONNX Runtime: native code calls back into these Java classes by name.
-keep class ai.onnxruntime.** { *; }

# Ktor server (CIO + websockets) uses reflection/service lookups in places; keep it whole.
-keep class io.ktor.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Our wire models are serialized with kotlinx.serialization (generated serializers).
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class app.photoframe.server.Wire* { *; }
-keep class app.photoframe.core.FrameConfig { *; }
-keepclassmembers class app.photoframe.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional/JVM-only classes referenced by Ktor & friends that don't exist on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.fusesource.**
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**
