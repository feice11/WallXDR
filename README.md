# WallXDR — Shader Editor HDR Fork

> 基于 [markusfisch/ShaderEditor](https://github.com/markusfisch/ShaderEditor) 的 Android GLSL 编辑器魔改版，重点增强 HDR 动态壁纸、VSync 刷新率控制和 HDR-native 工作流。
>
> An experimental Android GLSL editor fork based on [markusfisch/ShaderEditor](https://github.com/markusfisch/ShaderEditor), focused on HDR live wallpapers, VSync-aware frame-rate control, and an HDR-native shader workflow.

## 中文

### 主要新增功能

- **真正的 HDR 动态壁纸输出**
  - HDR10 能力检测。
  - 10-bit `r10g10b10a2` EGL window config。
  - GLES 3 优先，GLES 2 自动回退。
  - BT.2020 / PQ HDR window surface。
  - HDR 工作链优先使用 `RGBA16F` ping-pong 中间缓冲，保留 `> 1.0` 的线性高光信息。
  - HDR 条件不满足时自动回退 SDR，不让壁纸直接失效。

- **HDR-native shader 模式**

  在 shader 顶部显式加入：

  ```glsl
  #define SHADEREDITOR_HDR_NATIVE 1
  ```

  此模式下 RGB 按**线性 Rec.709**解释，当前约定 `1.0 = 203 nits`，大于 `1.0` 的值可用于 HDR 高光。

  可选内置 uniform：

  ```glsl
  uniform int hdrEnabled;
  uniform float hdrHeadroom;
  uniform float hdrReferenceWhiteNits;
  uniform float displayPeakNits;
  ```

  `hdrEnabled == 1` 仅表示完整 HDR 链路确实成立：HDR-native shader + HDR surface + FP16 工作缓冲。

- **用户可选壁纸刷新率 / VSync 下调**
  - 设置页会读取设备支持的显示模式，并生成可与 VSync 对齐的壁纸刷新率档位。
  - 例如 120Hz 设备可出现从约 15Hz 一直到 120Hz 的可用档位，具体取决于设备实际支持的模式。
  - 壁纸通过 `Surface.setFrameRate(..., FIXED_SOURCE)` 向 Android 报告目标内容帧率。
  - 渲染侧使用 `Choreographer` 在系统 VSync 回调上调度 `requestRender()`，而不是用 `sleep()` 粗暴限帧。
  - 低电量停止渲染逻辑继续有效。

- **改善应用内 SDR 预览色彩断层**
  - HDR-native shader 即使在应用内 SDR 预览，也会在 GLES 3 可用时优先使用 `RGBA16F` 中间缓冲，避免线性渐变先被 RGBA8 量化一次。
  - 最终 SDR preview 执行 linear Rec.709 → sRGB。
  - 在最终 8-bit SDR 输出前加入稳定的空间 dithering，减轻暗部和渐变色带。
  - 壁纸 HDR 输出仍使用 BT.2020/PQ，不受 SDR preview dithering 影响。

### HDR-native 最小示例

```glsl
#define SHADEREDITOR_HDR_NATIVE 1

precision highp float;
uniform vec2 resolution;
uniform int hdrEnabled;
uniform float hdrHeadroom;

void main() {
    vec2 uv = gl_FragCoord.xy / resolution.xy;
    float highlight = hdrEnabled != 0 ? min(hdrHeadroom, 4.0) : 1.0;
    float spot = exp(-80.0 * dot(uv - 0.5, uv - 0.5));
    gl_FragColor = vec4(vec3(0.03 + spot * highlight), 1.0);
}
```

### 当前验证设备

开发阶段主要在以下设备验证：

- Xiaomi `25102RKBEC`
- Android 17 / API 37
- Adreno 840
- HDR10
- Android 报告的 desired max luminance：约 3200 nits
- 已确认 SurfaceFlinger 壁纸链路为 10-bit BT.2020/PQ

不同厂商对 HDR window surface、颜色管理、动态刷新率和壁纸合成的实现不同，因此仍保留自动回退路径。

---

## English

### What this fork adds

- **Real HDR live-wallpaper output**
  - HDR10 capability detection.
  - 10-bit `r10g10b10a2` EGL window configuration.
  - GLES 3 preferred with GLES 2 fallback.
  - BT.2020 / PQ HDR window surfaces.
  - `RGBA16F` ping-pong working buffers when available, preserving linear values above `1.0` for HDR highlights.
  - Automatic SDR fallback when HDR prerequisites are unavailable.

- **Explicit HDR-native shader mode**

  Add this directive at the top of a shader:

  ```glsl
  #define SHADEREDITOR_HDR_NATIVE 1
  ```

  RGB is then interpreted as **linear Rec.709**. The current contract maps `1.0` to a 203-nit reference white, while values above `1.0` can represent HDR highlights.

  Optional built-in uniforms:

  ```glsl
  uniform int hdrEnabled;
  uniform float hdrHeadroom;
  uniform float hdrReferenceWhiteNits;
  uniform float displayPeakNits;
  ```

  `hdrEnabled == 1` only when the complete HDR path is active: an HDR-native shader, a real HDR output surface, and FP16 working render targets.

- **User-selectable VSync-aware wallpaper refresh rate**
  - The preferences screen derives selectable rates from the display modes reported by the device.
  - On a 120Hz-class device, selectable VSync-aligned rates can span roughly 15Hz through 120Hz, depending on the actual modes exposed by the panel/OS.
  - The wallpaper reports its content rate with `Surface.setFrameRate(..., FIXED_SOURCE)`.
  - Rendering is requested from `Choreographer` VSync callbacks instead of using `Thread.sleep()` frame limiting.
  - Existing low-battery rendering suspension remains intact.

- **Reduced color banding in the in-app SDR preview**
  - HDR-native shaders prefer `RGBA16F` intermediate render targets even when the editor window itself is SDR.
  - The final SDR pass converts linear Rec.709 to sRGB.
  - Stable spatial dithering is applied immediately before the final 8-bit SDR output to reduce visible gradient banding.
  - HDR wallpaper output remains BT.2020/PQ and does not use the SDR preview dithering path.

### Upstream Shader Editor features retained

- GLSL editing and live preview.
- Syntax and error highlighting.
- Live wallpaper support.
- Camera, accelerometer, gyroscope, magnetic field, light, pressure and proximity uniforms.
- Battery, touch and wallpaper-offset uniforms.
- Backbuffer textures, arbitrary textures and cube maps.
- Multiple render quality levels.

See [FAQ.md](FAQ.md) and [HDR_WALLPAPER_PLAN.md](HDR_WALLPAPER_PLAN.md) for implementation details and current limitations.

## Build

```bash
./gradlew assembleDebug
```

A release build uses the upstream environment-variable signing setup (`ANDROID_KEYFILE`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `ANDROID_STORE_PASSWORD`).

## Upstream / License

WallXDR is a fork of [markusfisch/ShaderEditor](https://github.com/markusfisch/ShaderEditor). Original project history, attribution and license remain applicable. This repository is not the upstream official build.
