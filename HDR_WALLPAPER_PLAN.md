# WallXDR — Android HDR Live Wallpaper Support Plan

## Goal

Add real HDR rendering support to ShaderEditor live wallpapers while preserving existing SDR shaders and devices.

## Implementation checkpoint — 2026-09-08

Phase A plus the first FP16 working-target implementation are implemented locally and `assembleDebug` passes.

Implemented now:

- Display HDR10 capability discovery and diagnostic logging.
- A 10-bit EGL config preference for the experimental wallpaper path.
- BT.2020/PQ EGL window-surface negotiation when the required EGL extensions are present.
- GLES 3 gating and explicit SDR fallback when HDR prerequisites are missing.
- A final-pass Rec.709/sRGB → BT.2020/PQ conversion so an HDR surface is not fed raw SDR code values.
- Live wallpapers now request HDR in release as well as debug builds. `ShaderView` still falls back to the stable SDR path if HDR capability or HDR surface negotiation is unavailable.
- Real-device validation on Xiaomi 25102RKBEC (Android 17 / API 37, Adreno 840) succeeded: the wallpaper selected an `r10g10b10a2` EGL config, created a GLES 3 BT.2020/PQ window surface, and reported `hdrSurface=true`.
- SurfaceFlinger confirmed `RGBA_1010102_UBWC` wallpaper buffers with dataspace `0x09c60000` (`DATASPACE_BT2020_PQ`) and recorded a full-screen HDR layer.
- On the current test device, SYSTEM/Home uses the debug ShaderEditor wallpaper while LOCK uses MIUI `ImageWallpaper`.
- The release package `de.markusfisch.android.shadereditor` is not currently installed for user 0 (it is absent even from `pm list packages -u`), which explains why WallpaperManager previously reported its service as unavailable. The debug package was therefore intentionally left installed and active for HDR development.
- Internal main/backbuffer ping-pong targets now prefer `RGBA16F` only when the active wallpaper surface is HDR and GLES 3 is available.
- Both ping-pong FBOs are checked with `glCheckFramebufferStatus`. If either FP16 target is incomplete, both FP16 targets are deleted and recreated together as RGBA8; mixed FP16/RGBA8 ping-pong state is not allowed.
- FP16 preset/backbuffer bitmap initialization allocates RGBA16F storage first and uploads pixels with `glTexSubImage2D`, so bitmap initialization cannot silently redefine the texture as RGBA8.
- Thumbnail rendering remains RGBA8/SDR.
- Real-device FP16 validation passed on the Xiaomi: logs show `Using RGBA16F HDR ping-pong render targets 1200x2608` while the output remains GLES 3 + `r10g10b10a2` + BT.2020/PQ.
- A temporary split-screen shader (`1.0` on the left, `2.0` on the right) produced a clearly brighter right half in an SDR screen capture after system tone mapping (center-region averages approximately 119 vs 203), demonstrating that values above 1.0 are no longer clamped in the internal path. The temporary test shader override was then removed.
- SurfaceFlinger simultaneously reported the wallpaper as `RGBA_1010102_UBWC` with dataspace `0x09c60000` and a full-screen HDR layer, so the current chain is: user shader extended values → RGBA16F working FBO → PQ final pass → 10-bit BT.2020/PQ wallpaper buffer.
- HDR-native shader output now has an explicit experimental opt-in marker: `#define SHADEREDITOR_HDR_NATIVE 1`.
- Marked shaders use a linear-light Rec.709 working contract where `1.0 = 203 nits` reference white and values above `1.0` are HDR highlights. The HDR final pass bypasses legacy sRGB decoding, converts linear Rec.709 to BT.2020, then applies PQ.
- Unmarked shaders keep the legacy sRGB-coded interpretation. SDR preview/thumbnail output for marked shaders performs linear → sRGB conversion and clips above SDR white instead of feeding linear code values directly to an SDR surface.
- Real-device HDR-native validation passed with a temporary marked `1.0`/`2.0` split shader: logs reported `HDR-native shader input active: hdrSurface=true, fp16Targets=true`; the system screenshot measured center-region luma approximately 111 vs 145, and SurfaceFlinger remained `RGBA_1010102_UBWC` + `0x09c60000`. The temporary override was removed after the test.
- User shaders can now query the active HDR path through four built-in uniforms: `hdrEnabled`, `hdrHeadroom`, `hdrReferenceWhiteNits`, and `displayPeakNits`. They are also exposed in the Add Uniform preset list.
- `hdrEnabled` is deliberately strict: it is `1` only for an HDR-native shader when the real output surface is HDR and the internal working targets are RGBA16F. `hdrHeadroom` is `displayPeakNits / 203` only in that usable state and otherwise falls back to `1.0`.
- `displayPeakNits` comes from Android `Display.HdrCapabilities.getDesiredMaxLuminance()`, is sanitized and capped to the 10,000-nit PQ range, and remains a display capability value even when the current surface is SDR. `hdrReferenceWhiteNits` is `203.0`.
- Real-device shader-side uniform validation passed on the Xiaomi with a temporary HDR-native assertion shader. The shader emitted bright green only if `hdrEnabled == 1`, reference white was approximately 203 nits, peak was approximately 3200 nits, and headroom matched `3200 / 203` (about 15.76); otherwise it emitted red. The captured center region was green-dominant at approximately RGB `(118, 189, 81)`, while logs remained `hdrSurface=true, fp16Targets=true` and SurfaceFlinger remained 10-bit BT.2020/PQ. The temporary assertion shader was removed afterward.

