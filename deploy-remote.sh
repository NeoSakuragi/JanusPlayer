#!/bin/bash
# Deploy Janus server to Hetzner VPS
# DB migrations run automatically on startup — no DB sync needed
set -e
VPS="root@195.201.91.211"
SSH="ssh -i ~/.ssh/id_ed25519"

echo "1. Uploading Go source + Python scripts..."
rsync -avq -e "ssh -i ~/.ssh/id_ed25519" \
    /home/bruno/CLProjects/Janus/server-go/{main.go,auth.go,pipeline.go,blob.go,supersrt.go,go.mod,go.sum} \
    /home/bruno/CLProjects/Janus/server-go/{gen_super_srt.py,jitendex_reader.py} \
    $VPS:/root/janus-server/

echo "2. Uploading dicts + jitendex..."
$SSH $VPS "mkdir -p /data/janus/dicts"
rsync -avq -e "ssh -i ~/.ssh/id_ed25519" \
    /data/janus/dicts/ $VPS:/data/janus/dicts/
rsync -avq -e "ssh -i ~/.ssh/id_ed25519" \
    /data/janus/jitendex.bin $VPS:/data/janus/jitendex.bin

echo "3. Building on VPS..."
$SSH $VPS "cd /root/janus-server && CGO_ENABLED=1 go build -o janus-server main.go auth.go pipeline.go blob.go supersrt.go 2>&1"

echo "4. Restarting service..."
$SSH $VPS "systemctl restart janus && sleep 8 && curl -s http://localhost:8900/api/health"

echo "Done."
