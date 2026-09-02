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

# ---- apksig ASN.1 反射（v1/v2/v3 签名编码链，release 必需）----
# Asn1DerEncoder/Asn1BerParser 通过反射读取 bean 的 @Asn1Class/@Asn1Field 注解
# 与无参构造器。R8 会剥离未 keep 的注解、删除仅被反射使用的构造器，运行期报
# "Failed to sign using signer CERT ← Failed to encode signature block"。
# 已用 R8 classfile 模式 + 本规则对 v1/v2/v3 全组合签名与校验做过全量验证。
-keep class com.android.apksig.internal.asn1.** { *; }
-keepclasseswithmembers class * {
    @com.android.apksig.internal.asn1.Asn1Field <fields>;
    public <init>();
}

# ---- OkHttp / commons-compress 等三方库的警告静默 ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# ---- JSch（MCP 外网隧道）----
# SSH 算法类（jce/bc 包）经 Class.forName 反射加载，R8 静态分析不可见，
# 误删会导致协商失败（Algorithm negotiation fail）。保留整个库最稳妥。
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.ietf.jgss.**
# JSch 可选依赖（编译期 classpath 缺失，R8 报 Missing class）：
# - jna/junixsocket：Windows Pageant / Unix ssh-agent 连接器，Android 不用
# - slf4j/log4j：可选日志门面，Android 走 java.util.logging
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
