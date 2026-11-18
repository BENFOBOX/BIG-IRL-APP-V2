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
