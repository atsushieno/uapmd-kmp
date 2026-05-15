// Teach webpack how to resolve the uapmd-wasm-adapter module.
// The processed resource lands in uapmd-binding/build/processedResources/wasmJs/main/
// relative to the kotlin/ directory (four levels up from this webpack config).
const path = require('path');
config.resolve = config.resolve || {};
config.resolve.alias = config.resolve.alias || {};
config.resolve.alias['uapmd-wasm-adapter'] = path.resolve(
    __dirname,
    '../../../../uapmd-binding/build/processedResources/wasmJs/main/uapmd-wasm-adapter.mjs'
);
config.resolve.alias['uapmd-c-api'] = path.resolve(
    __dirname,
    '../../../../../build-wasm/uapmd-c-api.js'
);
config.resolve.alias['uapmd-c-api.wasm'] = path.resolve(
    __dirname,
    '../../../../../build-wasm/uapmd-c-api.wasm'
);
