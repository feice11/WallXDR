package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import de.markusfisch.android.shadereditor.opengl.HdrDisplayCapabilities;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;

public class ShaderView extends GLSurfaceView {
	private static final String TAG = "ShaderEditor.HDR";

	private ShaderRenderer renderer;

	public ShaderView(Context context, int renderMode) {
		super(context);
		init(context, renderMode, false);
	}

	public ShaderView(Context context, int renderMode, boolean preferHdr) {
		super(context);
		init(context, renderMode, preferHdr);
	}

	public ShaderView(Context context) {
		super(context);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY, false);
	}

	public ShaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY, false);
	}

	@Override
	public void onPause() {
		super.onPause();
		renderer.unregisterListeners();
	}

	// Click handling is implemented in renderer.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		renderer.touchAt(event);
		return true;
	}

	public void setFragmentShader(String src, float quality) {
		onPause();
		// When pasting text from other apps, e.g. Gmail, the
		// text is sometimes tainted with useless non-ascii
		// characters that can raise an exception in the shader
		// compiler. To still allow UTF-8 characters in comments,
		// the source is cleaned up here.
		renderer.setFragmentShader(removeNonAscii(src), quality);
		onResume();
	}

	public ShaderRenderer getRenderer() {
		return renderer;
	}

	private void init(Context context, int renderMode, boolean preferHdr) {
		renderer = new ShaderRenderer(context);

		HdrDisplayCapabilities displayCapabilities = HdrDisplayCapabilities.query(context);
		renderer.setDisplayPeakNits(displayCapabilities.getDesiredMaxLuminance());
		boolean requestHdr = preferHdr && displayCapabilities.supportsHdr10();
		Log.i(TAG, "Display capabilities: " + displayCapabilities.describe() +
				", preferHdr=" + preferHdr + ", requestHdr=" + requestHdr);
		HdrEglController hdrController = new HdrEglController(renderer, requestHdr);

		// On some devices it's important to setEGLContextClientVersion()
		// even if the docs say it's not used when setEGLContextFactory()
		// is called. Not doing so will crash the app (e.g. on the FP1).
		setEGLContextClientVersion(2);
		setEGLConfigChooser(hdrController);
		setEGLContextFactory(new ContextFactory(renderer));
		setEGLWindowSurfaceFactory(hdrController);
		setRenderer(renderer);
		setRenderMode(renderMode);
	}

	private static String removeNonAscii(String text) {
		return text == null
				? null
				: text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}

	private static class ContextFactory
			implements GLSurfaceView.EGLContextFactory {
		private final ShaderRenderer renderer;

		private ContextFactory(ShaderRenderer renderer) {
			this.renderer = renderer;
		}

		@Override
		public EGLContext createContext(EGL10 egl, EGLDisplay display,
				EGLConfig eglConfig) {
			final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
			EGLContext context = egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							3,
							EGL10.EGL_NONE
					});
			if (context != null && context != EGL10.EGL_NO_CONTEXT &&
					context.getGL() != null) {
				renderer.setVersion(3);
				Log.i(TAG, "Created GLES 3 context");
				return context;
			}

			int error = egl.eglGetError();
			Log.w(TAG, "GLES 3 context unavailable, falling back to GLES 2: 0x" +
					Integer.toHexString(error));
			renderer.setVersion(2);
			return egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							2,
							EGL10.EGL_NONE
					});
		}

		@Override
		public void destroyContext(EGL10 egl, EGLDisplay display,
				EGLContext context) {
			egl.eglDestroyContext(display, context);
		}
	}

	private static class HdrEglController implements
			GLSurfaceView.EGLConfigChooser,
			GLSurfaceView.EGLWindowSurfaceFactory {
		private static final int EGL_RENDERABLE_TYPE = 0x3040;
		private static final int EGL_OPENGL_ES2_BIT = 0x0004;
		private static final int EGL_SURFACE_TYPE = 0x3033;
		private static final int EGL_WINDOW_BIT = 0x0004;
		private static final int EGL_EXTENSIONS = 0x3055;
		private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
		private static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340;

		private final ShaderRenderer renderer;
		private final boolean requestHdr;
		private boolean hdrConfigSelected;

		private HdrEglController(ShaderRenderer renderer, boolean requestHdr) {
			this.renderer = renderer;
			this.requestHdr = requestHdr;
		}

		@Override
		public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
			if (requestHdr) {
				EGLConfig hdrConfig = chooseConfig(egl, display, 10, 10, 10, 2);
				if (hdrConfig != null) {
					hdrConfigSelected = true;
					Log.i(TAG, "Selected HDR EGL config: " + describeConfig(egl, display, hdrConfig));
					return hdrConfig;
				}
				Log.w(TAG, "No 10-bit EGL window config; falling back to SDR config");
			}

			hdrConfigSelected = false;
			EGLConfig sdrConfig = chooseConfig(egl, display, 8, 8, 8, 0);
			if (sdrConfig == null) {
				throw new IllegalArgumentException("No compatible SDR EGL config");
			}
			Log.i(TAG, "Selected SDR EGL config: " + describeConfig(egl, display, sdrConfig));
			return sdrConfig;
		}

		@Override
		public EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display,
				EGLConfig config, Object nativeWindow) {
			renderer.setHdrSurfaceActive(false);
			if (requestHdr && hdrConfigSelected && renderer.getGlesVersion() >= 3) {
				String extensions = egl.eglQueryString(display, EGL_EXTENSIONS);
				boolean colorspace = extensions != null &&
						extensions.contains("EGL_KHR_gl_colorspace") &&
						extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq");
				Log.i(TAG, "EGL extensions support BT.2020 PQ=" + colorspace);
				if (colorspace) {
					try {
						EGLSurface hdrSurface = egl.eglCreateWindowSurface(
								display,
								config,
								nativeWindow,
								new int[]{
										EGL_GL_COLORSPACE_KHR,
										EGL_GL_COLORSPACE_BT2020_PQ_EXT,
										EGL10.EGL_NONE
								});
						if (hdrSurface != null && hdrSurface != EGL10.EGL_NO_SURFACE) {
							renderer.setHdrSurfaceActive(true);
							Log.i(TAG, "Created BT.2020 PQ HDR window surface");
							return hdrSurface;
						}
						Log.w(TAG, "HDR window surface creation failed: 0x" +
								Integer.toHexString(egl.eglGetError()) + "; falling back to SDR");
					} catch (RuntimeException e) {
						Log.w(TAG, "HDR window surface creation threw; falling back to SDR", e);
					}
				}
			} else if (requestHdr && renderer.getGlesVersion() < 3) {
				Log.w(TAG, "HDR requested but GLES 3 context is unavailable; falling back to SDR");
			}

			EGLSurface surface = egl.eglCreateWindowSurface(
					display,
					config,
					nativeWindow,
					null);
			if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
				Log.e(TAG, "SDR window surface creation failed: 0x" +
						Integer.toHexString(egl.eglGetError()));
			}
			return surface;
		}

		@Override
		public void destroySurface(EGL10 egl, EGLDisplay display, EGLSurface surface) {
			renderer.setHdrSurfaceActive(false);
			egl.eglDestroySurface(display, surface);
		}

		private static EGLConfig chooseConfig(EGL10 egl, EGLDisplay display,
				int red, int green, int blue, int alpha) {
			int[] attributes = {
					EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
					EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
					EGL10.EGL_RED_SIZE, red,
					EGL10.EGL_GREEN_SIZE, green,
					EGL10.EGL_BLUE_SIZE, blue,
					EGL10.EGL_ALPHA_SIZE, alpha,
					EGL10.EGL_DEPTH_SIZE, 16,
					EGL10.EGL_NONE
			};
			EGLConfig[] configs = new EGLConfig[32];
			int[] count = new int[1];
			if (!egl.eglChooseConfig(display, attributes, configs, configs.length, count) ||
					count[0] < 1) {
				return null;
			}
			return configs[0];
		}

		private static String describeConfig(EGL10 egl, EGLDisplay display,
				EGLConfig config) {
			return "r" + getConfigValue(egl, display, config, EGL10.EGL_RED_SIZE) +
					"g" + getConfigValue(egl, display, config, EGL10.EGL_GREEN_SIZE) +
					"b" + getConfigValue(egl, display, config, EGL10.EGL_BLUE_SIZE) +
					"a" + getConfigValue(egl, display, config, EGL10.EGL_ALPHA_SIZE) +
					" depth=" + getConfigValue(egl, display, config, EGL10.EGL_DEPTH_SIZE);
		}

		private static int getConfigValue(EGL10 egl, EGLDisplay display,
				EGLConfig config, int attribute) {
			int[] value = new int[1];
			return egl.eglGetConfigAttrib(display, config, attribute, value)
					? value[0]
					: -1;
		}
	}
}
