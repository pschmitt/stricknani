# AndroidJUnitRunner reaches this shared runtime class from its instrumentation dependencies.
-keep class androidx.tracing.Trace { *; }

# The opt-in transport test calls these facade methods from the separate test APK.
-keep class io.sentry.Sentry {
    public static boolean isEnabled();
    public static void flush(long);
    public static void withScope(io.sentry.ScopeCallback);
}

# These shared library APIs are called by the separate test APK after the target is shrunk.
# Preserve library entry points; media response class retention comes from production rules.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class retrofit2.** { *; }
-keep class com.jakewharton.retrofit2.** { *; }
-keep class io.sentry.** { *; }

# Accessors invoked from the separate test APK are otherwise inlined out of the target.
-keepclassmembers,allowobfuscation class blue.anika.wolle.data.api.v2.V2Envelope {
    public java.lang.Object getData();
}
-keepclassmembers,allowobfuscation class blue.anika.wolle.data.api.v2.MediaOrderDto {
    public java.util.List getIds();
}
# The live transport test constructs this reporting API from the separate test APK.
-keep class blue.anika.wolle.data.errorreporting.** { *; }
