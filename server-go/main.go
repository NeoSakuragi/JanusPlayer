package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

var (
	dataDir   string
	db        *sql.DB
	startTime time.Time
)

func main() {
	dataDir = envOr("JANUS_DATA", "/data/janus")

	if len(os.Args) > 1 {
		initDB()
		runCommand(os.Args[1], os.Args[2:])
		return
	}

	initDB()
	initAuth()
	startTime = time.Now()

	host := envOr("JANUS_HOST", "0.0.0.0")
	port := envOr("JANUS_PORT", "8900")

	mux := http.NewServeMux()

	// Auth
	mux.HandleFunc("/api/login", handleLogin)

	// Data endpoints (from DB)
	mux.HandleFunc("/api/health", handleHealth)
	mux.HandleFunc("/api/version", handleVersion)
	mux.HandleFunc("/api/library", handleLibrary)
	mux.HandleFunc("/api/items/", handleItems)

	// Stream by episode ID
	mux.HandleFunc("/api/stream/", handleStream)

	// Static file endpoints (legacy)
	mux.HandleFunc("/api/video/", handleVideo)
	mux.HandleFunc("/api/subs/", serveStatic("subs"))
	mux.HandleFunc("/api/covers/", serveStatic("covers"))
	mux.HandleFunc("/api/thumbs/", serveStatic("thumbs"))
	mux.HandleFunc("/api/update/", serveStaticAt("updates", "/api/update/"))

	addr := host + ":" + port
	fmt.Println("Janus Media Server (Go)")
	fmt.Printf("  Data:   %s\n", dataDir)
	fmt.Printf("  DB:     %s\n", filepath.Join(dataDir, "janus.db"))
	fmt.Printf("  Listen: http://%s\n\n", addr)

	server := &http.Server{
		Addr:         addr,
		Handler:      cors(authMiddleware(logger(mux))),
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 10 * time.Minute,
		IdleTimeout:  60 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// ── Database ──────────────────────────────────────────

func initDB() {
	dbPath := filepath.Join(dataDir, "janus.db")
	var err error
	db, err = sql.Open("sqlite3", dbPath+"?_journal=WAL&_busy_timeout=5000")
	if err != nil {
		log.Fatalf("DB open: %v", err)
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)

	for _, ddl := range schema {
		if _, err := db.Exec(ddl); err != nil {
			log.Fatalf("Schema: %v", err)
		}
	}
	log.Printf("DB ready: %s", dbPath)
}

var schema = []string{
	`CREATE TABLE IF NOT EXISTS items (
		id TEXT PRIMARY KEY,
		type TEXT NOT NULL,
		title_en TEXT NOT NULL,
		title_ja TEXT NOT NULL,
		cover TEXT DEFAULT '',
		episode_count INTEGER DEFAULT 0,
		season_count INTEGER DEFAULT 0,
		duration_min INTEGER DEFAULT 0,
		synopsis_en TEXT DEFAULT '',
		synopsis_fr TEXT DEFAULT '',
		synopsis_ja TEXT DEFAULT '',
		tmdb_id INTEGER DEFAULT 0,
		updated_at INTEGER DEFAULT (strftime('%s','now'))
	)`,
	`CREATE TABLE IF NOT EXISTS episodes (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		item_id TEXT NOT NULL REFERENCES items(id),
		season INTEGER NOT NULL,
		episode INTEGER NOT NULL,
		filename TEXT NOT NULL,
		duration_sec REAL DEFAULT 0,
		title_en TEXT DEFAULT '',
		synopsis_en TEXT DEFAULT '',
		synopsis_fr TEXT DEFAULT '',
		synopsis_ja TEXT DEFAULT '',
		thumb TEXT DEFAULT '',
		has_ja_subs INTEGER DEFAULT 0,
		has_en_subs INTEGER DEFAULT 0,
		has_fr_subs INTEGER DEFAULT 0,
		ja_srt_file TEXT DEFAULT '',
		en_srt_file TEXT DEFAULT '',
		fr_srt_file TEXT DEFAULT '',
		ja_sub_lines INTEGER DEFAULT 0,
		UNIQUE(item_id, season, episode)
	)`,
	`CREATE TABLE IF NOT EXISTS meta (
		key TEXT PRIMARY KEY,
		value TEXT NOT NULL,
		updated_at INTEGER DEFAULT (strftime('%s','now'))
	)`,
	`CREATE TABLE IF NOT EXISTS watch_progress (
		series_id TEXT NOT NULL,
		episode_num INTEGER NOT NULL,
		position_ms INTEGER DEFAULT 0,
		duration_ms INTEGER DEFAULT 0,
		filename TEXT DEFAULT '',
		updated_at INTEGER DEFAULT (strftime('%s','now')),
		PRIMARY KEY (series_id, episode_num)
	)`,
	`CREATE TABLE IF NOT EXISTS users (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		username TEXT UNIQUE NOT NULL,
		password_hash TEXT NOT NULL,
		role TEXT DEFAULT 'viewer',
		created_at INTEGER DEFAULT (strftime('%s','now'))
	)`,
	`CREATE TABLE IF NOT EXISTS subtitles (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		item_id TEXT NOT NULL,
		season INTEGER NOT NULL,
		episode INTEGER NOT NULL,
		language TEXT NOT NULL,
		label TEXT DEFAULT '',
		srt_file TEXT NOT NULL,
		UNIQUE(item_id, season, episode, srt_file)
	)`,
	`INSERT OR IGNORE INTO meta (key, value) VALUES ('library_version', '1')`,
	`INSERT OR IGNORE INTO meta (key, value) VALUES ('app_version_code', '9')`,
	`INSERT OR IGNORE INTO meta (key, value) VALUES ('app_version_name', '1.8')`,
}

// ── Handlers ──────────────────────────────────────────

func handleHealth(w http.ResponseWriter, r *http.Request) {
	var itemCount, epCount int
	db.QueryRow("SELECT COUNT(*) FROM items").Scan(&itemCount)
	db.QueryRow("SELECT COUNT(*) FROM episodes").Scan(&epCount)
	writeJSON(w, map[string]any{
		"status":   "ok",
		"uptime":   time.Since(startTime).Round(time.Second).String(),
		"items":    itemCount,
		"episodes": epCount,
	})
}

func handleVersion(w http.ResponseWriter, r *http.Request) {
	var code, name string
	db.QueryRow("SELECT value FROM meta WHERE key='app_version_code'").Scan(&code)
	db.QueryRow("SELECT value FROM meta WHERE key='app_version_name'").Scan(&name)
	writeJSON(w, map[string]any{
		"version_code": atoi(code),
		"version_name": name,
		"apk":          "janus.apk",
	})
}

func handleLibrary(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query("SELECT id, type, title_en, title_ja, cover, episode_count, season_count, duration_min FROM items ORDER BY title_en")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	var libVersion string
	db.QueryRow("SELECT value FROM meta WHERE key='library_version'").Scan(&libVersion)

	items := []map[string]any{}
	for rows.Next() {
		var id, typ, titleEn, titleJa, cover string
		var epCount, seasonCount, durMin int
		rows.Scan(&id, &typ, &titleEn, &titleJa, &cover, &epCount, &seasonCount, &durMin)
		item := map[string]any{
			"id": id, "type": typ,
			"title_en": titleEn, "title_ja": titleJa,
			"cover": cover, "episode_count": epCount,
		}
		if typ == "MOVIE" {
			item["duration_min"] = durMin
		} else {
			item["season_count"] = seasonCount
		}
		items = append(items, item)
	}

	writeJSON(w, map[string]any{
		"version":       2,
		"last_modified": atoi(libVersion),
		"items":         items,
	})
}

func handleItems(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/items/")
	parts := strings.Split(path, "/")

	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "not found", 404)
		return
	}

	itemID := strings.TrimSuffix(parts[0], ".json")

	// GET /api/items/{id} — item detail
	if len(parts) == 1 {
		handleItemDetail(w, itemID)
		return
	}

	// GET /api/items/{id}/season/{num}
	if len(parts) == 3 && parts[1] == "season" {
		handleSeason(w, itemID, atoi(parts[2]))
		return
	}

	// Legacy: /api/items/{id}/info.json or /api/items/{id}/season-{n}.json
	if len(parts) == 2 {
		name := parts[1]
		if name == "info.json" {
			handleItemDetail(w, itemID)
			return
		}
		if strings.HasPrefix(name, "season-") && strings.HasSuffix(name, ".json") {
			num := strings.TrimSuffix(strings.TrimPrefix(name, "season-"), ".json")
			handleSeason(w, itemID, atoi(num))
			return
		}
	}

	http.Error(w, "not found", 404)
}

