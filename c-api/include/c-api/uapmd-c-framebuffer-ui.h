/* uapmd C API — plugin editors that are a pixel buffer rather than a native view
 *
 * Some plugin formats have no window to embed. JSFX draws its editor into a CPU
 * framebuffer and expects input back, which is what uapmd exposes as the plugin
 * instance extension "framebuffer-ui.v1". These entry points are that extension,
 * flattened for callers that are not C++: ask for the size, copy out a frame, hand
 * back input, and answer the three services the plugin needs while it is on screen.
 *
 * A host built on this draws with whatever it already has -- a Compose canvas, a
 * Skia bitmap, an ImGui texture -- and needs no knowledge of the format.
 *
 * Threading: every function here is called from whatever thread the host draws on,
 * and none of them block. The plugin renders on its own thread; a frame copied out
 * is a snapshot, and input handed in is consumed at the plugin's own pace. The one
 * exception is documented on uapmd_instance_fbui_complete_menu.
 */
#ifndef UAPMD_C_FRAMEBUFFER_UI_H
#define UAPMD_C_FRAMEBUFFER_UI_H

#include "uapmd-c-common.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Frames ─────────────────────────────────────────────────────────────── */

/* Byte order of the pixels the plugin produces. It is a property of the plugin, so
 * a host that wants a different order converts as it copies. JSFX draws BGRA8,
 * which is also what Skia calls BGRA_8888, so a Skia host copies straight across. */
typedef enum uapmd_fbui_pixel_format {
    UAPMD_FBUI_PIXEL_FORMAT_BGRA8 = 0,
    UAPMD_FBUI_PIXEL_FORMAT_RGBA8 = 1
} uapmd_fbui_pixel_format_t;

typedef struct uapmd_fbui_frame_info {
    uint32_t width;
    uint32_t height;
    /* Distance in bytes between the start of one row and the next. */
    uint32_t stride_bytes;
    uapmd_fbui_pixel_format_t format;
    /* Increases every time the plugin draws. A host compares this against what it
     * last drew and skips the copy when nothing has changed. */
    uint64_t serial;
} uapmd_fbui_frame_info_t;

/* ── Input ──────────────────────────────────────────────────────────────── */

/* Modifier keys, as bits of uapmd_fbui_input_t::modifiers and of a key event's.
 * A host reports what its own toolkit calls these and the format translates. */
typedef enum uapmd_fbui_modifier {
    UAPMD_FBUI_MOD_SHIFT   = 1u << 0,
    UAPMD_FBUI_MOD_CONTROL = 1u << 1,
    UAPMD_FBUI_MOD_ALT     = 1u << 2,
    UAPMD_FBUI_MOD_SUPER   = 1u << 3
} uapmd_fbui_modifier_t;

/* Pointer buttons, as bits of uapmd_fbui_input_t::buttons. */
typedef enum uapmd_fbui_button {
    UAPMD_FBUI_BUTTON_LEFT   = 1u << 0,
    UAPMD_FBUI_BUTTON_RIGHT  = 1u << 1,
    UAPMD_FBUI_BUTTON_MIDDLE = 1u << 2
} uapmd_fbui_button_t;

/* Keys with no character of their own. Anything typable arrives as `character`
 * instead, so this names only what a code point cannot. */
typedef enum uapmd_fbui_key {
    UAPMD_FBUI_KEY_NONE = 0,
    UAPMD_FBUI_KEY_BACKSPACE, UAPMD_FBUI_KEY_TAB, UAPMD_FBUI_KEY_ENTER,
    UAPMD_FBUI_KEY_ESCAPE, UAPMD_FBUI_KEY_SPACE, UAPMD_FBUI_KEY_DELETE,
    UAPMD_FBUI_KEY_LEFT, UAPMD_FBUI_KEY_RIGHT, UAPMD_FBUI_KEY_UP, UAPMD_FBUI_KEY_DOWN,
    UAPMD_FBUI_KEY_PAGE_UP, UAPMD_FBUI_KEY_PAGE_DOWN, UAPMD_FBUI_KEY_HOME,
    UAPMD_FBUI_KEY_END, UAPMD_FBUI_KEY_INSERT,
    UAPMD_FBUI_KEY_F1, UAPMD_FBUI_KEY_F2, UAPMD_FBUI_KEY_F3, UAPMD_FBUI_KEY_F4,
    UAPMD_FBUI_KEY_F5, UAPMD_FBUI_KEY_F6, UAPMD_FBUI_KEY_F7, UAPMD_FBUI_KEY_F8,
    UAPMD_FBUI_KEY_F9, UAPMD_FBUI_KEY_F10, UAPMD_FBUI_KEY_F11, UAPMD_FBUI_KEY_F12
} uapmd_fbui_key_t;

