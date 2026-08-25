/* uapmd C API — implementation for the uapmd-addin-core bindings (uapmd 0.5.6) */

#include "c-api/uapmd-c-addin.h"
#include <uapmd-addin-core/uapmd-addin-core.hpp>
#include <uapmd-engine/uapmd-engine.hpp>
#include <cstring>
#include <string>

/* ── Cast helpers ─────────────────────────────────────────────────────────── */

static uapmd_addin::AddinManager* AM(uapmd_addin_manager_t h) {
    return reinterpret_cast<uapmd_addin::AddinManager*>(h);
}
static uapmd::SequencerEngine* E(uapmd_sequencer_engine_t h) {
    return reinterpret_cast<uapmd::SequencerEngine*>(h);
}

static size_t copy_string(const std::string& src, char* buf, size_t buf_size) {
    size_t required = src.size() + 1;
    if (!buf || buf_size == 0)
        return required;
    size_t to_copy = (src.size() < buf_size) ? src.size() : (buf_size - 1);
    std::memcpy(buf, src.data(), to_copy);
    buf[to_copy] = '\0';
    return to_copy;
}

/* AddinInfo::library_path is a std::filesystem::path, so it is the one member
 * that has to be materialised as a string rather than pointed at in place. */
static thread_local std::string tl_library_path;

/* ═══════════════════════════════════════════════════════════════════════════
 *  AddinManager
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_addin_manager_t uapmd_addin_manager_create(void) {
    return reinterpret_cast<uapmd_addin_manager_t>(new uapmd_addin::AddinManager());
}

void uapmd_addin_manager_destroy(uapmd_addin_manager_t mgr) {
    delete AM(mgr);
}

void uapmd_addin_manager_register_extension_point(uapmd_addin_manager_t mgr,
                                                    const char* path,
                                                    void* extension_point) {
    if (!mgr || !path) return;
    AM(mgr)->registerExtensionPoint(path, extension_point);
}

void uapmd_engine_register_addin_extension_points(uapmd_sequencer_engine_t engine,
                                                    uapmd_addin_manager_t mgr) {
    if (!engine || !mgr) return;
    E(engine)->registerAddinExtensionPoints(*AM(mgr));
}

void uapmd_addin_manager_initialize(uapmd_addin_manager_t mgr) {
    if (mgr) AM(mgr)->initialize();
}

bool uapmd_addin_manager_set_enabled(uapmd_addin_manager_t mgr,
                                       const char* package_id,
                                       const char* addin_id,
                                       bool enabled) {
    if (!mgr) return false;
    return AM(mgr)->setEnabled(package_id ? package_id : "", addin_id ? addin_id : "", enabled);
}

void uapmd_addin_manager_shutdown(uapmd_addin_manager_t mgr) {
    if (mgr) AM(mgr)->shutdown();
}

uint32_t uapmd_addin_manager_directory_count(uapmd_addin_manager_t mgr) {
    return mgr ? static_cast<uint32_t>(AM(mgr)->addinDirectories().size()) : 0;
}

size_t uapmd_addin_manager_get_directory(uapmd_addin_manager_t mgr, uint32_t index, char* buf, size_t buf_size) {
    if (!mgr) return 0;
    const auto& dirs = AM(mgr)->addinDirectories();
    if (index >= dirs.size()) return 0;
    return copy_string(dirs[index].string(), buf, buf_size);
}

uint32_t uapmd_addin_manager_addin_count(uapmd_addin_manager_t mgr) {
    return mgr ? static_cast<uint32_t>(AM(mgr)->addins().size()) : 0;
}

bool uapmd_addin_manager_get_addin(uapmd_addin_manager_t mgr, uint32_t index, uapmd_addin_info_t* out) {
    if (!mgr || !out) return false;
    const auto& addins = AM(mgr)->addins();
    if (index >= addins.size()) return false;
    const auto& info = addins[index];
    tl_library_path = info.library_path.string();
    out->package_id = info.package_id.c_str();
    out->addin_id = info.addin_id.c_str();
    out->name = info.name.c_str();
    out->path = info.path.c_str();
    out->library_path = tl_library_path.c_str();
    out->built_in = info.built_in;
    out->state = static_cast<uapmd_addin_state_t>(info.state);
    out->message = info.message.c_str();
    return true;
}

size_t uapmd_addin_manager_last_error(uapmd_addin_manager_t mgr, char* buf, size_t buf_size) {
    if (!mgr) return 0;
    return copy_string(AM(mgr)->lastError(), buf, buf_size);
}

bool uapmd_addin_supports_dynamic_loading(void) {
    return uapmd_addin::AddinManager::supportsDynamicLoading();
}

const char* uapmd_addin_state_name(uapmd_addin_state_t state) {
    return uapmd_addin::addinStateName(static_cast<uapmd_addin::AddinState>(state));
}
