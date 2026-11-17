# Strip verbose/debug/info logging from release builds (spec: no text in logcat in release).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
