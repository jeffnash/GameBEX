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
# Stream: Shizuku loads StreamShellService by name in the shell-uid process,
# and the emulator gate starts StreamShellMain through app_process.
-keep class com.gamebex.client.stream.shell.StreamShellService { public <init>(); public <init>(android.content.Context); }
-keep class com.gamebex.client.stream.shell.IStreamShell { *; }
-keep class com.gamebex.client.stream.shell.IStreamShell$* { *; }
-keep class com.gamebex.client.stream.shell.StreamShellMain { public static void main(java.lang.String[]); }
# Stream JSON crosses the binder as kotlinx.serialization text.
-keep class com.gamebex.client.stream.** implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class com.gamebex.client.stream.** { *** Companion; }
