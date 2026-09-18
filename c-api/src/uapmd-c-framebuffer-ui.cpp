/* uapmd C API — implementation for the plugin framebuffer UI extension */

#include "c-api/uapmd-c-framebuffer-ui.h"

#include <uapmd-plugin-hosting/uapmd-plugin-hosting.hpp>
#include <uapmd-plugin-hosting/detail/plugin-api/PluginFramebufferUIExtension.hpp>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace fb = uapmd_plugin_hosting;

static fb::AudioPluginInstanceAPI* I(uapmd_plugin_instance_t h) {
    return reinterpret_cast<fb::AudioPluginInstanceAPI*>(h);
}

static fb::PluginFramebufferUIExtension* FBUI(uapmd_plugin_instance_t h) {
    if (!h)
        return nullptr;
    auto* ext = I(h)->extension(fb::kPluginFramebufferUIExtensionId);
    return dynamic_cast<fb::PluginFramebufferUIExtension*>(ext);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Frames
 * ═══════════════════════════════════════════════════════════════════════════ */

bool uapmd_instance_has_framebuffer_ui(uapmd_plugin_instance_t inst) {
    return FBUI(inst) != nullptr;
}

bool uapmd_instance_fbui_preferred_size(uapmd_plugin_instance_t inst,
                                        uint32_t* out_width, uint32_t* out_height) {
    auto* ui = FBUI(inst);
    if (!ui)
        return false;
    uint32_t w = 0, h = 0;
    if (!ui->preferredSize(w, h))
        return false;
    if (out_width) *out_width = w;
    if (out_height) *out_height = h;
    return true;
}

void uapmd_instance_fbui_set_surface_size(uapmd_plugin_instance_t inst,
                                          uint32_t width, uint32_t height, double scale_factor) {
    if (auto* ui = FBUI(inst))
        ui->surfaceSize(width, height, scale_factor);
}

static uapmd_fbui_pixel_format_t to_c_pixel_format(fb::FramebufferPixelFormat value) {
    return value == fb::FramebufferPixelFormat::RGBA8
        ? UAPMD_FBUI_PIXEL_FORMAT_RGBA8 : UAPMD_FBUI_PIXEL_FORMAT_BGRA8;
}

bool uapmd_instance_fbui_frame_info(uapmd_plugin_instance_t inst, uapmd_fbui_frame_info_t* out_info) {
    auto* ui = FBUI(inst);
    if (!ui || !out_info)
        return false;
    bool got = false;
    ui->readFrame([&](const fb::FramebufferView& view) {
        out_info->width = view.width;
        out_info->height = view.height;
        out_info->stride_bytes = view.stride;
        out_info->format = to_c_pixel_format(ui->pixelFormat());
        out_info->serial = view.serial;
        got = true;
    });
    return got;
}

bool uapmd_instance_fbui_copy_frame(uapmd_plugin_instance_t inst,
                                    void* dst, size_t dst_capacity_bytes, uint32_t dst_stride_bytes,
                                    uapmd_fbui_frame_info_t* out_info) {
    auto* ui = FBUI(inst);
    if (!ui || !dst)
        return false;
    bool copied = false;
    ui->readFrame([&](const fb::FramebufferView& view) {
        if (!view.pixels || view.width == 0 || view.height == 0)
            return;
        /* A row must fit, and so must every row. The caller's stride may exceed the
           plugin's -- a bitmap that is wider than the frame is fine -- but never the
           other way round, or rows would overlap. */
        const uint32_t row_bytes = view.width * 4;
        const uint32_t stride = dst_stride_bytes ? dst_stride_bytes : row_bytes;
        if (stride < row_bytes)
            return;
        if (static_cast<size_t>(stride) * view.height > dst_capacity_bytes)
            return;
        auto* out = static_cast<uint8_t*>(dst);
        for (uint32_t y = 0; y < view.height; y++)
            std::memcpy(out + static_cast<size_t>(y) * stride,
                        view.pixels + static_cast<size_t>(y) * view.stride,
                        row_bytes);
        if (out_info) {
            out_info->width = view.width;
            out_info->height = view.height;
            out_info->stride_bytes = stride;
            out_info->format = to_c_pixel_format(ui->pixelFormat());
            out_info->serial = view.serial;
        }
        copied = true;
    });
    return copied;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Input
 * ═══════════════════════════════════════════════════════════════════════════ */

static fb::FramebufferKey to_cpp_key(uapmd_fbui_key_t key) {
    /* The two enumerations are declared in the same order, so the cast is the
       mapping. A value from outside the range becomes None rather than garbage. */
    auto raw = static_cast<uint32_t>(key);
    if (raw > static_cast<uint32_t>(fb::FramebufferKey::F12))
        return fb::FramebufferKey::None;
    return static_cast<fb::FramebufferKey>(raw);
}

void uapmd_instance_fbui_deliver_input(uapmd_plugin_instance_t inst,
                                       const uapmd_fbui_input_t* input,
                                       const uapmd_fbui_key_event_t* keys, size_t key_count) {
    auto* ui = FBUI(inst);
    if (!ui || !input)
        return;
    fb::FramebufferInput value{};
    value.pointerX = input->pointer_x;
    value.pointerY = input->pointer_y;
    value.buttons = input->buttons;
    value.modifiers = input->modifiers;
    value.wheel = input->wheel;
    value.horizontalWheel = input->horizontal_wheel;
    value.hasFocus = input->has_focus;
    value.visible = input->visible;
    value.pointerOver = input->pointer_over;
    if (keys) {
        value.keys.reserve(key_count);
        for (size_t i = 0; i < key_count; i++) {
            fb::FramebufferKeyEvent ev{};
            ev.modifiers = keys[i].modifiers;
            ev.character = keys[i].character;
            ev.key = to_cpp_key(keys[i].key);
            ev.pressed = keys[i].pressed;
            value.keys.emplace_back(std::move(ev));
        }
    }
    ui->deliverInput(std::move(value));
}

void uapmd_instance_fbui_set_displayed(uapmd_plugin_instance_t inst, bool displayed) {
    if (auto* ui = FBUI(inst))
        ui->displayed(displayed);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Host services
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {

/* One open menu. The plugin's renderer is waiting on `completed`, so the request
   outlives the callback that reported it and is destroyed only once answered. */
struct MenuRequest {
    std::function<void(int32_t)> completed;
    std::once_flag answered;
};

std::mutex g_menu_mutex;
std::unordered_map<MenuRequest*, std::shared_ptr<MenuRequest>> g_menus;

/* Flattens the item tree depth first, which is what the C struct describes.
   `labels` keeps the strings alive for the duration of the callback. */
void flatten_menu(const std::vector<fb::FramebufferMenuItem>& items,
                  std::vector<uapmd_fbui_menu_item_t>& out,
                  std::vector<std::string>& labels) {
    for (const auto& item : items) {
        labels.emplace_back(item.label);
        out.push_back(uapmd_fbui_menu_item_t{
            nullptr, item.id, item.disabled, item.checked, item.separator,
            static_cast<uint32_t>(item.children.size())
        });
        flatten_menu(item.children, out, labels);
    }
}

class CFramebufferUIHost : public fb::FramebufferUIHost {
public:
    uapmd_fbui_menu_cb_t menu_cb{nullptr};
    uapmd_fbui_cursor_cb_t cursor_cb{nullptr};
    uapmd_fbui_dropped_file_cb_t dropped_cb{nullptr};
    void* user_data{nullptr};

    void requestMenu(const std::vector<fb::FramebufferMenuItem>& items,
                     int32_t x, int32_t y,
                     std::function<void(int32_t)> completed) override {
        if (!menu_cb) {
            /* Dismissing is still an answer; dropping it hangs the renderer. */
            completed(0);
            return;
        }
        auto request = std::make_shared<MenuRequest>();
        request->completed = std::move(completed);
        {
            std::lock_guard lock(g_menu_mutex);
            g_menus[request.get()] = request;
        }

        std::vector<uapmd_fbui_menu_item_t> flat;
        std::vector<std::string> labels;
        labels.reserve(items.size());
        flatten_menu(items, flat, labels);
        for (size_t i = 0; i < flat.size(); i++)
            flat[i].label = labels[i].c_str();

        menu_cb(reinterpret_cast<uapmd_fbui_menu_request_t>(request.get()),
                flat.empty() ? nullptr : flat.data(), flat.size(), x, y, user_data);
    }

    void setCursor(fb::FramebufferCursor cursor) override {
        if (cursor_cb)
            cursor_cb(static_cast<uapmd_fbui_cursor_t>(cursor), user_data);
    }

    std::string droppedFile(int32_t index) override {
        if (!dropped_cb)
            return {};
        size_t needed = dropped_cb(index, nullptr, 0, user_data);
        if (needed == 0)
            return {};
        std::string value(needed, '\0');
        size_t written = dropped_cb(index, value.data(), value.size(), user_data);
        if (written == 0)
            return {};
        value.resize(std::strlen(value.c_str()));
        return value;
    }
};

std::mutex g_host_mutex;
std::unordered_map<fb::PluginFramebufferUIExtension*, std::unique_ptr<CFramebufferUIHost>> g_hosts;

} // namespace

void uapmd_instance_fbui_set_host(uapmd_plugin_instance_t inst,
                                  uapmd_fbui_menu_cb_t menu_callback,
                                  uapmd_fbui_cursor_cb_t cursor_callback,
                                  uapmd_fbui_dropped_file_cb_t dropped_file_callback,
                                  void* user_data) {
    auto* ui = FBUI(inst);
    if (!ui)
        return;
    if (!menu_callback && !cursor_callback && !dropped_file_callback) {
        /* Detach first: the plugin must not be left holding it. */
        ui->uiHost(nullptr);
        std::lock_guard lock(g_host_mutex);
        /* The entry is kept rather than erased, and only its callbacks are dropped.
           Detaching stops calls that have not started, but a menu request is made with
           the plugin's lock released -- it has to be, since it blocks the renderer until
           the host answers -- so one can still be inside this object as we return.
           Destroying it here would pull it out from under that call. Keeping it costs one
           small object per instance that has ever shown an editor, and the slot is reused
           when the editor opens again. */
        if (auto found = g_hosts.find(ui); found != g_hosts.end() && found->second) {
            auto& host = *found->second;
            host.menu_cb = nullptr;
            host.cursor_cb = nullptr;
            host.dropped_cb = nullptr;
            host.user_data = nullptr;
        }
        return;
    }
    std::lock_guard lock(g_host_mutex);
    auto& slot = g_hosts[ui];
    if (!slot)
        slot = std::make_unique<CFramebufferUIHost>();
    slot->menu_cb = menu_callback;
    slot->cursor_cb = cursor_callback;
    slot->dropped_cb = dropped_file_callback;
    slot->user_data = user_data;
    ui->uiHost(slot.get());
}

void uapmd_instance_fbui_complete_menu(uapmd_fbui_menu_request_t request, int32_t chosen_item) {
    auto* raw = reinterpret_cast<MenuRequest*>(request);
    if (!raw)
        return;
    std::shared_ptr<MenuRequest> held;
    {
        std::lock_guard lock(g_menu_mutex);
        auto it = g_menus.find(raw);
        if (it == g_menus.end())
            return;
        held = it->second;
        g_menus.erase(it);
    }
    /* "Exactly once" is the contract, and a host that answers twice would
       otherwise release a waiter that has already moved on. */
    std::call_once(held->answered, [&] { held->completed(chosen_item); });
}
