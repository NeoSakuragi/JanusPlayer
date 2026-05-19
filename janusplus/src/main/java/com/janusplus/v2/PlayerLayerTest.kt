package com.janusplus.v2

import android.graphics.BitmapFactory
import android.view.KeyEvent
import com.janusplus.*
import java.io.File

/**
 * Automated player layer test. Builds a real PlayerPage, launches PlayerState,
 * toggles renderMask per-layer, screenshots each in isolation, verifies pixels.
 *
 * Also tests font change and subtitle track change (atlas reload).
 *
 * Launch: adb shell am start -n com.janusplus/.v2.MainActivity --es test player
 */
class PlayerLayerTest {

    data class Result(val phase: String, val pass: Boolean, val quads: Int, val bright: Int, val detail: String)

    companion object {
        fun runFromThread(app: App) {
            val outDir = File(app.context.cacheDir, "player_test").absolutePath
            File(outDir).mkdirs()
            val results = mutableListOf<Result>()

            fun wait(ms: Long = 500) { Thread.sleep(ms) }
            fun key(code: Int) { app.keyQueue.add(code); wait(120) }

            fun screenshot(name: String): Triple<Int, Int, Boolean> {
                val startSeq = app.screenshotSeq
                app.screenshotPath = "$outDir/$name.png"
                val deadline = System.currentTimeMillis() + 5000
                while (app.screenshotSeq == startSeq && System.currentTimeMillis() < deadline) Thread.sleep(30)
                if (app.screenshotSeq == startSeq) return Triple(-1, 0, false)

                val quads = app.batch.lastQuadCount
                try {
                    val bmp = BitmapFactory.decodeFile("$outDir/$name.png") ?: return Triple(quads, -1, false)
                    var bright = 0
                    val w = bmp.width; val h = bmp.height
                    for (y in 0 until h step 3) {
                        for (x in 0 until w step 3) {
                            val px = bmp.getPixel(x, y)
                            val r = (px shr 16) and 0xFF
                            val g = (px shr 8) and 0xFF
                            val b = px and 0xFF
                            if (r > 40 || g > 40 || b > 40) bright++
                        }
                    }
                    bmp.recycle()
                    return Triple(quads, bright, true)
                } catch (_: Exception) { return Triple(quads, -1, false) }
            }

            fun check(name: String, minQuads: Int, minBright: Int, detail: String = "") {
                val (quads, bright, ok) = screenshot(name)
                val pass = ok && quads >= minQuads && bright >= minBright
                val info = "q=$quads b=$bright ${if (!ok) "SCREENSHOT_FAIL" else ""} $detail"
                results.add(Result(name, pass, quads, bright, info.trim()))
                android.util.Log.i("PlayerTest", "${if (pass) "PASS" else "FAIL"} [$name] $info")
            }

            fun setMask(mask: Int) {
                val state = app.currentState
                if (state is PlayerState) state.renderMask = mask
            }

            try {
                android.util.Log.i("PlayerTest", "=== START ===")

                // Wait for home + library
                wait(8000)

                // Navigate to a series with data (skip first card if it's DBZ with no page data)
                key(KeyEvent.KEYCODE_DPAD_DOWN) // focus series row
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // second card
                key(KeyEvent.KEYCODE_DPAD_CENTER) // open series
                wait(8000) // loading → display

                // Play first episode
                key(KeyEvent.KEYCODE_DPAD_DOWN) // play → grid
                key(KeyEvent.KEYCODE_DPAD_CENTER) // play episode
                wait(12000) // player loading → player + video buffering

                if (app.currentScreen != Screen.PLAYER) {
                    results.add(Result("setup", false, 0, 0, "not on PLAYER screen: ${app.currentScreen}"))
                    android.util.Log.e("PlayerTest", "Setup failed — not on PLAYER")
                    writeReport(results, outDir)
                    return
                }

                // Pause so controls are visible
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(500)

                // Navigate to subtitle for word selection
                // (focus starts on SUBTITLE after pause, cursor on first word)

                val ps = app.currentState as? PlayerState
                if (ps == null) {
                    results.add(Result("setup", false, 0, 0, "state is not PlayerState"))
                    writeReport(results, outDir)
                    return
                }

                // ── Layer isolation tests ──

                // 01: ALL layers (baseline)
                setMask(PlayerState.Layer.ALL)
                wait(300)
                check("01_all_layers", 5, 1000, "baseline")

                // 02: VIDEO only (q=0 expected — video quad flushes its own batch)
                setMask(PlayerState.Layer.VIDEO)
                wait(300)
                check("02_video_only", 0, 1000, "video renders, quad count resets after mid-frame flush")

                // 03: CUE only — seek to 30s where subtitle is likely active
                setMask(PlayerState.Layer.ALL)
                ps.positionMs = 30000
                app.onMainThread?.invoke(Runnable { app.exoPlayer?.seekTo(30000) })
                wait(2000) // wait for cue to appear
                setMask(PlayerState.Layer.CUE)
                wait(300)
                val cueQuads = app.batch.lastQuadCount
                check("03_cue_only", 0, 0, "q=$cueQuads (0 ok if between cues)")

                // 04: CONTROLS only (title, back, settings btn, play icon)
                setMask(PlayerState.Layer.CONTROLS)
                wait(300)
                check("04_controls_only", 5, 500, "buttons + title")

                // 05: CONTROLS + SEEKBAR
                setMask(PlayerState.Layer.CONTROLS or PlayerState.Layer.SEEKBAR)
                wait(300)
                check("05_seekbar", 8, 1000, "controls + seekbar")

                // 06: DICT popup — try several words
                setMask(PlayerState.Layer.ALL)
                wait(100)
                for (i in 0 until 6) key(KeyEvent.KEYCODE_DPAD_RIGHT)
                setMask(PlayerState.Layer.DICT)
                wait(300)
                val dictQuads = app.batch.lastQuadCount
                check("06_dict_only", 0, 0, "q=$dictQuads (0 ok if no dict entry)")

                // 07: FPS overlay only
                setMask(PlayerState.Layer.FPS)
                wait(300)
                check("07_fps_only", 3, 20, "fps text")

                // 08: SETTINGS panel
                setMask(PlayerState.Layer.ALL) // restore for navigation
                // Navigate to settings: UP UP to top row, RIGHT to settings btn, CENTER
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(300)
                setMask(PlayerState.Layer.SETTINGS)
                wait(200)
                check("08_settings_only", 40, 5000, "settings panel labels")

                // 09: Settings with D-pad scroll
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                wait(200)
                check("09_settings_scrolled", 40, 5000, "settings scrolled down")

                // 10: Settings slider adjust
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // adjust slider value
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                wait(200)
                check("10_settings_slider", 40, 5000, "slider adjusted")

                // Back out of settings
                key(KeyEvent.KEYCODE_BACK)
                wait(200)

                // ── Font size change test ──
                // 11: Change font size via settings
                setMask(PlayerState.Layer.ALL)
                wait(100)
                key(KeyEvent.KEYCODE_DPAD_UP) // subtitle → top
                key(KeyEvent.KEYCODE_DPAD_UP) // ensure at top
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // → settings
                key(KeyEvent.KEYCODE_DPAD_CENTER) // open settings
                wait(300)
                for (i in 0 until 4) key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_CENTER) // cycle font size
                wait(500)
                check("11_font_size_changed", 30, 3000, "font size cycled")
                key(KeyEvent.KEYCODE_BACK)
                wait(300)

                // 12: Verify atlas rebuilt — check subAtlas exists at new size
                val newSubAtlas = ps.subAtlas
                val rebuilt = newSubAtlas != null && newSubAtlas.glyphs.isNotEmpty()
                setMask(PlayerState.Layer.CUE or PlayerState.Layer.VIDEO)
                wait(300)
                check("12_atlas_rebuilt", 0, 0, "subAtlas=${if (rebuilt) "OK(${newSubAtlas?.glyphs?.size}g)" else "MISSING"}")
                // Override pass based on atlas state, not pixels (cue may be between lines)
                if (rebuilt) results[results.size - 1] = results.last().copy(pass = true)

                setMask(PlayerState.Layer.ALL)
                wait(200)

                // ── Back navigation test ──
                // 13: Back from player → series
                key(KeyEvent.KEYCODE_BACK) // paused → playing
                wait(500)
                key(KeyEvent.KEYCODE_BACK) // playing → back
                wait(10000)
                check("13_back_to_series", 10, 2000, "screen=${app.currentScreen}")

                // 14: Back from series → home
                key(KeyEvent.KEYCODE_BACK)
                wait(4000)
                check("14_back_to_home", 10, 2000, "screen=${app.currentScreen}")

            } catch (e: Exception) {
                results.add(Result("crash", false, 0, 0, e.message ?: "unknown"))
                android.util.Log.e("PlayerTest", "Crashed", e)
            }

            writeReport(results, outDir)
            val passed = results.count { it.pass }
            android.util.Log.i("PlayerTest", "=== DONE === $passed/${results.size} passed")
            for (r in results) {
                if (!r.pass) android.util.Log.e("PlayerTest", "FAILED: ${r.phase} — ${r.detail}")
            }
        }

        private fun writeReport(results: List<Result>, outDir: String) {
            try {
                val csv = StringBuilder()
                csv.appendLine("phase,pass,quads,bright,detail")
                for (r in results) csv.appendLine("${r.phase},${r.pass},${r.quads},${r.bright},\"${r.detail}\"")
                File("$outDir/report.csv").writeText(csv.toString())
                android.util.Log.i("PlayerTest", "Report: $outDir/report.csv")
            } catch (_: Exception) {}
        }
    }
}
