/**
 * uapmd-wasm-adapter.mjs
 *
 * JavaScript adapter that bridges:
 *   K/WasmJs Kotlin code  <->  Emscripten uapmd-c-api Wasm module
 */

let _mod = null;
let _ktDisp = null;

const _callbacks = new Map();
let _nextCbId = 1;

export function setUapmdModule(mod) {
    _mod = mod;
}

export function getUapmdModule() {
    return _mod;
}

export function setKotlinDispatchers(dispatchers) {
    _ktDisp = dispatchers;
}

/**
 * Creates the directories uapmd expects under /browser (the plugin list cache and
 * the upload staging area) and, when IDBFS is available, mounts it there and loads
 * whatever was persisted by an earlier session. uapmd writes the plugin list cache
 * to /browser/remidy-tooling/plugin-list-cache.json and calls FS.syncfs() after
 * saving it, so mounting IDBFS is what makes a scan survive a page reload.
 *
 * Returns a Promise that resolves to true when persisted content was loaded.
 */
export function initBrowserFileSystem() {
    return new Promise(function (resolve) {
        if (!_mod || !_mod.FS) {
            resolve(false);
            return;
        }
        const FS = _mod.FS;
        const ensureDir = function (path) {
            try {
                if (!FS.analyzePath(path).exists) FS.mkdir(path);
            } catch (err) {
                console.warn('[uapmd] Failed to create directory:', path, err);
            }
        };
        const ensureAppDirs = function () {
            ensureDir('/browser/uploads');
            ensureDir('/browser/remidy-tooling');
        };

        ensureDir('/browser');

        const idbfs = _mod.IDBFS || (typeof IDBFS !== 'undefined' ? IDBFS : null);
        if (!idbfs) {
            // No IDBFS in this build: the app still works, the cache just lives in memory.
            ensureAppDirs();
            resolve(false);
            return;
        }
        try {
            FS.mount(idbfs, {}, '/browser');
        } catch (err) {
            console.warn('[uapmd] IDBFS mount failed; plugin cache will not persist:', err);
            ensureAppDirs();
            resolve(false);
            return;
        }
        FS.syncfs(true, function (err) {
            if (err)
                console.warn('[uapmd] Failed to load persisted browser filesystem:', err);
            // The mount replaced the mountpoint, so (re)create the directories in it.
            ensureAppDirs();
            resolve(!err);
        });
    });
}

export function isReady() {
    return _mod !== null;
}

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

function getI32(ptr) { return _mod.getValue(ptr, 'i32'); }
function getU32(ptr) { return _mod.getValue(ptr, 'i32') >>> 0; }
function getI8(ptr) { return _mod.getValue(ptr, 'i8'); }
function getBool(ptr) { return getI8(ptr) !== 0; }
function getF64(ptr) { return _mod.getValue(ptr, 'double'); }
function getPtr(ptr) { return _mod.getValue(ptr, 'i32') >>> 0; }

function getI64(ptr) {
    const lo = _mod.getValue(ptr, 'i32') >>> 0;
    const hi = _mod.getValue(ptr + 4, 'i32');
    return hi * 4294967296 + lo;
}

function getCString(ptr) {
    if (ptr === 0) return "";
    return _mod.UTF8ToString(ptr);
}

function readTimelinePosition(ptr) {
    return {
        samples: getI64(ptr),
        legacyBeats: getF64(ptr + 8),
    };
}

export function readTimelineState(ptr) {
    return {
        playheadPosition: readTimelinePosition(ptr),
        isPlaying: getBool(ptr + 16),
        loopEnabled: getBool(ptr + 17),
        loopStart: readTimelinePosition(ptr + 24),
        loopEnd: readTimelinePosition(ptr + 40),
        tempo: getF64(ptr + 56),
        timeSignatureNumerator: getI32(ptr + 64),
        timeSignatureDenominator: getI32(ptr + 68),
        sampleRate: getI32(ptr + 72),
    };
}

export function readAudioFileProperties(ptr) {
    return {
        numFrames: getI64(ptr),
        numChannels: getU32(ptr + 8),
        sampleRate: getU32(ptr + 12),
    };
}

export function readAudioDeviceInfo(ptr) {
    return {
        directions: getI32(ptr),
        id: getI32(ptr + 4),
        name: getCString(getPtr(ptr + 8)),
        sampleRate: getU32(ptr + 12),
        channels: getU32(ptr + 16),
    };
}

