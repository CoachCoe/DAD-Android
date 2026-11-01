# R8/ProGuard rules for release builds.

# Retrofit interfaces and their generated service methods rely on runtime
# annotations and reflective type info; keep their signatures and annotations.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Gson (de)serializes the generated model classes reflectively by field name,
# so the generated DTOs must keep their field names.
-keep class org.dad.foundation.generated.model.** { <fields>; }
-keepclassmembers class org.dad.foundation.generated.model.** { <fields>; }

# ZXing is only reached through direct calls (Referral.qrMatrix -> QRCodeWriter); it uses no
# reflection or service loading, so R8 traces what it needs and no keep rule is required.
