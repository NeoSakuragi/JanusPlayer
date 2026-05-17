package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

var (
	dataDir   string
	mediaDir  string
	db        *sql.DB
	startTime time.Time
	jsonCache sync.Map // path → []byte
)

func main() {
	dataDir = envOr("JANUS_DATA", "/data/janus")
	mediaDir = envOr("JANUS_MEDIA", dataDir)

	if len(os.Args) > 1 {
		initDB()
		runCommand(os.Args[1], os.Args[2:])
		return
	}

	initDB()
	initAuth()
	startTime = time.Now()
	warmBlobCache()

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

	// Packed blob endpoints
	mux.HandleFunc("/api/blob/", handleBlob)
	mux.HandleFunc("/api/page/", handlePage)

	// Supercharged SRT
	mux.HandleFunc("/api/super-srt/", handleSuperSRT)

	// Stream by episode ID
	mux.HandleFunc("/api/stream/", handleStream)

	// User settings
	mux.HandleFunc("/api/settings", handleUserSettings)
	mux.HandleFunc("/api/season-settings/", handleSeasonSettings)

	// Debug
	mux.HandleFunc("/api/debug/events", handleDebugEvents)

	// Static file endpoints (legacy)
	mux.HandleFunc("/api/video/", handleVideo)
	mux.HandleFunc("/api/subs/", handleSubs)
	mux.HandleFunc("/api/covers/", serveStatic("covers"))
	mux.HandleFunc("/api/thumbs/", serveStatic("thumbs"))
	mux.HandleFunc("/api/update", handleUpdate)
	mux.HandleFunc("/api/update/", handleUpdate)
	mux.HandleFunc("/api/version/plus", handleVersionPlus)
	mux.HandleFunc("/api/update/plus", handleUpdatePlus)
	mux.HandleFunc("/install", handleInstallPage)
	mux.HandleFunc("/install/", handleInstallPage)

	addr := host + ":" + port
	fmt.Println("Janus Media Server (Go)")
	fmt.Printf("  DB:     %s\n", filepath.Join(dataDir, "janus.db"))
	fmt.Printf("  Media:  %s\n", mediaDir)
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
	runMigrations()
	log.Printf("DB ready: %s", dbPath)
}

// ── Migrations ───────────────────────────────────────

var migrations = []struct {
	id  string
	sql string
}{
	{"001_season_locales", `CREATE TABLE IF NOT EXISTS season_locales (
		item_id TEXT NOT NULL,
		season INTEGER NOT NULL,
		language TEXT NOT NULL,
		name TEXT DEFAULT '',
		PRIMARY KEY (item_id, season, language)
	)`},
	{"002_season_settings", `CREATE TABLE IF NOT EXISTS season_settings (
		item_id TEXT NOT NULL,
		season INTEGER NOT NULL,
		opening_sec REAL DEFAULT 0,
		ending_sec REAL DEFAULT 0,
		PRIMARY KEY (item_id, season)
	)`},
}

func runMigrations() {
	db.Exec(`CREATE TABLE IF NOT EXISTS migrations (
		id TEXT PRIMARY KEY,
		applied_at INTEGER DEFAULT (strftime('%s','now'))
	)`)
	for _, m := range migrations {
		var exists int
		db.QueryRow("SELECT COUNT(*) FROM migrations WHERE id=?", m.id).Scan(&exists)
		if exists > 0 {
			continue
		}
		if _, err := db.Exec(m.sql); err != nil {
			log.Printf("Migration %s failed: %v", m.id, err)
			continue
		}
		db.Exec("INSERT INTO migrations (id) VALUES (?)", m.id)
		log.Printf("Migration applied: %s", m.id)
	}
}