export function readParameterMetadata(ptr) {
    const namedCount = getU32(ptr + 44);
    const namedPtr = getPtr(ptr + 48);
    const namedValues = [];
    for (let i = 0; i < namedCount; i++) {
        const base = namedPtr + i * 16;
        namedValues.push({ value: getF64(base), name: getCString(getPtr(base + 8)) });
    }
    return {
        index: getU32(ptr),
        stableId: getCString(getPtr(ptr + 4)),
        name: getCString(getPtr(ptr + 8)),
        path: getCString(getPtr(ptr + 12)),
        defaultPlainValue: getF64(ptr + 16),
        minPlainValue: getF64(ptr + 24),
        maxPlainValue: getF64(ptr + 32),
        automatable: getBool(ptr + 40),
        hidden: getBool(ptr + 41),
        discrete: getBool(ptr + 42),
        namedValues,
    };
}

export function readPresetMetadata(ptr) {
    return {
        bank: getU32(ptr) & 0xFF,
        index: getU32(ptr + 4),
        stableId: getCString(getPtr(ptr + 8)),
        name: getCString(getPtr(ptr + 12)),
        path: getCString(getPtr(ptr + 16)),
    };
}

export function readBlocklistEntry(ptr) {
    return {
        id: getCString(getPtr(ptr)),
        format: getCString(getPtr(ptr + 4)),
        pluginId: getCString(getPtr(ptr + 8)),
        reason: getCString(getPtr(ptr + 12)),
    };
}

export function withStruct(size, cb) {
    const ptr = _mod._malloc(size);
    try {
        return cb(ptr);
    } finally {
        _mod._free(ptr);
    }
}

export function registerCallback(obj) {
    const id = _nextCbId++;
    _callbacks.set(id, obj);
    return id;
}

export function unregisterCallback(id) {
    _callbacks.delete(id);
}

export function makeCFunctionPtr(cbId, dispatchName, sig) {
    if (!_ktDisp || typeof _ktDisp[dispatchName] !== 'function') {
        console.warn(`uapmd-wasm-adapter: Kotlin dispatcher '${dispatchName}' not set; callback will be ignored`);
        return _mod.addFunction(function() {}, sig);
    }
    const dispatcher = _ktDisp[dispatchName];
    const fn = function(...args) { return dispatcher(cbId, ...args); };
    return _mod.addFunction(fn, sig);
}

export function removeCFunctionPtr(ptr) {
    if (_mod && ptr !== 0) _mod.removeFunction(ptr);
}

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

let _nextPickId = 1;

// Returns a Promise that resolves to the MEMFS path of the picked file, or null on cancel.
export function pickFile(accept) {
    const pickId = _nextPickId++;
    return new Promise(function(resolve) {
        var cancelled = true;
        var prefix = '/tmp/uapmd_pick_' + pickId + '_';

        var input = document.createElement('input');
        input.type = 'file';
        if (accept) input.accept = accept;
        input.style.display = 'none';
        document.body.appendChild(input);

        input.addEventListener('change', function(e) {
            cancelled = false;
            var file = e.target.files && e.target.files[0];
            if (!file) {
                if (input.parentNode) document.body.removeChild(input);
                resolve(null);
                return;
            }
            var reader = new FileReader();
            reader.onload = function(re) {
                var data = new Uint8Array(re.target.result);
                var vfsPath = prefix + file.name;
                try {
                    _mod.FS.writeFile(vfsPath, data);
                    resolve(vfsPath);
                } catch (err) {
                    console.error('[uapmd] pickFile FS.writeFile failed:', err);
                    resolve(null);
                }
                if (input.parentNode) document.body.removeChild(input);
            };
            reader.onerror = function() {
                if (input.parentNode) document.body.removeChild(input);
                resolve(null);
            };
            reader.readAsArrayBuffer(file);
        });

        // Detect cancel via window focus regained without a change event
        window.addEventListener('focus', function onFocus() {
            window.removeEventListener('focus', onFocus);
            setTimeout(function() {
                if (cancelled) {
                    if (input.parentNode) document.body.removeChild(input);
                    resolve(null);
                }
            }, 500);
        }, { once: true });

        input.click();
    });
}

globalThis.__uapmdWasmAdapter = {
    setUapmdModule,
    getUapmdModule,
    setKotlinDispatchers,
    isReady,
    initBrowserFileSystem,
    readCStringFromHandle,
    readCStringFromHandleIndex,
    withCString,
    withTwoCStrings,
    withThreeCStrings,
    readTimelineState,
    readAudioFileProperties,
    readAudioDeviceInfo,
    readParameterMetadata,
    readPresetMetadata,
    readBlocklistEntry,
    withStruct,
    registerCallback,
    unregisterCallback,
    makeCFunctionPtr,
    removeCFunctionPtr,
    makeStateCallbackPtr,
    pickFile
};