/* One key press or release. Either `character` carries what was typed, as a
 * Unicode code point, or `key` names a key that has no code point -- never both. */
typedef struct uapmd_fbui_key_event {
    uint32_t modifiers;
    uint32_t character;
    uapmd_fbui_key_t key;
    bool pressed;
} uapmd_fbui_key_event_t;

/* The pointer state as it is now, plus whatever else the plugin should know. Keys
 * travel separately because a pointer only has a current position, while key
 * presses must not be dropped. */
typedef struct uapmd_fbui_input {
    /* In framebuffer pixels, origin at the top left. */
    int32_t pointer_x;
    int32_t pointer_y;
    /* Bitmasks of what is currently held. */
    uint32_t buttons;
    uint32_t modifiers;
    /* Scroll since the last call, in steps normalised to +/-1.0. */
    double wheel;
    double horizontal_wheel;
    bool has_focus;
    bool visible;
    bool pointer_over;
} uapmd_fbui_input_t;

/* ── Services the plugin needs while it is displayed ─────────────────────── */

/* An open menu request, to be answered exactly once with
 * uapmd_instance_fbui_complete_menu. */
typedef struct uapmd_fbui_menu_request* uapmd_fbui_menu_request_t;

/* One entry of a menu the plugin has asked for.
 *
 * The plugin hands over a menu already built, rather than the string its own
 * format describes menus with: parsing that would mean knowing the format's rules
 * for disabled items, submenus and id numbering, none of which a host has any
 * business knowing.
 *
 * The tree arrives flattened depth first: an item with `child_count` N is
 * immediately followed by its N children, each of which may have children of its
 * own. A separator carries no label and no id; a submenu's own id is 0. */
typedef struct uapmd_fbui_menu_item {
    const char* label;
    int32_t id;
    bool disabled;
    bool checked;
    bool separator;
    uint32_t child_count;
} uapmd_fbui_menu_item_t;

/* Asks the host to open a menu at a position in framebuffer pixels. `items` is
 * only valid for the duration of this call, so a host that shows it later copies
 * what it needs.
 *
 * The host returns immediately and answers later from its own event loop: the
 * plugin waits on its own thread, never on the host's. */
typedef void (*uapmd_fbui_menu_cb_t)(uapmd_fbui_menu_request_t request,
                                     const uapmd_fbui_menu_item_t* items,
                                     size_t item_count,
                                     int32_t x, int32_t y,
                                     void* user_data);

/* Cursor shapes in terms every toolkit has. The format maps its own identifiers
 * onto these; the host maps these onto its toolkit's. */
typedef enum uapmd_fbui_cursor {
    UAPMD_FBUI_CURSOR_ARROW = 0,
    UAPMD_FBUI_CURSOR_IBEAM,
    UAPMD_FBUI_CURSOR_CROSSHAIR,
    UAPMD_FBUI_CURSOR_HAND,
    UAPMD_FBUI_CURSOR_SIZE_HORIZONTAL,
    UAPMD_FBUI_CURSOR_SIZE_VERTICAL,
    UAPMD_FBUI_CURSOR_SIZE_NESW,
    UAPMD_FBUI_CURSOR_SIZE_NWSE
} uapmd_fbui_cursor_t;

/* Asks for a mouse cursor shape. */
typedef void (*uapmd_fbui_cursor_cb_t)(uapmd_fbui_cursor_t cursor, void* user_data);

/* Asks for the path of a file dropped on the plugin, by index, written into `buf`
 * and returning its length. Returning 0 means there is no such file. An index of
 * -1 asks the host to forget the files it is holding. */
