-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.internetcalling.app.**$$serializer { *; }
-keepclassmembers class com.internetcalling.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.internetcalling.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class org.webrtc.** { *; }

-keep class com.google.firebase.** { *; }
