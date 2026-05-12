# Emulator Setup

## AVD: JanusTV
- **Profile**: Google TV, 1920x1080, API 34
- **ABI**: x86 (fast on desktop)
- **Supports**: D-pad (keyboard arrows) + touch (mouse clicks) simultaneously

## Launch
```bash
~/Android/Sdk/emulator/emulator -avd JanusTV
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
| Space | Play/Pause (in player) |

### Touch (mouse)
| Action | Result |
|--------|--------|
| Click on card | Select series/movie/episode |
| Click on video | Show/hide controls |
| Click on subtitle word | Jump to word in dictionary |
| Click on control button | Activate (audio, subs, font, etc.) |
| Click on seekbar | Seek to position |
| Click outside list | Dismiss list overlay |
| Drag on LazyRow | Scroll horizontally |

### Testing checklist
1. **D-pad**: Arrow to series, Enter to open, arrow to episode, Enter to play
2. **Touch**: Click a series card directly, click an episode to play
3. **Mixed**: Click a card (focus moves), then arrow key (continues from clicked position)
4. **Player D-pad**: Down to show subs/controls, arrows to navigate words, Up for buttons
5. **Player touch**: Click subtitle word, click control buttons, click seekbar
6. **Update banner**: Shows if installed versionCode < Hetzner version

## Server Connection
The emulator uses `10.0.2.2` to reach the host machine's localhost.
Default server URL: `http://10.0.2.2:8900`

Make sure the Janus server is running:
```bash
cd /data/janus/server && python3 /home/bruno/VideoPlayer/server/server.py
```
