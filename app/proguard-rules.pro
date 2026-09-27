# LiteRT-LM calls back into Kotlin from JNI and uses Gson/kotlin-reflect; keep its API intact.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**

# Gson (used by LiteRT-LM)
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn sun.misc.**

# Strip all android.util.Log calls from release builds so nothing (prompts, replies, paths) is logged.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# Flatten package names to make reverse engineering harder.
-repackageclasses ''
-allowaccessmodification
