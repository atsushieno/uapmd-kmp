package dev.atsushieno.uapmd

/**
 * A plugin editor that is a pixel buffer rather than a view.
 *
 * Some formats have no window to embed: JSFX draws its editor itself and expects input
 * back. A host asks for the size, copies out frames, hands in input, and answers the
 * three services below while the editor is on screen. What it draws with is its own
 * business, and nothing here is specific to any format.
 *
 * Obtained from [PluginInstance.framebufferUi], which is null for every plugin whose
 * editor is a native window and for every plugin with no editor at all.
 */
interface FramebufferUi {
    /** The size the plugin would like, or null when it has no opinion and the host picks. */
    val preferredSize: FramebufferSize?

    /** Tells the plugin how large a surface it is drawing into, and at what scale. */
    fun setSurfaceSize(width: Int, height: Int, scaleFactor: Double)

    /**
     * Describes the frame that would be copied now, without copying it. Null before the
     * plugin has drawn anything. Compare [FramebufferFrameInfo.serial] against the last
     * frame drawn to decide whether [copyFrame] is worth calling at all.
     */
    fun frameInfo(): FramebufferFrameInfo?

    /**
     * Copies the most recent frame into [destination], one row every [destinationStride]
     * bytes, and describes what was copied. Null when the plugin has not drawn yet or
     * [destination] is too small.
     */
    fun copyFrame(destination: ByteArray, destinationStride: Int): FramebufferFrameInfo?

    /** Hands the plugin everything that has happened since the last call. */
    fun deliverInput(input: FramebufferInput, keys: List<FramebufferKeyEvent> = emptyList())

    /** Whether the editor is on screen. A plugin that is not displayed stops drawing. */
    fun setDisplayed(displayed: Boolean)

    /**
     * Installs the services the plugin needs while displayed, or null to uninstall.
     * A host must uninstall before it stops drawing.
     */
    fun setHost(host: FramebufferUiHost?)
}

/** Services a displayed plugin asks of whoever is showing it. */
interface FramebufferUiHost {
    /**
     * Opens a menu at a position in framebuffer pixels and returns immediately. The
     * plugin waits on its own thread, so this must not block.
     *
     * [complete] must be called exactly once, with the id of the chosen item or 0 when
     * the user dismissed it. A host that cannot show a menu still has to answer with 0
     * rather than drop the request, or the plugin waits for ever.
     */
    fun requestMenu(items: List<FramebufferMenuItem>, x: Int, y: Int, complete: (Int) -> Unit)

    /** Asks for a mouse cursor shape. */
    fun setCursor(cursor: FramebufferCursor)

    /** The path of a file dropped on the plugin, or null. Index -1 means forget them. */
    fun droppedFile(index: Int): String?
}

data class FramebufferSize(val width: Int, val height: Int)

enum class FramebufferPixelFormat {
    /** Byte order in memory: B, G, R, A. What JSFX draws, and what Skia calls BGRA_8888. */
    BGRA8,
    RGBA8
}

data class FramebufferFrameInfo(
    val width: Int,
    val height: Int,
    /** Distance in bytes between the start of one row and the next. */
    val strideBytes: Int,
    val format: FramebufferPixelFormat,
    /** Increases every time the plugin draws. */
    val serial: Long
)

/** Bits of [FramebufferInput.modifiers] and [FramebufferKeyEvent.modifiers]. */
object FramebufferModifiers {
    const val SHIFT = 1
    const val CONTROL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
}

/** Bits of [FramebufferInput.buttons]. */
object FramebufferButtons {
    const val LEFT = 1
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
}

/** Keys with no character of their own; everything typable arrives as a character. */
enum class FramebufferKey {
    NONE,
    BACKSPACE, TAB, ENTER, ESCAPE, SPACE, DELETE,
    LEFT, RIGHT, UP, DOWN,
    PAGE_UP, PAGE_DOWN, HOME, END, INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

/**
 * One key press or release. Either [character] carries what was typed, as a Unicode code
 * point, or [key] names a key that has no code point — never both.
 */
data class FramebufferKeyEvent(
    val modifiers: Int = 0,
    val character: Int = 0,
    val key: FramebufferKey = FramebufferKey.NONE,
    val pressed: Boolean = true
)

/**
 * The pointer state as it is now, plus what else the plugin should know. Keys travel
 * separately because a pointer only has a current position, while presses must not be
 * dropped.
 */
data class FramebufferInput(
    /** In framebuffer pixels, origin at the top left. */
    val pointerX: Int = 0,
    val pointerY: Int = 0,
    val buttons: Int = 0,
    val modifiers: Int = 0,
    /** Scroll since the last call, in steps normalised to ±1.0. */
    val wheel: Double = 0.0,
    val horizontalWheel: Double = 0.0,
    val hasFocus: Boolean = false,
    val visible: Boolean = true,
    val pointerOver: Boolean = false
)

/**
 * One entry of a menu the plugin asked for. The plugin's format builds the menu, so a
 * host never has to know how that format spells one, nor how it numbers ids.
 *
 * A separator carries no label and no id; an entry with [children] is a submenu and its
 * own id is 0.
 */
data class FramebufferMenuItem(
    val label: String = "",
    val id: Int = 0,
    val disabled: Boolean = false,
    val checked: Boolean = false,
    val separator: Boolean = false,
    val children: List<FramebufferMenuItem> = emptyList()
)

/** Cursor shapes in terms every toolkit has. */
enum class FramebufferCursor {
    ARROW,
    IBEAM,
    CROSSHAIR,
    HAND,
    SIZE_HORIZONTAL,
    SIZE_VERTICAL,
    SIZE_NESW,
    SIZE_NWSE
}
