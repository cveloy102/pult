package ru.hl100.pult

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors of one theme from the Terminal 2.0 design code. Also used by the home screen widgets. */
data class Palette(
    val base: Color, val surface: Color, val plate: Color, val ink: Color, val sub: Color, val line: Color,
    val accent: Color, val accentInk: Color, val online: Color,
    val switchOff: Color, val switchBorder: Color, val thumbOff: Color, val thumbOn: Color,
    val light: Boolean,
)

private val Dark = Palette(
    base = Color(0xFF101010), surface = Color(0xFF181818), plate = Color(0xFF242220), ink = Color(0xFFE8E4D9), sub = Color(0xFFAAA79F), line = Color(0xFF353535),
    accent = Color(0xFFF0443E), accentInk = Color(0xFF101010), online = Color(0xFF8CDBAD),
    switchOff = Color(0xFF303030), switchBorder = Color(0xFF79766E), thumbOff = Color(0xFFE8E4D9), thumbOn = Color(0xFFE8E4D9),
    light = false,
)
private val Light = Palette(
    base = Color(0xFFF5F2EB), surface = Color(0xFFEEEBE4), plate = Color(0xFFE7E2D8), ink = Color(0xFF191918), sub = Color(0xFF68645D), line = Color(0xFFD5D0C7),
    accent = Color(0xFFCF332D), accentInk = Color(0xFFFFFFFF), online = Color(0xFF296744),
    switchOff = Color(0xFFD5D0C7), switchBorder = Color(0xFF999389), thumbOff = Color(0xFF68645D), thumbOn = Color(0xFFFFFFFF),
    light = true,
)

fun palette(theme: String): Palette = if (theme == THEME_LIGHT) Light else Dark

/** Corner radius by role: controls, cards, dialogs, switches. */
object Radius {
    val control = 14.dp
    val card = 16.dp
    val dialog = 22.dp
    val switch = 8.dp
    val thumb = 5.dp
}

/** Fonts bundled from the design code: display for titles, mono for the interface, sans for navigation. */
val DisplayFont = FontFamily(Font(R.font.inter_display_black, FontWeight.Black))
val MonoFont = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Normal))
val SansFont = FontFamily(Font(R.font.inter_medium, FontWeight.Normal))

@Composable
fun PultTheme(theme: String, content: @Composable () -> Unit) {
    val p = palette(theme)
    val colors = if (p.light) {
        lightColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.base, onBackground = p.ink, surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.sub, outline = p.line, outlineVariant = p.line, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.switchOff)
    } else {
        darkColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.base, onBackground = p.ink, surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.sub, outline = p.line, outlineVariant = p.line, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.switchOff)
    }
    val base = LocalTextStyle.current.copy(fontFamily = MonoFont)
    val typography = Typography(
        bodyLarge = base.copy(fontSize = 14.sp),
        bodyMedium = base.copy(fontSize = 13.sp),
        bodySmall = base.copy(fontSize = 12.sp),
        labelLarge = base.copy(fontSize = 14.sp),
        titleLarge = base.copy(fontSize = 17.sp),
        headlineSmall = base.copy(fontSize = 17.sp),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(Radius.control), small = RoundedCornerShape(Radius.control), medium = RoundedCornerShape(Radius.control),
        large = RoundedCornerShape(Radius.card), extraLarge = RoundedCornerShape(Radius.dialog),
    )
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

/** Card surface: card color with a hairline border. */
fun Modifier.card(p: Palette, radius: Dp = Radius.card) = this.background(p.surface, RoundedCornerShape(radius)).border(1.dp, p.line, RoundedCornerShape(radius))

/** Screen title in the display face, ending with the accent cursor. */
@Composable
fun ScreenTitle(text: String, p: Palette, size: Int = 28, cursor: Boolean = true) {
    Text(
        buildAnnotatedString { append(text); if (cursor) withStyle(SpanStyle(color = p.accent)) { append("_") } },
        fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = (-0.035 * size).sp, color = p.ink,
    )
}

