/* uapmd C API — implementation for remidy-tooling bindings */

#include "c-api/uapmd-c-tooling.h"
#include <uapmd-plugin-hosting/uapmd-plugin-hosting.hpp>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>

/* ── Cast helpers ─────────────────────────────────────────────────────────── */

static uapmd_plugin_hosting::PluginScanTool*   PST(uapmd_scan_tool_t h) { return reinterpret_cast<uapmd_plugin_hosting::PluginScanTool*>(h); }
static uapmd_plugin_hosting::PluginInstancing* PI(uapmd_plugin_instancing_t h) { return reinterpret_cast<uapmd_plugin_hosting::PluginInstancing*>(h); }
static uapmd_plugin_hosting::PluginFormatManager* PFM(uapmd_format_manager_t h) { return reinterpret_cast<uapmd_plugin_hosting::PluginFormatManager*>(h); }

/* ── copy_string ──────────────────────────────────────────────────────────── */

static size_t copy_string(const std::string& src, char* buf, size_t buf_size) {
    if (!buf || buf_size == 0)
        return src.size() + 1;
    size_t n = src.size() < buf_size - 1 ? src.size() : buf_size - 1;
    std::memcpy(buf, src.data(), n);
    buf[n] = '\0';
    return n;
}

/* ── Ownership registries ─────────────────────────────────────────────────── */

static std::mutex s_scan_tool_mutex;
static std::unordered_map<uapmd_plugin_hosting::PluginScanTool*, std::unique_ptr<uapmd_plugin_hosting::PluginScanTool>> s_owned_scan_tools;

static std::mutex s_instancing_mutex;
static std::unordered_map<uapmd_plugin_hosting::PluginInstancing*, std::unique_ptr<uapmd_plugin_hosting::PluginInstancing>> s_owned_instancings;

static std::mutex s_fmt_mgr_mutex;
static std::unordered_map<uapmd_plugin_hosting::PluginFormatManager*, std::unique_ptr<uapmd_plugin_hosting::PluginFormatManager>> s_owned_fmt_mgrs;

/* ── Thread-local storage ─────────────────────────────────────────────────── */

static thread_local std::vector<remidy::PluginFormat*> tl_formats;
static thread_local std::vector<uapmd_plugin_hosting::BlocklistEntry> tl_blocklist;

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginScanTool
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_scan_tool_t uapmd_scan_tool_create() {
    auto tool = uapmd_plugin_hosting::PluginScanTool::create();
    auto raw = tool.get();
    std::lock_guard lock(s_scan_tool_mutex);
    s_owned_scan_tools[raw] = std::move(tool);
    return reinterpret_cast<uapmd_scan_tool_t>(raw);
}

void uapmd_scan_tool_destroy(uapmd_scan_tool_t tool) {
    if (!tool) return;
    std::lock_guard lock(s_scan_tool_mutex);
    s_owned_scan_tools.erase(PST(tool));
}

uint32_t uapmd_scan_tool_catalog_entry_count(uapmd_scan_tool_t tool) {
    return static_cast<uint32_t>(PST(tool)->catalog().getPlugins().size());
}

uint32_t uapmd_scan_tool_format_count(uapmd_scan_tool_t tool) {
    tl_formats = PST(tool)->formats();
    return static_cast<uint32_t>(tl_formats.size());
}

size_t uapmd_scan_tool_get_format_name(uapmd_scan_tool_t tool, uint32_t index, char* buf, size_t buf_size) {
    if (tl_formats.empty())
        tl_formats = PST(tool)->formats();
    if (index >= tl_formats.size())
        return 0;
    return copy_string(tl_formats[index]->name(), buf, buf_size);
}

size_t uapmd_scan_tool_get_cache_file(uapmd_scan_tool_t tool, char* buf, size_t buf_size) {
    return copy_string(PST(tool)->pluginListCacheFile().string(), buf, buf_size);
}

void uapmd_scan_tool_set_cache_file(uapmd_scan_tool_t tool, const char* path) {
    PST(tool)->pluginListCacheFile() = path ? path : "";
}

void uapmd_scan_tool_save_cache(uapmd_scan_tool_t tool) {
    PST(tool)->savePluginListCache();
}

