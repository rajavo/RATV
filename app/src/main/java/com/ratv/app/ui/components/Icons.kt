package com.ratv.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons drawn from the same paths as the RATV design. */
object RIcons {

    private fun stroke(name: String, vararg paths: String, mirrored: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = mirrored
        )
        paths.forEach { d ->
            builder.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    private fun fill(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { d ->
            builder.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
        }
        return builder.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r},${cy}a${r},${r} 0 1,0 ${2 * r},0a${r},${r} 0 1,0 ${-2 * r},0z"

    private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
        val iw = w - 2 * r
        val ih = h - 2 * r
        return "M${x + r},${y}h${iw}a${r},${r} 0 0,1 ${r},${r}v${ih}a${r},${r} 0 0,1 ${-r},${r}" +
            "h${-iw}a${r},${r} 0 0,1 ${-r},${-r}v${-ih}a${r},${r} 0 0,1 ${r},${-r}z"
    }

    private const val STAR = "M12 2.8l2.83 5.73 6.32.92-4.58 4.46 1.08 6.3L12 17.24l-5.65 2.97 1.08-6.3-4.58-4.46 6.32-.92z"
    private const val HEART = "M12 20.5s-7.5-4.6-9.2-9.1C1.6 8 3.6 4.5 7.1 4.5c2 0 3.6 1.1 4.9 2.8 1.3-1.7 2.9-2.8 4.9-2.8 3.5 0 5.5 3.5 4.3 6.9-1.7 4.5-9.2 9.1-9.2 9.1z"

