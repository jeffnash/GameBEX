-keepattributes SourceFile,LineNumberTable
# Tink (via security-crypto) references compile-time-only errorprone annotations.
-dontwarn com.google.errorprone.annotations.**
# Shizuku UserService: the Shizuku (shell uid) process loads this class from
# our APK by name and instantiates it reflectively.
-keep class com.gamebex.client.launch.ShellFilesService { <init>(...); }
-keep class com.gamebex.client.launch.IShellFiles { *; }
-keep class com.gamebex.client.launch.IShellFiles$* { *; }
-keep class rikka.shizuku.ShizukuProvider { <init>(); }
# Play together link messages (kotlinx.serialization, sealed LinkMsg).
-keep class com.gamebex.client.together.LinkMsg$* { *; }
-keep class com.gamebex.client.together.** implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class com.gamebex.client.together.** { *** Companion; }
