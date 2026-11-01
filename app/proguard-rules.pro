# Keep JavascriptInterface methods for WebView bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class kr.or.kcaa.gwangju.portal.bridge.PortalJsBridge { *; }
