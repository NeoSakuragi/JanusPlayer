package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

const superSRTVersion = 1

var superSRTCache sync.Map // "v{ver}:{itemId}:{season}:{episode}" → []byte (JSON)

// GET /api/super-srt/{itemId}/{season}/{episode}
func handleSuperSRT(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/super-srt/")
	parts := strings.Split(path, "/")
	if len(parts) != 3 {
		http.Error(w, "not found", 404)
		return
	}

	itemID := parts[0]
	season := atoi(parts[1])
	episode := atoi(parts[2])
	key := fmt.Sprintf("v%d:%s:%d:%d", superSRTVersion, itemID, season, episode)

	// Check cache
	if cached, ok := superSRTCache.Load(key); ok {
		data := cached.([]byte)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(data)
		return
	}

	// Find the SRT file
	var srtFile string
	db.QueryRow("SELECT srt_file FROM subtitles WHERE item_id=? AND season=? AND episode=? AND language='ja' ORDER BY id LIMIT 1",
		itemID, season, episode).Scan(&srtFile)
	if srtFile == "" {
		http.Error(w, "no Japanese subtitles", 404)
		return
	}
	srtPath := filepath.Join(mediaDir, "subs", itemID, srtFile)
	if _, err := os.Stat(srtPath); err != nil {
		http.Error(w, "srt not found", 404)
		return
	}

	// Build args for the generator
	genScript := filepath.Join(exeDir(), "gen_super_srt.py")
	args := []string{genScript, srtPath}

	// Series dict
	dictPath := filepath.Join(dataDir, "dicts", itemID+".json")
	if _, err := os.Stat(dictPath); err == nil {
		args = append(args, dictPath)
	} else {
		args = append(args, "")
	}

	// MeCab user dict
	mecabDictPath := filepath.Join(dataDir, "dicts", itemID+".dic")
	if _, err := os.Stat(mecabDictPath); err == nil {
		args = append(args, mecabDictPath)
	}

	// Run generator
	cmd := exec.Command("python3", args...)
	jitendexPath := filepath.Join(dataDir, "jitendex.bin")
	cmd.Env = append(os.Environ(),
		"PYTHONDONTWRITEBYTECODE=1",
		"JITENDEX_PATH="+jitendexPath,
		fmt.Sprintf("SUPER_SRT_VERSION=%d", superSRTVersion),
	)
	out, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			log.Printf("super-srt gen failed for %s: %s", key, string(exitErr.Stderr))
		}
		http.Error(w, "generation failed", 500)
		return
	}

	// Cache and serve
	superSRTCache.Store(key, out)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Write(out)
}

func exeDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}
