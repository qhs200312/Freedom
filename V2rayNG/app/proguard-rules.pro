# Protocol/persisted DTOs use Gson reflection and unannotated JSON field names.
-keep,allowoptimization class com.v2ray.ang.dto.** { *; }
-keep,allowoptimization class com.v2ray.ang.handler.VerifiedUpdateManager$DownloadedUpdate { *; }
-keep,allowoptimization class com.v2ray.ang.handler.VerifiedUpdateManager$Store { *; }
-keep,allowoptimization class com.v2ray.ang.ipquality.IpQuality { *; }
-keep,allowoptimization class com.v2ray.ang.ipquality.IpQualityCacheEntry { *; }
-keepclassmembers enum com.v2ray.ang.** { <fields>; }
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# gomobile and JNI refer to their Java bindings by binary name.
-keep class go.** { *; }
-keep class corebundle.** { *; }
-keep class libbox.** { *; }
-keep class libv2ray.** { *; }
-keep class com.tencent.mmkv.** { *; }
-keep class com.v2ray.ang.service.TProxyService { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Keep useful release crash traces while removing original source filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
