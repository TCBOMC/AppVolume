-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Shizuku.newProcess() 是 private 的，靠反射调用执行 shell 命令。
# R8 会把没被引用的私有方法删掉或改名，必须显式保住签名和所在类。
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# AudioEffect 走 JNI 反射创建，类名不能被动
-keep class android.media.audiofx.** { *; }