/** Section heading inside a screen, e.g. "// Питание". */
@Composable
fun SectionLabel(text: String, p: Palette) {
    Text(text, fontSize = 13.sp, color = p.ink, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
}

/** One accent button per screen. */
@Composable
fun PrimaryButton(label: String, p: Palette, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(Radius.control),
        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk, disabledContainerColor = p.accent.copy(alpha = .45f), disabledContentColor = p.accentInk),
    ) { Text(label, fontSize = 14.sp) }
}

/** Card-colored button with a hairline border. */
@Composable
fun SecondaryButton(label: String, p: Palette, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, selected: Boolean = false, height: Dp = 50.dp, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = height).alpha(if (enabled) 1f else .45f)
            .background(p.surface, RoundedCornerShape(Radius.control))
            .border(1.dp, if (selected) p.accent else p.line, RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = if (height < 50.dp) 12.sp else 13.sp, color = if (selected || enabled) p.ink else p.sub) }
}

/** Quiet text action, e.g. "Забыть компьютер ×". */
@Composable
fun TextAction(label: String, p: Palette, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).alpha(if (enabled) 1f else .45f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, fontSize = 12.sp, color = p.sub) }
}

/** Selectable row with a square check, used for single choice lists. */
@Composable
fun OptionRow(label: String, selected: Boolean, p: Palette, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (selected) p.surface else p.base, RoundedCornerShape(Radius.control))
            .border(1.dp, if (selected) p.accent else p.line, RoundedCornerShape(Radius.control))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).border(1.dp, if (selected) p.accent else p.sub, RoundedCornerShape(Radius.thumb)), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(p.accent, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = p.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Switch as a rounded square, per the design code. */
@Composable
fun PultSwitch(checked: Boolean, p: Palette) {
    Box(
        Modifier.width(58.dp).height(30.dp)
            .background(if (checked) p.accent else p.switchOff, RoundedCornerShape(Radius.switch))
            .border(1.dp, if (checked) p.accent else p.switchBorder, RoundedCornerShape(Radius.switch)),
    ) {
        Box(Modifier.offset(x = if (checked) 31.dp else 3.dp, y = 3.dp).size(22.dp).background(if (checked) p.thumbOn else p.thumbOff, RoundedCornerShape(Radius.thumb)))
    }
}

/** Full-width label with a switch; the whole row toggles. */
@Composable
fun SwitchRow(label: String, checked: Boolean, p: Palette, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = p.ink, modifier = Modifier.weight(1f))
        PultSwitch(checked, p)
    }
}

/** Label and value on one line inside a card; clickable when [onClick] is set. */
@Composable
fun InfoRow(label: String, value: String, p: Palette, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = p.sub)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 13.sp, color = p.ink, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Text field colors for the theme. */
@Composable
fun fieldColors(p: Palette): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.accent, unfocusedBorderColor = p.line, focusedLabelColor = p.accent, unfocusedLabelColor = p.sub,
    cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink, focusedContainerColor = p.base, unfocusedContainerColor = p.base,
    focusedPlaceholderColor = p.sub, unfocusedPlaceholderColor = p.sub,
)

/** Line icons on a 24 grid with round caps: power, lock, monitor, restart, volume, timer; anything else is the settings icon. */
@Composable
fun Symbol(kind: String, color: Color, size: Int = 24) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * .083f
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
                drawRoundRect(color, Offset(w * .08f, h * .12f), Size(w * .84f, h * .6f), CornerRadius(w * .06f), style = Stroke(stroke))
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
                drawArc(color, -55f, 110f, false, Offset(w * .44f, h * .2f), Size(w * .5f, h * .61f), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "timer" -> {
                drawCircle(color, w * .33f, Offset(w * .5f, h * .57f), style = Stroke(stroke))
                line(.4f, .08f, .6f, .08f)
                line(.5f, .36f, .5f, .58f)
                line(.5f, .58f, .64f, .67f)
            }
            else -> {
                line(.13f, .25f, .87f, .25f); line(.13f, .5f, .87f, .5f); line(.13f, .75f, .87f, .75f)
                drawCircle(color, w * .09f, Offset(w * .62f, h * .25f))
                drawCircle(color, w * .09f, Offset(w * .36f, h * .5f))
                drawCircle(color, w * .09f, Offset(w * .7f, h * .75f))
            }
        }
    }
}
