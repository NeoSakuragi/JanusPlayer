package com.videoplayer

import androidx.compose.runtime.mutableStateOf

object Lang {
    val current = mutableStateOf("ja")

    private val strings = mapOf(
        "app_name" to mapOf("ja" to "ヤヌス", "en" to "Janus", "fr" to "Janus"),
        "continue_watching" to mapOf("ja" to "視聴中", "en" to "Continue Watching", "fr" to "Continuer"),
        "series" to mapOf("ja" to "シリーズ", "en" to "Series", "fr" to "Séries"),
        "movies" to mapOf("ja" to "映画", "en" to "Movies", "fr" to "Films"),
        "downloads" to mapOf("ja" to "ダウンロード", "en" to "Downloads", "fr" to "Téléchargements"),
        "login" to mapOf("ja" to "ログイン", "en" to "Login", "fr" to "Connexion"),
        "connect" to mapOf("ja" to "接続", "en" to "Connect", "fr" to "Connexion"),
        "server" to mapOf("ja" to "サーバー", "en" to "Server", "fr" to "Serveur"),
        "username" to mapOf("ja" to "ユーザー名", "en" to "Username", "fr" to "Identifiant"),
        "password" to mapOf("ja" to "パスワード", "en" to "Password", "fr" to "Mot de passe"),
        "back" to mapOf("ja" to "← 戻る", "en" to "← Back", "fr" to "← Retour"),
        "back_label" to mapOf("ja" to "戻る", "en" to "Back", "fr" to "Retour"),
        "play" to mapOf("ja" to "▶  再生", "en" to "▶  Play", "fr" to "▶  Lecture"),
        "resume" to mapOf("ja" to "▶  再開", "en" to "▶  Resume", "fr" to "▶  Reprendre"),
        "resume_ep" to mapOf("ja" to "▶  第%dエピソード再開", "en" to "▶  Resume Ep. %d", "fr" to "▶  Reprendre Ép. %d"),
        "play_ep" to mapOf("ja" to "▶  第%dエピソード再生", "en" to "▶  Play Episode %d", "fr" to "▶  Lire Épisode %d"),
        "download" to mapOf("ja" to "↓ ダウンロード", "en" to "↓ Download", "fr" to "↓ Télécharger"),
        "episodes" to mapOf("ja" to "%d エピソード", "en" to "%d episodes", "fr" to "%d épisodes"),
        "episode" to mapOf("ja" to "第%dエピソード", "en" to "Episode %d", "fr" to "Épisode %d"),
        "min" to mapOf("ja" to "%d分", "en" to "%d min", "fr" to "%d min"),
        "jp_subs" to mapOf("ja" to "日本語字幕", "en" to "JP subs", "fr" to "Sous-titres JP"),
        "no_downloads" to mapOf("ja" to "ダウンロードなし", "en" to "No downloads yet", "fr" to "Aucun téléchargement"),
        "delete" to mapOf("ja" to "削除", "en" to "Delete", "fr" to "Supprimer"),
        "loading" to mapOf("ja" to "読み込み中...", "en" to "Loading...", "fr" to "Chargement..."),
        "loading_library" to mapOf("ja" to "ライブラリを読み込み中...", "en" to "Loading library...", "fr" to "Chargement..."),
        "no_connection" to mapOf("ja" to "サーバーに接続できません", "en" to "Could not connect to server", "fr" to "Impossible de se connecter"),
        "watch_offline" to mapOf("ja" to "オフラインで視聴 (%d本)", "en" to "Watch offline (%d episodes)", "fr" to "Regarder hors-ligne (%d épisodes)"),
        "all_fields_required" to mapOf("ja" to "すべて入力してください", "en" to "All fields required", "fr" to "Tous les champs requis"),
        "invalid_credentials" to mapOf("ja" to "認証エラー", "en" to "Invalid credentials", "fr" to "Identifiants invalides"),
        "connection_failed" to mapOf("ja" to "接続失敗: %s", "en" to "Connection failed: %s", "fr" to "Échec de connexion: %s"),
        "audio" to mapOf("ja" to "音声", "en" to "Audio", "fr" to "Audio"),
        "subs" to mapOf("ja" to "字幕", "en" to "Subs", "fr" to "Sous-titres"),
        "cond_on" to mapOf("ja" to "凝縮 ON", "en" to "COND ON", "fr" to "COND ON"),
        "cond_off" to mapOf("ja" to "凝縮 OFF", "en" to "COND OFF", "fr" to "COND OFF"),
        "queued" to mapOf("ja" to "待機中", "en" to "Queued", "fr" to "En attente"),
        "failed" to mapOf("ja" to "失敗", "en" to "Failed", "fr" to "Échec"),
        "watched" to mapOf("ja" to "✓ 視聴済み", "en" to "✓ Watched", "fr" to "✓ Vu"),
        "lang_label" to mapOf("ja" to "日本語", "en" to "EN", "fr" to "FR"),
        "settings" to mapOf("ja" to "設定", "en" to "Settings", "fr" to "Paramètres"),
        "logout" to mapOf("ja" to "ログアウト", "en" to "Logout", "fr" to "Déconnexion"),
        "logged_in_as" to mapOf("ja" to "%s でログイン中", "en" to "Logged in as %s", "fr" to "Connecté en tant que %s"),
        "updates" to mapOf("ja" to "アップデート", "en" to "UPDATES", "fr" to "MISES À JOUR"),
        "check_app_update" to mapOf("ja" to "アプリの更新を確認", "en" to "Check for app update", "fr" to "Vérifier mise à jour"),
        "check_library" to mapOf("ja" to "ライブラリの更新を確認", "en" to "Check for library update", "fr" to "Vérifier la bibliothèque"),
        "server_url" to mapOf("ja" to "サーバーURL", "en" to "Server URL", "fr" to "URL du serveur"),
        "manage_downloads" to mapOf("ja" to "オフラインエピソードを管理", "en" to "Manage offline episodes", "fr" to "Gérer les épisodes hors-ligne"),
        "playback" to mapOf("ja" to "再生", "en" to "PLAYBACK", "fr" to "LECTURE"),
        "hw_decoding" to mapOf("ja" to "ハードウェアデコード", "en" to "Hardware decoding", "fr" to "Décodage matériel"),
        "anki_connect" to mapOf("ja" to "ANKI接続", "en" to "ANKI CONNECT", "fr" to "ANKI CONNECT"),
        "test_connection" to mapOf("ja" to "接続テスト", "en" to "Test connection", "fr" to "Tester la connexion"),
        "tap_to_check" to mapOf("ja" to "タップして確認", "en" to "Tap to check", "fr" to "Appuyer pour vérifier"),
        "tap_to_test" to mapOf("ja" to "タップしてテスト", "en" to "Tap to test", "fr" to "Appuyer pour tester"),
        "deck" to mapOf("ja" to "デッキ", "en" to "Deck", "fr" to "Paquet"),
        "note_type" to mapOf("ja" to "ノートタイプ", "en" to "Note type", "fr" to "Type de note"),
        "tags" to mapOf("ja" to "タグ", "en" to "Tags", "fr" to "Tags"),
        "dictionaries" to mapOf("ja" to "辞書", "en" to "DICTIONARIES", "fr" to "DICTIONNAIRES"),
        "field_mappings" to mapOf("ja" to "フィールドマッピング", "en" to "FIELD MAPPINGS", "fr" to "MAPPAGES"),
        "cancel" to mapOf("ja" to "キャンセル", "en" to "Cancel", "fr" to "Annuler"),
        "ok" to mapOf("ja" to "OK", "en" to "OK", "fr" to "OK"),
        "install" to mapOf("ja" to "インストール", "en" to "Install", "fr" to "Installer"),
    )

    fun s(key: String): String = strings[key]?.get(current.value) ?: strings[key]?.get("en") ?: key

    fun s(key: String, arg: Any): String = s(key).replace("%d", arg.toString()).replace("%s", arg.toString())
}
