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

    /** Sleep: a crescent moon with a small star. */
    val sleep = icon("sleep") {
        moveTo(19f, 14.5f); arcTo(8f, 8f, 0f, true, true, 9.5f, 5f)
        arcTo(6.5f, 6.5f, 0f, false, false, 19f, 14.5f)
        moveTo(17f, 3.5f); lineTo(17f, 6.5f); moveTo(15.5f, 5f); lineTo(18.5f, 5f)
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

    // ---- Watch hub ----
    val bell = icon("bell") {
        moveTo(6f, 16f); lineTo(6f, 11f); arcTo(6f, 6f, 0f, false, true, 18f, 11f); lineTo(18f, 16f); lineTo(20f, 18f); lineTo(4f, 18f); close()
        moveTo(10f, 21f); lineTo(14f, 21f)
    }
    val gear = icon("gear") {
        moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, true, 11.99f, 9f)
        moveTo(12f, 2.5f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 21.5f); moveTo(2.5f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(18.7f, 18.7f); moveTo(5.3f, 18.7f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(18.7f, 5.3f)
    }
    val grid = icon("grid") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val vibrate = icon("vibrate") {
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 20f); lineTo(8f, 20f); close()
        moveTo(4f, 8f); lineTo(4f, 16f); moveTo(20f, 8f); lineTo(20f, 16f)
    }
    val alarm = icon("alarm") {
        moveTo(12f, 6f); arcTo(7f, 7f, 0f, true, true, 11.99f, 6f)
        moveTo(12f, 9f); lineTo(12f, 13f); lineTo(14.5f, 14.5f)
        moveTo(4f, 6f); lineTo(7f, 3.5f); moveTo(20f, 6f); lineTo(17f, 3.5f)
    }
    val cloud = icon("cloud") {
        moveTo(7f, 18f); arcTo(4f, 4f, 0f, false, true, 7.5f, 10f); arcTo(5f, 5f, 0f, false, true, 17f, 10.5f); arcTo(3.8f, 3.8f, 0f, false, true, 17f, 18f); close()
    }
    val calendar = icon("calendar") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(4f, 10f); lineTo(20f, 10f); moveTo(8f, 3.5f); lineTo(8f, 7.5f); moveTo(16f, 3.5f); lineTo(16f, 7.5f)
    }
    val music = icon("music") {
        moveTo(9f, 17.5f); arcTo(2.5f, 2.5f, 0f, true, true, 8.99f, 17.5f)
        moveTo(11.5f, 17.5f); lineTo(11.5f, 4.5f); lineTo(19f, 3f); lineTo(19f, 15f)
        moveTo(19f, 15f); arcTo(2.5f, 2.5f, 0f, true, true, 18.99f, 15f)
    }
    val mic = icon("mic") {
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f); arcTo(3f, 3f, 0f, false, true, 9f, 11f); close()
        moveTo(5.5f, 11f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f); moveTo(12f, 17.5f); lineTo(12f, 21f)
    }
    val share = icon("share") {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }
    val data = icon("data") {
        moveTo(5f, 6f); arcTo(7f, 2.5f, 0f, false, true, 19f, 6f); arcTo(7f, 2.5f, 0f, false, true, 5f, 6f)
        moveTo(5f, 6f); lineTo(5f, 18f); arcTo(7f, 2.5f, 0f, false, false, 19f, 18f); lineTo(19f, 6f)
        moveTo(5f, 12f); arcTo(7f, 2.5f, 0f, false, false, 19f, 12f)
    }
    val person = icon("person") {
        moveTo(12f, 4f); arcTo(3.5f, 3.5f, 0f, true, true, 11.99f, 4f)
        moveTo(5f, 20f); arcTo(7f, 7f, 0f, false, true, 19f, 20f)
    }
    val palette = icon("palette") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, false, false, 12f, 21f); arcTo(2f, 2f, 0f, false, false, 13.5f, 17.5f); arcTo(2f, 2f, 0f, false, true, 15f, 14f)
        lineTo(17f, 14f); arcTo(4f, 4f, 0f, false, false, 21f, 10f); arcTo(9f, 7.5f, 0f, false, false, 12f, 3f)
        moveTo(8f, 10f); lineTo(8.01f, 10f); moveTo(12f, 7f); lineTo(12.01f, 7f); moveTo(16f, 9f); lineTo(16.01f, 9f)
    }
    val battery = icon("battery") {
        moveTo(3f, 8f); lineTo(18f, 8f); lineTo(18f, 16f); lineTo(3f, 16f); close()
        moveTo(21f, 11f); lineTo(21f, 13f); moveTo(6f, 11f); lineTo(6f, 13f); moveTo(9f, 11f); lineTo(9f, 13f)
    }
    val list = icon("list") {
        moveTo(8f, 6f); lineTo(20f, 6f); moveTo(8f, 12f); lineTo(20f, 12f); moveTo(8f, 18f); lineTo(20f, 18f)
        moveTo(4f, 6f); lineTo(4.01f, 6f); moveTo(4f, 12f); lineTo(4.01f, 12f); moveTo(4f, 18f); lineTo(4.01f, 18f)
    }
    val wave = icon("wave") {
        moveTo(2f, 12f); lineTo(5f, 12f); lineTo(7f, 6f); lineTo(10f, 18f); lineTo(13f, 4f); lineTo(16f, 20f); lineTo(18f, 12f); lineTo(22f, 12f)
    }
    val send = icon("send") {
        moveTo(3f, 11f); lineTo(21f, 3f); lineTo(13f, 21f); lineTo(11f, 13f); close()
        moveTo(11f, 13f); lineTo(21f, 3f)
    }
    val find = icon("find") {
        moveTo(10.5f, 4f); arcTo(6.5f, 6.5f, 0f, true, true, 10.49f, 4f)
        moveTo(15.5f, 15.5f); lineTo(20f, 20f)
    }
    val clock = icon("clock") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val sparkle = icon("sparkle") {
        moveTo(12f, 3f); lineTo(13.8f, 10.2f); lineTo(21f, 12f); lineTo(13.8f, 13.8f); lineTo(12f, 21f); lineTo(10.2f, 13.8f); lineTo(3f, 12f); lineTo(10.2f, 10.2f); close()
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
