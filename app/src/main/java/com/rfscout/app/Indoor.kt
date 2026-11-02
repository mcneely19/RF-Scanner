package com.rfscout.app

import android.location.Location
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Indoor position from your own steps (dead reckoning), for places GPS cannot reach.
 *
 * Each detected step moves the position one step length along the current heading. The heading comes
 * from the gyroscope-based orientation sensor, not the magnetic compass, so steel and wiring do not
 * bend it; it only drifts slowly. Positions are metres from where you pressed "reset start point",
 * in an arbitrary compass frame (0 degrees is wherever the sensor started), and are fed through the
 * same fit as GPS positions by pretending they are tiny offsets near latitude 0, longitude 0.
 * They are NOT real map positions.
 */
class IndoorTracker {
    var stepLenM = 0.7
    var x = 0.0          // metres "east" of the start in the sensor frame
    var y = 0.0          // metres "north" of the start
    var steps = 0

    fun reset() {
        x = 0.0
        y = 0.0
        steps = 0
    }

    fun onStep(headingDeg: Double) {
        val h = Math.toRadians(headingDeg)
        x += stepLenM * sin(h)
        y += stepLenM * cos(h)
        steps++
    }

    fun distFromStart(): Double = hypot(x, y)

    /** The position as a Location the rest of the app can use. Accuracy is a fixed, honest-ish 5 m. */
    fun location(): Location = Location("indoor").apply {
        latitude = y / 110_574.0
        longitude = x / 111_319.5
        accuracy = 5f
        time = System.currentTimeMillis()
    }
}
