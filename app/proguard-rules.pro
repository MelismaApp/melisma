# Kuromoji loads its dictionary from resources inside the jar via reflection-free
# resource lookups, but the class names are referenced from a generated index.
-keep class com.atilika.kuromoji.** { *; }
-keepclassmembers class com.atilika.kuromoji.** { *; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.melisma.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.melisma.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio platform shims.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The WebView bridge that receives the Spotify token.
#
# Nothing in Kotlin calls `onToken` — the injected JavaScript does, by name, through
# `addJavascriptInterface`. R8 sees an uncalled method on an otherwise unreferenced class and is
# right to remove it, which in a release build means the harvest waits out its timeout and reports
# a failure that never happens in debug.
-keepclassmembers class com.melisma.app.lyrics.provider.SpotifyBrowserToken$TokenBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# The installer Shizuku starts, and the interface it is reached through.
#
# Nothing in this app ever constructs PrivilegedInstallerService: Shizuku does, by name and
# reflectively, in a process of its own. R8 sees a class with no instantiation and marks it
# abstract, which fails at exactly that point with "cannot be instantiated".
-keep class com.melisma.app.update.PrivilegedInstallerService { *; }
-keep class com.melisma.app.update.IPrivilegedInstaller { *; }
-keep class com.melisma.app.update.IPrivilegedInstaller$* { *; }
