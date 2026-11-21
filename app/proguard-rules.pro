# Keep JavascriptInterface methods reachable from the WebView bridge.
-keepclassmembers class com.tsvsecret.schedule.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.tsvsecret.schedule.** {
    @android.webkit.JavascriptInterface <methods>;
}
