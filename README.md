# VidGrab — social media video downloader for Android

Download videos from **Instagram, TikTok, YouTube, Facebook, X (Twitter), Reddit, Vimeo,
Pinterest, Twitch, Dailymotion, Snapchat Spotlight, Threads** and 1000+ other sites, straight to your
phone's gallery.

It is built on [yt-dlp](https://github.com/yt-dlp/yt-dlp) (bundled through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android)) and ffmpeg, so it supports
every site yt-dlp supports, and it can update itself as sites change.

## Features

- **Share → VidGrab**: in Instagram, TikTok, YouTube etc. tap *Share* and pick VidGrab. The link is
  picked up and previewed for you.
- Paste a link, or paste the whole share text ("Look at this! https://vm.tiktok.com/…"); the app finds the URL in it.
- Preview the title, thumbnail, uploader and length before downloading.
- **Video (MP4)** in Best / 1080p / 720p / 480p / 360p, or **Audio only (MP3)**.
- Downloads keep running in the background with a progress notification and a Cancel button.
- Files are saved to `Movies/VidGrab` (videos) or `Music/VidGrab` (audio), so they show in your
  gallery and music apps. Open or share them from the app.
- Multi-video posts (e.g. Instagram carousels) download every video.
- The yt-dlp engine updates itself once a day, and you can update it from the ⋮ menu.

## Get the APK

Every push builds the app with GitHub Actions:

1. Open the repository's **Actions** tab → **Build APK** → the latest run.
2. Download the **VidGrab-apks** artifact and unzip it.
3. Install the APK that matches your phone. Most current phones use `app-arm64-v8a-release.apk`. If you're
   not sure, use `app-universal-release.apk` (bigger, but it runs on every phone).
4. Android will ask you to allow installing from this source. Allow it.

The release APK is signed with a debug key so it installs directly. Set up your own signing key
before you distribute it.

## Build it yourself

You need Android Studio (or JDK 17 and the Android SDK, API 35).

```bash
./gradlew assembleRelease      # APKs in app/build/outputs/apk/release/
./gradlew installDebug         # install on a connected phone
```

## Limits

- Private posts, and some Instagram content, need you to be logged in. VidGrab doesn't sign in to
  accounts, so it can't download those.
- Posts that are only photos (no video) aren't supported.
- If a site suddenly stops working, use ⋮ → *Update downloader engine*. yt-dlp is usually fixed within days.

## Please download responsibly

Only download videos you own or have permission to save, and follow each platform's terms of
service and your local copyright law.

## Project layout

```
app/src/main/java/com/noxyyd/vidgrab/
  VidGrabApp.kt      app start: notification channel, engine init + daily yt-dlp update
  Engine.kt          yt-dlp/ffmpeg setup, updating, readable error messages
  DownloadWorker.kt  background download (WorkManager), progress notification, yt-dlp options
  MediaSaver.kt      copies finished files into the public Movies/Music/Pictures/Download folders
  MainViewModel.kt   screen state, link preview, starting/cancelling downloads
  MainActivity.kt    handles links shared from other apps
  ui/                Jetpack Compose screen and theme
```
