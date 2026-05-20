// Teach webpack how to resolve the uapmd-wasm-adapter module.
// The processed resource lands in uapmd-binding/build/processedResources/wasmJs/main/
// relative to the kotlin/ directory (four levels up from this webpack config).
const path = require('path');
const fs = require('fs');
const buildWasmDir = path.resolve(__dirname, '../../../../../build-wasm');
const uapmdWebDir = path.resolve(__dirname, '../../../../../external/uapmd/source/tools/uapmd-app/web');

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
    buildWasmDir,
    'uapmd-c-api.wasm'
);

config.module = config.module || {};
config.module.rules = config.module.rules || [];
config.module.rules.push({
    test: /uapmd-c-api\.wasm$/,
    type: 'asset/resource',
});

config.devServer = config.devServer || {};
config.devServer.headers = {
    ...(config.devServer.headers || {}),
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Embedder-Policy': 'require-corp',
};

config.devServer.static = [
    ...(Array.isArray(config.devServer.static) ? config.devServer.static : []),
    { directory: buildWasmDir, watch: false },
    { directory: uapmdWebDir, watch: false },
];

class CopyUapmdWasmAssetsPlugin {
    apply(compiler) {
        compiler.hooks.thisCompilation.tap('CopyUapmdWasmAssetsPlugin', (compilation) => {
            compilation.hooks.processAssets.tap(
                {
                    name: 'CopyUapmdWasmAssetsPlugin',
                    stage: compiler.webpack.Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL,
                },
                () => {
                    const assets = [
                        [path.join(uapmdWebDir, 'coop-coep-sw.js'), 'coop-coep-sw.js'],
                        [path.join(uapmdWebDir, 'uapmd-webclap-worklet.js'), 'uapmd-webclap-worklet.js'],
                        [path.join(buildWasmDir, 'wclap.mjs'), 'wclap.mjs'],
                        [path.join(buildWasmDir, 'uapmd-wclap-host.wasm'), 'uapmd-wclap-host.wasm'],
                        [path.join(buildWasmDir, 'es6/generate-forwarding-wasm.mjs'), 'es6/generate-forwarding-wasm.mjs'],
                        [path.join(buildWasmDir, 'es6/targz.mjs'), 'es6/targz.mjs'],
                        [path.join(buildWasmDir, 'es6/wclap-plugin.mjs'), 'es6/wclap-plugin.mjs'],
                        [path.join(buildWasmDir, 'es6/wclap.mjs'), 'es6/wclap.mjs'],
                        [path.join(buildWasmDir, 'es6/wasi/wasi.mjs'), 'es6/wasi/wasi.mjs'],
                        [path.join(buildWasmDir, 'es6/wasi/wasi.wasm'), 'es6/wasi/wasi.wasm'],
                    ];
                    for (const [sourcePath, outputPath] of assets) {
                        compilation.emitAsset(
                            outputPath,
                            new compiler.webpack.sources.RawSource(fs.readFileSync(sourcePath))
                        );
                    }
                }
            );
        });
    }
}

config.plugins.push(new CopyUapmdWasmAssetsPlugin());
