# Emulator Setup

## AVD: JanusTV
- **Profile**: Google TV, 1920x1080, API 34
- **ABI**: x86
- **RAM**: 8 GB, heap 512 MB
- **GPU**: host mode (hardware accelerated)
- **Supports**: D-pad (keyboard arrows) + touch (mouse clicks) simultaneously

## Launch
```bash
~/Android/Sdk/emulator/emulator -avd JanusTV -gpu host
```

## Install & Run
```bash
./gradlew installDebug
adb shell am start -n com.videoplayer/.LibraryActivity
```

## Input Mapping

### D-pad (keyboard)
| Key | Action |
|-----|--------|
| Arrow keys | D-pad navigation |
| Enter | Select / confirm |
| Escape | Back |

### Touch (mouse)
| Action | Result |
|--------|--------|
| Click on card | Select series/movie/episode |
| Click on video | Show/hide controls |
| Click on subtitle word | Jump to word in dictionary |
| Click on control button | Activate (audio, subs, font, HW/SW, download) |
| Click on seekbar | Seek to position |
| Drag on seekbar | Scrub through video |

## Server Connection
Default URL: `http://10.0.2.2:8900` (emulator → host localhost)
LAN URL: `http://192.168.1.29:8900`

The Go server must be running:
```bash
/home/bruno/CLProjects/Janus/server-go/janus-server
```

## Note
Video playback is slow on the emulator — software HEVC/H.264 decoding only (no hardware decode on x86 emulator). Not representative of real device performance.