void uapmd_scan_tool_save_cache_to(uapmd_scan_tool_t tool, const char* path) {
    auto p = std::filesystem::path(path ? path : "");
    PST(tool)->savePluginListCache(p);
}

#ifdef __EMSCRIPTEN__

/* ── Asynchronous scanning (browser build) ─────────────────────────────────
 *
 * uapmd's InProcessScanSessionManager waits on a condition variable until each
 * bundle scan reports completion. In the browser, the WebCLAP scanner delivers
 * that completion through the JS event loop of the very thread that issued the
 * scan, so waiting on the main thread deadlocks the whole page (the UI never
 * repaints and the scan never finishes).
 *
 * Here we run the same per-bundle scanning loop as a continuation chain that
 * returns to the event loop between bundles, so the scan progresses and the UI
 * stays responsive. Observer callbacks are invoked on the main thread, which is
 * where the caller registered them.
 */

namespace {

struct AsyncScanBundle {
    remidy::FileOrUrlBasedPluginScanning* scanning;
    std::string format_name;
    std::filesystem::path path;
};

struct AsyncScanSession {
    uapmd_plugin_hosting::PluginScanTool* tool{};
    /* Copied by value: the caller may release its own struct once this returns. */
    uapmd_scan_observer_t observer{};
    bool has_observer{};
    bool require_fast_scanning{};
    std::vector<AsyncScanBundle> bundles{};
    size_t index{};
};

void async_scan_notify_error(const std::shared_ptr<AsyncScanSession>& session, const std::string& message) {
    if (session->has_observer && session->observer.error_occurred)
        session->observer.error_occurred(message.c_str(), session->observer.user_data);
}

void async_scan_finish(const std::shared_ptr<AsyncScanSession>& session) {
    if (session->has_observer && session->observer.slow_scan_completed)
        session->observer.slow_scan_completed(session->observer.user_data);
}

void async_scan_merge(const std::shared_ptr<AsyncScanSession>& session,
                      std::vector<remidy::PluginCatalogEntry>& results) {
    auto& catalog = session->tool->catalog();
    for (auto& entry : results) {
        if (!catalog.contains(entry.format(), entry.pluginId()))
            catalog.add(std::move(entry));
    }
}

void async_scan_step(std::shared_ptr<AsyncScanSession> session) {
    if (session->has_observer && session->observer.should_cancel &&
        session->observer.should_cancel(session->observer.user_data)) {
        async_scan_notify_error(session, "Scan canceled.");
        async_scan_finish(session);
        return;
    }
    if (session->index >= session->bundles.size()) {
        async_scan_finish(session);
        return;
    }

    auto& bundle = session->bundles[session->index];
    auto* scanning = bundle.scanning;
    auto path = bundle.path;

    if (session->has_observer && session->observer.bundle_scan_started)
        session->observer.bundle_scan_started(path.string().c_str(), session->observer.user_data);

    auto results = std::make_shared<std::vector<remidy::PluginCatalogEntry>>();
    scanning->scanBundle(path, session->require_fast_scanning, 0.0,
        [results](remidy::PluginCatalogEntry entry) {
            results->emplace_back(std::move(entry));
        },
        [session, results, path](std::string error) {
            /* A failing bundle is reported but does not abort the remaining ones:
               there is no caller left to catch an exception on the async chain. */
            if (!error.empty())
                async_scan_notify_error(session, path.string() + ": " + error);
            async_scan_merge(session, *results);
            if (session->has_observer && session->observer.bundle_scan_completed)
                session->observer.bundle_scan_completed(path.string().c_str(), session->observer.user_data);
            session->index++;
            async_scan_step(session);
        });
}

void perform_scanning_async(uapmd_scan_tool_t tool,
                            bool require_fast_scanning,
                            const uapmd_scan_observer_t* observer) {
    auto session = std::make_shared<AsyncScanSession>();
    session->tool = PST(tool);
    session->require_fast_scanning = require_fast_scanning;
    if (observer) {
        session->observer = *observer;
        session->has_observer = true;
    }

    auto* scan_tool = session->tool;
    auto& catalog = scan_tool->catalog();

    /* Seed the catalog from the plugin list cache, as the shared scan planner does. */
    auto& cache_file = scan_tool->pluginListCacheFile();
    if (!cache_file.empty() && std::filesystem::exists(cache_file)) {
        remidy::PluginCatalog cached;
        cached.load(cache_file);
        for (auto* entry : cached.getPlugins())
            if (entry && !catalog.contains(entry->format(), entry->pluginId()))
                catalog.add(*entry);
    }

    for (auto* format : scan_tool->formats()) {
        if (!format)
            continue;
        auto* scanning = format->scanning();
        if (!scanning)
            continue;

        auto fast_results = scanning->getAllFastScannablePlugins();
        async_scan_merge(session, fast_results);

        if (require_fast_scanning || !scanning->scanningMayBeSlow())
            continue;
        auto* file_scanning = dynamic_cast<remidy::FileOrUrlBasedPluginScanning*>(scanning);
        if (!file_scanning) {
            async_scan_notify_error(session,
                "Format " + format->name() + " reports slow scanning but does not implement FileOrUrlBasedPluginScanning.");
            continue;
        }
        /* Already covered by the cache: nothing to re-fetch. */
        if (!scan_tool->filterByFormat(catalog.getPlugins(), format->name()).empty())
            continue;

        for (auto& bundle : file_scanning->enumerateCandidateBundles(require_fast_scanning)) {
            if (scan_tool->isBundleBlocklisted(format->name(), bundle))
                continue;
            if (!scanning->scanRequiresLoadLibrary(bundle))
                continue;
            session->bundles.emplace_back(AsyncScanBundle{file_scanning, format->name(), bundle});
        }
    }

    if (session->has_observer && session->observer.slow_scan_started && !session->bundles.empty())
        session->observer.slow_scan_started(static_cast<uint32_t>(session->bundles.size()),
                                            session->observer.user_data);

    async_scan_step(session);
}

} // namespace