Not implemented yet:

- User-facing HDR preferences.
- Broader cross-device validation and a stronger EGL config fallback path for devices where a 10-bit config is selected but PQ window-surface creation later fails.
- SDR tone mapping that preserves visible highlight detail above `1.0` for HDR-native shaders; the current SDR fallback intentionally clips at reference white for predictable compatibility.

Next implementation gate: add a user-facing HDR output policy (`Auto` / `Off` / development-force equivalent) and then validate fallback behavior on additional devices/contexts without regressing legacy SDR shaders.

The preferred rendering contract is:

1. User fragment shaders render in a linear, extended-range working space.
2. Intermediate render targets preserve values above 1.0 when the GPU supports FP16 color attachments.
3. The existing final surface pass performs output conversion for the selected display path (HDR10/PQ or SDR).
4. Unsupported devices, launchers, wallpaper compositors, or GL drivers fall back to the current RGBA8 SDR path without breaking wallpapers.

## What the current code is doing

Relevant paths:

- `app/src/main/java/de/markusfisch/android/shadereditor/widget/ShaderView.java`
  - Uses `GLSurfaceView`.
  - Calls `setEGLContextClientVersion(2)` and supplies a custom context factory that tries GLES 3 then falls back to GLES 2.
  - Does **not** currently provide a custom `EGLConfigChooser` or `EGLWindowSurfaceFactory`, so the window surface is effectively the normal 8-bit SDR path.
- `app/src/main/java/de/markusfisch/android/shadereditor/service/ShaderWallpaperService.java`
  - Reuses `ShaderView` by overriding `getHolder()` to return the wallpaper engine's `SurfaceHolder`.
  - This is the main integration point for HDR wallpaper surface format / capability negotiation.
- `app/src/main/java/de/markusfisch/android/shadereditor/opengl/GlDevice.java`
  - `allocateTexture2D()` allocates `GL_RGBA + GL_UNSIGNED_BYTE`, i.e. an 8-bit render target.
- `app/src/main/java/de/markusfisch/android/shadereditor/opengl/ShaderRenderPipeline.java`
  - Renders the shader into ping-pong FBO textures, then performs a final surface pass.
  - Backbuffer and main render targets currently use the same RGBA8 allocation path.
- `app/src/main/java/de/markusfisch/android/shadereditor/opengl/RendererProgramManager.java`
  - The surface fragment shader currently just copies `frame` to `gl_FragColor`.
  - This is the cleanest place to add HDR output transfer / gamut conversion / SDR tone mapping.
- `app/build.gradle.kts`
  - `compileSdk = 36`, `minSdk = 23`, so modern HDR APIs can be compiled while remaining runtime-gated.

## Design principles

- Do not make HDR a global assumption. Treat it as a negotiated render mode.
- Do not silently clamp user shader output before the final output pass.
- Keep legacy shaders visually close to current behavior in SDR mode.
- Make the wallpaper service use the exact same render pipeline as preview where possible, but allow different surface capabilities.
- Every HDR path must have a deterministic SDR fallback.
- Capability checks must be based on the actual EGL/GL/display configuration, not only Android API level.

## Phase 0 — Feasibility spike on real wallpaper surfaces

Before large refactors, prove that HDR survives the wallpaper compositor on target devices.

Create a diagnostic shader with:

- SDR reference white patch.
- 1.5x / 2x / 4x linear highlight patches.
- Rec.709 and Display-P3/BT.2020 primary comparison patches.
- Smooth gradients to expose 8-bit banding.

Log:

