package com.videoplayer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

object JanusTheme {
    val isDark = mutableStateOf(true)

    val bg get() = if (isDark.value) Color(0xFF0A0A0A) else Color(0xFFF5F5F0)
    val surface get() = if (isDark.value) Color(0xFF1A1A2E) else Color(0xFFFFFFFF)
    val surfaceAlt get() = if (isDark.value) Color(0xFF2A2A4A) else Color(0xFFEEEEE8)
    val text get() = if (isDark.value) Color.White else Color(0xFF1A1A1A)
    val textSecondary get() = if (isDark.value) Color(0xFF888888) else Color(0xFF666666)
    val textTertiary get() = if (isDark.value) Color(0xFF999999) else Color(0xFF888888)
    val accent get() = if (isDark.value) Color(0xFFBB86FC) else Color(0xFF6200EE)
    val accentGreen get() = if (isDark.value) Color(0xFF81C784) else Color(0xFF2E7D32)
    val cardBg get() = if (isDark.value) Color(0xFF1A1A2E) else Color(0xFFFFFFFF)
    val toolbar get() = if (isDark.value) Color(0xFF222222) else Color(0xFFE0E0DA)
    val subtitleBg get() = if (isDark.value) Color(0x99000000) else Color(0x99FFFFFF)
    val subtitleText get() = if (isDark.value) Color.White else Color.Black
    val subtitleShadow get() = if (isDark.value) Color.Black else Color.White
    val subtitleRuby get() = if (isDark.value) Color(0xFFDDDDDD) else Color(0xFF444444)
    val subtitleHighlight get() = if (isDark.value) Color(0xFF7986CB) else Color(0xFF9FA8DA)
}
