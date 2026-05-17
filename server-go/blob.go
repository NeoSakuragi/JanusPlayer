package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Blob endpoints serve packed binary data for fast client rendering.
//
// GET /api/blob/{id}/hero           — JSON metadata + cover JPEG
// GET /api/blob/{id}/season/{n}     — all episode metadata as JSON
// GET /api/blob/{id}/season/{n}/thumbs — packed thumbnails binary
//
// Format: hero blob
//   [4 bytes LE: JSON length] [JSON bytes] [cover JPEG bytes]
//
// Format: thumbs blob
//   [4 bytes LE: entry count]
//   per entry: [4 bytes LE: episode num] [4 bytes LE: JPEG size] [JPEG bytes]

var blobCache sync.Map // key → []byte

func warmBlobCache() {
	rows, err := db.Query("SELECT id, type FROM items")
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var id, typ string
		rows.Scan(&id, &typ)

		// Hero blob
		if data := buildHeroBlob(id); data != nil {
			blobCache.Store("hero:"+id, data)
		}

		if typ == "MOVIE" {
			continue
		}

		// Season blobs
		srows, _ := db.Query("SELECT DISTINCT season FROM episodes WHERE item_id=? ORDER BY season", id)
		if srows == nil {
			continue
		}
		var seasons []int
		for srows.Next() {
			var s int
			srows.Scan(&s)
			seasons = append(seasons, s)
		}
		srows.Close()

		for _, s := range seasons {
			if data := buildSeasonCardsBlob(id, s); data != nil {
				blobCache.Store(fmt.Sprintf("season-cards:%s:%d", id, s), data)
			}
			if data := buildThumbsBlob(id, s); data != nil {
				blobCache.Store(fmt.Sprintf("season-thumbs:%s:%d", id, s), data)
			}
		}
	}
	log.Printf("Blob cache warmed")
}

