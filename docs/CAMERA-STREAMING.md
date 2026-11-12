# Camera streaming paths and evaluation

Home Assistant remains the discovery/configuration source. A camera's video transport can be chosen separately.

| Path | Current support | Purpose |
| --- | --- | --- |
| HA-negotiated WebRTC | Available automatically | Low-latency video; codec support depends on Android System WebView and the provider |
| Direct go2rtc HTTP SDP/WebRTC endpoint | Available as a camera endpoint override | Bypasses HA's stream-request/signaling API; go2rtc still relays the camera media |
| Direct HTTP HLS endpoint | Available as an HLS override | Native Media3 playback from the specified server |
| HA HLS | Automatic WebRTC fallback | Native Media3 decoding, with a labelled snapshot while starting/retrying |
| Direct camera RTSP over TCP | Evaluated; not implemented in 0.5.3 | Candidate native path that would avoid WebView and the HA streaming intermediary |

## Findings behind the 0.5.3 fix

Read-only diagnostics from an affected Sony TV showed WebView `NullVideoDecoder` errors and no-video fallbacks. This establishes a decoder-path problem, not that HA is the cause. The reported camera's main stream is already H.264; H.265 cannot be assumed to explain every failure.

The previous WebRTC watchdog used `totalVideoFrames`. That includes frames dropped before decoding, so an unsuccessful decoder could keep a blank card labelled Live indefinitely. The fixed watchdog subtracts dropped frames, requires nonzero video dimensions and sends progress heartbeats. Native code removes stale Live status and requests HLS fallback even if the renderer stops executing its watchdog. Native HLS also checks frame progress after the first rendered frame and retries a silent stall.

Tests reproduce the all-dropped-frame case in the production page, including a real Android WebView, and still decode synthetic WebRTC and simultaneous HLS video. These checks do not establish that every physical camera plays after the patch.

## Immediate comparison without new camera credentials

Reolink exposes Fluent (low resolution), Balanced and Clear (high resolution) camera entities. For the TV grid, try the available Fluent entities before the resource-intensive Clear streams. Change the selected camera entities in launcher settings or phone setup and save. This does not change the camera's recording configuration. Do not select a Snapshots entity expecting full-motion video.

## Direct RTSP candidate

The proposed path is Camera → RTSP over TCP → native Media3 player → TV. The Android RTSP module documents H.264 support and BASIC/DIGEST authentication. Start with a low-resolution H.264 substream; a transport change does not guarantee codec compatibility or additional hardware decoder capacity.

A future per-camera Direct RTSP mode should accept a local address and separate credentials through phone setup, store credentials encrypted with Android Keystore, construct authenticated URIs only in memory and keep credentials/stream URLs out of diagnostics. It must retain the current four-visible-player limit, fullscreen exclusivity, stop-on-background behaviour and HA fallback options. No camera password should be pasted into a GitHub issue or chat.

Compare native RTSP, HA WebRTC and HA HLS on the same stream: time to first decoded frame, measured latency, 30-minute continuity, reconnection after network loss, TV memory, simultaneous-feed limits, fullscreen return and standby/wake. Camera credentials and a supported local RTSP endpoint are needed before that path can be validated on a real camera. No camera/HA settings were changed during this evaluation.

References: [Media3 RTSP](https://developer.android.com/media/media3/exoplayer/rtsp), [Reolink camera streams and limitations](https://www.home-assistant.io/integrations/reolink/), [go2rtc](https://github.com/AlexxIT/go2rtc), [W3C playback-quality counters](https://w3c.github.io/media-playback-quality/).
