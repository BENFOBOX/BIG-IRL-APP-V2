# Project work log

Append new entries at the top. Record the date, what changed, how it was checked, and any host-side actions separately from repository changes. Use plain language and avoid secrets.

### 2026-11-22 — Persistent RIST diagnostic report
- Added a bounded persistent app trace, bounded native libRIST trace, and an in-app Copy RIST Diagnostic button.
- The report captures mode, endpoint, state, link count, startup failures, reconnect reasons, native creation failures, and libRIST warnings/errors. It survives app reopening and avoids credentials.
- Static source checks and ZIP integrity checks passed. Android CI/device testing remains required.
- Host-side actions: none.

## 2026-11-21 — SRTLA sender and receiver integration

- Added a distinct SRTLA app mode backed by the pinned Rust sender, with each available Wi-Fi/cellular UDP socket bound through Android's network handle. RootEncoder sends SRT to the local proxy; the app reports active receiver links separately from the local encoder connection and reconnects if all links fail.
- Added one-path fallback behavior for SRTLA and RIST Main when either network disappears, and added a pinned OpenIRL `srtla_rec` install script for the Nano forwarding UDP `10012` to its existing SRT input on `127.0.0.1:10000`. Public source inspection confirmed matching REG1/REG2 registration and backward-compatible extended keepalive handling.
- SRTLA reconnect backoff now resets only after an actual receiver link becomes active, not merely when RootEncoder connects to the phone-local proxy.
- Added SRTLA endpoint parser tests and Rust setup to CI. Native/APK build and device reception remain unverified because Java, Gradle, Cargo, Android SDK/NDK, and CMake are unavailable in this environment.
- Host-side actions: none. The Nano setup script was added but not executed; no firewall or service configuration was changed.

## 2026-11-21 — RIST multipath and method ranking

- Added RIST Main multipath as a separate app mode. It creates same-CNAME peers with equal weights, binds their UDP sockets to Android Wi-Fi and cellular networks before starting libRIST, and reports the connected-link count. The CNAME is generated per sender session to avoid merging unrelated streams.
- Clarified the SRT broadcast label and docs: it duplicates packets for path redundancy, not bandwidth aggregation. The RIST multipath receiver behavior on the Nano is still unknown; no host configuration was changed.
- Ranked the viable methods for this phone → Nano → Twitch setup: RIST Main multipath is the best custom-app aggregation candidate if the Nano can merge it; SRTLA is a well-established bonding option but requires a compatible receiver/service; Speedify is an easier VPN-level bonding experiment but conflicts with the app's explicit per-socket network binding; SRT broadcast is redundancy only; single-link SRT/RIST are baselines; direct RTMP via Larix is a simpler path to Twitch but bypasses the Nano.
- Validation intentionally deferred until implementation completion, as requested. No Android build, native build, or device test has run. The available local environment lacks the Android and native toolchains.
- Host-side actions: none. No Jetson configuration was changed.

## 2026-11-21 — Native bonded SRT integration in progress

- Added the bonded-SRT transport mode and a shared H.264/AAC MPEG-TS sender path for RIST and bonded SRT. The bonded sender creates an SRT broadcast group with Wi-Fi and cellular members and reports connected-link count.
- Added a pinned SRT v1.5.3 Android build with a source patch to bind each group-member UDP socket to its Android `Network`. The patch anchor was checked against the pinned upstream source and corrected to target the socket-creation block uniquely.
- Updated network callbacks so they observe Wi-Fi without requesting it, keep cellular availability requested, and report network-loss state without changing the phone's global data settings.
- Updated the README, bonding notes, and project instructions to distinguish this custom SRT group sender from SRTLA and to mark native transports as unverified.
- Validation: `git diff --check` passed. Local Android/native builds and Python syntax checks are unavailable because Java, Gradle, Android SDK/NDK, CMake, and Python 3 are absent. No GitHub Actions build or on-device receive test has run.
- Host-side actions: none. No Jetson configuration was changed.

## 2026-11-21 — RIST sender integration in progress