func handleItemDetail(w http.ResponseWriter, itemID string) {
	var id, typ, titleEn, titleJa, cover, synEn, synFr, synJa string
	var epCount, seasonCount, durMin int
	err := db.QueryRow("SELECT id, type, title_en, title_ja, cover, episode_count, season_count, duration_min, synopsis_en, synopsis_fr, synopsis_ja FROM items WHERE id=?", itemID).
		Scan(&id, &typ, &titleEn, &titleJa, &cover, &epCount, &seasonCount, &durMin, &synEn, &synFr, &synJa)
	if err != nil {
		http.Error(w, "not found", 404)
		return
	}

	if typ == "MOVIE" {
		// Return movie with its single episode
		ep := queryEpisode(itemID, 1, 1)
		if ep == nil {
			ep = map[string]any{}
		}
		writeJSON(w, map[string]any{
			"id": id, "type": typ,
			"title_en": titleEn, "title_ja": titleJa,
			"cover": cover, "episode": ep,
			"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
		})
	} else {
		// Return series with season list
		seasons := []map[string]any{}
		rows, _ := db.Query("SELECT season, COUNT(*) FROM episodes WHERE item_id=? GROUP BY season ORDER BY season", itemID)
		if rows != nil {
			defer rows.Close()
			for rows.Next() {
				var sNum, sCount int
				rows.Scan(&sNum, &sCount)
				seasons = append(seasons, map[string]any{"season": sNum, "episode_count": sCount})
			}
		}
		writeJSON(w, map[string]any{
			"id": id, "type": typ,
			"title_en": titleEn, "title_ja": titleJa,
			"cover": cover, "episode_count": epCount,
			"seasons": seasons,
		})
	}
}