- Android version / device model.
- GLES version.
- EGL vendor/version/extensions.
- Chosen EGL config component sizes.
- Supported surface colorspace extensions.
- Display HDR types / peak luminance information where available.
- Whether the wallpaper surface can actually be created with the requested HDR format/colorspace.

Acceptance gate: at least one HDR-capable device must visibly produce highlight headroom from a live wallpaper surface. If the launcher/wallpaper compositor clamps the surface to SDR, keep HDR preview support but mark wallpaper HDR unsupported on that device.

## Phase 1 — Introduce a render capability model

Add something like:

- `opengl/RenderCapabilities.java`
- `opengl/OutputColorMode.java`
- `opengl/RenderTargetFormat.java`

Suggested output modes:

- `SDR_SRGB`
- `HDR_BT2020_PQ`
- optional later: `HDR_HLG`

Suggested target formats:

- `RGBA8`
- `RGB10_A2`
- `RGBA16F`

Probe once per GL context and cache only data valid for that context/display.

The capability object should answer:

- GLES 3 available?
- FP16 color attachment renderable?
- 10-bit EGL window config available?
- Required EGL colorspace extension available?
- HDR display/output mode available?
- Final negotiated mode and reason for fallback.

Expose the negotiated mode in debug logs and renderer info so failures are diagnosable.

## Phase 2 — HDR-capable EGL/window surface creation

### First implementation: stay on `GLSurfaceView`

Extend `ShaderView` with:

1. A custom `EGLConfigChooser` that prefers a 10-bit window config for HDR and falls back to the existing 8-bit config.
2. A custom `EGLWindowSurfaceFactory` that requests the HDR colorspace on the window surface when supported.
3. An explicit `SurfaceHolder` pixel format request where the Android surface path requires it.
4. A constructor/options object so preview and wallpaper can request `AUTO`, `SDR`, or `HDR` without duplicating renderer code.

Do not remove the existing GLES3→GLES2 fallback for SDR.

### Decision gate

If `GLSurfaceView` + EGL10 proves unreliable for colorspace negotiation across devices, replace only the surface/context ownership layer with a small EGL14 render view/thread while keeping `ShaderRenderer`, `GlDevice`, `RendererProgramManager`, and `ShaderRenderPipeline` intact.

Avoid rewriting the renderer unless required.

## Phase 3 — Preserve HDR values in intermediate FBOs

Change `GlDevice.allocateTexture2D()` into a format-aware allocation API, for example:

`allocateTexture2D(texture, width, height, RenderTargetFormat format)`

Behavior:

- SDR: current `RGBA8` behavior.
- HDR preferred: `RGBA16F` when the context reports a renderable FP16 color attachment.
- HDR fallback: use a 10-bit path where practical, otherwise drop back to SDR and report why.

Update `ShaderRenderPipeline` so:

- Main ping-pong targets use the negotiated HDR working format.
- Backbuffer preserves the same working format.
- Thumbnail FBO remains RGBA8 unless an HDR thumbnail/export feature is added later.

Verify framebuffer completeness after every format choice. A failed HDR FBO must trigger a clean SDR reconfiguration rather than a black wallpaper.

## Phase 4 — Make the final surface pass color-managed

The existing surface fragment shader in `RendererProgramManager` is the correct choke point.

Replace the simple texture copy with output-mode variants or uniforms that can perform:

### HDR path

- Treat the main shader result as linear extended-range RGB.
- Convert working primaries to BT.2020 when needed.
- Map scene/reference-linear values to display-referred luminance using an explicit reference-white convention.
- Apply ST.2084/PQ encoding for HDR10 output.
- Preserve highlights above SDR white up to the negotiated target/headroom.

### SDR path

- Preserve current appearance as closely as possible.
- Tone-map extended values instead of hard clipping when an HDR-authored shader is shown on SDR.
- Encode to the expected SDR transfer function/colorspace.

Keep this conversion out of user shaders so old shaders continue to work.

## Phase 5 — Wallpaper integration

Update `ShaderWallpaperService` so each engine:

1. Queries display/output capabilities when the wallpaper surface is created or recreated.
2. Chooses the requested mode (`AUTO` by default).
3. Applies the required `SurfaceHolder` format before creating/attaching the GL surface.
4. Constructs `ShaderWallpaperView` with the negotiated output request.
5. Recreates the surface/render targets if HDR capability materially changes.

Important: wallpaper output must be tested independently from activity preview output. A phone may support HDR application windows while its launcher/wallpaper compositor still forces SDR.

Keep existing battery-low render-mode behavior unchanged.

## Phase 6 — User settings and shader-facing metadata

