#!/bin/sh
# Install the Hanzi Lens fork on a Fire TV / Android TV and fully compile it (faster start, smoother UI).
# Re-run after every rebuild: installing a new APK discards the compiled code.
#   tools/firetv-install.sh <device-ip>:5555 [path/to.apk]
DEV=${1:?usage: firetv-install.sh <device-ip>:5555 [apk]}
APK=${2:-"$(dirname "$0")/../app/build/outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk"}
adb connect "$DEV" >/dev/null
adb -s "$DEV" install -r "$APK" || exit 1
adb -s "$DEV" shell am force-stop com.nuviodebug.com </dev/null
printf 'compile: '; adb -s "$DEV" shell cmd package compile -m speed -f com.nuviodebug.com </dev/null