#endif /* __EMSCRIPTEN__ */

void uapmd_scan_tool_perform_scanning(uapmd_scan_tool_t tool,
                                       bool require_fast_scanning,
                                       const uapmd_scan_observer_t* observer) {
#ifdef __EMSCRIPTEN__
    /* The browser build must not block the thread that delivers scan results. */
    perform_scanning_async(tool, require_fast_scanning, observer);
#else
    auto* obs = new uapmd_plugin_hosting::PluginScanObserver{};
    void* ud = observer ? observer->user_data : nullptr;

    if (observer && observer->slow_scan_started)
        obs->slowScanStarted = [cb = observer->slow_scan_started, ud](uint32_t total) { cb(total, ud); };
    if (observer && observer->bundle_scan_started)
        obs->bundleScanStarted = [cb = observer->bundle_scan_started, ud](const std::filesystem::path& p) { cb(p.string().c_str(), ud); };
    if (observer && observer->bundle_scan_completed)
        obs->bundleScanCompleted = [cb = observer->bundle_scan_completed, ud](const std::filesystem::path& p) { cb(p.string().c_str(), ud); };
    if (observer && observer->error_occurred)
        obs->errorOccurred = [cb = observer->error_occurred, ud](const std::string& msg) { cb(msg.c_str(), ud); };
    if (observer && observer->should_cancel)
        obs->shouldCancel = [cb = observer->should_cancel, ud]() -> bool { return cb(ud); };

    /* obs is heap-allocated and self-deletes once the scan reports completion. */
    auto slow_complete_cb = observer ? observer->slow_scan_completed : nullptr;
    obs->slowScanCompleted = [obs, slow_complete_cb, ud]() {
        if (slow_complete_cb) slow_complete_cb(ud);
        delete obs;
    };

    PST(tool)->performPluginScanning(require_fast_scanning,
        uapmd_plugin_hosting::ScanMode::InProcess, false, 0.0, obs);
#endif
}

uint32_t uapmd_scan_tool_blocklist_count(uapmd_scan_tool_t tool) {
    tl_blocklist = PST(tool)->blocklistEntries();
    return static_cast<uint32_t>(tl_blocklist.size());
}