Add a setting under preferences:

- `HDR output`: `Auto` / `Off` / `Force when supported`

Optional advanced settings later:

- SDR reference white.
- Target peak nits / highlight scale.
- Tone-mapping operator.

Shader-facing HDR uniforms are now implemented:

- `hdrEnabled` — 0/1.
- `hdrHeadroom` — current usable linear headroom over SDR white.
- `hdrReferenceWhiteNits` — current linear reference white in nits (`203.0`).
- `displayPeakNits` — sanitized Android-reported desired maximum HDR luminance.

These allow shaders to intentionally create HDR highlights while remaining portable.

## Phase 7 — Preview parity

After wallpaper HDR is proven, enable the same negotiated HDR path in the full-screen preview.

The editor's embedded preview may default to SDR initially to avoid mixing HDR and normal UI content unexpectedly. A dedicated HDR preview toggle is preferable to forcing the whole editing activity into HDR.

## Phase 8 — Compatibility and failure policy

Fallback ladder:

1. HDR + FP16 working targets + 10-bit HDR window surface.
2. HDR + alternative supported working target + 10-bit HDR window surface.
3. SDR RGBA8, current behavior.

Never leave the renderer in a partially-HDR state where:

- FP16 shader output is copied into an 8-bit SDR surface without tone mapping.
- PQ-encoded output is sent to a surface treated as sRGB.
- The wallpaper becomes black because an HDR FBO/window config failed.

## Validation matrix

Test at minimum:

- Pixel device with HDR OLED.
- Samsung Galaxy HDR-capable device.
- One non-HDR / older GLES device.
- Android emulator only for SDR regression; do not use it as proof of HDR output.

For each HDR device test:

- Home wallpaper and lock-screen wallpaper where supported.
- Wallpaper picker preview versus applied wallpaper.
- 60/90/120 Hz modes.
- Battery saver on/off and charging transitions.
- Screen off/on and launcher restart.
- Orientation changes.
- Quality multipliers below/above 1.0.
- Backbuffer shaders.
- Camera/external texture shaders.
- Shader recompilation while wallpaper is active.

Visual checks:

- Highlights exceed SDR white on a real HDR panel.
- No obvious 8-bit gradient banding in HDR path.
- SDR shaders do not become washed out.
- Colors are not double-gamma encoded.
- Switching HDR off reproduces the current renderer closely.

## Suggested implementation order

### Milestone A — diagnostic branch

- Add EGL/GL/HDR capability logging.
- Add HDR test shader.
- Add custom 10-bit EGL config/window-surface experiment only for wallpaper.
- Prove actual wallpaper HDR headroom on one device.

### Milestone B — renderer plumbing

- Add `RenderCapabilities` / output mode.
- Make texture/FBO allocation format-aware.
- Add FP16 ping-pong target support.
- Keep thumbnails RGBA8.

### Milestone C — color pipeline

- Add linear-working-space contract.
- Add final SDR tone-map/output transform.
- Add BT.2020 + PQ output transform.
- Add debug display of active output mode.

### Milestone D — product integration

- Add Auto/Off HDR preference.
- Wire wallpaper lifecycle reconfiguration.
- Add preview support.
- Device matrix testing and regression fixes.

## First files to modify

1. `app/src/main/java/de/markusfisch/android/shadereditor/widget/ShaderView.java`
2. `app/src/main/java/de/markusfisch/android/shadereditor/service/ShaderWallpaperService.java`
3. `app/src/main/java/de/markusfisch/android/shadereditor/opengl/GlDevice.java`
4. `app/src/main/java/de/markusfisch/android/shadereditor/opengl/ShaderRenderPipeline.java`
5. `app/src/main/java/de/markusfisch/android/shadereditor/opengl/RendererProgramManager.java`
6. `app/src/main/java/de/markusfisch/android/shadereditor/opengl/ShaderRenderer.java`
7. `app/src/main/java/de/markusfisch/android/shadereditor/preference/Preferences.java`
8. `app/src/main/res/xml/preferences.xml`

## Definition of done for the first HDR wallpaper release

- A supported HDR phone shows measurable/visible highlight headroom from an applied ShaderEditor live wallpaper.
- The renderer reports which HDR path was negotiated.
- Backbuffer shaders retain extended-range values in HDR mode.
- SDR-only devices render exactly through a supported fallback and do not crash.
- HDR can be disabled by the user.
- Wallpaper battery-saving behavior still works.
- No black surface on EGL/FBO capability failure.
- Existing shader editing, preview, textures, thumbnails, and wallpaper selection remain functional.
