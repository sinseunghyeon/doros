# 숨김 API·ADB 라이브러리는 리플렉션/네이티브로 접근하므로 이름이 바뀌면 안 됨
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class com.sinseunghyeon.doros.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class android.sun.** { *; }

# conscrypt 가 KitKat 이하 전용으로 참조하는 플랫폼 클래스 (minSdk 24 라 실행 경로에 없음)
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
