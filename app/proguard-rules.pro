# TDM proguard rules (release keeps default optimize; TDLib JNI classes must not be renamed)
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
