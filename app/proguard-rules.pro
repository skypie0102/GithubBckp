# JGit is the core mirror engine. Keep it intact because aggressive R8 rewriting
# previously broke JGit runtime initialization on Android.
-keep class org.eclipse.jgit.** { *; }

# Optional JVM-only JGit integrations are not used by the HTTPS backup path.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Commons Compress has optional codec integrations that are not used for tar.gz.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