- Added a transport selector for Single SRT and RIST while retaining one RootEncoder camera/encoder pipeline. Both modes use the selected saved H.264/AAC profile; the default remains 1080p60 at 8 Mbps video.
- Added a RootEncoder `StreamBase` implementation that packetizes H.264/AAC as MPEG-TS and sends it through libRIST Main Profile. The native sender uses libRIST custom UDP callbacks and binds sockets to Android's selected cellular network.
- Added a pinned libRIST Android NDK/Meson build and CI setup. The first dependency/API review matched the JNI calls to the local libRIST v0.2.20 headers; this is not a substitute for the pending CI build.
- Updated the UI to show the selected transport and mode-specific destination. This entry describes the prior state before the bonded-SRT integration recorded above.
- Validation: `git diff --check` passed. A local APK build is unavailable because Java/Gradle/Android SDK are absent; Python syntax validation could not run because Python 3 is absent. No GitHub Actions build or on-device RIST receive test has run yet.
- Host-side actions: none. No Jetson configuration was changed.

## 2026-11-21 — Project and Nano baseline

- Revised the SIM-only SRT approach after checking Android's network APIs: the app requests cellular availability and waits for it, but no longer binds the entire app process to cellular. With Wi-Fi off, Android's default network should route the SRT connection over SIM; future bonded sending needs per-socket routing.
- Changed the app's default endpoint from the private home-LAN address to the previously tested public IPv6 SRT listener on UDP `10020`. Existing installs using the old default are migrated; custom saved endpoints remain unchanged. The public IPv6 address can change and must be kept current.
- Updated the README and Copilot project context to distinguish a single cellular SRT link from Wi-Fi/cellular bonding. No Jetson host configuration was changed.
- Read-only StreamBox check confirmed the configured IPv6 address is still assigned to `eth0` and the SRT listener is active on UDP `10020`.
- Validation: `git diff --check` passed. A local Android build could not be run because Java and Gradle are unavailable in this environment.
- Read the user's earlier project history: target is best practical image quality, FPS, and reliability from the Find X9 Pro through the Nano to Twitch. The first benchmark should hold one 1080p60 H.264/AAC encode and bitrate constant while testing single SRT, native SRT bonding, and RIST. SRTLA is deferred because the existing Nano does not run a production-compatible receiver.
- Verified integration requirements: RootEncoder's `GenericStream` exposes only one selected transport and has no RIST client or native SRT-group sender. The checked local SRT build has `ENABLE_BONDING=OFF`; libRIST has Android platform support in source but no Android sender is integrated into the app. A real comparison needs a shared encoded-frame path and native sender integrations, not just a transport dropdown. The SRT receiver/recording and RIST receiver are present on the Nano, but no Nano configuration was changed.
- Reviewed RootEncoder 2.8.2 source: `StreamBase` exposes encoded H.264/AAC callbacks, so the app can share one hardware-encoder pipeline across sender modes. Its SRT client owns its transport and MPEG-TS packetizer, so this still requires a custom stream class. The SRT bonding API allows per-member socket options, but there is no existing Android `Network` handle integration in this app. libRIST provides custom UDP transport callbacks that may support per-peer Android socket binding; this needs a native proof of concept.
- The attempted current START test produced no new recording; latest IPv6 recording remains from 15:33. The most recent downloaded APK is still the old SRT-only build. No new APK was built.
- Added the current stream status/error message to the control panel so START failures and SIM/network waiting states are visible instead of only changing the small camera overlay state.