// GET /api/page/{item_id}/{season} — pre-built page blob with ETag support
func handlePage(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/page/")
	parts := strings.Split(path, "/")
	if len(parts) != 2 {
		http.Error(w, "not found", 404)
		return
	}
	itemID := parts[0]
	season := parts[1]
	key := fmt.Sprintf("page:%s:%s", itemID, season)

	// Load or cache the blob
	var data []byte
	if cached, ok := blobCache.Load(key); ok {
		data = cached.([]byte)
	} else {
		filename := fmt.Sprintf("%s_s%s.bin", itemID, season)
		var err error
		data, err = os.ReadFile(filepath.Join(dataDir, "pages", filename))
		if err != nil {
			http.Error(w, "not found", 404)
			return
		}
		blobCache.Store(key, data)
	}

	// ETag based on content hash (computed once, cached alongside)
	etagKey := key + ":etag"
	var etag string
	if cached, ok := blobCache.Load(etagKey); ok {
		etag = cached.(string)
	} else {
		hash := sha256.Sum256(data)
		etag = fmt.Sprintf(`"%x"`, hash[:8])
		blobCache.Store(etagKey, etag)
	}

	w.Header().Set("ETag", etag)
	if match := r.Header.Get("If-None-Match"); match == etag {
		w.WriteHeader(304)
		return
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
	w.Write(data)
}

func handleBlob(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/blob/")
	parts := strings.Split(path, "/")

	if len(parts) < 2 {
		http.Error(w, "not found", 404)
		return
	}

	itemID := parts[0]

	// /api/blob/{id}/hero
	if len(parts) == 2 && parts[1] == "hero" {
		serveBlob(w, "hero:"+itemID, func() []byte { return buildHeroBlob(itemID) })
		return
	}

	// /api/blob/{id}/season/{n} — slim (mini card data only)
	if len(parts) == 3 && parts[1] == "season" {
		seasonNum := atoi(parts[2])
		serveBlob(w, fmt.Sprintf("season-cards:%s:%d", itemID, seasonNum), func() []byte {
			return buildSeasonCardsBlob(itemID, seasonNum)
		})
		return
	}

	// /api/blob/{id}/season/{n}/full — all episode data for playback
	if len(parts) == 4 && parts[1] == "season" && parts[3] == "full" {
		seasonNum := atoi(parts[2])
		serveBlob(w, fmt.Sprintf("season-full:%s:%d", itemID, seasonNum), func() []byte {
			return buildSeasonBlob(itemID, seasonNum)
		})
		return
	}

	// /api/blob/{id}/season/{n}/thumbs
	if len(parts) == 4 && parts[1] == "season" && parts[3] == "thumbs" {
		seasonNum := atoi(parts[2])
		serveBlob(w, fmt.Sprintf("season-thumbs:%s:%d", itemID, seasonNum), func() []byte {
			return buildThumbsBlob(itemID, seasonNum)
		})
		return
	}

	http.Error(w, "not found", 404)
}

func serveBlob(w http.ResponseWriter, key string, build func() []byte) {
	if cached, ok := blobCache.Load(key); ok {
		data := cached.([]byte)
		if data == nil {
			http.Error(w, "not found", 404)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
		w.Write(data)
		return
	}

	data := build()
	if data == nil {
		blobCache.Store(key, []byte(nil))
		http.Error(w, "not found", 404)
		return
	}
	blobCache.Store(key, data)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
	w.Write(data)
}

// ── Hero blob ────────────────────────────────────────

func buildHeroBlob(itemID string) []byte {
	var id, typ, titleEn, titleJa, cover, synEn, synFr, synJa string
	var epCount, seasonCount, durMin int
	err := db.QueryRow("SELECT id, type, title_en, title_ja, cover, episode_count, season_count, duration_min, synopsis_en, synopsis_fr, synopsis_ja FROM items WHERE id=?", itemID).
		Scan(&id, &typ, &titleEn, &titleJa, &cover, &epCount, &seasonCount, &durMin, &synEn, &synFr, &synJa)
	if err != nil {
		return nil
	}

	meta := map[string]any{
		"id": id, "type": typ,
		"title_en": titleEn, "title_ja": titleJa,
		"episode_count": epCount,
		"synopsis_en":   synEn,
		"synopsis_fr":   synFr,
		"synopsis_ja":   synJa,
		"locales":       queryItemLocales(id),
	}

	if typ == "MOVIE" {
		meta["duration_min"] = durMin
		ep := queryEpisode(itemID, 1, 1)
		if ep != nil {
			meta["episode"] = ep
		}
	} else {
		meta["season_count"] = seasonCount
		seasons := []map[string]any{}
		rows, _ := db.Query("SELECT season, COUNT(*) FROM episodes WHERE item_id=? GROUP BY season ORDER BY season", itemID)
		if rows != nil {
			defer rows.Close()
			for rows.Next() {
				var sNum, sCount int
				rows.Scan(&sNum, &sCount)
				names := map[string]string{}
				nameRows, _ := db.Query("SELECT language, name FROM season_locales WHERE item_id=? AND season=?", itemID, sNum)
				if nameRows != nil {
					for nameRows.Next() {
						var lang, name string
						nameRows.Scan(&lang, &name)
						names[lang] = name
					}
					nameRows.Close()
				}
				seasons = append(seasons, map[string]any{"season": sNum, "episode_count": sCount, "names": names})
			}
		}
		meta["seasons"] = seasons
	}

	jsonBytes, _ := json.Marshal(meta)

	// Read banner (hero backdrop) and cover (small card)
	bannerPath := filepath.Join(mediaDir, "covers", itemID+"-banner.jpg")
	coverPath := filepath.Join(mediaDir, "covers", itemID+".jpg")
	bannerBytes, _ := os.ReadFile(bannerPath)
	coverBytes, _ := os.ReadFile(coverPath)

	// Pack: [4 bytes json len] [json] [4 bytes banner len] [banner jpeg] [cover jpeg]
	buf := &bytes.Buffer{}
	binary.Write(buf, binary.LittleEndian, uint32(len(jsonBytes)))
	buf.Write(jsonBytes)
	binary.Write(buf, binary.LittleEndian, uint32(len(bannerBytes)))
	if bannerBytes != nil {
		buf.Write(bannerBytes)
	}
	if coverBytes != nil {
		buf.Write(coverBytes)
	}

	return buf.Bytes()
}

// ── Season cards blob (slim — just what mini cards need) ──

func buildSeasonCardsBlob(itemID string, seasonNum int) []byte {
	rows, err := db.Query(`SELECT episode, title_en, duration_sec
		FROM episodes WHERE item_id=? AND season=? ORDER BY episode`, itemID, seasonNum)
	if err != nil {
		return nil
	}
	defer rows.Close()

	// Batch-load localized titles
	titleMap := map[int]map[string]string{}
	lrows, _ := db.Query("SELECT episode, language, title FROM episode_locales WHERE item_id=? AND season=?", itemID, seasonNum)
	if lrows != nil {
		for lrows.Next() {
			var ep int
			var lang, title string
			lrows.Scan(&ep, &lang, &title)
			if titleMap[ep] == nil {
				titleMap[ep] = map[string]string{}
			}
			titleMap[ep][lang] = title
		}
		lrows.Close()
	}

	episodes := []map[string]any{}
	for rows.Next() {
		var episode int
		var titleEn string
		var durSec float64
		rows.Scan(&episode, &titleEn, &durSec)

		ep := map[string]any{
			"episode": episode, "title_en": titleEn, "duration_sec": durSec,
		}
		if titles, ok := titleMap[episode]; ok {
			ep["titles"] = titles
		}
		episodes = append(episodes, ep)
	}

	data, _ := json.Marshal(map[string]any{
		"season":        seasonNum,
		"episode_count": len(episodes),
		"episodes":      episodes,
	})
	return data
}

// ── Season full blob (all episode data for playback) ──

func buildSeasonBlob(itemID string, seasonNum int) []byte {
	rows, err := db.Query(`SELECT e.season, e.episode, e.filename, e.duration_sec, e.title_en,
		e.synopsis_en, e.synopsis_fr, e.synopsis_ja, e.thumb
		FROM episodes e WHERE e.item_id=? AND e.season=? ORDER BY e.episode`, itemID, seasonNum)
	if err != nil {
		return nil
	}
	defer rows.Close()

	// Batch-load all episode locales for this season in one query
	localeMap := map[int]map[string]map[string]string{}
	lrows, _ := db.Query("SELECT episode, language, title, synopsis FROM episode_locales WHERE item_id=? AND season=?", itemID, seasonNum)
	if lrows != nil {
		for lrows.Next() {
			var ep int
			var lang, title, syn string
			lrows.Scan(&ep, &lang, &title, &syn)
			if localeMap[ep] == nil {
				localeMap[ep] = map[string]map[string]string{}
			}
			localeMap[ep][lang] = map[string]string{"title": title, "synopsis": syn}
		}
		lrows.Close()
	}

	// Batch-load subtitles
	subMap := map[int][]map[string]string{}
	srows, _ := db.Query("SELECT episode, language, label, srt_file FROM subtitles WHERE item_id=? AND season=? ORDER BY episode, id", itemID, seasonNum)
	if srows != nil {
		for srows.Next() {
			var ep int
			var lang, label, srtFile string
			srows.Scan(&ep, &lang, &label, &srtFile)
			subMap[ep] = append(subMap[ep], map[string]string{"language": lang, "label": label, "srt_file": srtFile})
		}
		srows.Close()
	}

	episodes := []map[string]any{}
	for rows.Next() {
		var season, episode int
		var filename, titleEn, synEn, synFr, synJa, thumb string
		var durSec float64
		rows.Scan(&season, &episode, &filename, &durSec, &titleEn,
			&synEn, &synFr, &synJa, &thumb)

		ep := map[string]any{
			"season": season, "episode": episode, "filename": filename,
			"duration_sec": durSec, "title_en": titleEn,
			"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
			"thumb": thumb,
			"watch_progress_sec": 0, "completed": false,
		}
		if loc, ok := localeMap[episode]; ok {
			ep["locales"] = loc
		}
		if subs, ok := subMap[episode]; ok {
			ep["subtitles"] = subs
		} else {
			ep["subtitles"] = []map[string]string{}
		}
		episodes = append(episodes, ep)
	}

	data, _ := json.Marshal(map[string]any{
		"season":        seasonNum,
		"episode_count": len(episodes),
		"episodes":      episodes,
	})
	return data
}

// ── Thumbs blob ──────────────────────────────────────

func buildThumbsBlob(itemID string, seasonNum int) []byte {
	rows, err := db.Query("SELECT episode, thumb FROM episodes WHERE item_id=? AND season=? AND thumb != '' ORDER BY episode", itemID, seasonNum)
	if err != nil {
		return nil
	}
	defer rows.Close()

	type thumbEntry struct {
		episode int
		data    []byte
	}
	var entries []thumbEntry

	for rows.Next() {
		var epNum int
		var thumbPath string
		rows.Scan(&epNum, &thumbPath)
		if thumbPath == "" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(mediaDir, thumbPath))
		if err != nil {
			continue
		}
		entries = append(entries, thumbEntry{epNum, data})
	}

	if len(entries) == 0 {
		return nil
	}

	// Pack: [4 bytes count] [per entry: 4 bytes ep_num, 4 bytes jpeg_size, jpeg_data]
	buf := &bytes.Buffer{}
	binary.Write(buf, binary.LittleEndian, uint32(len(entries)))
	for _, e := range entries {
		binary.Write(buf, binary.LittleEndian, uint32(e.episode))
		binary.Write(buf, binary.LittleEndian, uint32(len(e.data)))
		buf.Write(e.data)
	}

	return buf.Bytes()
}
