package com.janusplus

class ScrollPhysics {
    var offset = 0f
    var velocity = 0f
    var min = 0f
    var max = 0f
    private var snapTarget = Float.NaN

    fun fling(v: Float) {
        velocity = v
        snapTarget = Float.NaN
    }

    fun snapTo(target: Float) {
        snapTarget = target.coerceIn(min, max)
        velocity = 0f
    }

    fun update(dt: Float): Boolean {
        if (!snapTarget.isNaN()) {
            val diff = snapTarget - offset
            if (kotlin.math.abs(diff) < 1f) {
                offset = snapTarget
                snapTarget = Float.NaN
                return false
            }
            offset += diff * (10f * dt).coerceAtMost(1f)
            return true
        }
        if (kotlin.math.abs(velocity) < 1f) {
            velocity = 0f
            return false
        }
        offset += velocity * dt
        velocity *= 0.95f
        if (offset < min) { offset = min; velocity = 0f }
        if (offset > max) { offset = max; velocity = 0f }
        return true
    }
}
