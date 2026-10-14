package de.heimai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** In den Design-Einstellungen wählbare Farbwelten. */
data class ThemeSpec(val name: String, val seed: Color, val seedDark: Color)

val THEMES = listOf(
    ThemeSpec("Indigo", Color(0xFF3F51B5), Color(0xFF9FA8DA)),
    ThemeSpec("Teal", Color(0xFF00796B), Color(0xFF80CBC4)),
    ThemeSpec("Amber", Color(0xFFB26A00), Color(0xFFFFCC80)),
    ThemeSpec("Rose", Color(0xFFAD1457), Color(0xFFF48FB1)),
    ThemeSpec("Grün", Color(0xFF2E7D32), Color(0xFFA5D6A7)),
)

@Composable
fun HeimAiTheme(
    themeName: String,
    darkMode: String, // system | dark | light
    content: @Composable () -> Unit,
) {
    val spec = THEMES.firstOrNull { it.name == themeName } ?: THEMES.first()
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = spec.seedDark,
            secondary = spec.seedDark,
            primaryContainer = spec.seed,
        )
    } else {
        lightColorScheme(
            primary = spec.seed,
            secondary = spec.seed,
            primaryContainer = spec.seedDark,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
