/* uapmd C API — implementation for the uapmd-addin-core bindings (uapmd 0.5.6) */

#include "c-api/uapmd-c-addin.h"
#include <uapmd-addin-core/uapmd-addin-core.hpp>
#include <uapmd-engine/uapmd-engine.hpp>
#include <uapmd-data/uapmd-data.hpp>
#include <cstring>
#include <deque>
#include <memory>
#include <string>
#include <vector>

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

/* ═══════════════════════════════════════════════════════════════════════════
 *  Host registries
 *
 *  The extension point paths are the ones uapmd's own addins ask for; they are
 *  part of the addin contract rather than a host choice, so they are spelled
 *  here rather than taken as parameters.
 * ═══════════════════════════════════════════════════════════════════════════ */

namespace {
constexpr const char* kCommandPath       = "/uapmd/app/command/v1";
constexpr const char* kClipCommandPath   = "/uapmd/app/clip-command/v1";
constexpr const char* kClipEditorPath    = "/uapmd/app/timeline/clip-editor/v1";
constexpr const char* kStemSeparatorPath = "/uapmd/audio-import/stem-separator/v1";

uapmd_addin::CommandRegistry* CR(uapmd_command_registry_t h) {
    return reinterpret_cast<uapmd_addin::CommandRegistry*>(h);
}
uapmd_addin::ClipCommandRegistry* CCR(uapmd_clip_command_registry_t h) {
    return reinterpret_cast<uapmd_addin::ClipCommandRegistry*>(h);
}
uapmd_addin::ClipEditorRegistry* CER(uapmd_clip_editor_registry_t h) {
    return reinterpret_cast<uapmd_addin::ClipEditorRegistry*>(h);
}
uapmd::import::StemSeparatorRegistry* SSR(uapmd_stem_separator_registry_t h) {
    return reinterpret_cast<uapmd::import::StemSeparatorRegistry*>(h);
}

/* The registries hand out std::string_view, which is not required to be
 * NUL-terminated, so every string this API returns has to be materialised. */
thread_local std::string tl_cmd_id;
thread_local std::string tl_cmd_title;
thread_local std::string tl_editor_id;
thread_local std::string tl_editor_name;
thread_local std::string tl_sep_id;
thread_local std::string tl_sep_name;
thread_local std::string tl_sep_model_label;

uapmd::import::StemSeparator* separator_at(uapmd_stem_separator_registry_t reg, uint32_t index) {
    if (!reg) return nullptr;
    auto list = SSR(reg)->separators();
    return index < list.size() ? list[index] : nullptr;
}

uapmd_addin::ClipCommandTarget to_cpp_target(uapmd_clip_command_target_t t) {
    return { t.track_index, t.clip_id, t.midi_clip, t.master_track };
}
} // namespace

/* ── CommandRegistry ─────────────────────────────────────────────────────── */

uapmd_command_registry_t uapmd_command_registry_create(void) {
    return reinterpret_cast<uapmd_command_registry_t>(new uapmd_addin::CommandRegistry());
}

void uapmd_command_registry_destroy(uapmd_command_registry_t reg) { delete CR(reg); }

void uapmd_addin_manager_register_command_registry(uapmd_addin_manager_t mgr, uapmd_command_registry_t reg) {
    if (!mgr || !reg) return;
    AM(mgr)->registerExtensionPoint(kCommandPath, CR(reg));
}

uint32_t uapmd_command_registry_count(uapmd_command_registry_t reg) {
    return reg ? static_cast<uint32_t>(CR(reg)->commands().size()) : 0;
}

bool uapmd_command_registry_get(uapmd_command_registry_t reg, uint32_t index, uapmd_addin_command_info_t* out) {
    if (!reg || !out) return false;
    auto commands = CR(reg)->commands();
    if (index >= commands.size()) return false;
    auto* c = commands[index];
    tl_cmd_id = c->id();
    tl_cmd_title = c->title();
    out->id = tl_cmd_id.c_str();
    out->title = tl_cmd_title.c_str();
    out->order = c->order();
    out->enabled = c->enabled();
    return true;
}

bool uapmd_command_registry_invoke(uapmd_command_registry_t reg, uint32_t index) {
    if (!reg) return false;
    auto commands = CR(reg)->commands();
    if (index >= commands.size()) return false;
    commands[index]->invoke();
    return true;
}

