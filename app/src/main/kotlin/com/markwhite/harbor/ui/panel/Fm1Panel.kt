package com.markwhite.harbor.ui.panel

/**
 * The FM-1's controls (ui-spec Appendix A.1) and its front panel's geometry, from fm1-emu's
 * crates/fm1-live/src/panel.rs (a 1280 x 820 unit panel; the LCD at 2x).
 */
object Fm1Panel {
    const val W = 1280f
    const val H = 820f

    data class Enc(val label: String, val short: String, val id: Int, val x: Float, val y: Float, val r: Float)
    data class Btn(val label: String, val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    val SELECT = Enc("SELECT", "SELECT", 0, 250f, 135f, 44f)
    val PRESETS = Enc("PRESETS", "PRST", 6, 110f, 295f, 44f)
    val ALGORITHM = Enc("ALGORITHM", "ALGO", 1, 250f, 295f, 44f)
    val KNOBS = listOf(
        Enc("KNOB 1", "KNOB1", 2, 935f, 120f, 36f),
        Enc("KNOB 2", "KNOB2", 3, 1027f, 120f, 36f),
        Enc("KNOB 3", "KNOB3", 4, 1119f, 120f, 36f),
        Enc("KNOB 4", "KNOB4", 5, 1211f, 120f, 36f),
    )
    /** MASTER: SARADC channel 4, absolute */
    const val POT_X = 110f
    const val POT_Y = 135f
    const val POT_R = 44f

    const val LCD_X = 370f
    const val LCD_Y = 42f
    const val LCD_SIZE = 480f

    val OCT = listOf(Btn("OCT−", 0, 62f, 398f, 112f, 44f), Btn("OCT+", 1, 186f, 398f, 112f, 44f))
    val ROW1 = listOf("FX", "SCL", "ENV", "LFO", "EDIT", "GLO").mapIndexed { i, n -> Btn(n, 2 + i, 906f + i * 58, 232f, 50f, 64f) }
    val ROW2 = listOf("HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC").mapIndexed { i, n -> Btn(n, 8 + i, 906f + i * 58, 308f, 50f, 64f) }
    val BUTTONS = OCT + ROW1 + ROW2

    /** The keybed's top in panel units (black keys), and its bottom. */
    const val KEYS_TOP = 574f
    const val KEYS_BOTTOM = 786f

    /** Keys F3..G5: semitone n (0..26) is matrix key 14 + n. */
    const val KEY_COUNT = 27
    const val FIRST_KEY_ID = 14
    private val BLACK = setOf(1, 3, 5, 8, 10, 13, 15, 17, 20, 22, 25)
    fun isBlack(n: Int) = n in BLACK

    private val NAMES = arrayOf("F", "F♯", "G", "G♯", "A", "A♯", "B", "C", "C♯", "D", "D♯", "E")
    /** "F♯3" for semitone n above F3. */
    fun noteName(n: Int): String = NAMES[n % 12] + (3 + (n + 5) / 12)
    /** Spoken form ("F sharp 3") for TalkBack. */
    fun spokenName(n: Int): String = noteName(n).replace("♯", " sharp ")
}
