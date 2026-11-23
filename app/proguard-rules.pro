# Keep the JS bridge members that the WebView calls by name.
-keepclassmembers class com.choi.filemanager.MainActivity$NasJs {
    public *;
}
-keepclassmembers class com.choi.filemanager.MainActivity$LocalJs {
    public *;
}
-keep class com.choi.filemanager.NasBridge { *; }
-keep class com.choi.filemanager.LocalFileBridge { *; }
