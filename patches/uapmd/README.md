# uapmd patches

Changes uapmd-kmp needs in `external/uapmd` that uapmd does not carry yet.

**Currently empty**: the two WebCLAP/WebAudio patches this directory was created
for are in uapmd as of `58180f40`, so they were deleted. The machinery below
stays for the next one; with no `*.patch` here it does nothing.

`external/uapmd` is a git submodule, so edits made directly in its working tree
are invisible to this repository and are discarded by the next
`git submodule update`. Keeping them here instead means they survive, travel
with a clone, and are visible in review.

`cmake/UapmdPatches.cmake` applies every `*.patch` in this directory to the
submodule at configure time, from all three entry points that build uapmd — the
desktop `CMakeLists.txt`, the Android one, and the Emscripten one. Applying is
idempotent, and a patch that no longer applies fails configure rather than
letting a build quietly lose the fix.

## Adding one

```
git -C external/uapmd diff -- <paths> > patches/uapmd/NNNN-short-description.patch
git -C external/uapmd checkout -- <paths>      # let the patch step own it
```

Patches apply in filename order, so the numeric prefix is what sequences two
patches that touch the same file.

## Removing one

Each of these is meant to be upstreamed. Once uapmd carries the change,
deleting the `.patch` file is the entire removal — the next configure sees the
change already present and reports it as applied.
