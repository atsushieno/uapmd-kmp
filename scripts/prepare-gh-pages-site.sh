#!/usr/bin/env bash
# Lays out the gh-pages tree for uapmd-kmp: a Wasm demo section and a Dokka API
# section, each with a `latest/` that every publish refreshes and optional
# `<version>/` snapshots that are kept forever. Modelled on uapmd's
# scripts/prepare-gh-pages-site.sh so both sites read the same way.
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
    cat >&2 <<'HELP'
Usage: prepare-gh-pages-site.sh <source-dir> <demo-artifacts-dir> <api-artifacts-dir> <target-dir> [version-tag]

  source-dir          site source files (index.html, styles.css) — pages-src/
  demo-artifacts-dir  downloaded wasmJsBrowserDistribution output
  api-artifacts-dir   downloaded Dokka HTML output
  target-dir          gh-pages checkout to update in place
  version-tag         optional; also snapshots under <section>/<version>/
HELP
    exit 1
fi

SOURCE_DIR="$1"
DEMO_ARTIFACTS_DIR="$2"
API_ARTIFACTS_DIR="$3"
TARGET_DIR="$4"
VERSION_TAG="${5:-}"

ROOT_INDEX="${SOURCE_DIR}/index.html"
ROOT_STYLES="${SOURCE_DIR}/styles.css"

for required in "${ROOT_INDEX}" "${ROOT_STYLES}"; do
    if [[ ! -f "${required}" ]]; then
        echo "error: missing site source file: ${required}" >&2
        exit 1
    fi
done

# Locate the real roots inside the downloaded artifacts. Artifact archives keep
# whatever directory nesting the upload had, so anchor on a known entry point
# rather than assuming a depth.
find_artifact_root() {
    local artifacts_dir="$1"
    local marker="$2"
    local label="$3"
    local found

    if [[ ! -d "${artifacts_dir}" ]]; then
        echo "error: ${label} artifacts directory does not exist: ${artifacts_dir}" >&2
        exit 1
    fi

    found="$(find "${artifacts_dir}" -type f -name "${marker}" | sort | head -n 1)"
    if [[ -z "${found}" ]]; then
        echo "error: missing ${label} artifact ${marker} under ${artifacts_dir}" >&2
        exit 1
    fi
    dirname "${found}"
}

# index.html identifies both: the demo bundle's page and Dokka's entry page.
# Pick the shallowest match so a nested index.html cannot win.
find_shallowest_index() {
    local artifacts_dir="$1"
    local label="$2"
    local found

    if [[ ! -d "${artifacts_dir}" ]]; then
        echo "error: ${label} artifacts directory does not exist: ${artifacts_dir}" >&2
        exit 1
    fi

    found="$(find "${artifacts_dir}" -type f -name 'index.html' \
        | awk -F/ '{print NF"\t"$0}' | sort -n | cut -f2- | head -n 1)"
    if [[ -z "${found}" ]]; then
        echo "error: missing ${label} index.html under ${artifacts_dir}" >&2
        exit 1
    fi
    dirname "${found}"
}

DEMO_ARTIFACT_ROOT="$(find_artifact_root "${DEMO_ARTIFACTS_DIR}" 'uapmd-cmp.js' 'demo')"
API_ARTIFACT_ROOT="$(find_shallowest_index "${API_ARTIFACTS_DIR}" 'api docs')"

normalize_version_tag() {
    local raw="$1"

    if [[ "${raw}" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)([.-].*)?$ ]]; then
        printf '%s.%s.%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    else
        printf '%s\n' "${raw//\//-}"
    fi
}

copy_tree() {
    local src_dir="$1"
    local dst_dir="$2"

    rm -rf "${dst_dir}"
    mkdir -p "${dst_dir}"
    cp -R "${src_dir}/." "${dst_dir}/"
}

