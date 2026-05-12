package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"

	_ "github.com/mattn/go-sqlite3"
)

// Run with: go run import.go
// Imports existing JSON data into janus.db

func main() {
	dataDir := "/data/janus"
	if len(os.Args) > 1 {
		dataDir = os.Args[1]
	}

	dbPath := filepath.Join(dataDir, "janus.db")
	db, err := sql.Open("sqlite3", dbPath+"?_journal=WAL&_busy_timeout=5000")
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	// Create tables
	for _, ddl := range []string{
		`CREATE TABLE IF NOT EXISTS items (
			id TEXT PRIMARY KEY, type TEXT NOT NULL,
			title_en TEXT NOT NULL, title_ja TEXT NOT NULL,
			cover TEXT DEFAULT '', episode_count INTEGER DEFAULT 0,
			season_count INTEGER DEFAULT 0, duration_min INTEGER DEFAULT 0,
			synopsis_en TEXT DEFAULT '', synopsis_fr TEXT DEFAULT '', synopsis_ja TEXT DEFAULT '',
			tmdb_id INTEGER DEFAULT 0,
			updated_at INTEGER DEFAULT (strftime('%s','now'))
		)`,
		`CREATE TABLE IF NOT EXISTS episodes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			item_id TEXT NOT NULL REFERENCES items(id),
			season INTEGER NOT NULL, episode INTEGER NOT NULL,
			filename TEXT NOT NULL, duration_sec REAL DEFAULT 0,
			title_en TEXT DEFAULT '',
			synopsis_en TEXT DEFAULT '', synopsis_fr TEXT DEFAULT '', synopsis_ja TEXT DEFAULT '',
			thumb TEXT DEFAULT '',
			has_ja_subs INTEGER DEFAULT 0, has_en_subs INTEGER DEFAULT 0, has_fr_subs INTEGER DEFAULT 0,
			ja_srt_file TEXT DEFAULT '', en_srt_file TEXT DEFAULT '', fr_srt_file TEXT DEFAULT '',
			ja_sub_lines INTEGER DEFAULT 0,
			UNIQUE(item_id, season, episode)
		)`,
		`CREATE TABLE IF NOT EXISTS meta (
			key TEXT PRIMARY KEY, value TEXT NOT NULL,
			updated_at INTEGER DEFAULT (strftime('%s','now'))
		)`,
		`INSERT OR IGNORE INTO meta (key, value) VALUES ('library_version', '1')`,
		`INSERT OR IGNORE INTO meta (key, value) VALUES ('app_version_code', '9')`,
		`INSERT OR IGNORE INTO meta (key, value) VALUES ('app_version_name', '1.8')`,
	} {
		db.Exec(ddl)
	}

	itemsDir := filepath.Join(dataDir, "items")

	// Import series
	dirs, _ := os.ReadDir(itemsDir)
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		importSeries(db, itemsDir, d.Name())
	}

	// Import movies
	files, _ := os.ReadDir(itemsDir)
	for _, f := range files {
		if f.IsDir() || !strings.HasSuffix(f.Name(), ".json") {
			continue
		}
		importMovie(db, itemsDir, f.Name())
	}

	// Count
	var ic, ec int
	db.QueryRow("SELECT COUNT(*) FROM items").Scan(&ic)
	db.QueryRow("SELECT COUNT(*) FROM episodes").Scan(&ec)
	fmt.Printf("Imported: %d items, %d episodes\n", ic, ec)
}

