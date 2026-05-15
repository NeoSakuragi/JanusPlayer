package com.janusplus

data class Dimens(
    val cardW: Float,
    val cardH: Float,
    val continueCardW: Float,
    val heroH: Float,
    val heroContentMaxW: Float,
    val gridMinCardW: Float,
    val gridRowH: Float,
    val padding: Float,
    val cardSpacing: Float = 16f,
    val gridSpacing: Float = 12f,
)

fun computeDimens(widthPx: Float, density: Float): Dimens {
    val widthDp = widthPx / density
    return when {
        widthDp < 600f -> Dimens(
            cardW = 130f * density, cardH = 182f * density,
            continueCardW = 160f * density, heroH = 260f * density,
            heroContentMaxW = widthPx, gridMinCardW = widthPx,
            gridRowH = 120f * density, padding = 16f * density,
            cardSpacing = 16f * density, gridSpacing = 12f * density,
        )
        widthDp < 1200f -> Dimens(
            cardW = 160f * density, cardH = 224f * density,
            continueCardW = 190f * density, heroH = 320f * density,
            heroContentMaxW = 400f * density, gridMinCardW = 140f * density,
            gridRowH = 250f * density, padding = 24f * density,
            cardSpacing = 16f * density, gridSpacing = 12f * density,
        )
        else -> Dimens(
            cardW = 200f * density, cardH = 280f * density,
            continueCardW = 220f * density, heroH = 400f * density,
            heroContentMaxW = 500f * density, gridMinCardW = 160f * density,
            gridRowH = 280f * density, padding = 32f * density,
            cardSpacing = 16f * density, gridSpacing = 12f * density,
        )
    }
}
