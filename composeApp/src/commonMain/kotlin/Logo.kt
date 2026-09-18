package revision.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * The app logo: a stopwatch with a tick inside, white on deep violet. Same geometry as the Android
 * launcher icon (androidApp/src/main/res/drawable/ic_launcher_foreground.xml), on a 108-unit grid.
 */
class LogoPainter : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val s = size.minDimension / 108f
        val white = Color.White

        drawRoundRect(Color(0xFF5E35B1), size = size, cornerRadius = CornerRadius(24f * s))
        drawCircle(white, radius = 21f * s, center = Offset(54f * s, 58f * s), style = Stroke(5f * s))
        drawRoundRect(white, topLeft = Offset(49f * s, 26f * s), size = Size(10f * s, 5f * s), cornerRadius = CornerRadius(2f * s))
        drawLine(white, Offset(54f * s, 31f * s), Offset(54f * s, 37f * s), strokeWidth = 4f * s)
        drawLine(white, Offset(69f * s, 40f * s), Offset(73f * s, 36f * s), strokeWidth = 4f * s, cap = StrokeCap.Round)

        val tick = Path().apply {
            moveTo(44f * s, 58f * s)
            lineTo(51f * s, 65f * s)
            lineTo(65f * s, 51f * s)
        }
        drawPath(tick, white, style = Stroke(5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
