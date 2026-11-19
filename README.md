# BIG IRL APP V2

Clean SRT-first Android contribution sender for the Find X9 Pro and the Jetson Nano StreamBox.

## First milestone

1. Camera and microphone are owned by an Android foreground service.
2. H.264 720p30 and AAC are packaged by RootEncoder for SRT.
3. The phone calls `srt://192.168.1.27:10000`.
4. The Jetson Nano listens with Haivision SRT 1.5.3.
5. Switching away from the app must not stop the stream.

## StreamBox test listener

```bash
~/srt/build/srt-live-transmit -ll:info "srt://0.0.0.0:10000?mode=listener" file://con
```

Binary MPEG-TS data may appear in the terminal after connection. For a cleaner later test, relay it to a file or an SRT-enabled FFmpeg build.

## Build

Push the project to GitHub, open **Actions**, run **Build BIG IRL APP V2**, then download the `BIG-IRL-APP-V2-debug-apk` artifact.

## V2 resilience update

- Saved SRT endpoint with the correct `/live` stream ID.
- Saved quality profile selector: 720p30, 720p60, 1080p30, 1080p60.
- Automatic SRT reconnect with 0, 0.5, 1, 2 and 5 second backoff.
- Correct IDLE, RECONNECTING and LIVE states.
- Preview reattachment no longer overwrites live bitrate status.

## StreamBox recordings manager

Copy `streambox/install-recordings-manager.sh` to the StreamBox and run it once. It installs a boot-enabled browser manager at `http://192.168.1.27:8081` with playback, download, storage reporting and token-protected deletion.
### Bonded receiver integration

Receiver values and the native sender boundary are documented in `BONDING-PATCH-NOTES.md`. The public receiver is `145.40.153.191`, using UDP ports `10010` (Wi-Fi member) and `10011` (cellular member). The current RootEncoder single-link path remains unchanged until the JNI SRT broadcast-group sender is integrated.

