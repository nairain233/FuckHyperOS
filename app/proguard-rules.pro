-dontwarn io.github.libxposed.annotation.**
# WindowManager 的可选 OEM 扩展由系统提供，AndroidX 在运行时检测可用性。
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
