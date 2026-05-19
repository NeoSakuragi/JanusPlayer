package com.janusplus.v2

import android.view.KeyEvent

/**
 * Centralized input mapping. Raw Android keycodes → app actions.
 * All remote/keyboard/gamepad quirks handled here, nowhere else.
 */
enum class Action {
    UP, DOWN, LEFT, RIGHT, SELECT, BACK,
    PLAY_PAUSE, MENU, REWIND, FORWARD,
}

object Input {
    fun map(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Action.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Action.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Action.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Action.RIGHT

        // Select: D-pad center, Enter, Numpad Enter (Fire TV remote = 160)
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> Action.SELECT

        KeyEvent.KEYCODE_BACK -> Action.BACK

        // Media keys (Fire TV remote, Bluetooth keyboards)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PLAY_PAUSE

        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_GUIDE -> Action.MENU

        KeyEvent.KEYCODE_MEDIA_REWIND -> Action.REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FORWARD

        else -> null
    }
}
