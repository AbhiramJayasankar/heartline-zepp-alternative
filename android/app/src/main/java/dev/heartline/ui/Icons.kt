package dev.heartline.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small hand-drawn line icon set (24 x 24, 1.8 stroke) instead of the large material-icons dependency. */
object Icons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    /** Today: a sunrise over the horizon. */
    val today = icon("today") {
        moveTo(3f, 18f); lineTo(21f, 18f)
        moveTo(7f, 18f); arcTo(5f, 5f, 0f, false, true, 17f, 18f)
        moveTo(12f, 6f); lineTo(12f, 8f)
        moveTo(5.6f, 9.6f); lineTo(7f, 11f)
        moveTo(18.4f, 9.6f); lineTo(17f, 11f)
    }

    /** Heart: one ECG beat. */
    val heart = icon("heart") {
        moveTo(2f, 13f); lineTo(7f, 13f); lineTo(9f, 9f); lineTo(12f, 18f); lineTo(14.5f, 5f); lineTo(16.5f, 13f); lineTo(22f, 13f)
    }

    val activity = icon("activity") {
        moveTo(5f, 20f); lineTo(5f, 13f)
        moveTo(10f, 20f); lineTo(10f, 7f)
        moveTo(15f, 20f); lineTo(15f, 11f)
        moveTo(20f, 20f); lineTo(20f, 4f)
    }

    val trends = icon("trends") {
        moveTo(3f, 19f); lineTo(21f, 19f)
        moveTo(4f, 15f); lineTo(9f, 10f); lineTo(13f, 13f); lineTo(20f, 6f)
        moveTo(16f, 6f); lineTo(20f, 6f); lineTo(20f, 10f)
    }

    /** Watch: the round GTR face with its strap. */
    val watch = icon("watch") {
        moveTo(12f, 6f); arcTo(6f, 6f, 0f, true, true, 11.99f, 6f)
        moveTo(9f, 6.8f); lineTo(9.6f, 3f); lineTo(14.4f, 3f); lineTo(15f, 6.8f)
        moveTo(9f, 17.2f); lineTo(9.6f, 21f); lineTo(14.4f, 21f); lineTo(15f, 17.2f)
        moveTo(12f, 9f); lineTo(12f, 12f); lineTo(14f, 13f)
    }

    val back = icon("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val chevron = icon("chevron") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val sync = icon("sync") {
        moveTo(20f, 11f); arcTo(8f, 8f, 0f, false, false, 5.5f, 7f)
        moveTo(4f, 13f); arcTo(8f, 8f, 0f, false, false, 18.5f, 17f)
        moveTo(5f, 3f); lineTo(5.5f, 7f); lineTo(9.5f, 6.5f)
        moveTo(19f, 21f); lineTo(18.5f, 17f); lineTo(14.5f, 17.5f)
    }
}