    val Search: ImageVector by lazy { stroke("search", circle(11f, 11f, 7f), "M20 20l-3.6-3.6") }
    val User: ImageVector by lazy { stroke("user", circle(12f, 8f, 4f), "M4.5 21a7.5 7.5 0 0 1 15 0") }
    val Home: ImageVector by lazy { stroke("home", "M3 10.2L12 3l9 7.2V20a1 1 0 0 1-1 1h-5.5v-6.5h-5V21H4a1 1 0 0 1-1-1z") }
    val Tv: ImageVector by lazy { stroke("tv", rrect(2.5f, 6.5f, 19f, 14f, 2.5f), "M8 2.5l4 4 4-4") }
    val Film: ImageVector by lazy {
        stroke("film", rrect(3f, 3f, 18f, 18f, 2.5f), "M7.5 3v18M16.5 3v18M3 12h18M3 7.5h4.5M3 16.5h4.5M16.5 7.5H21M16.5 16.5H21")
    }
    val Layers: ImageVector by lazy { stroke("layers", "M12 3l9 4.5-9 4.5-9-4.5z", "M3 12l9 4.5 9-4.5", "M3 16.5l9 4.5 9-4.5") }
    val Broadcast: ImageVector by lazy {
        stroke(
            "broadcast", circle(12f, 12f, 2f), "M16.2 7.8a6 6 0 0 1 0 8.4", "M7.8 16.2a6 6 0 0 1 0-8.4",
            "M19 5a10 10 0 0 1 0 14", "M5 19a10 10 0 0 1 0-14"
        )
    }
    val Calendar: ImageVector by lazy { stroke("calendar", rrect(3.5f, 5f, 17f, 15.5f, 2.5f), "M8 3v4M16 3v4M3.5 10h17") }
    // Directional arrows are drawn for left-to-right and mirrored in Arabic (where "<" leads on).
    val ChevronLeft: ImageVector by lazy { stroke("chevronLeft", "M9 18l6-6-6-6", mirrored = true) }
    val ChevronDown: ImageVector by lazy { stroke("chevronDown", "M6 9l6 6 6-6") }
    /** Points right: "back" in a right-to-left layout. */
    val Back: ImageVector by lazy { stroke("back", "M19 12H5", "M11 5l-7 7 7 7", mirrored = true) }
    /** Points left: "forward" in a right-to-left layout. */
    val Forward: ImageVector by lazy { stroke("forward", "M5 12h14", "M13 5l7 7-7 7", mirrored = true) }
    val Play: ImageVector by lazy { fill("play", "M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z") }
    val Pause: ImageVector by lazy { fill("pause", rrect(6f, 4.5f, 4f, 15f, 1.2f), rrect(14f, 4.5f, 4f, 15f, 1.2f)) }
    val Star: ImageVector by lazy { stroke("star", STAR) }
    val StarFilled: ImageVector by lazy { fill("starFilled", STAR) }
    val Heart: ImageVector by lazy { stroke("heart", HEART) }
    val HeartFilled: ImageVector by lazy { fill("heartFilled", HEART) }
    val Plus: ImageVector by lazy { stroke("plus", "M12 5v14M5 12h14") }
    val Check: ImageVector by lazy { stroke("check", "M20 6L9 17l-5-5") }
    val Close: ImageVector by lazy { stroke("close", "M18 6L6 18M6 6l12 12") }
    val Info: ImageVector by lazy { stroke("info", circle(12f, 12f, 9f), "M12 16v-4.5M12 8h.01") }
    val Help: ImageVector by lazy {
        stroke("help", circle(12f, 12f, 9f), "M9.6 9.2a2.5 2.5 0 1 1 3.4 2.3c-.6.3-1 .8-1 1.5v.5", "M12 16.8h.01")
    }
    val Eye: ImageVector by lazy { stroke("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", circle(12f, 12f, 3f)) }
    val EyeOff: ImageVector by lazy {
        stroke(
            "eyeOff",
            "M10.6 5.6A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16.4 16.4 0 0 1-2.4 3.2",
            "M6.6 6.6C3.9 8.3 2.5 12 2.5 12S6 18.5 12 18.5c1.8 0 3.3-.5 4.6-1.3",
            "M14.1 14.2a3 3 0 0 1-4.3-4.3",
            "M3 3l18 18"
        )
    }
    val Lock: ImageVector by lazy { stroke("lock", rrect(4.5f, 10.5f, 15f, 10.5f, 2.5f), "M8 10.5V7.5a4 4 0 0 1 8 0v3") }
    val Unlock: ImageVector by lazy { stroke("unlock", rrect(4.5f, 10.5f, 15f, 10.5f, 2.5f), "M8 10.5V7.5a4 4 0 0 1 7.7-1.5") }
    val Sliders: ImageVector by lazy {
        stroke("sliders", "M20 7h-9M6 7H4M20 17h-3M12 17H4", circle(8.5f, 7f, 2.5f), circle(14.5f, 17f, 2.5f))
    }
    // A four-pointed spark and a small one: the picture enhancement.
    val Sparkle: ImageVector by lazy {
        stroke("sparkle", "M10 3.5l1.7 4.8 4.8 1.7-4.8 1.7L10 16.5l-1.7-4.8L3.5 10l4.8-1.7z", "M18 14.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z")
    }
    val Captions: ImageVector by lazy { stroke("captions", rrect(3f, 5f, 18f, 14f, 2.5f), "M7 14.5h3.5M13.5 14.5H17M7 11h2M12 11h5") }
    val Trophy: ImageVector by lazy {
        stroke("trophy", "M8 4h8v5a4 4 0 0 1-8 0z", "M8 6H5.5a2.5 2.5 0 0 0 2.7 3.6", "M16 6h2.5a2.5 2.5 0 0 1-2.7 3.6", "M12 13v4", "M8.5 21h7", "M10 17h4v4h-4z")
    }
    val Mic: ImageVector by lazy { stroke("mic", rrect(9f, 3f, 6f, 11f, 3f), "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21", "M8.5 21h7") }
    val Pip: ImageVector by lazy { stroke("pip", rrect(2.5f, 4.5f, 19f, 15f, 2.5f), rrect(12f, 11.5f, 7f, 5.5f, 1.2f)) }
    val Aspect: ImageVector by lazy {
        stroke("aspect", "M8 3.5H5.5a2 2 0 0 0-2 2V8M20.5 8V5.5a2 2 0 0 0-2-2H16M3.5 16v2.5a2 2 0 0 0 2 2H8M16 20.5h2.5a2 2 0 0 0 2-2V16")
    }
    val ListIcon: ImageVector by lazy { stroke("list", "M8 6h12M8 12h12M8 18h12", "M4 6h.01M4 12h.01M4 18h.01") }
    val Rewind: ImageVector by lazy { stroke("rewind", "M3 12a9 9 0 1 0 2.64-6.36L3 8.3", "M3 3.5v4.8h4.8") }
    val FastForward: ImageVector by lazy { stroke("fastForward", "M21 12a9 9 0 1 1-2.64-6.36L21 8.3", "M21 3.5v4.8h-4.8") }
    val VolumeOff: ImageVector by lazy { stroke("volumeOff", "M11 5L6 9H3v6h3l5 4z", "M22 9l-6 6M16 9l6 6") }
    val Refresh: ImageVector by lazy {
        stroke("refresh", "M20 11a8 8 0 0 0-14.3-4.9L4 8", "M4 4v4h4", "M4 13a8 8 0 0 0 14.3 4.9L20 16", "M20 20v-4h-4")
    }
    val Swap: ImageVector by lazy { stroke("swap", "M7 7h13l-3-3", "M17 17H4l3 3") }
    val PlayCircle: ImageVector by lazy { stroke("playCircle", circle(12f, 12f, 9f), "M10 8.5v7l5.5-3.5z") }
    val FileIcon: ImageVector by lazy {
        stroke("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    }
    val Shield: ImageVector by lazy { stroke("shield", "M12 3l7.5 3v5.5c0 4.6-3.2 8.4-7.5 9.5-4.3-1.1-7.5-4.9-7.5-9.5V6z") }
    val Alert: ImageVector by lazy { stroke("alert", circle(12f, 12f, 9f), "M12 7.5v5.5M12 16.5h.01") }
    val Clock: ImageVector by lazy { stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2") }
    val Logout: ImageVector by lazy { stroke("logout", "M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3", "M14 17l5-5-5-5", "M19 12H9") }
    val SkipNext: ImageVector by lazy { fill("skipNext", "M5 5.6v12.8a1 1 0 0 0 1.55.83L15 13.6V18a1 1 0 0 0 2 0V6a1 1 0 0 0-2 0v4.4L6.55 4.77A1 1 0 0 0 5 5.6z") }
}
