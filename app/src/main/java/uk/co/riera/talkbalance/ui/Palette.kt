package uk.co.riera.talkbalance.ui

import androidx.compose.ui.graphics.Color

/**
 * The fixed 8-swatch speaker palette. A [uk.co.riera.talkbalance.data.PersonEntity.colorIndex] is an
 * index into this list; the same colour is the person's dot on Home, their enrollment swatch, their
 * live session bar (and its speaking glow), and their report share. Chosen to stay distinguishable
 * side by side on a light background.
 */
val SpeakerPalette: List<Color> = listOf(
    Color(0xFFE5484D), // red
    Color(0xFFF76B15), // orange
    Color(0xFFFFB224), // amber
    Color(0xFF46A758), // green
    Color(0xFF12A594), // teal
    Color(0xFF0091FF), // blue
    Color(0xFF8E4EC6), // purple
    Color(0xFFE93D82), // pink
)

/** The "Everyone else" / "Others" bucket colour — a neutral grey, never one of the 8 swatches. */
val EveryoneElseColor: Color = Color(0xFF9AA0A6)

/** Safe lookup: any out-of-range index falls back to the first swatch rather than crashing. */
fun colorForIndex(index: Int): Color =
    SpeakerPalette.getOrElse(index) { SpeakerPalette.first() }
