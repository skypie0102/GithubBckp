# JGit contains optional JVM-only integrations that are not available on Android.
# These code paths are not used by GithubBckp's HTTPS mirror workflow.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Google Play services AuthorizationClient reaches the internal sign-in bridge
# reflectively. R8 9.4 removed/rewrote the no-arg constructor in v0.2.0,
# producing NoSuchMethodException for the obfuscated com.google.android.gms.signin.zaa.
# Keep this small internal package intact for release builds.
-keep class com.google.android.gms.signin.** { *; }

# v0.2.0 also failed while initializing JGit's FileSnapshot after R8
# optimization. JGit is the core backup engine, so favor runtime correctness
# over squeezing a few more bytes from this library.
-keep class org.eclipse.jgit.** { *; }
