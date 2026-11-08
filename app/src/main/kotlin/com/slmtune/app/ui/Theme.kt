package com.slmtune.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The palette of the Windows app (App.xaml): ink background, magenta accent. */
object Slm {
    val Ink = Color(0xFF0B0B0D)
    val Card = Color(0xFF151518)
    val Card2 = Color(0xFF1C1C21)
    val Line = Color(0xFF2A2A30)
    val Tint = Color(0xFF2E0B2E)
    val Accent = Color(0xFFD100D1)
    val Accent2 = Color(0xFFFF3DFF)
    val Text = Color(0xFFF2F2F5)
    val Text2 = Color(0xFFA6A6B0)
    val Text3 = Color(0xFF6E6E78)
    val Ok = Color(0xFF34D399)
    val OkBg = Color(0xFF1E3A34)
    val Warn = Color(0xFFF5B83D)
    val Err = Color(0xFFFF5C7A)
    val Gradient = Brush.linearGradient(listOf(Accent, Accent2))
}

private val scheme = darkColorScheme(
    primary = Slm.Accent, onPrimary = Color.White, secondary = Slm.Accent2,
    background = Slm.Ink, onBackground = Slm.Text, surface = Slm.Card, onSurface = Slm.Text,
    surfaceVariant = Slm.Card2, onSurfaceVariant = Slm.Text2, outline = Slm.Line,
    surfaceContainer = Slm.Card, surfaceContainerHigh = Slm.Card2, surfaceContainerLow = Slm.Ink,
    primaryContainer = Slm.Tint, onPrimaryContainer = Color(0xFFFFB3FF), error = Slm.Err,
)

@Composable
fun SlmTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** A section title like the Windows "SectionTitle" style. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slm.Text2)

/** A card: rounded surface for one group of settings. */
@Composable
fun SlmCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.fillMaxWidth().background(Slm.Card, RoundedCornerShape(14.dp)).padding(14.dp)) { Column { content() } }

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) = Text(text, modifier, fontSize = 12.sp, color = Slm.Text3, lineHeight = 16.sp)