bool uapmd_command_registry_invoke_by_id(uapmd_command_registry_t reg, const char* id) {
    if (!reg || !id) return false;
    for (auto* c : CR(reg)->commands()) {
        if (c->id() == id) {
            c->invoke();
            return true;
        }
    }
    return false;
}

/* ── ClipCommandRegistry ─────────────────────────────────────────────────── */

uapmd_clip_command_registry_t uapmd_clip_command_registry_create(void) {
    return reinterpret_cast<uapmd_clip_command_registry_t>(new uapmd_addin::ClipCommandRegistry());
}

void uapmd_clip_command_registry_destroy(uapmd_clip_command_registry_t reg) { delete CCR(reg); }

void uapmd_addin_manager_register_clip_command_registry(uapmd_addin_manager_t mgr, uapmd_clip_command_registry_t reg) {
    if (!mgr || !reg) return;
    AM(mgr)->registerExtensionPoint(kClipCommandPath, CCR(reg));
}

uint32_t uapmd_clip_command_registry_count(uapmd_clip_command_registry_t reg) {
    return reg ? static_cast<uint32_t>(CCR(reg)->commands().size()) : 0;
}

bool uapmd_clip_command_registry_get(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_addin_command_info_t* out) {
    if (!reg || !out) return false;
    auto commands = CCR(reg)->commands();
    if (index >= commands.size()) return false;
    auto* c = commands[index];
    tl_cmd_id = c->id();
    tl_cmd_title = c->title();
    out->id = tl_cmd_id.c_str();
    out->title = tl_cmd_title.c_str();
    out->order = c->order();
    out->enabled = true;
    return true;
}

bool uapmd_clip_command_registry_applies_to(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target) {
    if (!reg) return false;
    auto commands = CCR(reg)->commands();
    if (index >= commands.size()) return false;
    return commands[index]->appliesTo(to_cpp_target(target));
}

bool uapmd_clip_command_registry_enabled(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target) {
    if (!reg) return false;
    auto commands = CCR(reg)->commands();
    if (index >= commands.size()) return false;
    return commands[index]->enabled(to_cpp_target(target));
}

bool uapmd_clip_command_registry_invoke(uapmd_clip_command_registry_t reg, uint32_t index, uapmd_clip_command_target_t target) {
    if (!reg) return false;
    auto commands = CCR(reg)->commands();
    if (index >= commands.size()) return false;
    commands[index]->invoke(to_cpp_target(target));
    return true;
}

/* ── ClipEditorRegistry ──────────────────────────────────────────────────── */

uapmd_clip_editor_registry_t uapmd_clip_editor_registry_create(void) {
    return reinterpret_cast<uapmd_clip_editor_registry_t>(new uapmd_addin::ClipEditorRegistry());
}

void uapmd_clip_editor_registry_destroy(uapmd_clip_editor_registry_t reg) { delete CER(reg); }

void uapmd_addin_manager_register_clip_editor_registry(uapmd_addin_manager_t mgr, uapmd_clip_editor_registry_t reg) {
    if (!mgr || !reg) return;
    AM(mgr)->registerExtensionPoint(kClipEditorPath, CER(reg));
}

uint32_t uapmd_clip_editor_registry_count(uapmd_clip_editor_registry_t reg) {
    return reg ? static_cast<uint32_t>(CER(reg)->editors().size()) : 0;
}

bool uapmd_clip_editor_registry_get(uapmd_clip_editor_registry_t reg, uint32_t index, uapmd_clip_editor_info_t* out) {
    if (!reg || !out) return false;
    auto editors = CER(reg)->editors();
    if (index >= editors.size()) return false;
    tl_editor_id = editors[index]->id();
    tl_editor_name = editors[index]->name();
    out->id = tl_editor_id.c_str();
    out->name = tl_editor_name.c_str();
    return true;
}

/* ── StemSeparatorRegistry ───────────────────────────────────────────────── */

uapmd_stem_separator_registry_t uapmd_stem_separator_registry_create(void) {
    return reinterpret_cast<uapmd_stem_separator_registry_t>(new uapmd::import::StemSeparatorRegistry());
}

void uapmd_stem_separator_registry_destroy(uapmd_stem_separator_registry_t reg) { delete SSR(reg); }

void uapmd_addin_manager_register_stem_separator_registry(uapmd_addin_manager_t mgr, uapmd_stem_separator_registry_t reg) {
    if (!mgr || !reg) return;
    AM(mgr)->registerExtensionPoint(kStemSeparatorPath, SSR(reg));
}

