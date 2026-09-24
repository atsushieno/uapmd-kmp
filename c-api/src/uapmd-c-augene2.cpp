/* uapmd C API — implementation for the uapmd-augene2 bindings */

#include "c-api/uapmd-c-augene2.h"
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#if UAPMD_HAS_AUGENE2
#include <uapmd-addin-core/uapmd-addin-core.hpp>
#include <uapmd-engine/uapmd-engine.hpp>
#include <uapmd-augene2/uapmd-augene2.hpp>

namespace {

size_t copy_string(const std::string& src, char* buf, size_t buf_size) {
    size_t required = src.size() + 1;
    if (!buf || buf_size == 0)
        return required;
    size_t to_copy = (src.size() < buf_size) ? src.size() : (buf_size - 1);
    std::memcpy(buf, src.data(), to_copy);
    buf[to_copy] = '\0';
    return to_copy;
}

/* The handle is a weak reference: the service belongs to the application, which
 * releases it before the timeline goes away, and a binding holding it must not
 * stretch its lifetime past that. */
using Handle = std::weak_ptr<uapmd_augene2::Integration>;

std::shared_ptr<uapmd_augene2::Integration> lock(uapmd_augene2_integration_t h) {
    return h ? reinterpret_cast<Handle*>(h)->lock() : nullptr;
}

thread_local uapmd_augene2::IntegrationSource tl_source;
thread_local uapmd_augene2::IntegrationTrackMapping tl_mapping;

}

bool uapmd_augene2_available(void) { return true; }

void uapmd_augene2_register_project_service(uapmd_timeline_facade_t timeline, uapmd_panel_registry_t panels) {
    if (!timeline || !panels)
        return;
    uapmd_augene2::registerProjectService(*reinterpret_cast<uapmd::TimelineFacade*>(timeline),
                                          *reinterpret_cast<uapmd_addin::PanelRegistry*>(panels));
}

uapmd_augene2_integration_t uapmd_augene2_integration(void) {
    auto integration = uapmd_augene2::integration();
    if (!integration)
        return nullptr;
    return reinterpret_cast<uapmd_augene2_integration_t>(new Handle(integration));
}

void uapmd_augene2_integration_release(uapmd_augene2_integration_t integration) {
    delete reinterpret_cast<Handle*>(integration);
}

bool uapmd_augene2_integration_is_open(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i && i->isOpen();
}

void uapmd_augene2_integration_set_open(uapmd_augene2_integration_t integration, bool open) {
    if (auto i = lock(integration))
        i->setOpen(open);
}

bool uapmd_augene2_integration_busy(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i && i->busy();
}

bool uapmd_augene2_integration_compiling(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i && i->compiling();
}

uint32_t uapmd_augene2_integration_source_count(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i ? static_cast<uint32_t>(i->sources().size()) : 0;
}

bool uapmd_augene2_integration_get_source(uapmd_augene2_integration_t integration, uint32_t index,
                                           uapmd_augene2_source_t* out) {
    auto i = lock(integration);
    if (!i || !out)
        return false;
    auto sources = i->sources();
    if (index >= sources.size())
        return false;
    tl_source = std::move(sources[index]);
    out->path = tl_source.path.c_str();
    out->external_path = tl_source.external_path.c_str();
    out->compile = tl_source.compile;
    return true;
}

uint32_t uapmd_augene2_integration_track_mapping_count(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i ? static_cast<uint32_t>(i->trackMappings().size()) : 0;
}

bool uapmd_augene2_integration_get_track_mapping(uapmd_augene2_integration_t integration, uint32_t index,
                                                  uapmd_augene2_track_mapping_t* out) {
    auto i = lock(integration);
    if (!i || !out)
        return false;
    auto mappings = i->trackMappings();
    if (index >= mappings.size())
        return false;
    tl_mapping = std::move(mappings[index]);
    out->key = tl_mapping.key.c_str();
    out->track_index = tl_mapping.track_index;
    return true;
}

size_t uapmd_augene2_integration_status(uapmd_augene2_integration_t integration, char* buf, size_t buf_size) {
    auto i = lock(integration);
    return copy_string(i ? i->status() : std::string{}, buf, buf_size);
}

