package com.poketrader.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poketrader.container
import com.poketrader.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFE3350D)
private val Yellow = Color(0xFFFFCB05)
private val Blue = Color(0xFF3B4CCA)

private val Light = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3D0600),
    secondary = Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE0FF),
    onSecondaryContainer = Color(0xFF00105C),
    tertiary = Color(0xFF7A5E00),
    tertiaryContainer = Yellow,
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFF8F0),
    surfaceContainerLow = Color(0xFFFFF1E6),
    surfaceContainer = Color(0xFFFBEBDD),
    surfaceContainerHigh = Color(0xFFF5E4D5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4A3),
    onPrimary = Color(0xFF611200),
    primaryContainer = Color(0xFF8A1E00),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFBAC3FF),
    secondaryContainer = Color(0xFF2B3AB3),
    onSecondaryContainer = Color(0xFFDEE0FF),
    tertiaryContainer = Color(0xFF5C4600),
    onTertiaryContainer = Color(0xFFFFE08A),
)

/** Colours for the fairness verdict; readable on both schemes. */
object VerdictColors {
    val fair = Color(0xFF2E9E5B)
    val favorsYou = Color(0xFF3D8BD9)
    val favorsThem = Color(0xFFE0772B)
}

val HoloColor = Color(0xFF9C6ADE)

/** Price trend arrows; readable on both schemes. */
object TrendColors {
    val up = Color(0xFF2E9E5B)
    val down = Color(0xFFD32F2F)
}

@Composable
fun PokeTheme(content: @Composable () -> Unit) {
    val mode by LocalContext.current.container.settings.themeMode.collectAsStateWithLifecycle()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Status and navigation bar icons readable on the chosen background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
