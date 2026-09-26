# ---------------------------------------------------------------------------
# Sprint 6 release hardening rules.
#
# 1. Disguised accessibility service. The service class lives under a Google
#    package on purpose; the Android accessibility framework instantiates it by
#    the literal class name declared in AndroidManifest.xml, and the app also
#    references it via string component names
#    ("com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService")
#    in MainActivity and KeepAliveService. String references are invisible to
#    R8, so the whole disguised package must survive shrinking AND obfuscation
#    with its exact names.
# ---------------------------------------------------------------------------
-keep class com.google.android.accessibility.** { *; }

# org.json usage in ChatMemory / KbStore is hand-written, direct API calls with
# no reflection (verified by source grep: no Class.forName / Gson / Jackson /
# kotlin.reflect anywhere in app/src). Nothing to keep for serialization.
#
# ML Kit text-recognition-chinese ships its own consumer ProGuard rules inside
# the aar; they are picked up automatically. Add -dontwarn/keep here only if a
# release build ever reports missing classes from com.google.mlkit / odml.
