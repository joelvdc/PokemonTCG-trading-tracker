# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.poketrader.**$$serializer { *; }
-keepclassmembers class com.poketrader.** { *** Companion; }
-keepclasseswithmembers class com.poketrader.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp optional platform integrations
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
