# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class blue.anika.wolle.**$$serializer {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class blue.anika.wolle.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class blue.anika.wolle.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