func handleSeason(w http.ResponseWriter, itemID string, seasonNum int) {
	rows, err := db.Query(`SELECT season, episode, filename, duration_sec, title_en,
		synopsis_en, synopsis_fr, synopsis_ja, thumb,
		has_ja_subs, has_en_subs, has_fr_subs, ja_srt_file, en_srt_file, fr_srt_file, ja_sub_lines
		FROM episodes WHERE item_id=? AND season=? ORDER BY episode`, itemID, seasonNum)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	episodes := []map[string]any{}
	for rows.Next() {
		var season, episode, hasJa, hasEn, hasFr, jaLines int
		var filename, titleEn, synEn, synFr, synJa, thumb, jaSrt, enSrt, frSrt string
		var durSec float64
		rows.Scan(&season, &episode, &filename, &durSec, &titleEn,
			&synEn, &synFr, &synJa, &thumb,
			&hasJa, &hasEn, &hasFr, &jaSrt, &enSrt, &frSrt, &jaLines)
		// Fetch subtitle tracks from subtitles table
		subTracks := []map[string]any{}
		subRows, _ := db.Query("SELECT language, label, srt_file FROM subtitles WHERE item_id=? AND season=? AND episode=? ORDER BY language, id",
			itemID, season, episode)
		if subRows != nil {
			for subRows.Next() {
				var sLang, sLabel, sFile string
				subRows.Scan(&sLang, &sLabel, &sFile)
				subTracks = append(subTracks, map[string]any{"language": sLang, "label": sLabel, "srt_file": sFile})
			}
			subRows.Close()
		}

		episodes = append(episodes, map[string]any{
			"season": season, "episode": episode, "filename": filename,
			"duration_sec": durSec, "title_en": titleEn,
			"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
			"thumb": thumb,
			"has_ja_subs": hasJa == 1, "has_en_subs": hasEn == 1, "has_fr_subs": hasFr == 1,
			"ja_srt_file": jaSrt, "en_srt_file": enSrt, "fr_srt_file": frSrt,
			"ja_sub_lines": jaLines,
			"subtitles": subTracks,
			"watch_progress_sec": 0, "completed": false,
		})
	}

	writeJSON(w, map[string]any{
		"season":        seasonNum,
		"episode_count": len(episodes),
		"episodes":      episodes,
	})
}

