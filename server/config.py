"""
Janus Server Configuration
"""
import os

# Base directory for all media data
DATA_DIR = os.environ.get("JANUS_DATA_DIR", "/data/janus")

# Server
HOST = os.environ.get("JANUS_HOST", "0.0.0.0")
PORT = int(os.environ.get("JANUS_PORT", "8900"))

# Paths
VIDEOS_DIR = os.path.join(DATA_DIR, "videos")
SUBS_DIR = os.path.join(DATA_DIR, "subs")
COVERS_DIR = os.path.join(DATA_DIR, "covers")
LIBRARY_FILE = os.path.join(DATA_DIR, "library.json")

# AniList API
ANILIST_API = "https://graphql.anilist.co"
