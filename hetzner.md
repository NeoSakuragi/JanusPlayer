# Hetzner Server

> **Deploys are CI/CD now (2026-07-17):** the Go server ships via GitHub Actions from
> `github.com/NeoSakuragi/JanusPlus` (`server-go/**` push → build on VPS → health-checked
> swap with rollback). The JLPT web app ships from `github.com/NeoSakuragi/jlpt-web`
> (push → rsync to `/var/www/kanji/jlpt/`). The old `Janus/server-go` tree and
> `deploy-remote.sh` were deleted — the server source lives ONLY in JanusPlus.
> `/jlpt/` is behind cookie-session auth: nginx `auth_request /jlpt-auth` →
> Janus `/api/authcheck` validates the HttpOnly `janus_token` cookie set by
> `/api/login`; unauthenticated requests 302 to the gate-exempt `/jlpt/login.html`.
> Credentials = Janus users. No htpasswd anymore; password changes need no re-seeding.

## Server
- **Provider**: Hetzner Cloud CX23 (shared with NeoMobiles26)
- **IP**: 195.201.91.211
- **Domain**: canneji.duckdns.org (SSL via Let's Encrypt)
- **SSH**: `ssh -i ~/.ssh/id_ed25519 root@195.201.91.211`
- **OS**: Ubuntu 24.04, nginx

## Janus APK (backup mirror)
- **Path on server**: `/var/www/kanji/janus/`
- **URL**: https://canneji.duckdns.org/janus/janus.apk
- **AFTV code**: aftv.news/7996988

Note: The primary update path is now the local Go server (`/api/update/janus.apk`). Hetzner is a backup for initial installs or when not on LAN.

## Nginx
Served by the `canneji.duckdns.org` config at `/etc/nginx/sites-enabled/kanji`. The `/janus/` path is handled by `try_files`.
