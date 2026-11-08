# libbox (gomobile) calls back into Java by name: keep the whole binding package.
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
# Our implementations of libbox interfaces are called from Go.
-keep class com.slmtune.app.service.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.slmtune.app.core.** { *** Companion; }
-keepclasseswithmembers class com.slmtune.app.core.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**

# sshj and Bouncy Castle find algorithms by reflection
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn com.jcraft.jzlib.**
