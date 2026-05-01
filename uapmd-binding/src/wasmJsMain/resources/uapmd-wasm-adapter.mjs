/**
 * uapmd-wasm-adapter.mjs
 *
 * JavaScript adapter that bridges:
 *   K/WasmJs Kotlin code  ↔  Emscripten uapmd-c-api Wasm module
 *
 * Initialization sequence (consumer app):
 *
 *   import UapmdCApi from './uapmd-c-api.js';
 *   import { setUapmdModule, setKotlinDispatchers } from './uapmd-wasm-adapter.mjs';
 *
 *   // 1. Initialize Emscripten module
 *   const cMod = await UapmdCApi();
 *   setUapmdModule(cMod);
 *
 *   // 2. Initialize K/WasmJs module and pass Kotlin dispatch functions
 *   import { default: initKt, ...ktExports } from './uapmd-binding.mjs';
 *   await initKt();
 *   setKotlinDispatchers(ktExports);
 */

// ── State ─────────────────────────────────────────────────────────────────────
let _mod = null;          // Initialized Emscripten module
let _ktDisp = null;       // Object with Kotlin @JsExport dispatch functions

// Callback registry: id → JS function (stored to prevent GC)
const _callbacks = new Map();
let _nextCbId = 1;

// ── Module management ─────────────────────────────────────────────────────────

export function setUapmdModule(mod) {
    _mod = mod;
}

export function getUapmdModule() {
    return _mod;
}

/**
 * Pass the Kotlin/Wasm module's exported dispatch functions so that C-side
 * callbacks can call back into Kotlin.
 *
 * @param {Object} dispatchers  Object with the @JsExport Kotlin functions:
 *   { uapmdDispatchCreateInstance, uapmdDispatchAddPlugin, uapmdDispatchMakeAlive,
 *     uapmdDispatchEventOutput, uapmdDispatchMidiInput,
 *     uapmdDispatchScanSlowStart, uapmdDispatchScanBundleStart,
 *     uapmdDispatchScanBundleComplete, uapmdDispatchScanSlowComplete,
 *     uapmdDispatchScanError, uapmdDispatchScanCancel,
 *     uapmdDispatchRenderProgress, uapmdDispatchRenderCancel,
 *     uapmdDispatchRequestState, uapmdDispatchLoadState,
 *     uapmdDispatchWindowResize }
 */
export function setKotlinDispatchers(dispatchers) {
    _ktDisp = dispatchers;
}

export function isReady() {
    return _mod !== null;
}

// ── String helpers ────────────────────────────────────────────────────────────

/**
 * Call a C function of the form  size_t fn(handle, char* buf, size_t buf_size)
 * and return the resulting UTF-8 string.
 */
export function readCStringFromHandle(fn, handle) {
    const size = fn(handle, 0, 0);
    if (size === 0) return "";
    const ptr = _mod._malloc(size);
    try {
        fn(handle, ptr, size);
        return _mod.UTF8ToString(ptr, size - 1);
    } finally {
        _mod._free(ptr);
    }
}

/**
 * Call a C function of the form  size_t fn(handle, uint32_t index, char* buf, size_t)
 * and return the resulting UTF-8 string.
 */
export function readCStringFromHandleIndex(fn, handle, index) {
    const size = fn(handle, index, 0, 0);
    if (size === 0) return "";
    const ptr = _mod._malloc(size);
    try {
        fn(handle, index, ptr, size);
        return _mod.UTF8ToString(ptr, size - 1);
    } finally {
        _mod._free(ptr);
    }
}

/**
 * Allocate a temporary C string, call callback(ptr), then free.
 */
export function withCString(str, callback) {
    if (str === null || str === undefined) return callback(0);
    const len = _mod.lengthBytesUTF8(str) + 1;
    const ptr = _mod._malloc(len);
    try {
        _mod.stringToUTF8(str, ptr, len);
        return callback(ptr);
    } finally {
        _mod._free(ptr);
    }
}

export function withTwoCStrings(s1, s2, callback) {
    return withCString(s1, ptr1 => withCString(s2, ptr2 => callback(ptr1, ptr2)));
}

export function withThreeCStrings(s1, s2, s3, callback) {
    return withCString(s1, p1 => withCString(s2, p2 => withCString(s3, p3 => callback(p1, p2, p3))));
}

// ── Memory / struct helpers ───────────────────────────────────────────────────

