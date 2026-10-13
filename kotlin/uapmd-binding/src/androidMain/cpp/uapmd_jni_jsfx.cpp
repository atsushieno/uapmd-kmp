/**
 * uapmd JNI bridge — JSFX (c-api/uapmd-c-jsfx.h), the catalog refresh, and the
 * document provider's reads, picks and folders (c-api/uapmd-c-file.h).
 *
 * Document handles cross as flat String[] triples {id, displayName, mimeType}.
 * Completion objects declare invoke() with the exact descriptor resolved here,
 * as AndroidAppModel.kt explains.
 */

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>

#include "c-api/uapmd-c-common.h"
#include "c-api/uapmd-c-app.h"
#include "c-api/uapmd-c-file.h"
#include "c-api/uapmd-c-jsfx.h"

/* Provided by uapmd_jni.cpp (C++ linkage, matching uapmd_jni_history.cpp). */
extern JNIEnv* uapmd_jni_env();

namespace {

template <typename T>
inline T j2p(jlong handle) { return reinterpret_cast<T>(static_cast<uintptr_t>(handle)); }

template <typename T>
inline jlong p2j(T ptr) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(ptr)); }

/** Borrows a jstring's UTF-8 for the duration of a call; null becomes "". */
struct JStr {
    JNIEnv* env;
    jstring str;
    const char* chars;
    JStr(JNIEnv* e, jstring s)
        : env(e), str(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (str && chars) env->ReleaseStringUTFChars(str, chars); }
    JStr(const JStr&) = delete;
    JStr& operator=(const JStr&) = delete;
    const char* c_str() const { return chars ? chars : ""; }
};

inline uapmd_app_model_t AM(jlong h) { return j2p<uapmd_app_model_t>(h); }
inline uapmd_document_provider_t DP(jlong h) { return j2p<uapmd_document_provider_t>(h); }
inline uapmd_jsfx_plugin_format_t JF(jlong h) { return j2p<uapmd_jsfx_plugin_format_t>(h); }

inline jstring jstr(JNIEnv* env, const char* s) { return env->NewStringUTF(s ? s : ""); }
inline jstring jstr_or_null(JNIEnv* env, const char* s) { return s ? env->NewStringUTF(s) : nullptr; }

/** A completion object held across the asynchronous call. Fires once. */
struct Ctx {
    jobject obj{nullptr};
    jmethodID mid{nullptr};

    Ctx(JNIEnv* env, jobject o, const char* signature)
        : obj(env->NewGlobalRef(o)),
          mid(env->GetMethodID(env->GetObjectClass(o), "invoke", signature)) {
        if (!mid) {
            env->ExceptionClear();
            if (jclass err = env->FindClass("java/lang/NoSuchMethodError"))
                env->ThrowNew(err, signature);
        }
    }

    ~Ctx() {
        if (obj) {
            JNIEnv* e = uapmd_jni_env();
            if (e) e->DeleteGlobalRef(obj);
        }
    }
};

jobjectArray handles_to_java(JNIEnv* env, const uapmd_document_handle_t* handles, uint32_t count) {
    jclass stringClass = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(static_cast<jsize>(count) * 3, stringClass, nullptr);
    for (uint32_t i = 0; i < count; i++) {
        const auto& h = handles[i];
        jstring parts[3] = {jstr(env, h.id), jstr(env, h.display_name), jstr(env, h.mime_type)};
        for (int k = 0; k < 3; k++) {
            env->SetObjectArrayElement(array, static_cast<jsize>(i * 3 + k), parts[k]);
            env->DeleteLocalRef(parts[k]);
        }
    }
    env->DeleteLocalRef(stringClass);
    return array;
}

std::vector<std::string> strings_from_java(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    if (!array) return result;
    const jsize n = env->GetArrayLength(array);
    result.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        {
            JStr chars(env, s);
            result.emplace_back(chars.c_str());
        }
        if (s) env->DeleteLocalRef(s);
    }
    return result;
}

std::vector<const char*> c_strs(const std::vector<std::string>& strings) {
    std::vector<const char*> result;
    result.reserve(strings.size());
    for (auto& s : strings) result.push_back(s.c_str());
    return result;
}

