# PhotoDuel R8 / ProGuard rules for the release build (isMinifyEnabled = true, isShrinkResources = true).
# Owned by PD-D5. Most libraries ship their own consumer rules (OkHttp, Coil, DataStore, kotlinx
# serialization and coroutines, Compose); only what the app itself needs is listed here. The rules
# below are verified by the release build on GitHub Actions, not on this development machine.

# --- Crash reports stay readable -------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization (the DTOs of data/remote) -----------------------------------------------
# These are the rules recommended by the library. They repeat what newer versions bundle, which is
# harmless, and protect the generated serializers if a bundled rule is ever missing.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Optional OkHttp platform classes that are not on Android -----------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# --- Release logging --------------------------------------------------------------------------------
# Verbose and debug logging is removed from release builds. The app never logs tokens, invitation
# links, photo bytes or URIs; this is a second line of defence, not the first. (Needs the optimizing
# default file proguard-android-optimize.txt, which build.gradle.kts uses.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