function getI32(ptr)  { return _mod.getValue(ptr, 'i32'); }
function getU32(ptr)  { return _mod.getValue(ptr, 'i32') >>> 0; }
function getI8(ptr)   { return _mod.getValue(ptr, 'i8'); }
function getBool(ptr) { return getI8(ptr) !== 0; }
function getF64(ptr)  { return _mod.getValue(ptr, 'double'); }
function getPtr(ptr)  { return _mod.getValue(ptr, 'i32') >>> 0; }  // 32-bit Wasm pointer

/** Read a Wasm i64 as a JS Number (safe for values < 2^53). */
function getI64(ptr) {
    const lo = _mod.getValue(ptr,     'i32') >>> 0;   // low 32 bits as unsigned
    const hi = _mod.getValue(ptr + 4, 'i32');          // high 32 bits as signed
    return hi * 4294967296 + lo;
}

function getCString(ptr) {
    if (ptr === 0) return "";
    return _mod.UTF8ToString(ptr);
}

// ── Struct readers ────────────────────────────────────────────────────────────

/**
 * uapmd_timeline_position_t  { int64_t samples; double legacy_beats; }
 * Size: 16 bytes
 */
function readTimelinePosition(ptr) {
    return {
        samples:      getI64(ptr),
        legacyBeats:  getF64(ptr + 8),
    };
}

/**
 * uapmd_timeline_state_t
 * Size: 80 bytes
 * Layout (Wasm32):
 *   +0   timeline_position playhead (16)
 *   +16  bool is_playing, bool loop_enabled (1+1)
 *   +18  [6 bytes padding]
 *   +24  timeline_position loop_start (16)
 *   +40  timeline_position loop_end (16)
 *   +56  double tempo (8)
 *   +64  i32 time_sig_num, i32 time_sig_den (4+4)
 *   +72  i32 sample_rate (4)
 */
export function readTimelineState(ptr) {
    return {
        playheadPosition:           readTimelinePosition(ptr),
        isPlaying:                  getBool(ptr + 16),
        loopEnabled:                getBool(ptr + 17),
        loopStart:                  readTimelinePosition(ptr + 24),
        loopEnd:                    readTimelinePosition(ptr + 40),
        tempo:                      getF64(ptr + 56),
        timeSignatureNumerator:     getI32(ptr + 64),
        timeSignatureDenominator:   getI32(ptr + 68),
        sampleRate:                 getI32(ptr + 72),
    };
}

/**
 * uapmd_audio_file_properties_t  { uint64_t num_frames; uint32_t num_channels; uint32_t sample_rate; }
 * Size: 16 bytes
 */
export function readAudioFileProperties(ptr) {
    return {
        numFrames:   getI64(ptr),
        numChannels: getU32(ptr + 8),
        sampleRate:  getU32(ptr + 12),
    };
}

/**
 * uapmd_audio_device_info_t
 * { enum directions(i32); i32 id; const char* name; uint32_t sample_rate; uint32_t channels; }
 * Size: 20 bytes
 */
export function readAudioDeviceInfo(ptr) {
    return {
        directions: getI32(ptr),
        id:         getI32(ptr + 4),
        name:       getCString(getPtr(ptr + 8)),
        sampleRate: getU32(ptr + 12),
        channels:   getU32(ptr + 16),
    };
}

/**
 * uapmd_parameter_metadata_t  (size 56 bytes in Wasm32):
 *   +0   uint32_t index
 *   +4   const char* stable_id
 *   +8   const char* name
 *   +12  const char* path
 *   +16  double default_plain_value
 *   +24  double min_plain_value
 *   +32  double max_plain_value
 *   +40  bool automatable, bool hidden, bool discrete
 *   +44  uint32_t named_values_count
 *   +48  const uapmd_parameter_named_value_t* named_values
 */
export function readParameterMetadata(ptr) {
    const namedCount = getU32(ptr + 44);
    const namedPtr   = getPtr(ptr + 48);
    const namedValues = [];
    // uapmd_parameter_named_value_t: { double value (+0); const char* name (+8); } stride 16
    for (let i = 0; i < namedCount; i++) {
        const base = namedPtr + i * 16;
        namedValues.push({ value: getF64(base), name: getCString(getPtr(base + 8)) });
    }
    return {
        index:             getU32(ptr),
        stableId:          getCString(getPtr(ptr + 4)),
        name:              getCString(getPtr(ptr + 8)),
        path:              getCString(getPtr(ptr + 12)),
        defaultPlainValue: getF64(ptr + 16),
        minPlainValue:     getF64(ptr + 24),
        maxPlainValue:     getF64(ptr + 32),
        automatable:       getBool(ptr + 40),
        hidden:            getBool(ptr + 41),
        discrete:          getBool(ptr + 42),
        namedValues,
    };
}