bool uapmd_scan_tool_get_blocklist_entry(uapmd_scan_tool_t tool, uint32_t index, uapmd_blocklist_entry_t* out) {
    if (tl_blocklist.empty())
        tl_blocklist = PST(tool)->blocklistEntries();
    if (index >= tl_blocklist.size()) return false;
    auto& e = tl_blocklist[index];
    out->id = e.id.c_str();
    out->format = e.format.c_str();
    out->plugin_id = e.pluginId.c_str();
    out->reason = e.reason.c_str();
    return true;
}

void uapmd_scan_tool_flush_blocklist(uapmd_scan_tool_t tool) { PST(tool)->flushBlocklist(); }

bool uapmd_scan_tool_unblock_bundle(uapmd_scan_tool_t tool, const char* entry_id) {
    return PST(tool)->unblockBundle(entry_id ? entry_id : "");
}

void uapmd_scan_tool_clear_blocklist(uapmd_scan_tool_t tool) { PST(tool)->clearBlocklist(); }

void uapmd_scan_tool_add_to_blocklist(uapmd_scan_tool_t tool,
                                       const char* format_name,
                                       const char* plugin_id,
                                       const char* reason) {
    PST(tool)->addToBlocklist(format_name ? format_name : "",
                              plugin_id ? plugin_id : "",
                              reason ? reason : "");
}

size_t uapmd_scan_tool_last_scan_error(uapmd_scan_tool_t tool, char* buf, size_t buf_size) {
    return copy_string(PST(tool)->lastScanError(), buf, buf_size);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginInstancing
 * ═══════════════════════════════════════════════════════════════════════════ */

uapmd_plugin_instancing_t uapmd_instancing_create(uapmd_scan_tool_t tool,
                                                    const char* format,
                                                    const char* plugin_id) {
    auto inst = std::make_unique<uapmd_plugin_hosting::PluginInstancing>(
        *PST(tool), format ? format : "", plugin_id ? plugin_id : "");
    auto raw = inst.get();
    std::lock_guard lock(s_instancing_mutex);
    s_owned_instancings[raw] = std::move(inst);
    return reinterpret_cast<uapmd_plugin_instancing_t>(raw);
}

void uapmd_instancing_destroy(uapmd_plugin_instancing_t inst) {
    if (!inst) return;
    std::lock_guard lock(s_instancing_mutex);
    s_owned_instancings.erase(PI(inst));
}

void uapmd_instancing_make_alive(uapmd_plugin_instancing_t inst,
                                  void* user_data,
                                  uapmd_instancing_cb_t callback) {
    PI(inst)->makeAlive([callback, user_data](std::string error) {
        if (callback)
            callback(error.empty() ? nullptr : error.c_str(), user_data);
    });
}

uapmd_instancing_state_t uapmd_instancing_state(uapmd_plugin_instancing_t inst) {
    return static_cast<uapmd_instancing_state_t>(PI(inst)->instancingState().load());
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  PluginFormatManager
 * ═══════════════════════════════════════════════════════════════════════════ */

static thread_local std::vector<remidy::PluginFormat*> tl_mgr_formats;

uapmd_format_manager_t uapmd_format_manager_create() {
    auto mgr = std::make_unique<uapmd_plugin_hosting::PluginFormatManager>();
    auto raw = mgr.get();
    std::lock_guard lock(s_fmt_mgr_mutex);
    s_owned_fmt_mgrs[raw] = std::move(mgr);
    return reinterpret_cast<uapmd_format_manager_t>(raw);
}

void uapmd_format_manager_destroy(uapmd_format_manager_t mgr) {
    if (!mgr) return;
    std::lock_guard lock(s_fmt_mgr_mutex);
    s_owned_fmt_mgrs.erase(PFM(mgr));
}

uint32_t uapmd_format_manager_format_count(uapmd_format_manager_t mgr) {
    tl_mgr_formats = PFM(mgr)->formats();
    return static_cast<uint32_t>(tl_mgr_formats.size());
}

size_t uapmd_format_manager_get_format_name(uapmd_format_manager_t mgr, uint32_t index,
                                              char* buf, size_t buf_size) {
    if (tl_mgr_formats.empty())
        tl_mgr_formats = PFM(mgr)->formats();
    if (index >= tl_mgr_formats.size())
        return 0;
    return copy_string(tl_mgr_formats[index]->name(), buf, buf_size);
}
