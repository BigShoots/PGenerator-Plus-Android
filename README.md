# PGenerator+ Android

A cross-platform HDMI display calibration pattern generator for Android devices. PGenerator+ Android turns an Android TV, Chromecast, or any Android device with HDMI output into a professional-grade test pattern generator — compatible with Calman, ColourSpace/LightSpace CMS, HCFR, and DisplayCAL.

## Features

- **Bit-perfect pattern rendering** via OpenGL ES 3.0 (RGBA8 framebuffer, no gamma/color space transforms)
- **Multi-protocol support**: PGenerator (TCP 85), CalMAN UPGCI (TCP 2100), Resolve XML (TCP 20002)
- **UDP discovery** on port 1977 — calibration software finds the device automatically
- **Embedded Web UI** (port 8080) — control patterns, signal settings, and view device info from any browser
- **HDR10/HLG support** via Android Display API + Amlogic sysfs (for Chromecast/Amlogic devices)
- **Dynamic HDR/SDR switching** — CalMAN can change HDR mode and metadata mid-session via UPGCI
- **DRM/AVI InfoFrame control** on Amlogic devices — sets correct EOTF and colorimetry flags for display calibration
- **Built-in test patterns**: PLUGE (BT.814-4), Color Bars (BT.2111-2), Window, Grayscale Ramp
- **Android TV / Leanback** compatible

## Supported Calibration Software

| Software | Protocol | Port | Notes |
|----------|----------|------|-------|
| **Calman** | UPGCI v2.0 | TCP 2100 | Dynamic HDR/SDR, bit depth, InfoFrame metadata |
| **ColourSpace / LightSpace CMS** | PGenerator | TCP 85 | Rectangle patterns with custom geometry |
| **HCFR** | PGenerator | TCP 85 | Full field and window patterns |
| **DisplayCAL / Resolve** | Resolve XML | TCP 20002 | Connect as client to DisplayCAL/Resolve server |

## Hardware Requirements

- Android device with HDMI output (API 24+ / Android 7.0+)
- **Recommended**: Amlogic-based devices (Chromecast with Google TV, etc.) for full HDR/InfoFrame control
- OpenGL ES 3.0 support required

## Building

```bash
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK
./gradlew clean              # Clean build artifacts
```

## Project Structure

```
app/src/main/java/com/pgeneratorplus/android/
├── MainActivity.kt              # Configuration & launch UI
├── PatternActivity.kt           # Fullscreen renderer + server management
├── model/
│   ├── AppState.kt              # Thread-safe shared state singleton
│   └── DrawCommand.kt           # OpenGL quad primitive (NDC coords + colors)
├── network/
│   ├── PGenServer.kt            # PGenerator TCP server (port 85)
│   ├── UPGCIServer.kt           # CalMAN UPGCI TCP server (port 2100)
│   ├── ResolveClient.kt         # Resolve XML TCP client
│   ├── XmlParser.kt             # Calibration XML parser
│   ├── DiscoveryService.kt      # UDP broadcast discovery (port 1977)
│   └── WebUIServer.kt           # Embedded HTTP server (NanoHTTPD, port 8080)
├── renderer/
│   └── PatternRenderer.kt       # OpenGL ES 3.0 pattern renderer
├── patterns/
│   └── PatternGenerator.kt      # Built-in test pattern generator
└── hdr/
    └── HdrController.kt         # HDR mode & metadata controller
```

## Web UI

When running in PGen mode, an embedded web server starts on port 8080. Open `http://<device-ip>:8080` in any browser to access the dashboard:

- **Device Information** — model, Android version, IP, resolution, HDR capabilities
- **Connection Status** — active protocol, connected clients, port assignments
- **Quick Patterns** — one-click Black, White, Red, Green, Blue, Clear
- **Custom RGB** — enter specific RGB values for full field or window patterns
- **Signal Configuration** — HDR toggle, bit depth, EOTF, color format, colorimetry, quantization range

## Protocol Details

### PGenerator Protocol (TCP 85)

Standard PGenerator protocol compatible with HCFR and LightSpace CMS:

- Messages terminated with `0x02 0x0D`
- Responses null-terminated
- Commands: `CMD:GET_RESOLUTION`, `CMD:GET_GPU_MEMORY`, `RGB=RECTANGLE;...`
- UDP discovery: client sends `"Who is a PGenerator"` → device replies `"I am a PGenerator <hostname>"`

### UPGCI Protocol (TCP 2100)

CalMAN Unified Pattern Generator Control Interface v2.0:

- Messages framed with STX (0x02) prefix, ETX (0x03) suffix
- All responses are a single ACK byte (0x06) — sent immediately to avoid CalMAN timeout
- RGB values are 10-bit (0-1023), converted to 8-bit: `floor(val * 256 / 1024)`
- `CONF_HDR` controls HDR mode, primaries, and mastering display metadata
- `CONF_LEVEL` controls bit depth, video range, color format, and gamma mode

### Resolve XML Protocol (TCP 20002)

Connects to DisplayCAL, Calman, or ColourSpace as a pattern client:

- 4-byte big-endian length prefix followed by XML payload
- Parses `<color>`, `<background>`, `<geometry>`, `<rectangle>` elements
- Supports both standard and LightSpace XML formats

## HDR Support

### Amlogic Devices (Chromecast, etc.)

Full HDR control via sysfs at `/sys/class/amhdmitx/amhdmitx0/`:

- `hdr_mode` — enable/disable HDR output
- `attr` — color format and bit depth (e.g., `444,10bit`)
- `hdr_mdata` — static HDR metadata (BT.2020 primaries, MaxCLL, MaxFALL, MaxDML)

### Standard Android Devices (API 26+)

HDR via `Window.colorMode` and `Display.HdrCapabilities`:

- Detects supported HDR types (HDR10, HLG, Dolby Vision, HDR10+)
- Reports max/min luminance capabilities
- Sets window color mode to HDR when enabled

## License

Part of the PGenerator+ project.
