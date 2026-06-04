# BiliBili API response classes
-keep class com.bililive.danmaku.data.remote.RoomInitResponse { *; }
-keep class com.bililive.danmaku.data.remote.RoomInitData { *; }
-keep class com.bililive.danmaku.data.remote.RoomInfoResponse { *; }
-keep class com.bililive.danmaku.data.remote.RoomInfoData { *; }
-keep class com.bililive.danmaku.data.remote.DanmakuMessage { *; }
-keep class com.bililive.danmaku.data.remote.BiliBiliPacket { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
