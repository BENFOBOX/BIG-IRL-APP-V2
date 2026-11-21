# BIG IRL APP V2

Clean SRT-first Android contribution sender for the Find X9 Pro and the Jetson Nano StreamBox.

## Current app

1. Camera and microphone are owned by an Android foreground service.
2. One H.264/AAC encoder pipeline supports selectable Single SRT and RIST senders. The saved default profile is 1080p60 at 8 Mbps video.
3. Single SRT uses the StreamBox public IPv6 listener on UDP `10020`; with Wi-Fi off, the app requests cellular availability and Android routes this single connection over its default cellular network.
4. RIST uses the StreamBox receiver on UDP `11000` and binds its UDP sockets to the requested cellular network when available.
5. Switching away from the app must not stop the stream.

The app's native SRT bonding sender is not implemented yet; the bonded listener on UDP `10010/10011` is not a valid endpoint for the Single SRT mode. The planned comparison is Single SRT, native SRT bonding, and RIST, with SRTLA deferred.

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

Receiver values and the native sender boundary are documented in `BONDING-PATCH-NOTES.md`. The public receiver is `145.40.153.191`, using UDP ports `10010` (Wi-Fi member) and `10011` (cellular member). The current RootEncoder single-link path is not bonded. The app requests cellular availability while streaming but leaves routing to Android's default network; with Wi-Fi off, that should be cellular. A private endpoint such as `192.168.1.27:10000` is only reachable on the home network.

