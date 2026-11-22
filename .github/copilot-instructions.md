# BIG IRL APP V2: project context

## End-to-end architecture and current status

**Intended path:** Android phone captures and encodes video/audio, sends it over Wi-Fi and cellular to the Jetson Nano, and the Nano relays the received stream to Twitch.

**Current implementation:** a transport selector routes one H.264/AAC encoder pipeline to Single SRT, SRT broadcast redundancy, SRTLA, single-path RIST, or RIST Main multipath. Single SRT uses RootEncoder `GenericStream` and the public IPv6 listener on UDP `10020`; the other senders use the shared MPEG-TS pipeline. SRT broadcast targets UDP `10010/10011`, binds each group socket to Android's Wi-Fi or cellular `Network`, and duplicates packets; it does not aggregate bandwidth. SRTLA uses the pinned Rust sender, local SRT proxy, Android-bound Wi-Fi/cellular sockets, and a Nano `srtla_rec` service on UDP `10012` forwarding to UDP `10000`. Single RIST uses UDP `11000` over cellular. RIST multipath falls back to one available path; compatibility with the current Nano receiver remains unconfirmed. The saved default profile is 1080p60 at 8 Mbps video. Native builds and on-device reception remain unverified. Neither Nano-to-Twitch forwarding nor end-to-end RIST reception has been verified.

The Nano currently receives and records several experimental paths, with HLS monitoring via nginx. No active service or script inspected was shown forwarding a received stream to Twitch. Treat Phone → Nano → Twitch as the target architecture, not a fully operational path.

## Key repository files

- `app/src/main/java/uk/co/bigirl/sender/StreamingService.kt` — camera/encoder lifecycle, transport selection, reconnect logic, cellular network request, bitrate state.
- `app/src/main/java/uk/co/bigirl/sender/MpegTsStream.kt` and `MpegTsMuxer.kt` — shared encoder callbacks and H.264/AAC MPEG-TS packetization for bonded SRT and RIST.
- `app/src/main/java/uk/co/bigirl/sender/SrtBondedStream.kt`, `SrtBondedSender.kt`, and `app/src/main/cpp/native_srt_bonded_sender.c` — bonded SRT sender and group link state.
- `app/src/main/java/uk/co/bigirl/sender/SrtlaConfig.kt`, `SrtlaProxy.kt`, and `app/src/main/rust/srtla-android/` — SRTLA endpoint validation, Rust sender JNI bridge, and Android network binding.
- `app/scripts/patch-srt-android.py` and `app/src/main/cpp/srt-android/CMakeLists.txt` — pinned SRT Android network-binding patch and native build.
- `app/src/main/cpp/native_rist_sender.c` — JNI libRIST sender and per-socket Android network binding.
- `app/scripts/build-native-android.py`, `app/src/main/cpp/meson.build`, and `app/src/main/cpp/srt-android/CMakeLists.txt` — pinned libRIST and patched SRT Android NDK builds.
- `app/src/main/java/uk/co/bigirl/sender/StreamProfile.kt` — 720p30, 720p60, 1080p30, and 1080p60 profiles and bitrates.
- `app/src/main/java/uk/co/bigirl/sender/BondingConfig.kt` — validated Wi-Fi/cellular bonded receiver settings used by the native sender.
- `app/src/main/java/uk/co/bigirl/sender/MainActivity.kt` — Compose UI, endpoint/profile controls, preview, and PiP.
- `app/src/main/java/uk/co/bigirl/sender/StreamBoxTelemetry.kt` — phone network display and Nano telemetry polling on port 8082.
- `app/src/main/AndroidManifest.xml` — camera, microphone, network, wake-lock, and foreground-service declarations.
- `app/build.gradle.kts` — Android/Kotlin configuration and RootEncoder dependency.
- `BONDING-PATCH-NOTES.md` — bonded SRT implementation status and validation gaps.
- `docs/QUALITY-ROADMAP.md` — planned live quality and network metrics.
- `streambox/` — Nano setup scripts for telemetry, recordings management, and the HLS monitor page.
- `streambox/install-srtla-receiver.sh` — installs the pinned OpenIRL SRTLA receiver as a system service; not yet run on the Nano.

