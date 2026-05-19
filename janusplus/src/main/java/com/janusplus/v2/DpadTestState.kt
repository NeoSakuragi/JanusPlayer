package com.janusplus.v2

import android.graphics.BitmapFactory
import android.view.KeyEvent
import java.io.File

/**
 * Automated D-pad navigation test with visual verification.
 * Launch: adb shell am start -n com.janusplus/.v2.MainActivity --es test dpad
 *
 * Each phase: inject keys → screenshot → count non-background pixels → verify screen + content.
 */
class DpadTestState {

    data class TestResult(
        val phase: String, val pass: Boolean, val screen: String,
        val quads: Int, val brightPixels: Int, val detail: String,
    )

    companion object {
        fun runFromThread(app: App) {
            val outDir = File(app.context.cacheDir, "dpad_test").absolutePath
            File(outDir).mkdirs()
            val results = mutableListOf<TestResult>()

            fun key(code: Int) { app.keyQueue.add(code); Thread.sleep(120) }
            fun wait(ms: Long = 500) { Thread.sleep(ms) }

            fun screenshot(name: String): Pair<Int, Int> {
                val startSeq = app.screenshotSeq
                app.screenshotPath = "$outDir/$name.png"
                val deadline = System.currentTimeMillis() + 5000
                while (app.screenshotSeq == startSeq && System.currentTimeMillis() < deadline) Thread.sleep(30)
                if (app.screenshotSeq == startSeq) return -1 to 0

                // Count bright pixels (text/covers are bright, dark bg is not)
                val quads = app.batch.lastQuadCount
                try {
                    val bmp = BitmapFactory.decodeFile("$outDir/$name.png") ?: return quads to -1
                    var bright = 0
                    val w = bmp.width; val h = bmp.height
                    for (y in 0 until h step 2) {
                        for (x in 0 until w step 2) {
                            val px = bmp.getPixel(x, y)
                            val r = (px shr 16) and 0xFF
                            val g = (px shr 8) and 0xFF
                            val b = px and 0xFF
                            if (r > 40 || g > 40 || b > 40) bright++
                        }
                    }
                    bmp.recycle()
                    return quads to bright
                } catch (_: Exception) { return quads to -1 }
            }

            fun check(name: String, expectScreen: Screen, minQuads: Int, minBright: Int) {
                val (quads, bright) = screenshot(name)
                val actual = app.currentScreen
                val screenOk = actual == expectScreen
                val quadsOk = quads >= minQuads
                val brightOk = bright >= minBright
                val pass = screenOk && quadsOk && brightOk
                val detail = "screen=${if (screenOk) "OK" else "WANT=$expectScreen GOT=$actual"} " +
                    "quads=${quads}${if (quadsOk) "" else " WANT>=$minQuads"} " +
                    "bright=${bright}${if (brightOk) "" else " WANT>=$minBright"}"
                results.add(TestResult(name, pass, actual.name, quads, bright, detail))
                val tag = if (pass) "PASS" else "FAIL"
                android.util.Log.i("DpadTest", "$tag [$name] $detail")
            }

            try {
                android.util.Log.i("DpadTest", "=== START ===")

                // 01: Home initial — should have labels + covers
                wait(8000)
                check("01_home_initial", Screen.HOME, 40, 5000)

                // 02: D-pad navigate series row
                key(KeyEvent.KEYCODE_DPAD_DOWN) // row 0→1
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                wait(300)
                check("02_home_navigate", Screen.HOME, 40, 5000)

                // 03: Open series
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(8000)
                check("03_series_loaded", Screen.SERIES, 100, 10000)

                // 04: Grid navigation
                key(KeyEvent.KEYCODE_DPAD_DOWN) // play → grid
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_DOWN) // next row
                wait(300)
                check("04_series_grid", Screen.SERIES, 100, 10000)

                // 05: Play episode
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(10000)
                check("05_player_playing", Screen.PLAYER, 10, 5000)

                // 06: Pause
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(500)
                check("06_player_paused", Screen.PLAYER, 30, 5000)

                // 07: Paused nav — full cycle
                key(KeyEvent.KEYCODE_DPAD_UP)    // sub→top
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // back→settings
                key(KeyEvent.KEYCODE_DPAD_DOWN)  // top→sub
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // word right
                key(KeyEvent.KEYCODE_DPAD_DOWN)  // sub→seekbar
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // seek +10s
                wait(300)
                check("07_paused_nav", Screen.PLAYER, 30, 5000)

                // 08: Open settings panel
                key(KeyEvent.KEYCODE_DPAD_UP)    // seek→sub
                key(KeyEvent.KEYCODE_DPAD_UP)    // sub→top
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // →settings
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(300)
                check("08_settings_panel", Screen.PLAYER, 40, 8000)

                // 09: Settings nav + slider
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_RIGHT) // slider adjust
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                key(KeyEvent.KEYCODE_BACK) // close
                wait(300)
                check("09_settings_close", Screen.PLAYER, 30, 5000)

                // 10: Back from player → series
                key(KeyEvent.KEYCODE_BACK) // paused→playing
                wait(300)
                key(KeyEvent.KEYCODE_BACK) // playing→back
                wait(8000)
                check("10_back_series", Screen.SERIES, 100, 10000)

                // 11: Back to home
                key(KeyEvent.KEYCODE_BACK)
                wait(3000)
                check("11_back_home", Screen.HOME, 40, 5000)

                // 12: Open settings screen
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                wait(2000)
                check("12_settings_screen", Screen.SETTINGS, 20, 2000)

                // 13: Settings navigation
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_UP)
                wait(300)
                check("13_settings_nav", Screen.SETTINGS, 20, 2000)

                // 14: Back to home
                key(KeyEvent.KEYCODE_BACK)
                wait(2000)
                check("14_final_home", Screen.HOME, 40, 5000)

            } catch (e: Exception) {
                results.add(TestResult("crash", false, "N/A", 0, 0, e.message ?: "unknown"))
                android.util.Log.e("DpadTest", "Test crashed", e)
            }

            val passed = results.count { it.pass }
            android.util.Log.i("DpadTest", "=== DONE === $passed/${results.size} passed")
            for (r in results) {
                if (!r.pass) android.util.Log.e("DpadTest", "FAILED: ${r.phase} — ${r.detail}")
            }
            try {
                val csv = StringBuilder()
                csv.appendLine("phase,pass,screen,quads,bright_pixels,detail")
                for (r in results) csv.appendLine("${r.phase},${r.pass},${r.screen},${r.quads},${r.brightPixels},\"${r.detail}\"")
                File("$outDir/report.csv").writeText(csv.toString())
                android.util.Log.i("DpadTest", "Report: $outDir/report.csv")
            } catch (_: Exception) {}
        }
    }
}