/** cb: invoke(success: Boolean, handles: Array<String?>, error: String?) */
void pick_trampoline(uapmd_document_pick_result_t result, void* ud) {
    auto* ctx = static_cast<Ctx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        auto handles = handles_to_java(e, result.handles, result.handle_count);
        auto error = jstr_or_null(e, result.error);
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jboolean>(result.success), handles, error);
        if (e->ExceptionCheck()) e->ExceptionClear();
        e->DeleteLocalRef(handles);
        if (error) e->DeleteLocalRef(error);
    }
    delete ctx;
}

/** cb: invoke(success: Boolean, error: String?, data: ByteArray) */
void read_trampoline(uapmd_document_io_result_t result, const uint8_t* data, size_t size, void* ud) {
    auto* ctx = static_cast<Ctx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        auto bytes = e->NewByteArray(static_cast<jsize>(data ? size : 0));
        if (data && size > 0)
            e->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
        auto error = jstr_or_null(e, result.error);
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jboolean>(result.success), error, bytes);
        if (e->ExceptionCheck()) e->ExceptionClear();
        e->DeleteLocalRef(bytes);
        if (error) e->DeleteLocalRef(error);
    }
    delete ctx;
}

/** cb: invoke(success, path, documents: Array<String?>, token, displayName, error: String?) */
void folder_trampoline(const uapmd_folder_pick_result_t* result, void* ud) {
    auto* ctx = static_cast<Ctx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid && result) {
        auto path = jstr(e, result->path);
        auto documents = handles_to_java(e, result->documents, result->document_count);
        auto token = jstr(e, result->token);
        auto name = jstr(e, result->display_name);
        auto error = jstr_or_null(e, result->error);
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jboolean>(result->success),
                          path, documents, token, name, error);
        if (e->ExceptionCheck()) e->ExceptionClear();
        e->DeleteLocalRef(path);
        e->DeleteLocalRef(documents);
        e->DeleteLocalRef(token);
        e->DeleteLocalRef(name);
        if (error) e->DeleteLocalRef(error);
    }
    delete ctx;
}

/** cb: invoke(error: String?) */
void error_trampoline(const char* error, void* ud) {
    auto* ctx = static_cast<Ctx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        auto err = jstr_or_null(e, error);
        e->CallVoidMethod(ctx->obj, ctx->mid, err);
        if (e->ExceptionCheck()) e->ExceptionClear();
        if (err) e->DeleteLocalRef(err);
    }
    delete ctx;
}

/** cb: invoke(filesWritten: Int, error: String?) */
void folder_sync_trampoline(uint32_t written, const char* error, void* ud) {
    auto* ctx = static_cast<Ctx*>(ud);
    if (!ctx) return;
    JNIEnv* e = uapmd_jni_env();
    if (e && ctx->mid) {
        auto err = jstr_or_null(e, error);
        e->CallVoidMethod(ctx->obj, ctx->mid, static_cast<jint>(written), err);
        if (e->ExceptionCheck()) e->ExceptionClear();
        if (err) e->DeleteLocalRef(err);
    }
    delete ctx;
}

constexpr const char* kPickSig = "(Z[Ljava/lang/String;Ljava/lang/String;)V";
constexpr const char* kReadSig = "(ZLjava/lang/String;[B)V";
constexpr const char* kFolderSig =
        "(ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
constexpr const char* kErrorSig = "(Ljava/lang/String;)V";
constexpr const char* kFolderSyncSig = "(ILjava/lang/String;)V";

/** Returns null when the constructor left a NoSuchMethodError pending. */
Ctx* make_ctx(JNIEnv* env, jobject cb, const char* signature) {
    auto* ctx = new Ctx(env, cb, signature);
    if (!ctx->mid) {
        delete ctx;
        return nullptr;
    }
    return ctx;
}

/** {id, displayName, mimeType}; the strings are owned by the caller's JStrs. */
struct HandleArg {
    JStr id, name, mime;
    HandleArg(JNIEnv* env, jstring i, jstring n, jstring m) : id(env, i), name(env, n), mime(env, m) {}
    uapmd_document_handle_t get() const { return {id.c_str(), name.c_str(), mime.c_str()}; }
};

