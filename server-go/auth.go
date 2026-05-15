package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

var jwtSecret []byte

func initAuth() {
	// Load or generate JWT secret
	var secret string
	db.QueryRow("SELECT value FROM meta WHERE key='jwt_secret'").Scan(&secret)
	if secret == "" {
		b := make([]byte, 32)
		rand.Read(b)
		secret = hex.EncodeToString(b)
		db.Exec(`INSERT OR REPLACE INTO meta (key, value) VALUES ('jwt_secret', ?)`, secret)
	}
	jwtSecret = []byte(secret)

	// Ensure at least one admin exists
	var count int
	db.QueryRow("SELECT COUNT(*) FROM users").Scan(&count)
	if count == 0 {
		log.Println("No users found. Create one with: janus-server add-user --name=<name> --password=<pass>")
	}
}

// ── User CLI ──────────────────────────────────────────

func cmdAddUser(args []string) {
	name := flagVal(args, "name", "")
	pass := flagVal(args, "password", "")
	role := flagVal(args, "role", "viewer")

	if name == "" || pass == "" {
		fmt.Println("Usage: add-user --name=<name> --password=<pass> [--role=admin|viewer]")
		os.Exit(1)
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(pass), bcrypt.DefaultCost)
	if err != nil {
		log.Fatalf("Hash failed: %v", err)
	}

	_, err = db.Exec(`INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)`, name, string(hash), role)
	if err != nil {
		log.Fatalf("Create user failed: %v", err)
	}
	fmt.Printf("User created: %s (role: %s)\n", name, role)
}

func cmdListUsers() {
	rows, _ := db.Query("SELECT username, role, created_at FROM users ORDER BY username")
	if rows == nil {
		return
	}
	defer rows.Close()
	fmt.Println("Users:")
	for rows.Next() {
		var name, role string
		var created int64
		rows.Scan(&name, &role, &created)
		t := time.Unix(created, 0).Format("2006-01-02")
		fmt.Printf("  %-20s %-8s %s\n", name, role, t)
	}
}

func cmdDeleteUser(args []string) {
	name := flagVal(args, "name", "")
	if name == "" {
		fmt.Println("Usage: delete-user --name=<name>")
		os.Exit(1)
	}
	db.Exec("DELETE FROM users WHERE username=?", name)
	db.Exec("DELETE FROM watch_progress WHERE user_id=(SELECT id FROM users WHERE username=?)", name)
	fmt.Printf("Deleted user: %s\n", name)
}

// ── HTTP Handlers ─────────────────────────────────────

func handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}

	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", 400)
		return
	}

	var id int
	var hash, role string
	err := db.QueryRow("SELECT id, password_hash, role FROM users WHERE username=?", req.Username).Scan(&id, &hash, &role)
	if err != nil {
		http.Error(w, `{"error":"invalid credentials"}`, 401)
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(req.Password)); err != nil {
		http.Error(w, `{"error":"invalid credentials"}`, 401)
		return
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"user_id":  id,
		"username": req.Username,
		"role":     role,
		"exp":      time.Now().Add(30 * 24 * time.Hour).Unix(),
	})
	tokenStr, err := token.SignedString(jwtSecret)
	if err != nil {
		http.Error(w, "token error", 500)
		return
	}

	writeJSON(w, map[string]any{
		"token":    tokenStr,
		"username": req.Username,
		"role":     role,
	})
}

// ── Middleware ─────────────────────────────────────────

type userContext struct {
	UserID   int
	Username string
	Role     string
}

func authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Public endpoints — no auth required
		path := r.URL.Path
		if path == "/api/login" || path == "/api/health" || path == "/api/version" ||
			strings.HasPrefix(path, "/api/covers/") ||
			strings.HasPrefix(path, "/install") {
			next.ServeHTTP(w, r)
			return
		}

		// Check if any users exist — if none, skip auth (first-run mode)
		var userCount int
		db.QueryRow("SELECT COUNT(*) FROM users").Scan(&userCount)
		if userCount == 0 {
			next.ServeHTTP(w, r)
			return
		}

		auth := r.Header.Get("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			if qToken := r.URL.Query().Get("token"); qToken != "" {
				auth = "Bearer " + qToken
			} else {
				http.Error(w, `{"error":"unauthorized"}`, 401)
				return
			}
		}

		tokenStr := strings.TrimPrefix(auth, "Bearer ")
		token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method")
			}
			return jwtSecret, nil
		})

		if err != nil || !token.Valid {
			http.Error(w, `{"error":"invalid token"}`, 401)
			return
		}

		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			http.Error(w, `{"error":"invalid token"}`, 401)
			return
		}

		// Store user info in header for downstream handlers
		userID := int(claims["user_id"].(float64))
		r.Header.Set("X-User-ID", fmt.Sprintf("%d", userID))
		r.Header.Set("X-Username", claims["username"].(string))
		r.Header.Set("X-Role", claims["role"].(string))

		next.ServeHTTP(w, r)
	})
}
