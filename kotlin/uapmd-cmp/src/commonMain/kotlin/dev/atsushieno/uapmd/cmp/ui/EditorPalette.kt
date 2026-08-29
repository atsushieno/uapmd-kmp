package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colours for the surfaces that paint themselves — the timeline lanes, the
 * navigator and the piano roll grid.
 *
 * Material supplies colours to *components*; these draw into a `Canvas`, so they
 * were written as fixed dark constants. The toolbar's theme toggle therefore
 * switched the chrome to the light scheme and left black lanes, near-black master
 * track and dark clips behind it. The dark values below are exactly the previous
 * constants, so dark mode is unchanged; the light ones are their counterparts.
 *
 * Resolved from the scheme in use rather than plumbed through every composable,
 * so a floating window (piano roll, clip properties) gets the same answer as the
 * main window without extra parameters.
 */
@Immutable
data class EditorPalette(
    // timeline
    val laneBackground: Color,
    val masterLaneBackground: Color,
    val navigatorBackground: Color,
    val navigatorWindow: Color,
    val midiClip: Color,
    val audioClip: Color,
    val clipBorder: Color,
    val note: Color,
    val playhead: Color,
    val rangeFill: Color,
    val muted: Color,
    val solo: Color,
    val frozen: Color,
    val rendering: Color,
    // piano roll
    val rowWhite: Color,
    val rowBlack: Color,
    val gridLine: Color,
    val noteFill: Color,
    val noteSelected: Color,
    val keyPanelBackground: Color,
    val keyWhite: Color,
    val keyBlack: Color,
    val keyPreviewWhite: Color,
    val keyPreviewBlack: Color,
    val keySeparator: Color,
    val keyLabel: Color,
    // level meters and the sequence editor table
    val meterBackground: Color,
    val meterBar: Color,
    val tableHeaderBackground: Color,
    val tableRowDivider: Color,
)

private val DarkEditorPalette = EditorPalette(
    laneBackground       = Color(0xFF1E1E24),
    masterLaneBackground = Color(0xFF26262F),
    navigatorBackground  = Color(0xFF17171C),
    navigatorWindow      = Color(0x334F8FD0),
    midiClip             = Color(0xFF3D5A75),
    audioClip            = Color(0xFF4A3D75),
    clipBorder           = Color(0xFF9A8FC7),
    note                 = Color(0xFFBFD8F0),
    playhead             = Color(0xFFE8C547),
    rangeFill            = Color(0x552F6FA8),
    muted                = Color(0xFFB32828),
    solo                 = Color(0xFFD1850F),
    frozen               = Color(0xFF7FD4F0),
    rendering            = Color(0xFFD1850F),
    rowWhite             = Color(0xFF2A2A32),
    rowBlack             = Color(0xFF1B1B20),
    gridLine             = Color(0xFF3A3A45),
    noteFill             = Color(0xFF7FA9DE),
    noteSelected         = Color(0xFFE8C547),
    keyPanelBackground   = Color(0xFF17171C),
    keyWhite             = Color(0xFFE8E8E8),
    keyBlack             = Color(0xFF1B1B20),
    keyPreviewWhite      = Color(0xFF9FD8B0),
    keyPreviewBlack      = Color(0xFF3F7A54),
    keySeparator         = Color(0xFF3A3A45),
    keyLabel             = Color(0xFF303030),
    meterBackground      = Color(0xFF15151A),
    meterBar             = Color(0xFF6FCF97),
    tableHeaderBackground = Color(0xFF2A2A33),
    tableRowDivider      = Color(0xFF3A3A44),
)

/**
 * Light counterparts. Clips become tints rather than solids so the label — which
 * is a Material `Text` and therefore already dark on a light scheme — stays
 * readable on top of them, and the note marks drawn inside flip to dark.
 */
private val LightEditorPalette = EditorPalette(
    laneBackground       = Color(0xFFF4F4F8),
    masterLaneBackground = Color(0xFFE4E4EC),
    navigatorBackground  = Color(0xFFE9E9F0),
    navigatorWindow      = Color(0x552F6FA8),
    midiClip             = Color(0xFFC3D8EE),
    audioClip            = Color(0xFFD4C9EC),
    clipBorder           = Color(0xFF6E62A6),
    note                 = Color(0xFF1D3E63),
    playhead             = Color(0xFFC2410C),
    rangeFill            = Color(0x552F6FA8),
    muted                = Color(0xFFB32828),
    solo                 = Color(0xFFA96A00),
    frozen               = Color(0xFF0E7C9E),
    rendering            = Color(0xFFA96A00),
    rowWhite             = Color(0xFFFFFFFF),
    rowBlack             = Color(0xFFEDEDF3),
    gridLine             = Color(0xFFCFCFD9),
    noteFill             = Color(0xFF3E6FA8),
    noteSelected         = Color(0xFFC2410C),
    keyPanelBackground   = Color(0xFFE9E9F0),
    keyWhite             = Color(0xFFFFFFFF),
    keyBlack             = Color(0xFF2A2A32),
    keyPreviewWhite      = Color(0xFF7FC79A),
    keyPreviewBlack      = Color(0xFF2E6B44),
    keySeparator         = Color(0xFFBFBFC9),
    keyLabel             = Color(0xFF303030),
    meterBackground      = Color(0xFFE4E4EC),
    meterBar             = Color(0xFF2E9E68),
    tableHeaderBackground = Color(0xFFE4E4EC),
    tableRowDivider      = Color(0xFFC9C9D4),
)

/**
 * The palette matching the active scheme. Keyed on the scheme's own background
 * luminance rather than a flag threaded down from `MainWindow`, so it stays
 * correct if the theme ever follows the system instead of the toolbar toggle.
 */
val editorPalette: EditorPalette
    @Composable @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
        LightEditorPalette else DarkEditorPalette
