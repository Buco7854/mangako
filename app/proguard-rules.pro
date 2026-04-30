-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep kotlinx-serialization-generated classes
-keep,includedescriptorclasses class com.mangako.app.**$$serializer { *; }
-keepclassmembers class com.mangako.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mangako.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Rules (polymorphic sealed)
-keep class com.mangako.app.domain.rule.** { *; }

# Ktor + OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
