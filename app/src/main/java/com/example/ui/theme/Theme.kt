package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val VaultLightColorScheme = lightColorScheme(
    primary = VaultNavyPrimary,
    onPrimary = VaultOnNavyPrimary,
    primaryContainer = VaultNavyContainer,
    onPrimaryContainer = VaultOnNavyContainer,
    secondary = VaultAmberSecondary,
    onSecondary = VaultOnAmberSecondary,
    secondaryContainer = VaultAmberContainer,
    onSecondaryContainer = VaultOnAmberContainer,
    tertiary = VaultTealTertiary,
    onTertiary = VaultOnTealTertiary,
    tertiaryContainer = VaultTealContainer,
    onTertiaryContainer = VaultOnTealContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = VaultErrorLight,
    onError = Color.White,
    errorContainer = VaultErrorContainerLight,
    onErrorContainer = VaultErrorLight
)

private val VaultDarkColorScheme = darkColorScheme(
    primary = DarkNavyPrimary,
    onPrimary = DarkOnNavyPrimary,
    primaryContainer = DarkNavyContainer,
    onPrimaryContainer = DarkOnNavyContainer,
    secondary = DarkAmberSecondary,
    onSecondary = DarkOnAmberSecondary,
    secondaryContainer = DarkAmberContainer,
    onSecondaryContainer = DarkOnAmberContainer,
    tertiary = DarkTealTertiary,
    onTertiary = DarkOnTealTertiary,
    tertiaryContainer = DarkTealContainer,
    onTertiaryContainer = DarkOnTealContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = VaultErrorDark,
    onError = Color(0xFF3D0A06),
    errorContainer = VaultErrorContainerDark,
    onErrorContainer = Color(0xFFFEE4E2)
)

val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PdfVaultTheme(
    themeMode: String = "system",
    isBangla: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) VaultDarkColorScheme else VaultLightColorScheme
    val typography = buildVaultTypography(isBangla = isBangla)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = VaultShapes,
        content = content
    )
}
