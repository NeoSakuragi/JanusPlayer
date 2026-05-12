# Hetzner Server — Janus APK Hosting

## Server
- **Provider**: Hetzner Cloud CX23 (shared with NeoMobiles26)
- **IP**: 195.201.91.211
- **Domain**: canneji.duckdns.org (SSL via Let's Encrypt)
- **SSH**: `ssh -i ~/.ssh/id_ed25519 root@195.201.91.211`
- **OS**: Ubuntu 24.04, nginx

## Janus Update Files
- **Path on server**: `/var/www/kanji/janus/`
- **Version manifest**: https://canneji.duckdns.org/janus/version.json
- **APK downloads**: https://canneji.duckdns.org/janus/janus-v{VERSION}.apk

### version.json format
```json
{"version_code": 2, "version_name": "1.1", "apk": "janus-v1.1.apk"}
```

## Deploying a New Version
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. Run `./deploy.sh`

This builds the APK, uploads it to Hetzner with a versioned filename, and updates version.json. The app checks on startup and shows an update banner if a newer version is available.

## Nginx
Served by the existing `canneji.duckdns.org` config at `/etc/nginx/sites-enabled/kanji`. The `/janus/` path is handled by the `try_files` directive — no additional nginx config needed.
