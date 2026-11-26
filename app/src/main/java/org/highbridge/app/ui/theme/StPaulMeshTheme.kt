package org.highbridge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colors taken straight from stpaulmesh.com's stylesheet (light and dark modes).
 * The old palette (navy #1D3557, forest #1B4332) didn't match the site.
 */
object StPaulMeshPalette {
    // Light (site :root)
    val Cream = Color(0xFFFDF6EC)      // --bg
    val Ink = Color(0xFF243320)        // --fg
    val Moss = Color(0xFF5A6650)       // --muted
    val Card = Color(0xFFFFFBF3)       // --card
    val Line = Color(0xFFE8DCC3)       // --line
    val Green = Color(0xFF2D6A2E)      // --accent
    val Bronze = Color(0xFF98600F)     // --accent2
    val Gold = Color(0xFFF0A030)       // --gold (rules, highlights, focus)
    val Wave = Color(0xFF4A8F4A)       // --wave (signal graphics)

    // Dark (site prefers-color-scheme: dark)
    val NightBg = Color(0xFF111912)
    val NightFg = Color(0xFFF1EAD8)
    val NightMuted = Color(0xFFA9B79F)
    val NightCard = Color(0xFF1A251B)
    val NightLine = Color(0xFF2D3B2E)
    val NightGreen = Color(0xFF86C986)
    val NightAmber = Color(0xFFF0B048)
    val NightWave = Color(0xFF6FB36F)

    // One color per protocol, chosen to sit inside the site palette and stay apart for color-blind users.
    val Meshtastic = Color(0xFF2D6A2E)   // site green
    val MeshCore = Color(0xFF2B6F8C)     // river blue (Mississippi)
    val Reticulum = Color(0xFF98600F)    // bronze
    val MeshtasticDark = Color(0xFF86C986)
    val MeshCoreDark = Color(0xFF7CC3E0)
    val ReticulumDark = Color(0xFFF0B048)
    val Danger = Color(0xFFB3261E)       // site .warn border (SOS, wipe)

    // West 7th accents used in illustrations (Highbridge design canvas).
    val SchmidtBrick = Color(0xFF8E3B2B)
    val BrickLight = Color(0xFFC48B7E)
    val River = Color(0xFFBCD9E4)
    val Parchment = Color(0xFFF4EAD8)
    val Stone = Color(0xFFC9BFA8)
    val GreenSoft = Color(0xFFDCEBD5)
}

private val Light = lightColorScheme(
    primary = StPaulMeshPalette.Green, onPrimary = StPaulMeshPalette.Cream,
    primaryContainer = Color(0xFFDCEBD5), onPrimaryContainer = StPaulMeshPalette.Ink,
    secondary = StPaulMeshPalette.Bronze, onSecondary = Color.White,
    tertiary = StPaulMeshPalette.Gold, onTertiary = StPaulMeshPalette.Ink,
    background = StPaulMeshPalette.Cream, onBackground = StPaulMeshPalette.Ink,
    surface = StPaulMeshPalette.Card, onSurface = StPaulMeshPalette.Ink,
    surfaceVariant = Color(0xFFF4EAD8), onSurfaceVariant = StPaulMeshPalette.Moss,
    outline = StPaulMeshPalette.Line, error = StPaulMeshPalette.Danger,
)

private val Dark = darkColorScheme(
    primary = StPaulMeshPalette.NightGreen, onPrimary = StPaulMeshPalette.NightBg,
    primaryContainer = Color(0xFF24402A), onPrimaryContainer = StPaulMeshPalette.NightFg,
    secondary = StPaulMeshPalette.NightAmber, onSecondary = StPaulMeshPalette.NightBg,
    tertiary = StPaulMeshPalette.Gold, onTertiary = StPaulMeshPalette.NightBg,
    background = StPaulMeshPalette.NightBg, onBackground = StPaulMeshPalette.NightFg,
    surface = StPaulMeshPalette.NightCard, onSurface = StPaulMeshPalette.NightFg,
    surfaceVariant = Color(0xFF223024), onSurfaceVariant = StPaulMeshPalette.NightMuted,
    outline = StPaulMeshPalette.NightLine, error = Color(0xFFF2B8B5),
)

/** Headings use the site's italic serif (Georgia on the web, the system serif on Android). */
val DisplayFont = androidx.compose.ui.text.font.FontFamily.Serif

private val HbTypography = androidx.compose.material3.Typography().let { t ->
    val serif = androidx.compose.ui.text.TextStyle(fontFamily = DisplayFont, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    t.copy(
        displaySmall = t.displaySmall.merge(serif), headlineLarge = t.headlineLarge.merge(serif),
        headlineMedium = t.headlineMedium.merge(serif), headlineSmall = t.headlineSmall.merge(serif), titleLarge = t.titleLarge.merge(serif),
    )
}

@Composable
fun StPaulMeshTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Dynamic (wallpaper) color is deliberately off so the app always looks like stpaulmesh.com.
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = HbTypography, content = content)
}

/** Colour for each protocol, light or dark. */
@Composable
fun protocolColor(p: org.highbridge.core.protocol.ProtocolType): Color {
    val dark = MaterialTheme.colorScheme.background == StPaulMeshPalette.NightBg
    return when (p) {
        org.highbridge.core.protocol.ProtocolType.MESHTASTIC -> if (dark) StPaulMeshPalette.MeshtasticDark else StPaulMeshPalette.Meshtastic
        org.highbridge.core.protocol.ProtocolType.MESHCORE -> if (dark) StPaulMeshPalette.MeshCoreDark else StPaulMeshPalette.MeshCore
        org.highbridge.core.protocol.ProtocolType.RETICULUM -> if (dark) StPaulMeshPalette.ReticulumDark else StPaulMeshPalette.Reticulum
    }
}
