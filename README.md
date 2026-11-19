# Whatbox Extractor

A small Android app for browsing the folders on a Whatbox seedbox and extracting `.zip`, `.rar` and `.7z` archives with one tap, right next to the archive.

## Install
Download the latest `WhatboxExtractor-*.apk` from [Releases](../../releases/latest) on your phone and open it. (Allow "install unknown apps" for your browser the first time.)

After that, the app checks this repo for new releases on launch and shows an **Install** button when there's an update.

## How it works
- Connects over SSH/SFTP with your Whatbox login (stored encrypted on the phone only).
- Tapping an archive runs the extraction **on the server** (`unzip` / `unrar` / `7z`, whichever is installed) in the background, so it's fast and keeps going if your phone sleeps or you close the app.
- Options: extract into a new folder named after the archive, and delete the archive after a successful extract.
- Multi-part RARs: tap the `.part1.rar` file.

## Builds
Every push to `main` builds a signed APK with GitHub Actions and publishes it as a new release (`v1.<build number>`). The signing key is in `signing/` so every build can update the previous one.
