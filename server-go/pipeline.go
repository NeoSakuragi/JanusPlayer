package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

func runCommand(cmd string, args []string) {
	switch cmd {
	case "serve":
		// Fall through to main server startup handled by caller
		// This case shouldn't be reached since we return early
	case "status":
		cmdStatus()
	case "add-series":
		cmdAddSeries(args)
	case "add-movie":
		cmdAddMovie(args)
	case "fetch-tmdb":
		cmdFetchTMDB()
	case "extract-thumbs":
		cmdExtractThumbs()
	case "strip-tags":
		cmdStripTags()
	default:
		fmt.Printf("Unknown command: %s\n\n", cmd)
		fmt.Println("Usage: janus-server [command]")
		fmt.Println()
		fmt.Println("Commands:")
		fmt.Println("  (none)          Start HTTP server")
		fmt.Println("  status          Show library stats")
		fmt.Println("  add-series      Add a TV series: add-series <video-dir> --id=<id> --pattern=<regex> [--tmdb=<id>] [--season=<n>] [--tmdb-offset=<n>]")
		fmt.Println("  add-movie       Add a movie: add-movie <video-file> --id=<id> [--tmdb=<id>]")
		fmt.Println("  fetch-tmdb      Refresh TMDB data for all items")
		fmt.Println("  extract-thumbs  Generate missing thumbnails from video files")
		fmt.Println("  strip-tags      Strip HTML/ASS tags from all SRT files")
		os.Exit(1)
	}
}

// ── Status ────────────────────────────────────────────

func cmdStatus() {
	var itemCount, epCount int
	db.QueryRow("SELECT COUNT(*) FROM items").Scan(&itemCount)
	db.QueryRow("SELECT COUNT(*) FROM episodes").Scan(&epCount)

	fmt.Printf("Library: %d items, %d episodes\n\n", itemCount, epCount)

	rows, _ := db.Query("SELECT id, type, title_en, episode_count, season_count, duration_min FROM items ORDER BY title_en")
	if rows == nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var id, typ, title string
		var epC, sC, dur int
		rows.Scan(&id, &typ, &title, &epC, &sC, &dur)
		if typ == "MOVIE" {
			fmt.Printf("  [MOVIE]  %-25s %d min\n", title, dur)
		} else {
			fmt.Printf("  [SERIES] %-25s %d eps, %d seasons\n", title, epC, sC)
		}
	}

	var totalSize int64
	for _, sub := range []string{"videos", "subs", "covers", "thumbs"} {
		dir := filepath.Join(dataDir, sub)
		filepath.Walk(dir, func(_ string, info os.FileInfo, _ error) error {
			if info != nil && !info.IsDir() {
				totalSize += info.Size()
			}
			return nil
		})
	}
	fmt.Printf("\nDisk usage: %.1f GB\n", float64(totalSize)/(1024*1024*1024))
}

// ── Add Series ────────────────────────────────────────