/** Fills outStrings[0..1] = {destination, error}; returns {success, filesWritten}. */
jintArray import_result(JNIEnv* env, const uapmd_jsfx_import_result_t& r, jobjectArray outStrings) {
    if (outStrings && env->GetArrayLength(outStrings) >= 2) {
        auto destination = jstr(env, r.destination);
        auto error = jstr(env, r.error);
        env->SetObjectArrayElement(outStrings, 0, destination);
        env->SetObjectArrayElement(outStrings, 1, error);
        env->DeleteLocalRef(destination);
        env->DeleteLocalRef(error);
    }
    jint values[2] = {r.success ? 1 : 0, static_cast<jint>(r.files_written)};
    auto array = env->NewIntArray(2);
    env->SetIntArrayRegion(array, 0, 2, values);
    return array;
}

} // namespace

extern "C" {

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_atsushieno_uapmd_JniBridge_##name

/* ═══════════════════════════════════════════════════════════════════════════
 *  Document provider
 * ═══════════════════════════════════════════════════════════════════════════ */

/** labels[i], mimeTypes[i] and extensions[i] describe filter i. */
JNI_FN(void, uapmdDocumentProviderPickOpen)(JNIEnv* env, jclass, jlong provider,
                                             jobjectArray labels, jobjectArray mimeTypes,
                                             jobjectArray extensions, jboolean allowMultiple,
                                             jobject cb) {
    auto* ctx = make_ctx(env, cb, kPickSig);
    if (!ctx) return;
    auto labelStrings = strings_from_java(env, labels);
    std::vector<std::vector<std::string>> mimeStrings, extStrings;
    std::vector<std::vector<const char*>> mimePtrs, extPtrs;
    for (size_t i = 0; i < labelStrings.size(); i++) {
        auto m = static_cast<jobjectArray>(env->GetObjectArrayElement(mimeTypes, static_cast<jsize>(i)));
        auto x = static_cast<jobjectArray>(env->GetObjectArrayElement(extensions, static_cast<jsize>(i)));
        mimeStrings.push_back(strings_from_java(env, m));
        extStrings.push_back(strings_from_java(env, x));
        if (m) env->DeleteLocalRef(m);
        if (x) env->DeleteLocalRef(x);
    }
    std::vector<uapmd_document_filter_t> filters;
    for (size_t i = 0; i < labelStrings.size(); i++) {
        mimePtrs.push_back(c_strs(mimeStrings[i]));
        extPtrs.push_back(c_strs(extStrings[i]));
    }
    for (size_t i = 0; i < labelStrings.size(); i++)
        filters.push_back({labelStrings[i].c_str(),
                           mimePtrs[i].data(), static_cast<uint32_t>(mimePtrs[i].size()),
                           extPtrs[i].data(), static_cast<uint32_t>(extPtrs[i].size())});
    uapmd_document_provider_pick_open(DP(provider), filters.data(), static_cast<uint32_t>(filters.size()),
                                      allowMultiple, ctx, pick_trampoline);
}

JNI_FN(void, uapmdDocumentProviderRead)(JNIEnv* env, jclass, jlong provider,
                                         jstring id, jstring displayName, jstring mimeType, jobject cb) {
    auto* ctx = make_ctx(env, cb, kReadSig);
    if (!ctx) return;
    HandleArg handle(env, id, displayName, mimeType);
    uapmd_document_provider_read(DP(provider), handle.get(), ctx, read_trampoline);
}

JNI_FN(void, uapmdDocumentProviderPickFolder)(JNIEnv* env, jclass, jlong provider, jobject cb) {
    auto* ctx = make_ctx(env, cb, kFolderSig);
    if (!ctx) return;
    uapmd_document_provider_pick_folder(DP(provider), ctx, folder_trampoline);
}

JNI_FN(jboolean, uapmdDocumentProviderFolderPathsAreUsable)(JNIEnv*, jclass, jlong provider) {
    return uapmd_document_provider_folder_paths_are_usable(DP(provider));
}

JNI_FN(void, uapmdDocumentProviderListFolderDocuments)(JNIEnv* env, jclass, jlong provider,
                                                        jstring token, jobject cb) {
    auto* ctx = make_ctx(env, cb, kFolderSig);
    if (!ctx) return;
    JStr t(env, token);
    uapmd_document_provider_list_folder_documents(DP(provider), t.c_str(), ctx, folder_trampoline);
}

JNI_FN(void, uapmdDocumentProviderReleaseFolder)(JNIEnv* env, jclass, jlong provider, jstring token) {
    JStr t(env, token);
    uapmd_document_provider_release_folder(DP(provider), t.c_str());
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  AppModel
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jlong, uapmdAppPluginScanTool)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_plugin_scan_tool(AM(app)));
}

