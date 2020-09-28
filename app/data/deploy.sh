#!/bin/sh
adb root
adb remount
adb push ../../build/wedaban/app/outputs/apk/debug/app-debug.apk /sdcard/
adb shell 'cat /sdcard/app-debug.apk > /system/priv-app/Backup.apk'
adb reboot
