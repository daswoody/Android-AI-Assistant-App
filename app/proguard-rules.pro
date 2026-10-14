# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class de.heimai.app.**$$serializer { *; }
-keepclassmembers class de.heimai.app.** {
    *** Companion;
}
-keepclasseswithmembers class de.heimai.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Porcupine
-keep class ai.picovoice.** { *; }