# Every published version directory except `latest`, newest last.
collect_versions() {
    local section_dir="$1"
    local subdir name

    for subdir in "${section_dir}"/*; do
        [[ -d "${subdir}" ]] || continue
        name="$(basename "${subdir}")"
        [[ "${name}" == "latest" ]] && continue
        printf '%s\n' "${name}"
    done | sort -V
}

render_section_index() {
    local section_dir="$1"
    local title="$2"
    local eyebrow="$3"
    local heading="$4"
    local lede="$5"
    local latest_blurb="$6"
    shift 6
    local versions=("$@")
    local versions_markup=""
    local version

    if [[ ${#versions[@]} -gt 0 ]]; then
        for version in "${versions[@]}"; do
            versions_markup="${versions_markup}                <li><a href=\"./${version}/\">${version}</a></li>
"
        done
    else
        versions_markup='                <li>Nothing has been published under a version tag yet.</li>
'
    fi

    cat > "${section_dir}/index.html" <<EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>${title}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link rel="stylesheet" href="../styles.css">
</head>
<body>
<div class="shell">
    <nav class="nav">
        <a class="brand" href="../">uapmd-kmp web</a>
        <div class="nav-links">
            <a class="nav-link" href="../demo/">Demo</a>
            <a class="nav-link" href="../api/">API reference</a>
            <a class="nav-link" href="https://github.com/atsushieno/uapmd-kmp">Repository</a>
        </div>
    </nav>

    <section class="hero">
        <p class="eyebrow">${eyebrow}</p>
        <h1>${heading}</h1>
        <p class="lede">${lede}</p>
        <div class="actions">
            <a class="button button-primary" href="./latest/">Open latest</a>
        </div>
    </section>

    <div class="grid two">
        <section class="card">
            <p class="eyebrow">Current</p>
            <h2><a href="./latest/">latest</a></h2>
            <p>${latest_blurb}</p>
        </section>

        <section class="card">
            <p class="eyebrow">Published versions</p>
            <h2>Snapshots</h2>
            <ul class="list">
${versions_markup}            </ul>
        </section>
    </div>
</div>
</body>
</html>
EOF
}

mkdir -p "${TARGET_DIR}/demo" "${TARGET_DIR}/api"

cp "${ROOT_INDEX}" "${TARGET_DIR}/index.html"
cp "${ROOT_STYLES}" "${TARGET_DIR}/styles.css"

copy_tree "${DEMO_ARTIFACT_ROOT}" "${TARGET_DIR}/demo/latest"
copy_tree "${API_ARTIFACT_ROOT}" "${TARGET_DIR}/api/latest"

if [[ -n "${VERSION_TAG}" ]]; then
    NORMALIZED_VERSION_TAG="$(normalize_version_tag "${VERSION_TAG}")"
    copy_tree "${DEMO_ARTIFACT_ROOT}" "${TARGET_DIR}/demo/${NORMALIZED_VERSION_TAG}"
    copy_tree "${API_ARTIFACT_ROOT}" "${TARGET_DIR}/api/${NORMALIZED_VERSION_TAG}"
    echo "Published snapshot ${NORMALIZED_VERSION_TAG} (from tag '${VERSION_TAG}')."
else
    echo "Refreshed latest/ only; pass a version tag to also archive a snapshot."
fi

DEMO_VERSIONS=()
while IFS= read -r line; do
    [[ -n "${line}" ]] && DEMO_VERSIONS+=("${line}")
done < <(collect_versions "${TARGET_DIR}/demo")

API_VERSIONS=()
while IFS= read -r line; do
    [[ -n "${line}" ]] && API_VERSIONS+=("${line}")
done < <(collect_versions "${TARGET_DIR}/api")

render_section_index "${TARGET_DIR}/demo" \
    "uapmd-kmp demo builds" \
    "Demo" \
    "Browser builds of uapmd-cmp" \
    "The Compose Multiplatform host application, compiled to WebAssembly. The latest build is refreshed on every manual Pages run; versioned snapshots are kept." \
    "The current browser build of uapmd-cmp. Audio needs a cross-origin isolated page, which a service worker arranges on first visit." \
    ${DEMO_VERSIONS[@]+"${DEMO_VERSIONS[@]}"}

render_section_index "${TARGET_DIR}/api" \
    "uapmd-kmp API reference" \
    "API reference" \
    "Dokka reference for uapmd-binding" \
    "The generated Kotlin API reference for the multiplatform binding. The latest set is refreshed on every manual Pages run; versioned snapshots are kept." \
    "The current API reference, generated from the sources on the published commit." \
    ${API_VERSIONS[@]+"${API_VERSIONS[@]}"}

# Without this, Jekyll would drop the underscore-prefixed files that both Dokka
# and the Kotlin/Wasm bundle emit.
touch "${TARGET_DIR}/.nojekyll"

echo "Site prepared under ${TARGET_DIR}."
