# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# kotlinx.serialization
# Retrofit reads generic return types (including suspend Continuation types) at runtime.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# Retrofit 3's generic-return keep rule sees `Object` for Kotlin suspend functions. Preserve this
# response model because it is only named in the `Continuation<V2Envelope<MediaOrderDto>>` signature
# used reflectively when replaying a title-image reorder.
-keep,allowoptimization,allowobfuscation class blue.anika.wolle.data.api.v2.MediaOrderDto
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

# T130: the JNI symbols of the in-app server (src/local_jni.rs) name this class and its methods.
-keep class blue.anika.wolle.localserver.LocalServerNative {
    native <methods>;
}
