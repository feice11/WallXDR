# Frequently Asked Questions

## How do I use GLES 3.0 (or 3.1 / 3.2)?

Declare requested GLES version in **very first line** of shader:

```glsl
#version 300 es
```

You can also use `310 es` or `320 es` if your device supports it.

In GLES 3.x, fragment output changes from `gl_FragColor` to your own `out vec4` variable.
ShaderEditor detects version directive and creates matching GLES context automatically.

Do not put comments or blank lines before `#version ...`.

## How many FPS do I need for a live wallpaper?

Some devices limit GPU usage to consume less power when not plugged in.
Always check performance with soft keyboard hidden and power cord off.

As rough rule, shader should make at least around **30 FPS** to avoid slowing down UI.
If it does not, lower render quality with quality spinner (`1/2`, `1/4`, etc.).

## How much battery does a live wallpaper use?

Live wallpaper should only consume battery when you actually see it.
So real impact depends on how often and how long you look at it.
With normal use, you usually should not notice much difference.

When **Save battery** is enabled, rendering pauses on low battery to reduce power use further.

## What if errors are not highlighted?

Unfortunately error information is disabled on some devices and GPU drivers.
Error highlighting and reporting are not possible on those devices.

Shader can still compile and run normally even when driver does not report useful error logs.

## `Set wallpaper` does not work

You need to set ShaderEditor as live wallpaper first.
Go to system **Settings → Display → Wallpaper → Live Wallpapers** and choose **Shader Editor**.

As soon as Shader Wallpaper is active live wallpaper, you can change what shader is running from app menu with **Set as wallpaper** and **Update wallpaper**.

## Why can’t both cameras be opened at same time?

This is system limitation.
Android only allows one camera to be opened at a time.
No app can use both front and back camera simultaneously.

## `time` uniform loses accuracy / framerate drops over time

Most probably `time` is medium precision float in fragment shader.
That is default on many devices and it eventually loses precision.

Try high precision if hardware supports it:

```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
```

Or use `second`, `subsecond`, or `ftime` uniforms instead.
See issue [#10](https://github.com/markusfisch/ShaderEditor/issues/10#issuecomment-160463706) for details.

## How does backbuffer work?

Declare backbuffer like this:

```glsl
uniform sampler2D backbuffer;
```

Then sample previous frame with something like:

```glsl
vec4 prev = texture2D(backbuffer, uv);
```

This enables stateful effects like trails, blur, feedback, and cellular automata.

If you add `///p:noise` after declaration, backbuffer starts with noise texture instead of black:

```glsl
uniform sampler2D backbuffer;///p:noise
```

## Can I paste ShaderToy code directly?

Yes.
If pasted code contains `mainImage()`, ShaderEditor automatically converts common ShaderToy names:

- `iResolution` → `resolution`
- `iGlobalTime` → `time`
- `iMouse` → `mouse`
- `iDate` → `date`

A wrapper `main()` is generated automatically.

## How do I import or export shaders?

Use **Settings → Import/Export Database** to back up and restore entire shader collection, including textures.

On older Android versions (below 10), you can also export individual `.glsl` files to `Downloads/ShaderEditor`.

## Can I use custom textures?

Yes.
Open **Menu → Add Uniform → 2D Textures** or **Cube Maps** tab.
Pick image, crop if needed, set sampler parameters like wrap and filter, and ShaderEditor inserts uniform declaration into code.

## What is `#define SHADER_EDITOR`?

ShaderEditor injects this line automatically into every shader:

```glsl
#define SHADER_EDITOR 1
```

Use `#ifdef SHADER_EDITOR` when you want code that only runs inside ShaderEditor.
Useful for portable shaders shared with other GLSL environments.

## How do I opt in to experimental HDR-native shader output?

Add this line to the shader source:

```glsl
#define SHADEREDITOR_HDR_NATIVE 1
```

This explicitly changes the shader RGB output contract to linear-light Rec.709:

- `1.0` is SDR reference white (203 nits on the current HDR output path).
- Values above `1.0` are HDR highlights when the wallpaper negotiated an HDR surface and FP16 render targets.
- The final wallpaper pass converts linear Rec.709 to BT.2020/PQ.
- SDR preview and thumbnail output converts the linear values back to sRGB and clips values above SDR white.

Shaders without this define keep the legacy sRGB-coded interpretation, so existing shaders are not silently reinterpreted.

Example:

```glsl
#define SHADEREDITOR_HDR_NATIVE 1

void main() {
    // About 2x SDR reference white on the HDR path.
    gl_FragColor = vec4(vec3(2.0), 1.0);
}
```

This mode is experimental. If FP16 render targets are unavailable, ShaderEditor falls back to RGBA8 instead of failing; values above `1.0` then lose HDR highlight headroom.

HDR-native shaders can also declare these built-in uniforms:

```glsl
uniform int hdrEnabled;
uniform float hdrHeadroom;
uniform float hdrReferenceWhiteNits;
uniform float displayPeakNits;
```

- `hdrEnabled` is `1` only when the shader opted into HDR-native output, the current output surface is HDR, and FP16 working render targets are active. Otherwise it is `0`.
- `hdrHeadroom` is the available linear-light multiplier above reference white. It is `displayPeakNits / hdrReferenceWhiteNits` while HDR is usable, or `1.0` otherwise.
- `hdrReferenceWhiteNits` is currently `203.0`, so a linear shader value of `1.0` represents 203 nits.
- `displayPeakNits` is Android's reported HDR desired maximum luminance, clamped to the PQ range. It can still be non-zero while the current surface is SDR, so check `hdrEnabled` before emitting values above `1.0`.

These uniforms are also available from **Menu → Add Uniform**.

Example that scales a highlight only when the full HDR path is available:

```glsl
#define SHADEREDITOR_HDR_NATIVE 1

uniform int hdrEnabled;
uniform float hdrHeadroom;

void main() {
    float highlight = hdrEnabled != 0 ? min(4.0, hdrHeadroom) : 1.0;
    gl_FragColor = vec4(vec3(highlight), 1.0);
}
```

## What fonts are available?

Several popular coding fonts are built in, including **JetBrains Mono**, **Fira Code**, and **Source Code Pro**.
Ligature support can be toggled independently.

Choose font in **Settings → Editor → Font**.

## How do I change render resolution?

Use quality spinner in toolbar.
Values range from `1/32` up to `2×`.

Lower values render at fraction of screen resolution and can greatly improve performance or battery life.
Each shader remembers its own quality setting.

## Where can I learn more about shaders?

### Basics

- [The Book of Shaders](https://thebookofshaders.com/)
- [An Introduction to Shaders](https://aerotwist.com/tutorials/an-introduction-to-shaders-part-1/)

### Advanced

- [The OpenGL ES Shading Language](https://www.khronos.org/files/opengles_shading_language.pdf)
- [OpenGL API documentation](https://docs.gl/)
- [GLSL Programming](https://en.wikibooks.org/wiki/GLSL_Programming)
- [Best Practices for Shaders](https://developer.apple.com/library/content/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/BestPracticesforShaders/BestPracticesforShaders.html)