// ── Schema ───────────────────────────────────────────

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
	`CREATE TABLE IF NOT EXISTS debug_events (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		session TEXT NOT NULL,
		item_id TEXT DEFAULT '',
		episode INTEGER DEFAULT 0,
		ts_client REAL NOT NULL,
		event TEXT NOT NULL,
		position_ms INTEGER DEFAULT 0,
		target_ms INTEGER DEFAULT 0,
		speed REAL DEFAULT 1.0,
		detail TEXT DEFAULT '',
		created_at INTEGER DEFAULT (strftime('%s','now'))
	)`,
	`CREATE TABLE IF NOT EXISTS user_settings (
		user_id INTEGER NOT NULL,
		key TEXT NOT NULL,
		value TEXT DEFAULT '',
		PRIMARY KEY (user_id, key)
	)`,
	`CREATE TABLE IF NOT EXISTS item_locales (
		item_id TEXT NOT NULL,
		language TEXT NOT NULL,
		title TEXT DEFAULT '',
		synopsis TEXT DEFAULT '',
		PRIMARY KEY (item_id, language)
	)`,
	`CREATE TABLE IF NOT EXISTS episode_locales (
		item_id TEXT NOT NULL,
		season INTEGER NOT NULL,
		episode INTEGER NOT NULL,
		language TEXT NOT NULL,
		title TEXT DEFAULT '',
		synopsis TEXT DEFAULT '',
		PRIMARY KEY (item_id, season, episode, language)
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
	var code, name, size, sha256 string
	db.QueryRow("SELECT value FROM meta WHERE key='app_version_code'").Scan(&code)
	db.QueryRow("SELECT value FROM meta WHERE key='app_version_name'").Scan(&name)
	db.QueryRow("SELECT value FROM meta WHERE key='app_size'").Scan(&size)
	db.QueryRow("SELECT value FROM meta WHERE key='app_sha256'").Scan(&sha256)
	writeJSON(w, map[string]any{
		"version_code": atoi(code),
		"version_name": name,
		"size":         atoi(size),
		"sha256":       sha256,
		"apk":          "janus.apk",
	})
}

func handleVersionPlus(w http.ResponseWriter, r *http.Request) {
	var code, name, size, sha256 string
	db.QueryRow("SELECT value FROM meta WHERE key='plus_version_code'").Scan(&code)
	db.QueryRow("SELECT value FROM meta WHERE key='plus_version_name'").Scan(&name)
	db.QueryRow("SELECT value FROM meta WHERE key='plus_size'").Scan(&size)
	db.QueryRow("SELECT value FROM meta WHERE key='plus_sha256'").Scan(&sha256)
	writeJSON(w, map[string]any{
		"version_code": atoi(code),
		"version_name": name,
		"size":         atoi(size),
		"sha256":       sha256,
		"apk":          "janusplus.apk",
	})
}

func handleUpdatePlus(w http.ResponseWriter, r *http.Request) {
	apkPath := filepath.Join(dataDir, "updates", "janusplus.apk")
	f, err := os.Open(apkPath)
	if err != nil {
		http.Error(w, "no update available", 404)
		return
	}
	defer f.Close()
	stat, _ := f.Stat()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	http.ServeContent(w, r, "janusplus.apk", stat.ModTime(), f)
}

func handleLibrary(w http.ResponseWriter, r *http.Request) {
	writeCachedJSON(w, "library", func() any {
		rows, err := db.Query("SELECT id, type, title_en, title_ja, cover, episode_count, season_count, duration_min FROM items ORDER BY title_en")
		if err != nil {
			return map[string]any{"error": err.Error()}
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
				"locales": queryItemLocales(id),
			}
			if typ == "MOVIE" {
				item["duration_min"] = durMin
			} else {
				item["season_count"] = seasonCount
			}
			items = append(items, item)
		}

		return map[string]any{
			"version":       2,
			"last_modified": atoi(libVersion),
			"items":         items,
		}
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
	writeCachedJSON(w, "item:"+itemID, func() any {
		var id, typ, titleEn, titleJa, cover, synEn, synFr, synJa string
		var epCount, seasonCount, durMin int
		err := db.QueryRow("SELECT id, type, title_en, title_ja, cover, episode_count, season_count, duration_min, synopsis_en, synopsis_fr, synopsis_ja FROM items WHERE id=?", itemID).
			Scan(&id, &typ, &titleEn, &titleJa, &cover, &epCount, &seasonCount, &durMin, &synEn, &synFr, &synJa)
		if err != nil {
			return map[string]any{"error": "not found"}
		}

		if typ == "MOVIE" {
			ep := queryEpisode(itemID, 1, 1)
			if ep == nil {
				ep = map[string]any{}
			}
			return map[string]any{
				"id": id, "type": typ,
				"title_en": titleEn, "title_ja": titleJa,
				"cover": cover, "episode": ep,
				"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
			}
		}

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
		return map[string]any{
			"id": id, "type": typ,
			"title_en": titleEn, "title_ja": titleJa,
			"cover": cover, "episode_count": epCount,
			"seasons": seasons,
		}
	})
}

func handleSeason(w http.ResponseWriter, itemID string, seasonNum int) {
	writeCachedJSON(w, fmt.Sprintf("season:%s:%d", itemID, seasonNum), func() any {
		rows, err := db.Query(`SELECT season, episode, filename, duration_sec, title_en,
			synopsis_en, synopsis_fr, synopsis_ja, thumb
			FROM episodes WHERE item_id=? AND season=? ORDER BY episode`, itemID, seasonNum)
		if err != nil {
			return map[string]any{"error": err.Error()}
		}
		defer rows.Close()

		episodes := []map[string]any{}
		for rows.Next() {
			var season, episode int
			var filename, titleEn, synEn, synFr, synJa, thumb string
			var durSec float64
			rows.Scan(&season, &episode, &filename, &durSec, &titleEn,
				&synEn, &synFr, &synJa, &thumb)

			subTracks := querySubtitles(itemID, season, episode)

			episodes = append(episodes, map[string]any{
				"season": season, "episode": episode, "filename": filename,
				"duration_sec": durSec, "title_en": titleEn,
				"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
				"thumb": thumb,
				"subtitles":          subTracks,
				"locales":            queryEpisodeLocales(itemID, season, episode),
				"watch_progress_sec": 0, "completed": false,
			})
		}

		return map[string]any{
			"season":        seasonNum,
			"episode_count": len(episodes),
			"episodes":      episodes,
		}
	})
}

func queryEpisode(itemID string, season, episode int) map[string]any {
	var s, ep int
	var filename, titleEn, synEn, synFr, synJa, thumb string
	var durSec float64
	err := db.QueryRow(`SELECT season, episode, filename, duration_sec, title_en,
		synopsis_en, synopsis_fr, synopsis_ja, thumb
		FROM episodes WHERE item_id=? AND season=? AND episode=?`, itemID, season, episode).
		Scan(&s, &ep, &filename, &durSec, &titleEn,
			&synEn, &synFr, &synJa, &thumb)
	if err != nil {
		return nil
	}

	subTracks := querySubtitles(itemID, s, ep)

	return map[string]any{
		"season": s, "episode": ep, "filename": filename,
		"duration_sec": durSec, "title_en": titleEn,
		"synopsis_en": synEn, "synopsis_fr": synFr, "synopsis_ja": synJa,
		"thumb": thumb,
		"subtitles":          subTracks,
		"locales":            queryEpisodeLocales(itemID, s, ep),
		"watch_progress_sec": 0, "completed": false,
	}
}

func querySubtitles(itemID string, season, episode int) []map[string]any {
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
	return subTracks
}

func queryItemLocales(itemID string) map[string]map[string]string {
	locales := map[string]map[string]string{}
	rows, _ := db.Query("SELECT language, title, synopsis FROM item_locales WHERE item_id=?", itemID)
	if rows != nil {
		for rows.Next() {
			var lang, title, synopsis string
			rows.Scan(&lang, &title, &synopsis)
			locales[lang] = map[string]string{"title": title, "synopsis": synopsis}
		}
		rows.Close()
	}
	// Fallback: populate from legacy columns on items table
	if len(locales) == 0 {
		var titleEn, titleJa, synEn, synFr, synJa string
		db.QueryRow("SELECT title_en, title_ja, synopsis_en, synopsis_fr, synopsis_ja FROM items WHERE id=?", itemID).
			Scan(&titleEn, &titleJa, &synEn, &synFr, &synJa)
		if titleEn != "" {
			locales["en"] = map[string]string{"title": titleEn, "synopsis": synEn}
		}
		if titleJa != "" {
			locales["ja"] = map[string]string{"title": titleJa, "synopsis": synJa}
		}
		if synFr != "" {
			locales["fr"] = map[string]string{"title": titleEn, "synopsis": synFr}
		}
	}
	return locales
}

func queryEpisodeLocales(itemID string, season, episode int) map[string]map[string]string {
	locales := map[string]map[string]string{}
	rows, _ := db.Query("SELECT language, title, synopsis FROM episode_locales WHERE item_id=? AND season=? AND episode=?",
		itemID, season, episode)
	if rows != nil {
		for rows.Next() {
			var lang, title, synopsis string
			rows.Scan(&lang, &title, &synopsis)
			locales[lang] = map[string]string{"title": title, "synopsis": synopsis}
		}
		rows.Close()
	}
	// Fallback from episodes table
	if len(locales) == 0 {
		var titleEn, synEn, synFr, synJa string
		db.QueryRow("SELECT title_en, synopsis_en, synopsis_fr, synopsis_ja FROM episodes WHERE item_id=? AND season=? AND episode=?",
			itemID, season, episode).Scan(&titleEn, &synEn, &synFr, &synJa)
		if titleEn != "" {
			locales["en"] = map[string]string{"title": titleEn, "synopsis": synEn}
		}
		if synJa != "" {
			locales["ja"] = map[string]string{"title": "", "synopsis": synJa}
		}
		if synFr != "" {
			locales["fr"] = map[string]string{"title": "", "synopsis": synFr}
		}
	}
	return locales
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

	var filename string
	err := db.QueryRow("SELECT filename FROM episodes WHERE item_id=? AND season=? AND episode=?",
		itemID, season, episode).Scan(&filename)
	if err != nil {
		http.Error(w, "not found", 404)
		return
	}

	// Video stream
	if len(parts) == 3 {
		videoPath := filepath.Join(mediaDir, "videos", itemID, filename)
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

		if srtFile == "" {
			http.Error(w, "not found", 404)
			return
		}
		srtPath := filepath.Join(mediaDir, "subs", itemID, srtFile)
		data, err := os.ReadFile(srtPath)
		if err != nil {
			http.Error(w, "not found", 404)
			return
		}
		content := string(data)
		if lang == "ja" && hasMecab {
			content = segmentSRT(content)
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte(content))
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
	f, err := os.Open(filepath.Join(mediaDir, "videos", path))
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

func handleSubs(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/subs/")
	if path == "" || strings.Contains(path, "..") {
		http.Error(w, "not found", 404)
		return
	}

	full := filepath.Join(mediaDir, "subs", path)
	data, err := os.ReadFile(full)
	if err != nil {
		http.Error(w, "not found", 404)
		return
	}

	content := string(data)

	// Segment Japanese SRTs on the fly with MeCab
	if strings.Contains(path, "_ja") && hasMecab {
		content = segmentSRT(content)
	}

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Write([]byte(content))
}

var hasMecab bool

func init() {
	_, err := exec.LookPath("mecab")
	hasMecab = err == nil
}

func handleInstallPage(w http.ResponseWriter, r *http.Request) {
	if strings.HasSuffix(r.URL.Path, "/download") {
		apkPath := filepath.Join(dataDir, "updates", "janus.apk")
		f, err := os.Open(apkPath)
		if err != nil {
			http.Error(w, "no APK available", 404)
			return
		}
		defer f.Close()
		stat, _ := f.Stat()
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", "attachment; filename=\"janus.apk\"")
		http.ServeContent(w, r, "janus.apk", stat.ModTime(), f)
		return
	}

	var name, size string
	db.QueryRow("SELECT value FROM meta WHERE key='app_version_name'").Scan(&name)
	db.QueryRow("SELECT value FROM meta WHERE key='app_size'").Scan(&size)
	sizeMB := atoi(size) / (1024 * 1024)

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, installPageHTML, name, sizeMB)
}

const installPageHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Janus — Install</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: #0a0a0a; color: #e0e0e0; font-family: -apple-system, sans-serif;
         display: flex; justify-content: center; align-items: center; min-height: 100vh; }
  .card { text-align: center; max-width: 360px; padding: 48px 32px; }
  .logo { font-size: 48px; margin-bottom: 8px; }
  h1 { font-size: 28px; font-weight: 700; color: #fff; margin-bottom: 4px; }
  .sub { color: #888; font-size: 14px; margin-bottom: 32px; }
  .btn { display: inline-block; background: #7986CB; color: #fff; text-decoration: none;
         font-size: 16px; font-weight: 600; padding: 14px 40px; border-radius: 8px;
         transition: background 0.2s; }
  .btn:hover { background: #5C6BC0; }
  .meta { color: #666; font-size: 12px; margin-top: 16px; }
  .steps { text-align: left; color: #aaa; font-size: 13px; margin-top: 32px; line-height: 1.8; }
  .steps span { color: #7986CB; font-weight: 600; }
</style>
</head>
<body>
<div class="card">
  <div class="logo">ヤヌス</div>
  <h1>Janus</h1>
  <p class="sub">Japanese Immersion Video Player</p>
  <a href="install/download" class="btn">Install v%s</a>
  <p class="meta">Android · %d MB</p>
  <div class="steps">
    <span>1.</span> Tap Install to download the APK<br>
    <span>2.</span> Open the file and allow installation<br>
    <span>3.</span> Launch Janus and log in
  </div>
</div>
</body>
</html>
`

