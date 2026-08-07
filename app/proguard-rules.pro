# ============================================================
# fler release R8 / ProGuard 规则
# ============================================================

# ---- JNI 原生绑定（静态符号查找 Java_com_ai_fler_...，禁止改名）----
-keep class com.ai.fler.core.jni.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- Hilt（生成组件由 hilt 自带 consumer 规则保留，这里兜底）----
-keep class com.ai.fler.FlerApplication { *; }
-keep class dagger.hilt.** { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keepnames class * extends androidx.lifecycle.ViewModel

# ---- kotlinx-serialization（@Serializable 数据类）----
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, ExceptionTable
-keep class com.ai.fler.**$$serializer { *; }
-keepclasseswithmembers class com.ai.fler.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.ai.fler.** {
    *** Companion;
    *** INSTANCE;
}
-keep @kotlinx.serialization.Serializable class com.ai.fler.** { *; }

# ---- Room（自带 consumer 规则兜底，避免 kotlinx-metadata 误删）----
-keep class * extends androidx.room.RoomDatabase { *; }

# ---- 版本信息读取（BuildConfig 字段）----
-keep class com.ai.fler.BuildConfig { *; }

# ---- OkHttp / commons-compress 等三方库的警告静默 ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
