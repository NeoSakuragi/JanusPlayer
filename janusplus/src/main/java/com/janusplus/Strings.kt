package com.janusplus

object Lang {
    var current: String = "ja"

    private val strings = mapOf(
        "app_name" to mapOf("ja" to "ヤヌス", "en" to "Janus", "fr" to "Janus"),
        "continue_watching" to mapOf("ja" to "視聴中", "en" to "Continue Watching", "fr" to "Continuer"),
        "series" to mapOf("ja" to "シリーズ", "en" to "Series", "fr" to "Séries"),
        "movies" to mapOf("ja" to "映画", "en" to "Movies", "fr" to "Films"),
        "login" to mapOf("ja" to "ログイン", "en" to "Login", "fr" to "Connexion"),
        "connect" to mapOf("ja" to "接続", "en" to "Connect", "fr" to "Connexion"),
        "server" to mapOf("ja" to "サーバー", "en" to "Server", "fr" to "Serveur"),
        "username" to mapOf("ja" to "ユーザー名", "en" to "Username", "fr" to "Identifiant"),
        "password" to mapOf("ja" to "パスワード", "en" to "Password", "fr" to "Mot de passe"),
        "play" to mapOf("ja" to "▶  再生", "en" to "▶  Play", "fr" to "▶  Lecture"),
        "resume" to mapOf("ja" to "▶  再開", "en" to "▶  Resume", "fr" to "▶  Reprendre"),
        "resume_ep" to mapOf("ja" to "▶  第%dエピソード再開", "en" to "▶  Resume Ep. %d", "fr" to "▶  Reprendre Ép. %d"),
        "play_ep" to mapOf("ja" to "▶  第%dエピソード再生", "en" to "▶  Play Episode %d", "fr" to "▶  Lire Épisode %d"),
        "download" to mapOf("ja" to "↓ ダウンロード", "en" to "↓ Download", "fr" to "↓ Télécharger"),
        "episodes" to mapOf("ja" to "%d エピソード", "en" to "%d episodes", "fr" to "%d épisodes"),
        "episode" to mapOf("ja" to "第%dエピソード", "en" to "Episode %d", "fr" to "Épisode %d"),
        "min" to mapOf("ja" to "%d分", "en" to "%d min", "fr" to "%d min"),
        "jp_subs" to mapOf("ja" to "日本語字幕", "en" to "JP subs", "fr" to "Sous-titres JP"),
        "loading" to mapOf("ja" to "読み込み中...", "en" to "Loading...", "fr" to "Chargement..."),
        "loading_library" to mapOf("ja" to "ライブラリを読み込み中...", "en" to "Loading library...", "fr" to "Chargement..."),
        "no_connection" to mapOf("ja" to "サーバーに接続できません", "en" to "Could not connect to server", "fr" to "Impossible de se connecter"),
        "all_fields_required" to mapOf("ja" to "すべて入力してください", "en" to "All fields required", "fr" to "Tous les champs requis"),
        "invalid_credentials" to mapOf("ja" to "認証エラー", "en" to "Invalid credentials", "fr" to "Identifiants invalides"),
        "settings" to mapOf("ja" to "設定", "en" to "Settings", "fr" to "Paramètres"),
    )

    fun s(key: String): String = strings[key]?.get(current) ?: strings[key]?.get("en") ?: key
    fun s(key: String, arg: Any): String = s(key).replace("%d", arg.toString()).replace("%s", arg.toString())
}