func handleUpdate(w http.ResponseWriter, r *http.Request) {
	apkPath := filepath.Join(dataDir, "updates", "janus.apk")
	f, err := os.Open(apkPath)
	if err != nil {
		http.Error(w, "no update available", 404)
		return
	}
	defer f.Close()
	stat, _ := f.Stat()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	http.ServeContent(w, r, "janus.apk", stat.ModTime(), f)
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
		full := filepath.Join(mediaDir, subdir, path)
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

func handleUserSettings(w http.ResponseWriter, r *http.Request) {
	userID := atoi(r.Header.Get("X-User-ID"))
	if userID == 0 {
		http.Error(w, `{"error":"unauthorized"}`, 401)
		return
	}

	if r.Method == "PUT" || r.Method == "POST" {
		var settings map[string]string
		if err := json.NewDecoder(r.Body).Decode(&settings); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		for k, v := range settings {
			db.Exec("INSERT OR REPLACE INTO user_settings (user_id, key, value) VALUES (?,?,?)", userID, k, v)
		}
		writeJSON(w, map[string]any{"ok": true})
		return
	}

	rows, err := db.Query("SELECT key, value FROM user_settings WHERE user_id=?", userID)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	settings := map[string]string{}
	for rows.Next() {
		var k, v string
		rows.Scan(&k, &v)
		settings[k] = v
	}
	writeJSON(w, settings)
}

// GET/PUT /api/season-settings/{itemId}/{season}
func handleSeasonSettings(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/season-settings/")
	parts := strings.Split(path, "/")
	if len(parts) != 2 {
		http.Error(w, "not found", 404)
		return
	}
	itemID := parts[0]
	season := atoi(parts[1])

	if r.Method == "PUT" || r.Method == "POST" {
		var req struct {
			OpeningSec float64 `json:"opening_sec"`
			EndingSec  float64 `json:"ending_sec"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		db.Exec("INSERT OR REPLACE INTO season_settings (item_id, season, opening_sec, ending_sec) VALUES (?,?,?,?)",
			itemID, season, req.OpeningSec, req.EndingSec)
		clearCache()
		writeJSON(w, map[string]any{"ok": true})
		return
	}

	var openingSec, endingSec float64
	db.QueryRow("SELECT opening_sec, ending_sec FROM season_settings WHERE item_id=? AND season=?", itemID, season).
		Scan(&openingSec, &endingSec)
	writeJSON(w, map[string]any{
		"item_id":     itemID,
		"season":      season,
		"opening_sec": openingSec,
		"ending_sec":  endingSec,
	})
}

func handleDebugEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method == "POST" {
		var batch []struct {
			Session    string  `json:"session"`
			ItemID     string  `json:"item_id"`
			Episode    int     `json:"episode"`
			TsClient   float64 `json:"ts_client"`
			Event      string  `json:"event"`
			PositionMs int64   `json:"position_ms"`
			TargetMs   int64   `json:"target_ms"`
			Speed      float64 `json:"speed"`
			Detail     string  `json:"detail"`
		}
		if err := json.NewDecoder(r.Body).Decode(&batch); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		for _, e := range batch {
			db.Exec(`INSERT INTO debug_events (session, item_id, episode, ts_client, event, position_ms, target_ms, speed, detail) VALUES (?,?,?,?,?,?,?,?,?)`,
				e.Session, e.ItemID, e.Episode, e.TsClient, e.Event, e.PositionMs, e.TargetMs, e.Speed, e.Detail)
		}
		writeJSON(w, map[string]any{"inserted": len(batch)})
		return
	}

	// GET: retrieve events, optionally filtered by session
	session := r.URL.Query().Get("session")
	var rows *sql.Rows
	var err error
	if session != "" {
		rows, err = db.Query("SELECT id, session, item_id, episode, ts_client, event, position_ms, target_ms, speed, detail FROM debug_events WHERE session=? ORDER BY ts_client", session)
	} else {
		rows, err = db.Query("SELECT id, session, item_id, episode, ts_client, event, position_ms, target_ms, speed, detail FROM debug_events ORDER BY id DESC LIMIT 500")
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	events := []map[string]any{}
	for rows.Next() {
		var id, episode int
		var posMs, targetMs int64
		var tsClient, speed float64
		var sess, itemID, event, detail string
		rows.Scan(&id, &sess, &itemID, &episode, &tsClient, &event, &posMs, &targetMs, &speed, &detail)
		events = append(events, map[string]any{
			"id": id, "session": sess, "item_id": itemID, "episode": episode,
			"ts_client": tsClient, "event": event,
			"position_ms": posMs, "target_ms": targetMs, "speed": speed, "detail": detail,
		})
	}
	writeJSON(w, events)
}

func writeJSON(w http.ResponseWriter, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	json.NewEncoder(w).Encode(data)
}

func writeCachedJSON(w http.ResponseWriter, key string, build func() any) {
	if cached, ok := jsonCache.Load(key); ok {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-cache")
		w.Write(cached.([]byte))
		return
	}
	data := build()
	b, _ := json.Marshal(data)
	jsonCache.Store(key, b)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(b)
}

func clearCache() {
	jsonCache.Range(func(key, _ any) bool {
		jsonCache.Delete(key)
		return true
	})
}

func atoi(s string) int {
	n := 0
	fmt.Sscanf(s, "%d", &n)
	return n
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
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
		user := r.Header.Get("X-Username")
		if user == "" { user = "-" }
		ip := r.RemoteAddr
		if fwd := r.Header.Get("X-Real-IP"); fwd != "" { ip = fwd }
		appVer := r.Header.Get("X-App-Version")
		if appVer == "" { appVer = "-" }
		log.Printf("%s %s v%s %s %d %s %s", ip, user, appVer, r.URL.Path, lw.status, time.Since(start).Round(time.Microsecond), r.Method)
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