uint32_t uapmd_augene2_integration_diagnostic_count(uapmd_augene2_integration_t integration) {
    auto i = lock(integration);
    return i ? static_cast<uint32_t>(i->diagnostics().size()) : 0;
}

size_t uapmd_augene2_integration_get_diagnostic(uapmd_augene2_integration_t integration, uint32_t index,
                                                 char* buf, size_t buf_size) {
    auto i = lock(integration);
    if (!i)
        return copy_string({}, buf, buf_size);
    auto diagnostics = i->diagnostics();
    return copy_string(index < diagnostics.size() ? diagnostics[index] : std::string{}, buf, buf_size);
}

size_t uapmd_augene2_integration_resource_folder(uapmd_augene2_integration_t integration, char* buf, size_t buf_size) {
    auto i = lock(integration);
    return copy_string(i ? i->resourceFolder() : std::string{}, buf, buf_size);
}

void uapmd_augene2_integration_set_resource_folder(uapmd_augene2_integration_t integration, const char* folder) {
    if (auto i = lock(integration))
        i->setResourceFolder(folder ? folder : "");
}

void uapmd_augene2_integration_import_sources(uapmd_augene2_integration_t integration, bool compile) {
    if (auto i = lock(integration))
        i->importSources(compile);
}

void uapmd_augene2_integration_relink_source(uapmd_augene2_integration_t integration, const char* path) {
    if (auto i = lock(integration); i && path)
        i->relinkSource(path);
}

void uapmd_augene2_integration_remove_source(uapmd_augene2_integration_t integration, const char* path) {
    if (auto i = lock(integration); i && path)
        i->removeSource(path);
}

void uapmd_augene2_integration_compile(uapmd_augene2_integration_t integration) {
    if (auto i = lock(integration))
        i->compile();
}

#else /* !UAPMD_HAS_AUGENE2 */

static size_t empty_string(char* buf, size_t buf_size) {
    if (buf && buf_size > 0)
        buf[0] = '\0';
    return buf ? 0 : 1;
}

bool uapmd_augene2_available(void) { return false; }
void uapmd_augene2_register_project_service(uapmd_timeline_facade_t, uapmd_panel_registry_t) {}
uapmd_augene2_integration_t uapmd_augene2_integration(void) { return nullptr; }
void uapmd_augene2_integration_release(uapmd_augene2_integration_t) {}
bool uapmd_augene2_integration_is_open(uapmd_augene2_integration_t) { return false; }
void uapmd_augene2_integration_set_open(uapmd_augene2_integration_t, bool) {}
bool uapmd_augene2_integration_busy(uapmd_augene2_integration_t) { return false; }
bool uapmd_augene2_integration_compiling(uapmd_augene2_integration_t) { return false; }
uint32_t uapmd_augene2_integration_source_count(uapmd_augene2_integration_t) { return 0; }
bool uapmd_augene2_integration_get_source(uapmd_augene2_integration_t, uint32_t, uapmd_augene2_source_t*) { return false; }
uint32_t uapmd_augene2_integration_track_mapping_count(uapmd_augene2_integration_t) { return 0; }
bool uapmd_augene2_integration_get_track_mapping(uapmd_augene2_integration_t, uint32_t, uapmd_augene2_track_mapping_t*) { return false; }
size_t uapmd_augene2_integration_status(uapmd_augene2_integration_t, char* buf, size_t buf_size) { return empty_string(buf, buf_size); }
uint32_t uapmd_augene2_integration_diagnostic_count(uapmd_augene2_integration_t) { return 0; }
size_t uapmd_augene2_integration_get_diagnostic(uapmd_augene2_integration_t, uint32_t, char* buf, size_t buf_size) { return empty_string(buf, buf_size); }
size_t uapmd_augene2_integration_resource_folder(uapmd_augene2_integration_t, char* buf, size_t buf_size) { return empty_string(buf, buf_size); }
void uapmd_augene2_integration_set_resource_folder(uapmd_augene2_integration_t, const char*) {}
void uapmd_augene2_integration_import_sources(uapmd_augene2_integration_t, bool) {}
void uapmd_augene2_integration_relink_source(uapmd_augene2_integration_t, const char*) {}
void uapmd_augene2_integration_remove_source(uapmd_augene2_integration_t, const char*) {}
void uapmd_augene2_integration_compile(uapmd_augene2_integration_t) {}

#endif
