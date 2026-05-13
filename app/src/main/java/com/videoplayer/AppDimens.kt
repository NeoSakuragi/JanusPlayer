package com.videoplayer

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppDimens(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val continueCardWidth: Dp,
    val heroHeight: Dp,
    val heroContentMaxWidth: Dp,
    val gridMinCardWidth: Dp,
    val gridRowHeight: Int,
    val rowPadding: Dp,
    val loginFieldFraction: Float,
    val listPanelWidth: Dp,
    val ctrlBtnWidth: Dp,
    val ctrlBtnHeight: Dp,
    val subBottomPadding: Dp,
    val dictTopPadding: Dp,
    val dictTermSize: TextUnit,
)

val LocalDimens = staticCompositionLocalOf { expanded() }

fun computeDimens(widthDp: Dp): AppDimens = when {
    widthDp < 600.dp -> compact()
    widthDp < 1200.dp -> medium()
    else -> expanded()
}

private fun compact() = AppDimens(
    cardWidth = 130.dp,
    cardHeight = 182.dp,
    continueCardWidth = 160.dp,
    heroHeight = 260.dp,
    heroContentMaxWidth = Dp.Infinity,
    gridMinCardWidth = 130.dp,
    gridRowHeight = 220,
    rowPadding = 16.dp,
    loginFieldFraction = 0.9f,
    listPanelWidth = 240.dp,
    ctrlBtnWidth = 45.dp,
    ctrlBtnHeight = 35.dp,
    subBottomPadding = 32.dp,
    dictTopPadding = 48.dp,
    dictTermSize = 24.sp,
)

private fun medium() = AppDimens(
    cardWidth = 160.dp,
    cardHeight = 224.dp,
    continueCardWidth = 190.dp,
    heroHeight = 320.dp,
    heroContentMaxWidth = 400.dp,
    gridMinCardWidth = 140.dp,
    gridRowHeight = 250,
    rowPadding = 24.dp,
    loginFieldFraction = 0.7f,
    listPanelWidth = 300.dp,
    ctrlBtnWidth = 51.dp,
    ctrlBtnHeight = 40.dp,
    subBottomPadding = 44.dp,
    dictTopPadding = 64.dp,
    dictTermSize = 28.sp,
)

private fun expanded() = AppDimens(
    cardWidth = 200.dp,
    cardHeight = 280.dp,
    continueCardWidth = 220.dp,
    heroHeight = 400.dp,
    heroContentMaxWidth = 500.dp,
    gridMinCardWidth = 160.dp,
    gridRowHeight = 280,
    rowPadding = 32.dp,
    loginFieldFraction = 0.5f,
    listPanelWidth = 340.dp,
    ctrlBtnWidth = 58.dp,
    ctrlBtnHeight = 45.dp,
    subBottomPadding = 60.dp,
    dictTopPadding = 80.dp,
    dictTermSize = 34.sp,
)
