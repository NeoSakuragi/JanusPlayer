# Janus Build Tools (Python)

**Note**: These Python scripts are build tools only. The Go server (`/server-go/`) handles serving.

## Tools

| Script | Purpose |
|--------|---------|
| `build_library.py` | Scan videos, extract subs (ffmpeg), fetch TMDB metadata, generate JSON |
| `fetch_covers.py` | Download cover art from AniList API |
| `manage.py` | CLI wrapper: build, covers, status |
| `config.py` | Data directory paths |

## Usage

```bash
python3 manage.py build     # Extract subs + fetch TMDB + generate JSON
python3 manage.py covers    # Fetch cover art from AniList
python3 manage.py status    # Show library stats
```

After building, import the JSON data into SQLite:
```bash
cd /home/bruno/CLProjects/Janus/server-go
./janus-import /data/janus
```

## Adding Content

### Series
1. Copy MKV files to `/data/janus/videos/{series-id}/`
2. Add entry to `SERIES` list in `build_library.py` with file pattern and TMDB ID
3. Run `python3 manage.py build`
4. Run `./janus-import /data/janus` to import into SQLite

### Movie
1. Encode/copy MKV to `/data/janus/videos/{movie-id}/`
2. Add entry to `MOVIES` list in `build_library.py` with TMDB ID
3. Run `python3 manage.py build`
4. Run `./janus-import /data/janus`

## TMDB Integration
- Episode synopses in EN/FR/JA
- Episode thumbnails
- Movie synopses and backdrops
- Cached in `tmdb_cache.json` (fetched once, reused on rebuilds)
- API key: configured in `build_library.py`

## Data Directory

```
/data/janus/
├── janus.db              ← SQLite (production data)
├── videos/{id}/          ← MKV files
├── subs/{id}/            ← SRT files
├── covers/               ← Cover art + banners
├── thumbs/{id}/          ← Episode thumbnails
├── updates/              ← APK for self-update
├── items/                ← JSON files (legacy, imported to DB)
└── tmdb_cache.json       ← TMDB API response cache
```
