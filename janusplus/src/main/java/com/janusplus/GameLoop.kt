package com.janusplus

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Single-threaded game loop state. All reads happen in onDrawFrame.
 * Touch events are queued from the main thread, consumed each frame.
 */
object GameLoop {

    data class TouchEvent(val action: Int, val x: Float, val y: Float, val time: Long)

    // Input queue — main thread writes, GL thread reads
    val touchQueue = ConcurrentLinkedQueue<TouchEvent>()

    // Player state — main thread writes atomically, GL thread reads
    @Volatile var playerPositionMs = 0L
    @Volatile var playerDurationMs = 0L
    @Volatile var playerIsPlaying = false

    // Commands from GL thread → main thread executes
    @Volatile var cmdSeek: Long? = null
    @Volatile var cmdPause = false
    @Volatile var cmdPlay = false

    // Seekbar drag state — GL thread owns this
    var isDragging = false
    var dragPositionMs = 0L
}
