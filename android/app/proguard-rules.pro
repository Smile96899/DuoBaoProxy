# App-specific R8 rules. Compose, Navigation and kotlinx.serialization ship their own consumer rules.

# gomobile (dbcore.aar): the Go side finds classes, constructors, fields and methods by name over
# JNI (go.Seq, go.Universe, com.duobao.core.mobile.*), and calls back into the Kotlin classes that
# implement the gomobile interfaces (Host, Protector, LocalDNS, LogHandler) by their Java names.
-keep class go.** { *; }
-keep class com.duobao.core.** { *; }
-keep class * implements com.duobao.core.mobile.Host { *; }
-keep class * implements com.duobao.core.mobile.Protector { *; }
-keep class * implements com.duobao.core.mobile.LocalDNS { *; }
-keep class * implements com.duobao.core.mobile.LogHandler { *; }
-keep class * implements go.Seq$Proxy { *; }
-dontwarn go.**
