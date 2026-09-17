# JGit contains optional JVM-only integrations that are not available on Android.
# These code paths are not used by GithubBckp's HTTPS mirror workflow.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
