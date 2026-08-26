-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.agentcall.app.**$$serializer { *; }
-keepclassmembers class com.agentcall.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.agentcall.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# sherpa-onnx (bundled Piper TTS, backlog item 11): JNI entry points are
# resolved by name from native code — R8 must not strip or rename them.
-keep class com.k2fsa.sherpa.onnx.** { *; }
