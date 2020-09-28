Intro
=====

Wedaban is Android backup transport that implements [Android Backup API](https://developer.android.com/guide/topics/data/backup) and stores the backups on any WebDAV-compatible server.

This enables doing backups to the custom servers and without having Google account. Due to the use of Android Backup API, the backups for most applications should "just work", similarly to how they work with the "default" Google backup transport for Android.

**NOTE: Wedaban requires non-trivial setup, don't try to use it unless you're ready to dig quite deep into how things work!** In particular, Wedaban must run as a system app and needs some modifications to the system settings, so you either need root access to the device or should build your custom Android firmware to successfully integrate and use this application.

License
=======

Copyright (C) 2019 - 2020 Alexander Tsvyashchenko <android@endl.ch>

The license is [GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).

Details
=======

Both "Key / Value" and "Auto" backup APIs are supported. For "Key / Value", both "incremental" and "full" backups are supported.

The implementation makes the extensive use of WebDAV properties functionality to store backups metadata + "key / value" data when the values are small, so WebDAV server must support all standard properties-related functionality - in particular, the retrieval of properties via the `propname` element in PROPFIND command. For example, [SabreDAV](https://sabre.io/), which is used by [Nextcloud](https://nextcloud.com/), doesn't support `propname` element and needs custom patch to make it work, see `app/data/nextcloud-propfind-propname.patch`.

To compile Wedaban, you'll need the custom version of Android SDK with hidden APIs exposed - see https://github.com/aeab13/android-jar-with-hidden-api or https://github.com/anggrayudi/android-hidden-api for more information on this.

Wedaban needs to reside in `/system/priv-app` so that it can access the necessary system functionality. Additionally, it needs to be whitelisted and given extra permissions - see the files `app/data/permissions_ch.endl.wedaban.xml` and `apps/data/whitelist_ch.endl.wedaban.xml`, these need to be placed to `/system/etc/permissions` and `/system/etc/sysconfig` directories respectively.

Once Wedaban is properly installed on the device, you can configure the server settings in Wedaban UI, enable the transport via [bmgr](https://developer.android.com/studio/command-line/bmgr) command-line tool that's accessible via `adb shell`, trigger the initial backup through the same tool and check system logs to see whether there are any issues.

Why this name?
==============

WEbDAv Backups for ANdroid.

Possible improvements
=====================

* Configurability: currently many settings, such as max backup size, are hard-coded, it would be better to have these customizable in `Settings`.
* Backups management, reporting and UI: right now the only functioning piece of UI are settings, there's no UI to see the status of the recent backups or manage backups.
* System tests: right now there are only unit tests, given relatively complex interactions with the system services, WebDAV server and all the networking involved - having system tests that would bring up the test instance of WebDAV server and interact with it using the app running in emulator would make it much easier to ensure that the app "as a whole" works correctly.
