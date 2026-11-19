# BENFOBOX Android bonding patch notes

## Confirmed receiver configuration

- Public IPv4: `145.40.153.191`
- Wi-Fi member: UDP `10010`
- Cellular member: UDP `10011`
- Stream ID: `live`
- Nano LAN address: `192.168.1.27`

## Important implementation boundary

The current app uses RootEncoder `GenericStream.startStream(endpoint)`, which creates one SRT sender connection.
A real SRT broadcast group cannot be produced merely by starting two `GenericStream` objects because camera/audio encoders cannot safely be owned twice and the resulting streams would have independent packet sequencing.

The next implementation must add a native Android sender that:

1. receives one encoded MPEG-TS packet stream from the existing encoder pipeline;
2. creates an SRT broadcast group;
3. creates two group-member sockets;
4. binds the first socket to an Android Wi-Fi `Network`;
5. binds the second socket to an Android cellular `Network`;
6. sends each MPEG-TS packet once through the group API;
7. reports per-member state and bitrate to `StreamingService`.

`BondingConfig.kt` added by this patch provides the validated receiver values without changing the currently working single-link sender.

## Why this patch does not change the default endpoint

Sending the current ordinary RootEncoder SRT connection to port `10010` would not turn it into a bonded group and could fail against the group listener. The existing endpoint is therefore left unchanged until the native sender is present.