uint32_t uapmd_stem_separator_registry_count(uapmd_stem_separator_registry_t reg) {
    return reg ? static_cast<uint32_t>(SSR(reg)->separators().size()) : 0;
}

bool uapmd_stem_separator_registry_get(uapmd_stem_separator_registry_t reg, uint32_t index, uapmd_stem_separator_info_t* out) {
    if (!out) return false;
    auto* sep = separator_at(reg, index);
    if (!sep) return false;
    auto spec = sep->modelFileSpec();
    tl_sep_id = sep->id();
    tl_sep_name = sep->name();
    tl_sep_model_label = spec.label;
    out->id = tl_sep_id.c_str();
    out->name = tl_sep_name.c_str();
    out->model_file_label = tl_sep_model_label.c_str();
    out->model_file_extension_count = static_cast<uint32_t>(spec.extensions.size());
    return true;
}

size_t uapmd_stem_separator_registry_get_model_extension(uapmd_stem_separator_registry_t reg, uint32_t index, uint32_t extension_index, char* buf, size_t buf_size) {
    auto* sep = separator_at(reg, index);
    if (!sep) return 0;
    auto spec = sep->modelFileSpec();
    if (extension_index >= spec.extensions.size()) return 0;
    return copy_string(spec.extensions[extension_index], buf, buf_size);
}

/* ── Audio import ────────────────────────────────────────────────────────── */

namespace {
/* The result borrows from these, so they outlive the call and are replaced by
 * the next one on this thread. */
thread_local std::string tl_import_error;
thread_local std::deque<std::string> tl_import_strings;
thread_local std::vector<const char*> tl_import_warnings;
thread_local std::vector<uapmd_audio_stem_import_t> tl_import_stems;

const char* intern_import_string(const std::string& s) {
    tl_import_strings.push_back(s);
    return tl_import_strings.back().c_str();
}
} // namespace

uapmd_audio_import_result_t uapmd_import_audio_file(uapmd_stem_separator_registry_t reg,
                                                      const char* separator_id,
                                                      const char* filepath,
                                                      const char* output_directory,
                                                      const char* model_path,
                                                      void* user_data,
                                                      uapmd_import_progress_cb_t progress) {
    tl_import_error.clear();
    tl_import_strings.clear();
    tl_import_warnings.clear();
    tl_import_stems.clear();

    auto fail = [](const char* message) {
        tl_import_error = message;
        return uapmd_audio_import_result_t{ false, false, tl_import_error.c_str(), 0, nullptr, 0, nullptr };
    };

    if (!reg || !separator_id)
        return fail("no stem separator was selected");
    if (!filepath)
        return fail("no audio file was given");

    /* The lease keeps the contributing addin's separator alive for the whole
     * run; withdrawing it (by disabling the addin) cancels the run instead of
     * unloading the code underneath it. */
    auto lease = SSR(reg)->acquire(separator_id);
    if (!lease)
        return fail("the selected stem separator is no longer available");

    uapmd::import::AudioImportResult result;
    {
        uapmd::import::TrackImporter::AudioImportOptions options;
        options.separator = lease.get();
        if (model_path) options.modelPath = model_path;
        if (output_directory) options.outputDirectory = output_directory;

        /* The progress callback doubles as the cancel signal, matching the
         * shape uapmd itself uses: returning false from it means stop. */
        auto canceled = std::make_shared<bool>(false);
        if (progress) {
            options.progressCallback = [progress, user_data, canceled](float value, const std::string& message) {
                if (!progress(value, message.c_str(), user_data))
                    *canceled = true;
            };
        }
        options.shouldCancel = [canceled, &lease]() {
            return *canceled || lease.withdrawn();
        };

        result = uapmd::import::TrackImporter::importAudioFile(filepath, options);
    }
    lease.release();

    tl_import_error = result.error;
    for (const auto& w : result.warnings)
        tl_import_warnings.push_back(intern_import_string(w));
    for (const auto& stem : result.stems) {
        tl_import_stems.push_back({
            intern_import_string(stem.stemName),
            intern_import_string(stem.filepath.string()),
            intern_import_string(stem.clipDisplayName)
        });
    }

    return {
        result.success,
        result.canceled,
        tl_import_error.empty() ? nullptr : tl_import_error.c_str(),
        static_cast<uint32_t>(tl_import_warnings.size()),
        tl_import_warnings.empty() ? nullptr : tl_import_warnings.data(),
        static_cast<uint32_t>(tl_import_stems.size()),
        tl_import_stems.empty() ? nullptr : tl_import_stems.data()
    };
}
