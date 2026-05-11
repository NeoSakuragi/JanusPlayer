# Janus Media Server

Serves curated anime library to the Janus player app.

## Setup

```bash
pip install -r requirements.txt
```

## Directory Structure

```
/data/janus/
├── videos/
│   ├── saint-seiya/          # MKV files
│   └── maison-ikkoku/
├── subs/
│   ├── saint-seiya/          # Pre-extracted SRT files (JA/FR/EN)
│   └── maison-ikkoku/
├── covers/                   # Cover art from AniList
└── library.json              # Generated metadata
```

## Usage

```bash
# Rebuild library from video files (extracts subs, generates metadata)
python3 manage.py build

# Fetch cover art from AniList
python3 manage.py covers

# Show library status
python3 manage.py status

# Start server
python3 manage.py serve
```

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/library` | Full library JSON |
| `GET /api/video/{series}/{file}` | Video stream (Range support) |
| `GET /api/subs/{series}/{file}` | SRT subtitle file |
| `GET /api/covers/{file}` | Cover/banner image |
| `GET /api/health` | Health check |

## Adding a Series

1. Create `videos/{series-id}/` and copy MKV files
2. Edit `build_library.py` SERIES list with file pattern
3. Run `python3 manage.py build`
4. Run `python3 manage.py covers`