/**
 * uapmd_preset_metadata_t  (size 20 bytes):
 *   +0  uint8_t bank
 *   +4  uint32_t index  (aligned to 4)
 *   +8  const char* stable_id
 *   +12 const char* name
 *   +16 const char* path
 */
export function readPresetMetadata(ptr) {
    return {
        bank:     getU32(ptr) & 0xFF,       // uint8_t, read as i8
        index:    getU32(ptr + 4),
        stableId: getCString(getPtr(ptr + 8)),
        name:     getCString(getPtr(ptr + 12)),
        path:     getCString(getPtr(ptr + 16)),
    };
}

/**
 * uapmd_blocklist_entry_t  { const char* id; format; plugin_id; reason; }
 * Size: 16 bytes
 */
export function readBlocklistEntry(ptr) {
    return {
        id:       getCString(getPtr(ptr)),
        format:   getCString(getPtr(ptr + 4)),
        pluginId: getCString(getPtr(ptr + 8)),
        reason:   getCString(getPtr(ptr + 12)),
    };
}

/** Allocate struct-sized heap memory, call cb(ptr), free, return cb result. */
export function withStruct(size, cb) {
    const ptr = _mod._malloc(size);
    try {
        return cb(ptr);
    } finally {
        _mod._free(ptr);
    }
}

// ── Callback management ───────────────────────────────────────────────────────

/**
 * Register an opaque callback object and return a stable integer ID.
 * The stored JS object is kept alive (preventing GC) until unregistered.
 */
export function registerCallback(obj) {
    const id = _nextCbId++;
    _callbacks.set(id, obj);
    return id;
}

export function unregisterCallback(id) {
    _callbacks.delete(id);
}

/**
 * Create a native function pointer (Emscripten table entry) that, when
 * called from C, calls the Kotlin dispatch function identified by dispatchName
 * with the given cbId prepended to the arguments.
 *
 * @param {number}   cbId         - Callback registry ID (passed as first arg to dispatcher)
 * @param {string}   dispatchName - Name of the Kotlin @JsExport dispatcher
 * @param {string}   sig          - Emscripten function signature string (e.g. "viiii")
 * @returns {number} Function table index (use as C function pointer)
 */
export function makeCFunctionPtr(cbId, dispatchName, sig) {
    if (!_ktDisp || typeof _ktDisp[dispatchName] !== 'function') {
        console.warn(`uapmd-wasm-adapter: Kotlin dispatcher '${dispatchName}' not set; callback will be ignored`);
        const noop = function() {};
        return _mod.addFunction(noop, sig);
    }
    const dispatcher = _ktDisp[dispatchName];
    const fn = function(...args) { return dispatcher(cbId, ...args); };
    return _mod.addFunction(fn, sig);
}

export function removeCFunctionPtr(ptr) {
    if (_mod && ptr !== 0) _mod.removeFunction(ptr);
}

// ── uapmd_instance_request_state / load_state helpers ────────────────────────
// These callbacks deliver byte buffers (state data).

/**
 * Make a C function pointer for a state callback:
 *   void cb(const uint8_t* data, size_t size, const char* error, void* ctx)
 * Delivers data as a Uint8Array to the Kotlin dispatcher.
 */
export function makeStateCallbackPtr(cbId, dispatchName) {
    if (!_ktDisp || typeof _ktDisp[dispatchName] !== 'function') {
        return _mod.addFunction(function() {}, 'viiii');
    }
    const dispatcher = _ktDisp[dispatchName];
    const fn = function(dataPtr, size, errorPtr) {
        let data = null;
        if (dataPtr !== 0 && size > 0) {
            data = new Uint8Array(_mod.HEAPU8.buffer, dataPtr, size).slice();
        }
        const error = errorPtr !== 0 ? _mod.UTF8ToString(errorPtr) : null;
        dispatcher(cbId, data, error);
    };
    return _mod.addFunction(fn, 'viii');
}