func importSeries(db *sql.DB, itemsDir, seriesID string) {
	seriesDir := filepath.Join(itemsDir, seriesID)
	infoPath := filepath.Join(seriesDir, "info.json")

	data, err := os.ReadFile(infoPath)
	if err != nil {
		log.Printf("Skip %s: %v", seriesID, err)
		return
	}

	var info map[string]any
	json.Unmarshal(data, &info)

	id := str(info, "id")
	if id == "" {
		id = seriesID
	}

	epCount := intVal(info, "episode_count")
	seasons := info["seasons"].([]any)

	db.Exec(`INSERT OR REPLACE INTO items (id, type, title_en, title_ja, cover, episode_count, season_count) VALUES (?,?,?,?,?,?,?)`,
		id, str(info, "type"), str(info, "title_en"), str(info, "title_ja"), str(info, "cover"), epCount, len(seasons))

	fmt.Printf("Series: %s (%d episodes, %d seasons)\n", str(info, "title_en"), epCount, len(seasons))

	// Import each season file
	for _, s := range seasons {
		sm := s.(map[string]any)
		sNum := intVal(sm, "season")
		seasonFile := filepath.Join(seriesDir, fmt.Sprintf("season-%d.json", sNum))
		sData, err := os.ReadFile(seasonFile)
		if err != nil {
			continue
		}
		var sd map[string]any
		json.Unmarshal(sData, &sd)

		eps := sd["episodes"].([]any)
		for _, e := range eps {
			ep := e.(map[string]any)
			db.Exec(`INSERT OR REPLACE INTO episodes
				(item_id, season, episode, filename, duration_sec, title_en,
				synopsis_en, synopsis_fr, synopsis_ja, thumb,
				has_ja_subs, has_en_subs, has_fr_subs,
				ja_srt_file, en_srt_file, fr_srt_file, ja_sub_lines)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
				id, intVal(ep, "season"), intVal(ep, "episode"), str(ep, "filename"),
				floatVal(ep, "duration_sec"), str(ep, "title_en"),
				str(ep, "synopsis_en"), str(ep, "synopsis_fr"), str(ep, "synopsis_ja"),
				str(ep, "thumb"),
				boolInt(ep, "has_ja_subs"), boolInt(ep, "has_en_subs"), boolInt(ep, "has_fr_subs"),
				str(ep, "ja_srt_file"), str(ep, "en_srt_file"), str(ep, "fr_srt_file"),
				intVal(ep, "ja_sub_lines"),
			)
		}
		fmt.Printf("  Season %d: %d episodes\n", sNum, len(eps))
	}
}

func importMovie(db *sql.DB, itemsDir, filename string) {
	data, err := os.ReadFile(filepath.Join(itemsDir, filename))
	if err != nil {
		return
	}
	var movie map[string]any
	json.Unmarshal(data, &movie)

	if str(movie, "type") != "MOVIE" {
		return
	}

	id := str(movie, "id")
	ep, ok := movie["episode"].(map[string]any)
	if !ok {
		return
	}

	durMin := int(floatVal(ep, "duration_sec") / 60)

	db.Exec(`INSERT OR REPLACE INTO items (id, type, title_en, title_ja, cover, episode_count, duration_min, synopsis_en, synopsis_fr, synopsis_ja) VALUES (?,?,?,?,?,?,?,?,?,?)`,
		id, "MOVIE", str(movie, "title_en"), str(movie, "title_ja"), str(movie, "cover"),
		1, durMin,
		str(ep, "synopsis_en"), str(ep, "synopsis_fr"), str(ep, "synopsis_ja"),
	)

	db.Exec(`INSERT OR REPLACE INTO episodes
		(item_id, season, episode, filename, duration_sec, title_en,
		synopsis_en, synopsis_fr, synopsis_ja, thumb,
		has_ja_subs, has_en_subs, has_fr_subs,
		ja_srt_file, en_srt_file, fr_srt_file, ja_sub_lines)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		id, 1, 1, str(ep, "filename"), floatVal(ep, "duration_sec"), str(ep, "title_en"),
		str(ep, "synopsis_en"), str(ep, "synopsis_fr"), str(ep, "synopsis_ja"),
		str(ep, "thumb"),
		boolInt(ep, "has_ja_subs"), boolInt(ep, "has_en_subs"), boolInt(ep, "has_fr_subs"),
		str(ep, "ja_srt_file"), str(ep, "en_srt_file"), str(ep, "fr_srt_file"),
		intVal(ep, "ja_sub_lines"),
	)

	fmt.Printf("Movie: %s (%d min)\n", str(movie, "title_en"), durMin)
}

func str(m map[string]any, key string) string {
	v, _ := m[key].(string)
	return v
}

func intVal(m map[string]any, key string) int {
	switch v := m[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	}
	return 0
}

func floatVal(m map[string]any, key string) float64 {
	v, _ := m[key].(float64)
	return v
}

func boolInt(m map[string]any, key string) int {
	v, _ := m[key].(bool)
	if v {
		return 1
	}
	return 0
}
