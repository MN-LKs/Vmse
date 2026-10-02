# Vmse — Spatial Stereo Music Plugin for Minecraft

**Vmse** (formerly DSMPMusic) is a music playback plugin for Minecraft 1.21.11 servers, built on Simple Voice Chat's `LocationalAudioChannel`. It delivers true stereo / 5.1 surround sound with dynamic source tracking, spatial acoustics, playlist looping, music regions, a golden-hoe sound-source tool, effect presets, and synced lyrics.

## Features

- 🔊 **Stereo / 5.1 Surround** — sources rotate with player yaw; per-source cursors avoid stutter
- 🗺️ **Music Regions** — circle/square zones, auto-play on enter, auto-stop on leave
- 📋 **Playlists** — SEQUENTIAL / SHUFFLE / LOOP / LOOP_ONE, YAML-persisted
- 🎵 **Effect Presets** — Viper / Dolby / 3D Surround / Bass Boost / Clear Voice (real-time DSP)
- 🔧 **Golden Hoe** — right-click to place a sound source inside a region
- 📝 **Lyrics** — mc_lyrics v1 JSON, per-word highlight, actionbar / title / bossbar / chat
- 🔄 **Migration** — auto-migrates legacy DSMPMusic data, hot-reload safe

## Dependencies

- Paper API 1.21.11
- Simple Voice Chat API 2.6.20
- Voice Messages (optional, `WWLeFuHa:mBsQhubX`)
- JLayer / MP3SPI / VorbisSPI

## Install

1. Drop `Vmse-1.0.0.jar` into `plugins/`
2. Restart; config generates on first run
3. `/vmse help` for all commands

## Commands

| Command | Description |
|---|---|
| `/vmse play <target> <file> [-original]` | Play music |
| `/vmse stop / pause / list / reload` | Playback control |
| `/vmse surround [player] [stereo\|5.1\|mono]` | Surround layout |
| `/vmse region create / remove / list / info` | Music regions |
| `/vmse effect list / set / info` | Effect presets |
| `/vmse hoe` | Golden hoe tool |
| `/vmse confirm source / bandwidth` | Confirm actions |

## Build

```bash
cd vmse
mvn -q -o clean package -DskipTests
# output: target/Vmse.jar → copy to plugins/
```

## Version

- **1.0.0** — independent plugin, effect system rewrite, region playlist fix

## License

MIT

---

## 中文版

[📖 点击查看中文版描述](./_zh.md)
