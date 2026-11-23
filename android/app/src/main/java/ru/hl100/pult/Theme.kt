package ru.hl100.pult

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors and corner radius of one theme. Also used by the home screen widgets. */
data class Palette(val base: Color, val surface: Color, val ink: Color, val sub: Color, val line: Color, val accent: Color, val accentInk: Color, val radius: Int)

fun palette(theme: String): Palette = when (theme) {
    "quiet" -> Palette(Color(0xFFF6F3EC), Color(0xFFEBE7DF), Color(0xFF272C29), Color(0xFF626B63), Color(0xFFD8DCD3), Color(0xFF425749), Color(0xFFFFFDF6), 22)
    "orbit" -> Palette(Color(0xFF121724), Color(0xFF1C2433), Color(0xFFF1F4FB), Color(0xFFA7B4CC), Color(0xFF354259), Color(0xFFB5CEFF), Color(0xFF172642), 18)
    else -> Palette(Color(0xFF101011), Color(0xFF19191A), Color(0xFFEEEBDF), Color(0xFFA4A29A), Color(0xFF343433), Color(0xFFF04E48), Color(0xFF111111), 3)
}

fun themeFont(theme: String): FontFamily = if (theme == "signal") FontFamily.Monospace else FontFamily.SansSerif

@Composable
fun PultTheme(theme: String, content: @Composable () -> Unit) {
    val p = palette(theme)
    val colors = if (theme == "quiet") {
        lightColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.base, onBackground = p.ink, surface = p.surface, onSurface = p.ink, outline = p.line)
    } else {
        darkColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.base, onBackground = p.ink, surface = p.surface, onSurface = p.ink, outline = p.line)
    }
    val family = themeFont(theme)
    val typography = Typography(
        bodyLarge = LocalTextStyle.current.copy(fontFamily = family, fontSize = 15.sp),
        bodyMedium = LocalTextStyle.current.copy(fontFamily = family, fontSize = 13.sp),
        titleLarge = LocalTextStyle.current.copy(fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Full-width accent button. */
@Composable
fun PrimaryButton(label: String, p: Palette, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(p.radius.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk),
    ) { Text(label, fontSize = 14.sp) }
}

/** Small caps heading above a section, e.g. "ПИТАНИЕ / 01". */
@Composable
fun SectionLabel(text: String, p: Palette) {
    Text(text, fontSize = 10.sp, color = p.sub, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
}

/** Line icons drawn in code: power, lock, monitor, restart, volume, timer; anything else is the settings icon. */
@Composable
fun Symbol(kind: String, color: Color, size: Int = 24) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * .075f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke, StrokeCap.Round)
        when (kind) {
            "power" -> {
                drawArc(color, -50f, 280f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(.5f, .06f, .5f, .48f)
            }
            "lock" -> {
                drawRoundRect(color, Offset(w * .2f, h * .44f), Size(w * .6f, h * .46f), CornerRadius(w * .08f), style = Stroke(stroke))
                drawArc(color, 180f, 180f, false, Offset(w * .31f, h * .1f), Size(w * .38f, h * .58f), style = Stroke(stroke))
                line(.5f, .63f, .5f, .73f)
            }
            "monitor" -> {
                drawRoundRect(color, Offset(w * .08f, h * .12f), Size(w * .84f, h * .6f), CornerRadius(w * .04f), style = Stroke(stroke))
                line(.5f, .73f, .5f, .92f)
                line(.3f, .93f, .7f, .93f)
            }
            "restart" -> {
                drawArc(color, -90f, 290f, false, Offset(w * .17f, h * .17f), Size(w * .66f, h * .66f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(.5f, .04f, .5f, .29f)
                line(.5f, .29f, .71f, .16f)
            }
            "volume" -> {
                line(.1f, .39f, .3f, .39f); line(.1f, .39f, .1f, .64f); line(.1f, .64f, .3f, .64f)
                line(.3f, .39f, .5f, .22f); line(.5f, .22f, .5f, .8f); line(.5f, .8f, .3f, .64f)
                drawArc(color, -55f, 110f, false, Offset(w * .44f, h * .2f), Size(w * .5f, h * .61f), style = Stroke(stroke))
            }
            "timer" -> {
                drawCircle(color, w * .33f, Offset(w * .5f, h * .57f), style = Stroke(stroke))
                line(.4f, .08f, .6f, .08f)
                line(.5f, .3f, .5f, .58f)
                line(.5f, .58f, .65f, .66f)
            }
            else -> {
                line(.13f, .25f, .87f, .25f); line(.13f, .5f, .87f, .5f); line(.13f, .75f, .87f, .75f)
                drawCircle(color, w * .075f, Offset(w * .35f, h * .25f))
                drawCircle(color, w * .075f, Offset(w * .65f, h * .5f))
                drawCircle(color, w * .075f, Offset(w * .4f, h * .75f))
            }
        }
    }
}
