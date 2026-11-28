# MediaHub (API 17+, armeabi-v7a)
Local files (internal/SD/USB), images, and web links played with libVLC.

Build: open this folder in Android Studio (needs internet once, to fetch libVLC),
then Build > Build APK. Output: app/build/outputs/apk/debug/app-debug.apk

Install on the box: adb install -r app-debug.apk
Remote keys: OK = pause/play, LEFT/RIGHT = seek 10s (images: previous/next), BACK = up/exit.