/** cb may be null. */
JNI_FN(void, uapmdAppRefreshFastScannedPlugins)(JNIEnv* env, jclass, jlong app, jobject cb) {
    if (!cb) {
        uapmd_app_refresh_fast_scanned_plugins(AM(app), nullptr, nullptr);
        return;
    }
    auto* ctx = make_ctx(env, cb, kErrorSig);
    if (!ctx) return;
    uapmd_app_refresh_fast_scanned_plugins(AM(app), ctx, error_trampoline);
}

JNI_FN(jlong, uapmdAppJsfxPluginFormat)(JNIEnv*, jclass, jlong app) {
    return p2j(uapmd_app_jsfx_plugin_format(AM(app)));
}

JNI_FN(void, uapmdAppSyncJsfxRegisteredFolders)(JNIEnv* env, jclass, jlong app, jobject cb) {
    auto* ctx = make_ctx(env, cb, kFolderSyncSig);
    if (!ctx) return;
    uapmd_app_sync_jsfx_registered_folders(AM(app), ctx, folder_sync_trampoline);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  JSFX
 * ═══════════════════════════════════════════════════════════════════════════ */

JNI_FN(jboolean, uapmdJsfxAvailable)(JNIEnv*, jclass) { return uapmd_jsfx_available(); }

JNI_FN(void, uapmdJsfxSetEditorFontData)(JNIEnv* env, jclass, jbyteArray data) {
    if (!data) {
        uapmd_jsfx_set_editor_font_data(nullptr, 0);
        return;
    }
    const jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> bytes(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    uapmd_jsfx_set_editor_font_data(bytes.data(), bytes.size());
}

JNI_FN(jboolean, uapmdJsfxUsesOwnFontBackend)(JNIEnv*, jclass) { return uapmd_jsfx_uses_own_font_backend(); }

JNI_FN(jstring, uapmdJsfxUserContentDirectory)(JNIEnv* env, jclass) {
    return jstr(env, uapmd_jsfx_user_content_directory());
}

JNI_FN(jboolean, uapmdJsfxLooksLikeArchive)(JNIEnv* env, jclass, jstring fileName) {
    JStr f(env, fileName);
    return uapmd_jsfx_looks_like_archive(f.c_str());
}

/** Fills outStrings[0..1] = {destination, error}; returns {success, filesWritten}. */
JNI_FN(jintArray, uapmdJsfxImportContent)(JNIEnv* env, jclass, jstring fileName, jbyteArray data,
                                           jstring subdirectory, jobjectArray outStrings) {
    JStr f(env, fileName);
    JStr sub(env, subdirectory);
    const jsize n = data ? env->GetArrayLength(data) : 0;
    std::vector<uint8_t> bytes(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    uapmd_jsfx_import_result_t r{};
    uapmd_jsfx_import_content(f.c_str(), bytes.data(), bytes.size(), sub.c_str(), &r);
    return import_result(env, r, outStrings);
}

JNI_FN(jstring, uapmdJsfxFolderMirrorRoot)(JNIEnv* env, jclass) {
    return jstr(env, uapmd_jsfx_folder_mirror_root());
}

/** Flat {token, name} pairs. */
JNI_FN(jobjectArray, uapmdJsfxRegisteredFolders)(JNIEnv* env, jclass) {
    const uint32_t count = uapmd_jsfx_registered_folder_count();
    jclass stringClass = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(static_cast<jsize>(count) * 2, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    for (uint32_t i = 0; i < count; i++) {
        uapmd_jsfx_registered_folder_t folder{};
        if (!uapmd_jsfx_get_registered_folder(i, &folder)) continue;
        auto token = jstr(env, folder.token);
        auto name = jstr(env, folder.name);
        env->SetObjectArrayElement(array, static_cast<jsize>(i * 2), token);
        env->SetObjectArrayElement(array, static_cast<jsize>(i * 2 + 1), name);
        env->DeleteLocalRef(token);
        env->DeleteLocalRef(name);
    }
    return array;
}

JNI_FN(jstring, uapmdJsfxRegisterFolder)(JNIEnv* env, jclass, jstring token, jstring name) {
    JStr t(env, token);
    JStr n(env, name);
    return jstr(env, uapmd_jsfx_register_folder(t.c_str(), n.c_str()));
}

JNI_FN(void, uapmdJsfxUnregisterFolder)(JNIEnv* env, jclass, jstring token) {
    JStr t(env, token);
    uapmd_jsfx_unregister_folder(t.c_str());
}

JNI_FN(jboolean, uapmdJsfxClearFolderMirror)(JNIEnv* env, jclass, jstring name) {
    JStr n(env, name);
    return uapmd_jsfx_clear_folder_mirror(n.c_str());
}

/** Fills outStrings[0..1] = {destination, error}; returns {success, filesWritten}. */
JNI_FN(jintArray, uapmdJsfxWriteFolderMirrorFile)(JNIEnv* env, jclass, jstring name, jstring relativePath,
                                                   jbyteArray data, jobjectArray outStrings) {
    JStr n(env, name);
    JStr rel(env, relativePath);
    const jsize size = data ? env->GetArrayLength(data) : 0;
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    if (size > 0) env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    uapmd_jsfx_import_result_t r{};
    uapmd_jsfx_write_folder_mirror_file(n.c_str(), rel.c_str(), bytes.data(), bytes.size(), &r);
    return import_result(env, r, outStrings);
}

JNI_FN(void, uapmdJsfxFormatSetSearchPaths)(JNIEnv* env, jclass, jlong format, jobjectArray paths,
                                             jboolean alsoUseDefaults) {
    auto strings = strings_from_java(env, paths);
    auto ptrs = c_strs(strings);
    uapmd_jsfx_format_set_search_paths(JF(format), ptrs.data(), static_cast<uint32_t>(ptrs.size()),
                                       alsoUseDefaults);
}

JNI_FN(jobjectArray, uapmdJsfxFormatSearchPaths)(JNIEnv* env, jclass, jlong format) {
    const uint32_t count = uapmd_jsfx_format_search_path_count(JF(format));
    jclass stringClass = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    for (uint32_t i = 0; i < count; i++) {
        auto s = jstr(env, uapmd_jsfx_format_get_search_path(JF(format), i));
        env->SetObjectArrayElement(array, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return array;
}

JNI_FN(jboolean, uapmdJsfxFormatUseDefaultSearchPaths)(JNIEnv*, jclass, jlong format) {
    return uapmd_jsfx_format_use_default_search_paths(JF(format));
}

JNI_FN(jobjectArray, uapmdJsfxFormatDefaultSearchPaths)(JNIEnv* env, jclass, jlong format) {
    const uint32_t count = uapmd_jsfx_format_default_search_path_count(JF(format));
    jclass stringClass = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    for (uint32_t i = 0; i < count; i++) {
        auto s = jstr(env, uapmd_jsfx_format_get_default_search_path(JF(format), i));
        env->SetObjectArrayElement(array, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return array;
}

JNI_FN(jint, uapmdJsfxFormatDuplicateListing)(JNIEnv*, jclass, jlong format) {
    return static_cast<jint>(uapmd_jsfx_format_duplicate_listing(JF(format)));
}

JNI_FN(void, uapmdJsfxFormatSetDuplicateListing)(JNIEnv*, jclass, jlong format, jint value) {
    uapmd_jsfx_format_set_duplicate_listing(JF(format), static_cast<uapmd_jsfx_duplicate_listing_t>(value));
}

} // extern "C"
