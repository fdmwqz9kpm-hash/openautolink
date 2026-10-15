package com.openautolink.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openautolink.app.data.AppPreferences

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),       // Light blue accent
    onPrimary = Color.Black,
    secondary = Color(0xFF81C784),      // Green for connected states
    onSecondary = Color.Black,
    tertiary = Color(0xFFFFB74D),       // Orange for warnings
    background = Color.Black,
    surface = Color(0xFF121212),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFEF5350),
    onError = Color.White,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = Color.White
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        color = Color.White
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        color = Color(0xFFB0B0B0)
    )
)

@Composable
fun OpenAutoLinkTheme(
    oalUiScalePercent: Int? = null,
    content: @Composable () -> Unit,
) {
    // Optional override keeps previews/tests independent of application services.
    val percent = if (oalUiScalePercent != null || LocalInspectionMode.current) {
        oalUiScalePercent ?: AppPreferences.DEFAULT_OAL_UI_SCALE_PERCENT
    } else {
        val context = LocalContext.current
        val preferences = remember(context.applicationContext) { AppPreferences.getInstance(context) }
        val stored by preferences.oalUiScalePercent.collectAsStateWithLifecycle(
            initialValue = AppPreferences.DEFAULT_OAL_UI_SCALE_PERCENT,
        )
        stored
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalOalUiScalePercent provides AppPreferences.normalizeOalUiScalePercent(percent),
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
