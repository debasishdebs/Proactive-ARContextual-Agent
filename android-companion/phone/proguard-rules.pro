# R8 is OFF (isMinifyEnabled = false); this file is intentionally empty of rules.
# Rules needed before enabling: SQLCipher, JNI llama/ONNX, CXR SDK, kotlinx.serialization, Room, WorkManager, Nexus AIDL.

# V1 offline captioner JNI (:services:vision-local ships the same rule as a consumer rule): the native library binds Java_com_rokid_context_vision_local_NativeVlm_* by name.
-keep class com.rokid.context.vision.local.NativeVlm { native <methods>; *; }