## Jetson Nano relay (observed 2026-11-21; recheck before relying on live state)

Use the `jetson` wrapper for every host command (see below). Active BIG IRL services observed:

- `bigirl-receiver.service`: SRT UDP `10000`; `srt-live-transmit` feeds FFmpeg stream-copy recording and HLS under `/var/www/html/live`.
- `bigirl-ipv6-receiver.service`: IPv6 SRT UDP `10020`, then loopback UDP `10021` into FFmpeg recording/HLS.
- `bigirl-bonded-receiver.service`: custom multipath SRT test receiver on UDP `10010` (Wi-Fi member) and `10011` (cellular member); records to `~/bigirl-bonded-recordings` and writes HLS under `/var/www/html/live-bonded`. It runs `srt-test-mpbond-bonding`; this is not evidence that the Android app is using SRTLA.
- `bigirl-rist-receiver.service`: RIST Main Profile input UDP `11000`, output to loopback UDP `11001`. No receiving/forwarding process on `11001` or Twitch output was verified.
- `bigirl-recordings-web.service`: recordings UI/API on TCP `8081`.
- `bigirl-telemetry-api.service`: status API on TCP `8082`.
- nginx serves the HLS monitor on TCP `80`.

Other observed listeners include the RIST test sender `ristsender` on UDP `11002` and `52804`; their role in an end-to-end relay was not established. Installed streaming tools include `srt-live-transmit`, `srt-test-mpbond-bonding`, `ristreceiver`, `ristsender`, FFmpeg, and nginx. `srtla_rec`, SLS, and MediaMTX were not found. The legacy and current SRT paths are separate from the RIST receiver.

The user has successfully tested the direct IPv6 SRT route `srt://[2a0e:cb01:e9:cf00:4ab0:2dff:fe2f:610e]:10020/live` over Wi-Fi and cellular separately. The address was assigned globally on `eth0` at inspection; recheck it with `jetson ip -6 addr show dev eth0` because ISP IPv6 addressing can change. This proves single-link reachability/failover, not simultaneous bonding: `GenericStream` uses one SRT connection.

## SRTLA vs. RIST A/B test gaps

1. Build and device-test the SRTLA sender with the pinned Nano receiver on UDP `10012`; both sender and receiver setup are implemented but unverified.
2. Provide equivalent Nano output paths for SRTLA and RIST. RIST currently ends at loopback UDP `11001`; the SRT bonded receiver records/HLS but is not wired to Twitch.
3. Configure the Nano’s Twitch forwarding stage without putting stream keys in source or logs. Keep encoding/profile, output handling, and test conditions identical; collect per-link and receiver loss, latency, throughput, and stream-quality measurements.

## APK build and sideload workflow

`.github/workflows/build.yml` (`Build BIG IRL APP V2`) runs on branch pushes, pull requests, or manual dispatch. It uses Ubuntu 24.04, Java 17, and Gradle 8.11.1, then runs `gradle --no-daemon test assembleDebug --stacktrace`. The workflow also builds the pinned native libRIST dependency. On success, download the `BIG-IRL-APP-V2-debug-apk` artifact from the Actions run and sideload its APK to the phone. This workflow does not publish or install the APK on the device.

Several other manually dispatched workflows apply ZIP-based patches and commit them to the repository; inspect their effects before using them. `install-v2.yml` is a legacy project-install workflow, not the normal APK build.

## Host-command rule

This assistant may run inside a Docker container on the Nano. Prefix every command intended for the Jetson host with `jetson` (the wrapper enters the host namespace), for example `jetson systemctl --type=service --state=running --no-pager` or `jetson ss -tulnp`. Do not run host inspection or administration commands directly in the container. Avoid reading or printing secrets such as the recordings delete token or Twitch stream key.