func cmdAddSeries(args []string) {
	if len(args) < 1 {
		fmt.Println("Usage: add-series <video-dir> --id=<id> --pattern=<regex> [--tmdb=<id>] [--season=<n>] [--tmdb-offset=<n>]")
		os.Exit(1)
	}

	videoDir := args[0]
	id := flagVal(args, "id", "")
	pattern := flagVal(args, "pattern", "")
	tmdbID := flagInt(args, "tmdb", 0)
	defaultSeason := flagInt(args, "season", 1)
	tmdbOffset := flagInt(args, "tmdb-offset", 0)

	if id == "" || pattern == "" {
		fmt.Println("--id and --pattern are required")
		os.Exit(1)
	}

	re, err := regexp.Compile(pattern)
	if err != nil {
		fmt.Printf("Invalid pattern: %v\n", err)
		os.Exit(1)
	}

	files, _ := os.ReadDir(videoDir)
	sort.Slice(files, func(i, j int) bool { return files[i].Name() < files[j].Name() })

	subsDir := filepath.Join(dataDir, "subs", id)
	os.MkdirAll(subsDir, 0755)

	var episodes []episodeInfo
	for _, f := range files {
		if f.IsDir() || !strings.HasSuffix(f.Name(), ".mkv") {
			continue
		}
		matches := re.FindStringSubmatch(f.Name())
		if matches == nil {
			fmt.Printf("  SKIP: %s (no match)\n", f.Name())
			continue
		}

		season := defaultSeason
		epNum := 0
		if len(matches) == 3 {
			season, _ = strconv.Atoi(matches[1])
			epNum, _ = strconv.Atoi(matches[2])
		} else if len(matches) == 2 {
			epNum, _ = strconv.Atoi(matches[1])
		}
		if epNum == 0 {
			continue
		}

		videoPath := filepath.Join(videoDir, f.Name())
		fmt.Printf("  Processing: S%02dE%03d - %s\n", season, epNum, f.Name())

		info := probeVideo(videoPath)
		if info == nil {
			fmt.Println("    ERROR: ffprobe failed")
			continue
		}

		// Extract subtitles
		hasJa, hasFr, hasEn := extractSubs(videoPath, subsDir, epNum, info)

		// TMDB data
		var titleEn, synEn, synFr, synJa, thumbPath string
		if tmdbID > 0 {
			tmdbEpNum := epNum + tmdbOffset
			td := fetchTMDBEpisode(tmdbID, defaultSeason, tmdbEpNum)
			titleEn = td.titleEn
			synEn = td.synopsisEn
			synFr = td.synopsisFr
			synJa = td.synopsisJa
			if td.stillPath != "" {
				thumbDir := filepath.Join(dataDir, "thumbs", id)
				os.MkdirAll(thumbDir, 0755)
				thumbFile := fmt.Sprintf("ep%03d.jpg", epNum)
				downloadFile(tmdbImgBase+td.stillPath, filepath.Join(thumbDir, thumbFile))
				thumbPath = fmt.Sprintf("thumbs/%s/%s", id, thumbFile)
			}
			if titleEn != "" {
				fmt.Printf("    TMDB: %s\n", titleEn)
			}
		}

		// Fallback thumbnail from video
		if thumbPath == "" {
			thumbDir := filepath.Join(dataDir, "thumbs", id)
			os.MkdirAll(thumbDir, 0755)
			thumbFile := fmt.Sprintf("ep%03d.jpg", epNum)
			tp := filepath.Join(thumbDir, thumbFile)
			extractFrame(videoPath, tp, 360)
			if fileExists(tp) {
				thumbPath = fmt.Sprintf("thumbs/%s/%s", id, thumbFile)
			}
		}

		episodes = append(episodes, episodeInfo{
			season: season, episode: epNum, filename: f.Name(),
			durationSec: info.duration, titleEn: titleEn,
			synopsisEn: synEn, synopsisFr: synFr, synopsisJa: synJa,
			thumb: thumbPath, hasJa: hasJa, hasEn: hasEn, hasFr: hasFr,
			jaSrt: srtName(epNum, "ja", hasJa), enSrt: srtName(epNum, "en", hasEn), frSrt: srtName(epNum, "fr", hasFr),
		})
	}

	if len(episodes) == 0 {
		fmt.Println("No episodes found")
		return
	}

	// Count seasons
	seasonSet := map[int]int{}
	for _, ep := range episodes {
		seasonSet[ep.season]++
	}

	// Get title from TMDB or use ID
	titleEn := id
	titleJa := ""
	if tmdbID > 0 {
		ti := fetchTMDBSeriesInfo(tmdbID)
		if ti.titleEn != "" {
			titleEn = ti.titleEn
		}
		titleJa = ti.titleJa
	}

	// Insert into DB
	db.Exec(`INSERT OR REPLACE INTO items (id, type, title_en, title_ja, cover, episode_count, season_count, tmdb_id) VALUES (?,?,?,?,?,?,?,?)`,
		id, "TV_SERIES", titleEn, titleJa, fmt.Sprintf("covers/%s.jpg", id), len(episodes), len(seasonSet), tmdbID)

	for _, ep := range episodes {
		db.Exec(`INSERT OR REPLACE INTO episodes (item_id, season, episode, filename, duration_sec, title_en, synopsis_en, synopsis_fr, synopsis_ja, thumb, has_ja_subs, has_en_subs, has_fr_subs, ja_srt_file, en_srt_file, fr_srt_file) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			id, ep.season, ep.episode, ep.filename, ep.durationSec, ep.titleEn,
			ep.synopsisEn, ep.synopsisFr, ep.synopsisJa, ep.thumb,
			boolToInt(ep.hasJa), boolToInt(ep.hasEn), boolToInt(ep.hasFr),
			ep.jaSrt, ep.enSrt, ep.frSrt)
	}

	// Update library version
	db.Exec(`UPDATE meta SET value=?, updated_at=strftime('%s','now') WHERE key='library_version'`, fmt.Sprintf("%d", time.Now().Unix()))

	fmt.Printf("\nAdded: %s — %d episodes, %d seasons\n", titleEn, len(episodes), len(seasonSet))
}

// ── Add Movie ─────────────────────────────────────────

func cmdAddMovie(args []string) {
	if len(args) < 1 {
		fmt.Println("Usage: add-movie <video-file> --id=<id> [--tmdb=<id>]")
		os.Exit(1)
	}

	videoFile := args[0]
	id := flagVal(args, "id", "")
	tmdbID := flagInt(args, "tmdb", 0)

	if id == "" {
		fmt.Println("--id is required")
		os.Exit(1)
	}

	fmt.Printf("Processing: %s\n", filepath.Base(videoFile))

	info := probeVideo(videoFile)
	if info == nil {
		fmt.Println("ERROR: ffprobe failed")
		os.Exit(1)
	}

	subsDir := filepath.Join(dataDir, "subs", id)
	os.MkdirAll(subsDir, 0755)

	hasJa, hasFr, hasEn := extractMovieSubs(videoFile, subsDir, info)

	// Check for external subs
	extJa := findExternalSubs(subsDir, "movie_ja")
	if len(extJa) > 0 {
		hasJa = true
	}

	titleEn := id
	titleJa := ""
	var synEn, synFr, synJa string
	if tmdbID > 0 {
		md := fetchTMDBMovie(tmdbID)
		if md.titleEn != "" {
			titleEn = md.titleEn
		}
		titleJa = md.titleJa
		synEn = md.synopsisEn
		synFr = md.synopsisFr
		synJa = md.synopsisJa
		// Download backdrop
		if md.backdrop != "" {
			downloadFile(tmdbImgBase+md.backdrop, filepath.Join(dataDir, "covers", id+"-banner.jpg"))
		}
		if md.poster != "" {
			downloadFile(tmdbImgBase+md.poster, filepath.Join(dataDir, "covers", id+".jpg"))
		}
	}

	durMin := int(info.duration / 60)
	jaSrt := ""
	if hasJa {
		if len(extJa) > 0 {
			jaSrt = extJa[0]
		}
	}

	db.Exec(`INSERT OR REPLACE INTO items (id, type, title_en, title_ja, cover, episode_count, duration_min, synopsis_en, synopsis_fr, synopsis_ja, tmdb_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
		id, "MOVIE", titleEn, titleJa, fmt.Sprintf("covers/%s.jpg", id), 1, durMin, synEn, synFr, synJa, tmdbID)

	db.Exec(`INSERT OR REPLACE INTO episodes (item_id, season, episode, filename, duration_sec, synopsis_en, synopsis_fr, synopsis_ja, has_ja_subs, has_en_subs, has_fr_subs, ja_srt_file) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
		id, 1, 1, filepath.Base(videoFile), info.duration, synEn, synFr, synJa,
		boolToInt(hasJa), boolToInt(hasEn), boolToInt(hasFr), jaSrt)

	db.Exec(`UPDATE meta SET value=?, updated_at=strftime('%s','now') WHERE key='library_version'`, fmt.Sprintf("%d", time.Now().Unix()))

	fmt.Printf("\nAdded: %s — %d min\n", titleEn, durMin)
}

// ── Fetch TMDB ────────────────────────────────────────

func cmdFetchTMDB() {
	rows, _ := db.Query("SELECT id, type, tmdb_id FROM items WHERE tmdb_id > 0")
	if rows == nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var id, typ string
		var tmdbID int
		rows.Scan(&id, &typ, &tmdbID)

		if typ == "MOVIE" {
			md := fetchTMDBMovie(tmdbID)
			db.Exec(`UPDATE items SET synopsis_en=?, synopsis_fr=?, synopsis_ja=? WHERE id=?`,
				md.synopsisEn, md.synopsisFr, md.synopsisJa, id)
			fmt.Printf("  %s: synopsis updated\n", id)
		} else {
			epRows, _ := db.Query("SELECT season, episode FROM episodes WHERE item_id=? ORDER BY season, episode", id)
			if epRows == nil {
				continue
			}
			for epRows.Next() {
				var season, epNum int
				epRows.Scan(&season, &epNum)
				td := fetchTMDBEpisode(tmdbID, season, epNum)
				if td.titleEn != "" {
					db.Exec(`UPDATE episodes SET title_en=?, synopsis_en=?, synopsis_fr=?, synopsis_ja=? WHERE item_id=? AND season=? AND episode=?`,
						td.titleEn, td.synopsisEn, td.synopsisFr, td.synopsisJa, id, season, epNum)
				}
			}
			epRows.Close()
			fmt.Printf("  %s: episodes updated\n", id)
		}
	}
}

// ── Extract Thumbs ────────────────────────────────────

func cmdExtractThumbs() {
	rows, _ := db.Query("SELECT e.item_id, e.episode, e.filename, i.type FROM episodes e JOIN items i ON i.id = e.item_id WHERE e.thumb = '' OR e.thumb IS NULL")
	if rows == nil {
		return
	}
	defer rows.Close()

	count := 0
	for rows.Next() {
		var itemID, filename, typ string
		var epNum int
		rows.Scan(&itemID, &epNum, &filename, &typ)

		videoPath := filepath.Join(dataDir, "videos", itemID, filename)
		if !fileExists(videoPath) {
			continue
		}

		thumbDir := filepath.Join(dataDir, "thumbs", itemID)
		os.MkdirAll(thumbDir, 0755)
		thumbFile := fmt.Sprintf("ep%03d.jpg", epNum)
		thumbPath := filepath.Join(thumbDir, thumbFile)

		if extractFrame(videoPath, thumbPath, 360) {
			relPath := fmt.Sprintf("thumbs/%s/%s", itemID, thumbFile)
			db.Exec(`UPDATE episodes SET thumb=? WHERE item_id=? AND episode=?`, relPath, itemID, epNum)
			count++
			fmt.Printf("  %s ep%d: thumbnail extracted\n", itemID, epNum)
		}
	}
	fmt.Printf("Extracted %d thumbnails\n", count)
}

// ── Strip Tags ────────────────────────────────────────

func cmdStripTags() {
	subsDir := filepath.Join(dataDir, "subs")
	count := 0
	filepath.Walk(subsDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".srt") {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		content := string(data)
		if !strings.Contains(content, "<font") && !strings.Contains(content, "</") && !strings.Contains(content, "{\\") {
			return nil
		}
		cleaned := regexp.MustCompile(`<[^>]+>`).ReplaceAllString(content, "")
		cleaned = regexp.MustCompile(`\{[^}]+\}`).ReplaceAllString(cleaned, "")
		os.WriteFile(path, []byte(cleaned), 0644)
		count++
		fmt.Printf("  Stripped: %s\n", path)
		return nil
	})
	fmt.Printf("Cleaned %d files\n", count)
}

// ── FFmpeg/FFprobe helpers ────────────────────────────

type videoInfo struct {
	duration float64
	jaSubIdx int
	enSubIdx int
	frSubIdx int
}

type episodeInfo struct {
	season, episode                    int
	filename, titleEn                  string
	durationSec                        float64
	synopsisEn, synopsisFr, synopsisJa string
	thumb                              string
	hasJa, hasEn, hasFr                bool
	jaSrt, enSrt, frSrt                string
}

func probeVideo(path string) *videoInfo {
	out, err := exec.Command("ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path).Output()
	if err != nil {
		return nil
	}
	var data struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
		Streams []struct {
			Index     int    `json:"index"`
			CodecType string `json:"codec_type"`
			CodecName string `json:"codec_name"`
			Tags      struct {
				Language string `json:"language"`
			} `json:"tags"`
		} `json:"streams"`
	}
	json.Unmarshal(out, &data)

	dur, _ := strconv.ParseFloat(data.Format.Duration, 64)
	info := &videoInfo{duration: dur, jaSubIdx: -1, enSubIdx: -1, frSubIdx: -1}

	textCodecs := map[string]bool{"subrip": true, "srt": true, "ass": true, "ssa": true, "webvtt": true, "mov_text": true}
	for _, s := range data.Streams {
		if s.CodecType != "subtitle" || !textCodecs[s.CodecName] {
			continue
		}
		switch s.Tags.Language {
		case "jpn":
			if info.jaSubIdx < 0 {
				info.jaSubIdx = s.Index
			}
		case "eng":
			if info.enSubIdx < 0 {
				info.enSubIdx = s.Index
			}
		case "fre", "fra":
			if info.frSubIdx < 0 {
				info.frSubIdx = s.Index
			}
		}
	}
	return info
}

func extractSrt(videoPath, outputPath string, streamIdx int) bool {
	if fileExists(outputPath) {
		return true
	}
	err := exec.Command("ffmpeg", "-v", "quiet", "-y", "-i", videoPath, "-map", fmt.Sprintf("0:%d", streamIdx), outputPath).Run()
	if err != nil {
		return false
	}
	stripSrtFile(outputPath)
	return true
}

func extractSubs(videoPath, subsDir string, epNum int, info *videoInfo) (hasJa, hasFr, hasEn bool) {
	if info.jaSubIdx >= 0 {
		srtPath := filepath.Join(subsDir, fmt.Sprintf("ep%03d_ja.srt", epNum))
		if extractSrt(videoPath, srtPath, info.jaSubIdx) {
			hasJa = true
		}
	} else if fileExists(filepath.Join(subsDir, fmt.Sprintf("ep%03d_ja.srt", epNum))) {
		hasJa = true
	}

	if info.frSubIdx >= 0 {
		srtPath := filepath.Join(subsDir, fmt.Sprintf("ep%03d_fr.srt", epNum))
		if extractSrt(videoPath, srtPath, info.frSubIdx) {
			hasFr = true
		}
	}

	if info.enSubIdx >= 0 {
		srtPath := filepath.Join(subsDir, fmt.Sprintf("ep%03d_en.srt", epNum))
		if extractSrt(videoPath, srtPath, info.enSubIdx) {
			hasEn = true
		}
	}
	return
}

func extractMovieSubs(videoPath, subsDir string, info *videoInfo) (hasJa, hasFr, hasEn bool) {
	if info.jaSubIdx >= 0 {
		if extractSrt(videoPath, filepath.Join(subsDir, "movie_ja.srt"), info.jaSubIdx) {
			hasJa = true
		}
	}
	if info.enSubIdx >= 0 {
		if extractSrt(videoPath, filepath.Join(subsDir, "movie_en.srt"), info.enSubIdx) {
			hasEn = true
		}
	}
	if info.frSubIdx >= 0 {
		if extractSrt(videoPath, filepath.Join(subsDir, "movie_fr.srt"), info.frSubIdx) {
			hasFr = true
		}
	}
	return
}

func extractFrame(videoPath, outputPath string, seekSec int) bool {
	if fileExists(outputPath) {
		return true
	}
	return exec.Command("ffmpeg", "-v", "quiet", "-y", "-ss", strconv.Itoa(seekSec), "-i", videoPath, "-vframes", "1", "-q:v", "2", outputPath).Run() == nil
}

func stripSrtFile(path string) {
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	content := string(data)
	if !strings.Contains(content, "<") && !strings.Contains(content, "{\\") {
		return
	}
	cleaned := regexp.MustCompile(`<[^>]+>`).ReplaceAllString(content, "")
	cleaned = regexp.MustCompile(`\{[^}]+\}`).ReplaceAllString(cleaned, "")
	os.WriteFile(path, []byte(cleaned), 0644)
}

func findExternalSubs(subsDir, prefix string) []string {
	var result []string
	files, _ := os.ReadDir(subsDir)
	for _, f := range files {
		if strings.HasPrefix(f.Name(), prefix) && strings.HasSuffix(f.Name(), ".srt") {
			result = append(result, f.Name())
		}
	}
	sort.Strings(result)
	return result
}

func srtName(epNum int, lang string, exists bool) string {
	if !exists {
		return ""
	}
	return fmt.Sprintf("ep%03d_%s.srt", epNum, lang)
}

// ── TMDB helpers ──────────────────────────────────────

const (
	tmdbAPIKey  = "86d0df9095f6e5d35ba68f7660189c27"
	tmdbImgBase = "https://image.tmdb.org/t/p/w400"
)

type tmdbEpisodeData struct {
	titleEn, synopsisEn, synopsisFr, synopsisJa, stillPath string
}

type tmdbMovieData struct {
	titleEn, titleJa, synopsisEn, synopsisFr, synopsisJa, backdrop, poster string
}

type tmdbSeriesInfo struct {
	titleEn, titleJa string
}

func fetchTMDBEpisode(tmdbID, season, epNum int) tmdbEpisodeData {
	var result tmdbEpisodeData
	for _, lang := range []string{"en", "fr", "ja"} {
		url := fmt.Sprintf("https://api.themoviedb.org/3/tv/%d/season/%d/episode/%d?api_key=%s&language=%s", tmdbID, season, epNum, tmdbAPIKey, lang)
		data := httpGetJSON(url)
		if data == nil {
			continue
		}
		overview, _ := data["overview"].(string)
		switch lang {
		case "en":
			result.synopsisEn = overview
			result.titleEn, _ = data["name"].(string)
			if sp, ok := data["still_path"].(string); ok {
				result.stillPath = sp
			}
		case "fr":
			result.synopsisFr = overview
		case "ja":
			result.synopsisJa = overview
		}
		time.Sleep(30 * time.Millisecond)
	}
	return result
}

func fetchTMDBMovie(tmdbID int) tmdbMovieData {
	var result tmdbMovieData
	for _, lang := range []string{"en", "fr", "ja"} {
		url := fmt.Sprintf("https://api.themoviedb.org/3/movie/%d?api_key=%s&language=%s", tmdbID, tmdbAPIKey, lang)
		data := httpGetJSON(url)
		if data == nil {
			continue
		}
		overview, _ := data["overview"].(string)
		switch lang {
		case "en":
			result.synopsisEn = overview
			result.titleEn, _ = data["title"].(string)
			result.backdrop, _ = data["backdrop_path"].(string)
			result.poster, _ = data["poster_path"].(string)
		case "fr":
			result.synopsisFr = overview
		case "ja":
			result.synopsisJa = overview
			result.titleJa, _ = data["title"].(string)
		}
		time.Sleep(30 * time.Millisecond)
	}
	return result
}

func fetchTMDBSeriesInfo(tmdbID int) tmdbSeriesInfo {
	var result tmdbSeriesInfo
	for _, lang := range []string{"en", "ja"} {
		url := fmt.Sprintf("https://api.themoviedb.org/3/tv/%d?api_key=%s&language=%s", tmdbID, tmdbAPIKey, lang)
		data := httpGetJSON(url)
		if data == nil {
			continue
		}
		switch lang {
		case "en":
			result.titleEn, _ = data["name"].(string)
		case "ja":
			result.titleJa, _ = data["name"].(string)
		}
	}
	return result
}

// ── HTTP/file helpers ─────────────────────────────────

func httpGetJSON(url string) map[string]any {
	resp, err := http.Get(url)
	if err != nil || resp.StatusCode != 200 {
		return nil
	}
	defer resp.Body.Close()
	var data map[string]any
	json.NewDecoder(resp.Body).Decode(&data)
	return data
}

func downloadFile(url, path string) bool {
	if fileExists(path) {
		return true
	}
	resp, err := http.Get(url)
	if err != nil || resp.StatusCode != 200 {
		return false
	}
	defer resp.Body.Close()
	f, err := os.Create(path)
	if err != nil {
		return false
	}
	defer f.Close()
	io.Copy(f, resp.Body)
	return true
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() > 100
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func flagVal(args []string, name, fallback string) string {
	prefix := "--" + name + "="
	for _, a := range args {
		if strings.HasPrefix(a, prefix) {
			return strings.TrimPrefix(a, prefix)
		}
	}
	return fallback
}

func flagInt(args []string, name string, fallback int) int {
	s := flagVal(args, name, "")
	if s == "" {
		return fallback
	}
	n, _ := strconv.Atoi(s)
	return n
}
