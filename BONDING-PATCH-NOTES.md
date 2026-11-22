# BENFOBOX Android bonding patch notes

## Confirmed receiver configuration

- Public IPv4: `145.40.153.191`
- Wi-Fi member: UDP `10010`
- Cellular member: UDP `10011`
- Stream ID: `live`
- Nano LAN address: `192.168.1.27`

## Implementation status

The app now has an unverified native Android SRT broadcast-group sender. It shares the existing H.264/AAC encoder and MPEG-TS packetizer rather than creating duplicate encoders. SRT broadcast mode sends each packet on every active link, providing path redundancy rather than aggregated bandwidth. Its implementation:

1. creates an SRT broadcast group with Wi-Fi and cellular member sockets;
2. binds each socket to its Android `Network` using a pinned SRT v1.5.3 source patch;
3. sends the shared MPEG-TS packets through the group API;
4. reports the number of connected group members to `StreamingService`.

The build and device reception have not yet been verified. This is a custom SRT group sender, not SRTLA. No Nano receiver configuration was changed.

## SRTLA sender and receiver

The app also has a separate SRTLA mode. It sends RootEncoder's SRT output to a local Rust SRTLA sender, which routes its uplink sockets through Android's Wi-Fi and cellular `Network` handles. The pinned OpenIRL `srtla_rec` receiver can be installed with `streambox/install-srtla-receiver.sh`; it listens on UDP `10012` and forwards SRT to the existing Nano listener on UDP `10000`. The install script has not been run, and neither build nor phone-to-Nano reception is verified. Allow inbound UDP `10012` through the server firewall before testing.

## RIST multipath

The app also has an unverified RIST Main Profile multipath sender. It creates two peers with a shared per-session CNAME, binds one UDP socket to Wi-Fi and the other to cellular before starting libRIST, and displays the connected path count. The existing Nano RIST listener on UDP `11000` has not been tested with these peers; receiver-side multipath compatibility is unknown.

## Why this patch does not change the default endpoint

The ordinary Single SRT mode continues to use UDP `10020`; it is not redirected to the bonded listener.
