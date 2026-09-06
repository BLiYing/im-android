# kotlinx.serialization：@Serializable 类的合成 serializer 需保留。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# 协议 DTO 全保留：字段名即协议线格式，混淆会改坏 JSON。
-keep class com.libeyond.imandroid.sdk.protocol.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