- Reviewed the app, build workflow, relevant sources, and the ten latest commits. Repository head was `1207349` (`Apply all-in-one stability PiP and live quality update`).
- Added `.github/copilot-instructions.md` to capture the Phone → Nano → Twitch target architecture, current implementation, key files, observed host services/ports, APK workflow, and the `jetson` command rule.
- Android status at the initial baseline review, before the SIM-routing fix above: RootEncoder sent one SRT stream and the cellular request did not bind it to a network. `BondingConfig` is only configuration; the app has no SRTLA bonding sender or RIST sender.
- Nano status observed: SRT input on UDP `10000`; custom multipath SRT receiver on UDP `10010/10011`; IPv6 SRT input on UDP `10020` forwarded locally to `10021`; RIST input on UDP `11000` outputting locally to UDP `11001`. Existing SRT paths record and publish HLS. No downstream process on `11001` or Twitch forwarding path was verified.
- Observed Nano management/monitoring listeners: nginx TCP `80`, recordings manager TCP `8081`, and telemetry API TCP `8082`. Installed tools include `srt-live-transmit`, `srt-test-mpbond-bonding`, `ristreceiver`, `ristsender`, FFmpeg, and nginx; `srtla_rec`, SLS, and MediaMTX were not found.
- APK build workflow: `.github/workflows/build.yml` runs tests and `assembleDebug`, then uploads `BIG-IRL-APP-V2-debug-apk` for sideloading.
- Build check attempted: this container has no Java, Gradle, or Android SDK, so it cannot produce an APK locally. Use the repository's GitHub Actions build runner for APK validation.
- The user chose one APK with an in-app transport selector for the eventual A/B test. No transport implementation has started.
- The user clarified: build a new APK before testing; do not use the installed app for the next test. The requested test profile is 1080p60.
- Feasibility review found a naming mismatch to resolve: the Nano's bonded listener runs the custom `srt-test-mpbond-bonding` SRT broadcast-group binary; `srtla_rec` was not found. It is not yet verified to be the standard SRTLA protocol the user wants to compare with RIST.
- Created local branch `feature/srtla-rist-ab-test`. Further review confirms RootEncoder 2.8.2 exposes SRT and UDP clients, while a SRTLA/RIST app path requires additional transport integration. The SRTLA sender project is Rust and has an Android socket-binding callback; librist exposes native C transport hooks. These are feasibility findings, not integrated app code.
- The new APK cannot be built in this container because Java, Gradle, and the Android SDK are missing; the project GitHub Actions build runner is the available build path.
- Checked GitHub Actions: run #52 succeeded for latest `main` commit `1207349` and produced `BIG-IRL-APP-V2-debug-apk`. That APK includes the existing 1080p60 profile, but is still the SRT-only app, not the planned SRTLA/RIST build.
- Twitch's developer documentation confirms Twitch ingest is RTMP and requires a stream key. The suitable target design is phone transport (SRTLA/RIST) to Nano, then a Nano-side RTMP relay using stream copy where compatible. The current Nano only records/HLS-monitors its SRT input; no Twitch relay is configured. Keep the stream key on the Nano, not in chat or source.
- The user reports testing Wi-Fi and SIM separately using `srt://[2a0e:cb01:e9:cf00:4ab0:2dff:fe2f:610e]:10020/live`. Current host inspection confirms that global IPv6 address is assigned to `eth0`, the IPv6 SRT receiver is active on UDP `10020`, and its FFmpeg pipeline forwards locally to UDP `10021`.
- `ffprobe` on the prior IPv6 recording `bigirl-ipv6-2026-11-21_01-34-16.ts` found H.264 High, 1920x1080, 60000/1001 fps, AAC-LC stereo at 48 kHz, and ~5.90 Mbps overall for 98.78 seconds. This confirms a good 1080p60-class single-link SRT stream reached the Nano. It does not prove simultaneous Wi-Fi+cellular bonding or Twitch delivery.
- Prior-chat context confirms the user connected from Termius using `StreamBox Home`; the Nano identified itself as `streambox`, with home address `192.168.1.27` and Tailscale address `100.68.249.56`. The prior chat proposed a first single-link test to `srt://192.168.1.27:10000/live` at 720p30, but did not include that test's result.
- Read-only Nano check at 14:59 UTC: `bigirl-receiver.service` was active and UDP `10000`, `10010`, and `10011` were listening. No receiver log entries from the last 30 minutes or current main HLS files were present; no live stream was confirmed.
- No Android implementation or Nano configuration/service was changed during this inspection. The host checks were read-only.
- A clean SRTLA-vs-RIST test still needs both phone senders, equivalent Nano receive/forward paths, a Twitch forwarding stage, and comparable transport/stream metrics.

## Future work

For each project task, add a dated entry describing repository changes, Nano host changes (if any), build/test outcomes, and remaining work. For Nano commands, use the `jetson` wrapper and distinguish observed runtime state from committed project configuration.
