package com.videoplayer

import android.app.Activity
import android.content.Intent

object AppNavigator {

    enum class Screen {
        MAIN, SETTINGS, ITEM_DETAIL, VIDEO_PLAYER
    }

    enum class Action {
        OPEN_SETTINGS, CLOSE_SETTINGS,
        OPEN_ITEM, CLOSE_ITEM,
        PLAY_VIDEO, CLOSE_VIDEO,
        BACK
    }

    private val transitions = mapOf(
        Screen.MAIN to mapOf(
            Action.OPEN_SETTINGS to Screen.SETTINGS,
            Action.OPEN_ITEM to Screen.ITEM_DETAIL,
            Action.PLAY_VIDEO to Screen.VIDEO_PLAYER,
            Action.BACK to null,
        ),
        Screen.SETTINGS to mapOf(
            Action.CLOSE_SETTINGS to Screen.MAIN,
            Action.BACK to Screen.MAIN,
        ),
        Screen.ITEM_DETAIL to mapOf(
            Action.CLOSE_ITEM to Screen.MAIN,
            Action.PLAY_VIDEO to Screen.VIDEO_PLAYER,
            Action.BACK to Screen.MAIN,
        ),
        Screen.VIDEO_PLAYER to mapOf(
            Action.CLOSE_VIDEO to Screen.ITEM_DETAIL,
            Action.BACK to Screen.ITEM_DETAIL,
        ),
    )

    var currentScreen = Screen.MAIN
        private set

    private var onExitCallbacks = mutableMapOf<Screen, () -> Unit>()

    fun registerOnExit(screen: Screen, callback: () -> Unit) {
        onExitCallbacks[screen] = callback
    }

    fun canNavigate(action: Action): Boolean {
        return transitions[currentScreen]?.containsKey(action) == true
    }

    fun navigate(activity: Activity, action: Action, intentExtras: ((Intent) -> Unit)? = null): Boolean {
        val allowed = transitions[currentScreen] ?: return false
        if (!allowed.containsKey(action)) return false
        val target = allowed[action]

        onExitCallbacks[currentScreen]?.invoke()

        if (target == null) {
            activity.finish()
            return true
        }

        currentScreen = target

        when (target) {
            Screen.VIDEO_PLAYER -> {
                val intent = Intent(activity, ExoPlayerActivity::class.java)
                intentExtras?.invoke(intent)
                activity.startActivity(intent)
            }
            Screen.SETTINGS -> {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            else -> {}
        }

        return true
    }

    fun onActivityResumed(screen: Screen) {
        currentScreen = screen
    }
}
