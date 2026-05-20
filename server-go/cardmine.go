package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
)

// POST /api/card-resolve
//
// Request: { "item_id", "season", "episode", "start_ms", "end_ms" }
// Response: [4B meta JSON len][meta JSON][4B audio len][audio MP3][4B image len][image JPEG]
//
// Server only extracts media. All word data (expression, reading, meaning, jlpt)
// comes from the client's SuperSRT data.
func handleCardResolve(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "POST required", 405)
		return
	}

	var req struct {
		ItemID  string  `json:"item_id"`
		Season  int     `json:"season"`
		Episode int     `json:"episode"`
		StartMs float64 `json:"start_ms"`
		EndMs   float64 `json:"end_ms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request: "+err.Error(), 400)
		return
	}

	var filename string
	db.QueryRow("SELECT filename FROM episodes WHERE item_id=? AND season=? AND episode=?",
		req.ItemID, req.Season, req.Episode).Scan(&filename)
	if filename == "" {
		http.Error(w, "episode not found", 404)
		return
	}
	videoPath := filepath.Join(mediaDir, "videos", req.ItemID, filename)
	if !fileExists(videoPath) {
		http.Error(w, "video file not found", 404)
		return
	}

	startSec := math.Max(0, req.StartMs/1000.0-0.3)
	duration := (req.EndMs/1000.0 - startSec) + 0.3
	audioData := extractAudioClip(videoPath, startSec, duration)

	midSec := (req.StartMs + req.EndMs) / 2000.0
	imageData := extractScreenshot(videoPath, midSec)

	meta := map[string]string{}
	metaJSON, _ := json.Marshal(meta)

	var buf bytes.Buffer
	binary.Write(&buf, binary.LittleEndian, int32(len(metaJSON)))
	buf.Write(metaJSON)
	binary.Write(&buf, binary.LittleEndian, int32(len(audioData)))
	buf.Write(audioData)
	binary.Write(&buf, binary.LittleEndian, int32(len(imageData)))
	buf.Write(imageData)

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Write(buf.Bytes())
}

func extractAudioClip(videoPath string, startSec, duration float64) []byte {
	tmpFile := filepath.Join(os.TempDir(), fmt.Sprintf("janus_audio_%d.mp3", os.Getpid()))
	defer os.Remove(tmpFile)

	exec.Command("ffmpeg",
		"-ss", fmt.Sprintf("%.3f", startSec),
		"-i", videoPath,
		"-t", fmt.Sprintf("%.3f", duration),
		"-vn", "-acodec", "libmp3lame", "-ab", "128k", "-ar", "44100", "-ac", "1",
		"-y", tmpFile,
	).Run()

	data, _ := os.ReadFile(tmpFile)
	return data
}

func extractScreenshot(videoPath string, timeSec float64) []byte {
	tmpFile := filepath.Join(os.TempDir(), fmt.Sprintf("janus_shot_%d.jpg", os.Getpid()))
	defer os.Remove(tmpFile)

	exec.Command("ffmpeg",
		"-ss", fmt.Sprintf("%.3f", timeSec),
		"-i", videoPath,
		"-frames:v", "1",
		"-vf", "scale=854:-1",
		"-q:v", "3",
		"-update", "1",
		"-y", tmpFile,
	).Run()

	data, _ := os.ReadFile(tmpFile)
	return data
}
