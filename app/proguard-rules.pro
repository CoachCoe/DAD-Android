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

# The stored session, attribution and link tokens are persisted as Gson JSON keyed by field name. Obfuscated
# names (a, b, c) are not stable between releases, so an update could fail to read the stored session or, worse,
# read one field's value as another's; keep the names the stored data was written with.
-keepclassmembers class org.dad.foundation.Credentials { <fields>; }
-keepclassmembers class org.dad.foundation.Attribution { <fields>; }
-keepclassmembers class org.dad.foundation.LinkTokens { <fields>; }
