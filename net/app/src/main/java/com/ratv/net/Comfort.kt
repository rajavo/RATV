package com.ratv.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * «راحة العين»: a warm, dimmer screen over every app, for long evenings in front of the TV.
 * The accessibility service draws it (any TV allows that, no extra permission); this keeps the strength.
 */
object Comfort {
    /** -1 until it is read from the settings. */
    val level = MutableStateFlow(-1)

    val NAMES = listOf("طافية", "خفيفة", "متوسطة", "قوية")

    /** Dark amber: whites turn warm and softer, blacks stay black. 0 means nothing to draw. */
    fun color(level: Int): Int {
        val alpha = when (level) {
            1 -> 0x38
            2 -> 0x60
            3 -> 0x88
            else -> return 0
        }
        return (alpha shl 24) or 0x401C00
    }

    fun load(context: Context) {
        if (level.value < 0) level.value = Prefs(context).comfort
    }

    fun set(context: Context, value: Int) {
        Prefs(context).comfort = value
        level.value = value
    }
}
