# RemoteLink WebRTC uses JNI callbacks whose class/member names must remain stable.
# Keep these even when release minification is re-enabled later.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep interface org.jni_zero.** { *; }

# Preserve native method names for JNI bindings.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepattributes *Annotation*
-keepattributes Signature
