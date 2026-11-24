# BK Simulator (Android)

An offline Android prototype for building, importing, testing and exporting onboard-computer sound packs.

## Build APK
Every push to `main` runs **Build BK Simulator APK**. Open the repository's **Actions** tab, select the latest successful run, then download the `BK-Simulator-Android` artifact. It contains:
- `app-debug.apk` — install on Android (allow installation from the file manager if prompted).
- `BK-Starter-KyivMetro.zip` — sample BK pack with two generated WAV announcement tones.

You can also start a build manually from Actions → Build BK Simulator APK → Run workflow.

## Pack format (v1)
A ZIP contains `bk.json` and the audio files referenced by event.file:

```json
{
  "formatVersion": 1,
  "id": "kiyvmetro",
  "name": "Киевский метрополитен",
  "author": "BK Creator",
  "events": {
    "stop": {
      "name": "Остановка: Киево-Печерская лавра",
      "file": "kiyevopecher.wav"
    },
    "nextStation": {
      "name": "Следующая станция",
      "file": "kiyevopecher1.wav"
    }
  }
}
```

Supported audio playback depends on Android's media codecs (commonly MP3, WAV, OGG/Opus, M4A). The app imports ZIP archives through Android's document picker, validates paths and JSON, and stores the imported pack locally.

## Current prototype
- Event list, event editor and raw JSON editor.
- Pick audio files from the phone for events.
- ZIP import/export.
- Audio preview.
- Offline operation after installation.

The first version is a prototype; release signing, richer validation, multiple saved packs and a more complete route/stop simulator can be added next.