typedef size_t (*uapmd_fbui_dropped_file_cb_t)(int32_t index,
                                               char* buf, size_t buf_size,
                                               void* user_data);

/* ── Instance API ───────────────────────────────────────────────────────── */

/* Whether this instance draws its editor rather than embedding a view. False for
 * every format whose editor is a native window, and for a format that has no
 * editor at all. */
UAPMD_C_EXPORT bool uapmd_instance_has_framebuffer_ui(uapmd_plugin_instance_t inst);

/* The size the plugin would like, if it has an opinion. False when it has none, in
 * which case the host picks. */
UAPMD_C_EXPORT bool uapmd_instance_fbui_preferred_size(uapmd_plugin_instance_t inst,
                                                          uint32_t* out_width,
                                                          uint32_t* out_height);

/* Tells the plugin how large a surface it is drawing into, and at what scale. Call
 * it before the first frame and whenever the surface changes size. */
UAPMD_C_EXPORT void uapmd_instance_fbui_set_surface_size(uapmd_plugin_instance_t inst,
                                                            uint32_t width, uint32_t height,
                                                            double scale_factor);

/* Describes the frame that would be copied now, without copying it. A host calls
 * this to size its own bitmap, and to compare `serial` against the last frame it
 * drew. False when the plugin has not drawn anything yet. */
UAPMD_C_EXPORT bool uapmd_instance_fbui_frame_info(uapmd_plugin_instance_t inst,
                                                      uapmd_fbui_frame_info_t* out_info);

/* Copies the most recent frame into `dst`, one row at a time into `dst_stride_bytes`,
 * and fills `out_info` with what was actually copied -- including the serial, so the
 * host knows which frame it now holds.
 *
 * Returns false and copies nothing when the plugin has not drawn yet, or when `dst`
 * is too small for the frame. Copying rather than lending a pointer is deliberate:
 * the frame is only valid while the plugin's renderer is held off, and a lock that
 * has to be released by a caller in a garbage-collected language is a deadlock
 * waiting to happen. */
UAPMD_C_EXPORT bool uapmd_instance_fbui_copy_frame(uapmd_plugin_instance_t inst,
                                                      void* dst,
                                                      size_t dst_capacity_bytes,
                                                      uint32_t dst_stride_bytes,
                                                      uapmd_fbui_frame_info_t* out_info);

/* Hands the plugin everything that has happened since the last call. `keys` may be
 * NULL when `key_count` is 0. */
UAPMD_C_EXPORT void uapmd_instance_fbui_deliver_input(uapmd_plugin_instance_t inst,
                                                         const uapmd_fbui_input_t* input,
                                                         const uapmd_fbui_key_event_t* keys,
                                                         size_t key_count);

/* Whether the editor is on screen. A plugin that is not displayed stops drawing. */
UAPMD_C_EXPORT void uapmd_instance_fbui_set_displayed(uapmd_plugin_instance_t inst, bool displayed);

/* Installs the services above. Any callback may be NULL; a NULL menu callback means
 * menus are dismissed rather than shown, which is still an answer. Pass NULL for
 * every callback to uninstall, which a host must do before it stops drawing. */
UAPMD_C_EXPORT void uapmd_instance_fbui_set_host(uapmd_plugin_instance_t inst,
                                                    uapmd_fbui_menu_cb_t menu_callback,
                                                    uapmd_fbui_cursor_cb_t cursor_callback,
                                                    uapmd_fbui_dropped_file_cb_t dropped_file_callback,
                                                    void* user_data);

/* Answers an open menu request with the `id` of the chosen item, or 0 when the user
 * dismissed it. Must be called exactly once per request, and may be called from any
 * thread, including from inside the menu callback itself. A host that cannot show a
 * menu still has to answer with 0 rather than drop the request, or the plugin's
 * renderer waits for ever. */
UAPMD_C_EXPORT void uapmd_instance_fbui_complete_menu(uapmd_fbui_menu_request_t request,
                                                         int32_t chosen_item);

#ifdef __cplusplus
}
#endif

#endif /* UAPMD_C_FRAMEBUFFER_UI_H */
