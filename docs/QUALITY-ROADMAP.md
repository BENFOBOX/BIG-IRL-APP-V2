# BENFOBOX live quality roadmap

Planned for a later control-panel patch:

- change video bitrate while streaming without stopping the SRT session;
- user-selectable small/medium/large quality steps;
- separate automatic increase and decrease thresholds;
- cooldown and hysteresis so quality does not oscillate;
- preserve 1080p60 where bandwidth permits;
- show requested bitrate, actual transmitted bitrate, packet loss and RTT;
- when Wi-Fi + SIM redundancy is added, allow per-link differential targets and a combined ceiling;
- request a keyframe after a material live encoder change;
- protect audio bitrate and avoid resolution changes during brief network dips.

The first implementation should use RootEncoder's runtime bitrate control and keep resolution/FPS fixed during a live session. Resolution or codec-profile changes should remain stop/reprepare operations unless tested safe on the target handset.
