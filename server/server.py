#!/usr/bin/env python3
"""
Janus Media Server

Serves: library metadata, video files (with Range support), subtitles, cover art.
Run: python3 server.py
Config: environment variables or config.py defaults.
"""
import http.server
import json
import os
import sys
from urllib.parse import unquote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import DATA_DIR, HOST, PORT, VIDEOS_DIR, SUBS_DIR, COVERS_DIR, LIBRARY_FILE


class JanusHandler(http.server.BaseHTTPRequestHandler):

    def do_GET(self):
        path = unquote(self.path)

        if path == "/api/library":
            self.serve_json(LIBRARY_FILE)
        elif path.startswith("/api/items/"):
            self.serve_file(os.path.join(DATA_DIR, "items", path[11:]))
        elif path.startswith("/api/thumbs/"):
            self.serve_file(os.path.join(DATA_DIR, "thumbs", path[12:]))
        elif path.startswith("/api/subs/"):
            self.serve_file(os.path.join(SUBS_DIR, path[10:]))
        elif path.startswith("/api/covers/"):
            self.serve_file(os.path.join(COVERS_DIR, path[12:]))
        elif path.startswith("/api/video/"):
            self.serve_video(os.path.join(VIDEOS_DIR, path[11:]))
        elif path == "/api/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())
        else:
            self.send_error(404)

    def serve_json(self, filepath):
        if not os.path.exists(filepath):
            self.send_error(404); return
        data = open(filepath, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(data))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def serve_file(self, filepath):
        if not os.path.exists(filepath):
            self.send_error(404); return
        data = open(filepath, "rb").read()
        ct = "application/octet-stream"
        if filepath.endswith(".srt"): ct = "text/plain; charset=utf-8"
        elif filepath.endswith(".jpg") or filepath.endswith(".jpeg"): ct = "image/jpeg"
        elif filepath.endswith(".png"): ct = "image/png"
        elif filepath.endswith(".json"): ct = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", len(data))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def serve_video(self, filepath):
        if not os.path.exists(filepath):
            self.send_error(404); return
        file_size = os.path.getsize(filepath)
        range_header = self.headers.get("Range")

        if range_header:
            range_str = range_header.replace("bytes=", "").strip()
            parts = range_str.split("-")
            start = int(parts[0]) if parts[0] else 0
            end = int(parts[1]) if len(parts) > 1 and parts[1] else file_size - 1
            end = min(end, file_size - 1)
            length = end - start + 1
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
            self.send_header("Content-Length", length)
        else:
            start = 0
            length = file_size
            self.send_response(200)
            self.send_header("Content-Length", file_size)

        self.send_header("Content-Type", "video/x-matroska")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        with open(filepath, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk: break
                try:
                    self.wfile.write(chunk)
                except BrokenPipeError:
                    break
                remaining -= len(chunk)

    def log_message(self, format, *args):
        path = args[0].split(" ")[1] if args else ""
        if "/api/video/" in path: return
        print(f"[{self.log_date_time_string()}] {format % args}")


def main():
    print(f"Janus Media Server")
    print(f"  Data:    {DATA_DIR}")
    print(f"  Videos:  {VIDEOS_DIR}")
    print(f"  Subs:    {SUBS_DIR}")
    print(f"  Covers:  {COVERS_DIR}")
    print(f"  Library: {LIBRARY_FILE}")
    print(f"  Listen:  http://{HOST}:{PORT}")
    print()

    server = http.server.HTTPServer((HOST, PORT), JanusHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
