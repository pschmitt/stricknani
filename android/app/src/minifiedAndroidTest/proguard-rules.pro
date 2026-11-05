# AndroidJUnitRunner reaches this shared runtime class from its instrumentation dependencies.
-keep class androidx.tracing.Trace { *; }

# The opt-in transport test calls these facade methods from the separate test APK.
-keep class io.sentry.Sentry {
    public static boolean isEnabled();
    public static void flush(long);
    public static void withScope(io.sentry.ScopeCallback);
}
