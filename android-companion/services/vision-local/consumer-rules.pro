# JNI: the native library binds Java_com_rokid_context_vision_local_NativeVlm_* by name, so neither the class nor its native methods may be renamed or removed.
-keep class com.rokid.context.vision.local.NativeVlm { native <methods>; *; }
-keepclasseswithmembernames class * { native <methods>; }