func queryEpisode(itemID string, season, episode int) map[string]any {
	var s, ep, hasJa, hasEn, hasFr, jaLines int
	var filename, titleEn, synEn, synFr, synJa, thumb, jaSrt, enSrt, frSrt string
	var durSec float64
	err := db.QueryRow(`SELECT season, episode, filename, duration_sec, title_en,
		synopsis_en, synopsis_fr, synopsis_ja, thumb,
		has_ja_subs, has_en_subs, has_fr_subs, ja_srt_file, en_srt_file, fr_srt_file, ja_sub_lines
		FROM episodes WHERE item_id=? AND season=? AND episode=?`, itemID, season, episode).
		Scan(&s, &ep, &filename, &durSec, &titleEn,
			&synEn, &synFr, &synJa, &thumb,
			&hasJa, &hasEn, &hasFr, &jaSrt, &enSrt, &frSrt, &jaLines)
	if err != nil {
		return nil
	}

	subTracks := []map[string]any{}
	subRows, _ := db.Query("SELECT language, label, srt_file FROM subtitles WHERE item_id=? AND season=? AND episode=? ORDER BY language, id",
		itemID, season, episode)
	if subRows != nil {
		for subRows.Next() {
			var sLang, sLabel, sFile string
			subRows.Scan(&sLang, &sLabel, &sFile)
			subTracks = append(subTracks, map[string]any{"language": sLang, "label": sLabel, "srt_file": sFile})
		}
		subRows.Close()
	}

	return map[string]any{
		"season": s, "episode": ep, "filename": filename,
		"duration_sec": durSec, "title_en": titleEn,
		"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
		"thumb": thumb,
		"has_ja_subs": hasJa == 1, "has_en_subs": hasEn == 1, "has_fr_subs": hasFr == 1,
		"ja_srt_file": jaSrt, "en_srt_file": enSrt, "fr_srt_file": frSrt,
		"ja_sub_lines": jaLines,
		"subtitles": subTracks,
		"watch_progress_sec": 0, "completed": false,
	}
}

// ── Static files ──────────────────────────────────────

func handleStream(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/stream/")
	parts := strings.Split(path, "/")

	// /api/stream/{itemId}/{season}/{episode} → video
	// /api/stream/{itemId}/{season}/{episode}/subs/{lang} → subtitle
	if len(parts) < 3 {
		http.Error(w, "not found", 404)
		return
	}

	itemID := parts[0]
	season := atoi(parts[1])
	episode := atoi(parts[2])

	var filename, jaSrt, enSrt, frSrt string
	err := db.QueryRow("SELECT filename, ja_srt_file, en_srt_file, fr_srt_file FROM episodes WHERE item_id=? AND season=? AND episode=?",
		itemID, season, episode).Scan(&filename, &jaSrt, &enSrt, &frSrt)
	if err != nil {
		http.Error(w, "not found", 404)
		return
	}

	// Video stream
	if len(parts) == 3 {
		videoPath := filepath.Join(dataDir, "videos", itemID, filename)
		f, err := os.Open(videoPath)
		if err != nil {
			http.Error(w, "not found", 404)
			return
		}
		defer f.Close()
		stat, _ := f.Stat()
		w.Header().Set("Content-Type", "video/x-matroska")
		w.Header().Set("Accept-Ranges", "bytes")
		http.ServeContent(w, r, stat.Name(), stat.ModTime(), f)
		return
	}

	// Subtitles: /api/stream/{id}/{s}/{e}/subs/{lang}[/{index}]
	if len(parts) >= 5 && parts[3] == "subs" {
		lang := parts[4]
		trackIdx := 0
		if len(parts) >= 6 {
			trackIdx = atoi(parts[5])
		}

		var srtFile string
		if trackIdx > 0 {
			db.QueryRow("SELECT srt_file FROM subtitles WHERE item_id=? AND season=? AND episode=? AND language=? ORDER BY id LIMIT 1 OFFSET ?",
				itemID, season, episode, lang, trackIdx-1).Scan(&srtFile)
		} else {
			db.QueryRow("SELECT srt_file FROM subtitles WHERE item_id=? AND season=? AND episode=? AND language=? ORDER BY id LIMIT 1",
				itemID, season, episode, lang).Scan(&srtFile)
		}

		// Fallback to episodes table
		if srtFile == "" {
			switch lang {
			case "ja":
				srtFile = jaSrt
			case "en":
				srtFile = enSrt
			case "fr":
				srtFile = frSrt
			}
		}

		if srtFile == "" {
			http.Error(w, "not found", 404)
			return
		}
		srtPath := filepath.Join(dataDir, "subs", itemID, srtFile)
		f, err := os.Open(srtPath)
		if err != nil {
			http.Error(w, "not found", 404)
			return
		}
		defer f.Close()
		stat, _ := f.Stat()
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		http.ServeContent(w, r, stat.Name(), stat.ModTime(), f)
		return
	}

	http.Error(w, "not found", 404)
}

func handleVideo(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/video/")
	if path == "" || strings.Contains(path, "..") {
		http.Error(w, "not found", 404)
		return
	}
	f, err := os.Open(filepath.Join(dataDir, "videos", path))
	if err != nil {
		http.Error(w, "not found", 404)
		return
	}
	defer f.Close()
	stat, _ := f.Stat()
	w.Header().Set("Content-Type", "video/x-matroska")
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, stat.Name(), stat.ModTime(), f)
}

func serveStatic(subdir string) http.HandlerFunc {
	return serveStaticAt(subdir, "/api/"+subdir+"/")
}

func serveStaticAt(subdir, prefix string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		path := strings.TrimPrefix(r.URL.Path, prefix)
		if path == "" || strings.Contains(path, "..") {
			http.Error(w, "not found", 404)
			return
		}
		full := filepath.Join(dataDir, subdir, path)
		f, err := os.Open(full)
		if err != nil {
			http.Error(w, "not found", 404)
			return
		}
		defer f.Close()
		stat, _ := f.Stat()
		ct := "application/octet-stream"
		switch {
		case strings.HasSuffix(path, ".srt"):
			ct = "text/plain; charset=utf-8"
		case strings.HasSuffix(path, ".jpg"), strings.HasSuffix(path, ".jpeg"):
			ct = "image/jpeg"
		case strings.HasSuffix(path, ".png"):
			ct = "image/png"
		}
		w.Header().Set("Content-Type", ct)
		http.ServeContent(w, r, stat.Name(), stat.ModTime(), f)
	}
}

// ── Helpers ───────────────────────────────────────────

func writeJSON(w http.ResponseWriter, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	json.NewEncoder(w).Encode(data)
}

func atoi(s string) int {
	n := 0
	fmt.Sscanf(s, "%d", &n)
	return n
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func logger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/video/") {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		lw := &logWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(lw, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, lw.status, time.Since(start).Round(time.Microsecond))
	})
}

type logWriter struct {
	http.ResponseWriter
	status int
}

func (lw *logWriter) WriteHeader(code int) {
	lw.status = code
	lw.ResponseWriter.WriteHeader(code)
}
