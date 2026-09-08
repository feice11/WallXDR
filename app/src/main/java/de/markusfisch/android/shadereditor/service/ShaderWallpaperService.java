package de.markusfisch.android.shadereditor.service;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.preference.WallpaperFrameRateOptions;
import de.markusfisch.android.shadereditor.project.LegacyShaderProjectSource;
import de.markusfisch.android.shadereditor.project.ShaderProjectSession;
import de.markusfisch.android.shadereditor.receiver.BatteryLevelReceiver;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class ShaderWallpaperService extends WallpaperService {
	private static final String FRAME_RATE_TAG = "ShaderEditor.FrameRate";
	private static ShaderWallpaperEngine engine;

	private ComponentName batteryLevelComponent;

	public static boolean isRunning() {
		return engine != null;
	}

	public static void setRenderMode(int renderMode) {
		if (engine != null) {
			engine.setRenderMode(renderMode);
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		batteryLevelComponent = new ComponentName(this,
				BatteryLevelReceiver.class);
		enableComponent(batteryLevelComponent, true);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		enableComponent(batteryLevelComponent, false);
		engine = null;
	}

	@Override
	public Engine onCreateEngine() {
		engine = new ShaderWallpaperEngine();
		return engine;
	}

	private class ShaderWallpaperEngine
			extends Engine
			implements SharedPreferences.OnSharedPreferenceChangeListener {
		private ShaderWallpaperView view;

		@Override
		public void onSharedPreferenceChanged(
				SharedPreferences preferences,
				String key) {
			if (Preferences.WALLPAPER_SHADER.equals(key)) {
				setShader();
			} else if (Preferences.WALLPAPER_FRAME_RATE.equals(key)) {
				ShaderEditorApp.preferences.update(ShaderWallpaperService.this);
				if (view != null) {
					view.setTargetFrameRate(
							ShaderEditorApp.preferences.getWallpaperFrameRate());
				}
			}
		}

		@Override
		public void onCreate(SurfaceHolder holder) {
			super.onCreate(holder);
			view = new ShaderWallpaperView();
			setShader();
		}

		@Override
		public void onSurfaceCreated(SurfaceHolder holder) {
			super.onSurfaceCreated(holder);
			if (view != null) {
				view.applyFrameRateToSurface();
			}
		}

		@Override
		public void onDestroy() {
			super.onDestroy();
			// Unregister listener to prevent memory leaks.
			ShaderEditorApp.preferences.getSharedPreferences()
					.unregisterOnSharedPreferenceChangeListener(this);
			if (view != null) {
				view.destroy();
				view = null;
			}
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);
			if (view == null) {
				return;
			}
			if (visible) {
				view.onResume();
			} else {
				view.onPause();
			}
		}

		@Override
		public void onTouchEvent(MotionEvent e) {
			super.onTouchEvent(e);
			if (view != null) {
				view.getRenderer().touchAt(e);
			}
		}

		@Override
		public void onOffsetsChanged(
				float xOffset,
				float yOffset,
				float xStep,
				float yStep,
				int xPixels,
				int yPixels) {
			if (view != null) {
				view.getRenderer().setOffset(xOffset, yOffset);
			}
		}

		private ShaderWallpaperEngine() {
			super();
			ShaderEditorApp.preferences.getSharedPreferences()
					.registerOnSharedPreferenceChangeListener(this);
			setTouchEventsEnabled(true);
		}

		private void setRenderMode(int renderMode) {
			if (view != null) {
				view.setFrameSchedulingEnabled(
						renderMode != GLSurfaceView.RENDERMODE_WHEN_DIRTY);
			}
		}

		private void setShader() {
			ShaderProjectSession projectSession = openWallpaperProjectSession();
			if (view != null && projectSession != null) {
				view.getRenderer().setFragmentShader(
						projectSession.getEntryPointSource(),
						projectSession.getQuality());
			}
		}

		@Nullable
		private ShaderProjectSession openWallpaperProjectSession() {
			DataSource dataSource = Database.getInstance(
					ShaderWallpaperService.this).getDataSource();

			long shaderId = ShaderEditorApp.preferences.getWallpaperShader();
			DataRecords.Shader shader = dataSource.shader.getShader(shaderId);

			// If the saved shader doesn't exist, pick a random one.
			if (shader == null) {
				shader = dataSource.shader.getRandomShader();

				// If there are no shaders at all, we can't do anything.
				if (shader == null) {
					return null;
				}

				// Update the preferences to store the new random shader ID.
				ShaderEditorApp.preferences.setWallpaperShader(shader.id());
			}

			return new LegacyShaderProjectSource(shader).openSession();
		}

		private class ShaderWallpaperView extends ShaderView {
			private static final long NS_PER_SECOND = 1000000000L;
			private final Choreographer choreographer = Choreographer.getInstance();
			private final Choreographer.FrameCallback frameCallback = this::onVsync;
			private boolean visible;
			private boolean frameSchedulingEnabled;
			private boolean callbackPosted;
			private long nextFrameTimeNs;
			private long framePeriodNs = NS_PER_SECOND /
					WallpaperFrameRateOptions.DEFAULT_FRAME_RATE;
			private int targetFrameRate = WallpaperFrameRateOptions.DEFAULT_FRAME_RATE;

			public ShaderWallpaperView() {
				super(ShaderWallpaperService.this,
						GLSurfaceView.RENDERMODE_WHEN_DIRTY,
						true);
				frameSchedulingEnabled = !ShaderEditorApp.preferences.isBatteryLow();
				setTargetFrameRate(ShaderEditorApp.preferences.getWallpaperFrameRate());
			}

			@Override
			public void onResume() {
				super.onResume();
				visible = true;
				nextFrameTimeNs = 0L;
				scheduleFrameCallback();
			}

			@Override
			public void onPause() {
				visible = false;
				removeFrameCallback();
				super.onPause();
			}

			private void setFrameSchedulingEnabled(boolean enabled) {
				frameSchedulingEnabled = enabled;
				nextFrameTimeNs = 0L;
				if (enabled) {
					scheduleFrameCallback();
				} else {
					removeFrameCallback();
				}
			}

			private void setTargetFrameRate(int requestedRate) {
				targetFrameRate = WallpaperFrameRateOptions.getClosestSupportedRate(
						ShaderWallpaperService.this,
						requestedRate);
				framePeriodNs = Math.max(1L, NS_PER_SECOND / targetFrameRate);
				nextFrameTimeNs = 0L;
				Log.i(FRAME_RATE_TAG, "Wallpaper target frame rate=" +
						targetFrameRate + " Hz, selectable=" +
						WallpaperFrameRateOptions.getSelectableRates(
								ShaderWallpaperService.this));
				applyFrameRateToSurface();
			}

			private void onVsync(long frameTimeNanos) {
				callbackPosted = false;
				if (!visible || !frameSchedulingEnabled) {
					return;
				}
				if (nextFrameTimeNs == 0L || frameTimeNanos >= nextFrameTimeNs) {
					requestRender();
					if (nextFrameTimeNs == 0L) {
						nextFrameTimeNs = frameTimeNanos + framePeriodNs;
					} else {
						do {
							nextFrameTimeNs += framePeriodNs;
						} while (nextFrameTimeNs <= frameTimeNanos);
					}
				}
				scheduleFrameCallback();
			}

			private void scheduleFrameCallback() {
				if (!visible || !frameSchedulingEnabled || callbackPosted) {
					return;
				}
				callbackPosted = true;
				choreographer.postFrameCallback(frameCallback);
			}

			private void removeFrameCallback() {
				if (!callbackPosted) {
					return;
				}
				callbackPosted = false;
				choreographer.removeFrameCallback(frameCallback);
			}

			private void applyFrameRateToSurface() {
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
					return;
				}
				Surface surface = getHolder().getSurface();
				if (surface == null || !surface.isValid()) {
					return;
				}
				try {
					surface.setFrameRate(
							targetFrameRate,
							Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
					Log.i(FRAME_RATE_TAG, "Applied Surface frame rate hint=" +
							targetFrameRate + " Hz");
				} catch (IllegalStateException e) {
					Log.w(FRAME_RATE_TAG, "Unable to apply wallpaper frame rate", e);
				}
			}

			@Override
			public final SurfaceHolder getHolder() {
				return ShaderWallpaperEngine.this.getSurfaceHolder();
			}

			public void destroy() {
				visible = false;
				removeFrameCallback();
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					Surface surface = getHolder().getSurface();
					if (surface != null && surface.isValid()) {
						try {
							surface.setFrameRate(
									0f,
									Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
						} catch (IllegalStateException ignored) {
						}
					}
				}
				super.onDetachedFromWindow();
			}
		}
	}

	private void enableComponent(ComponentName name, boolean enable) {
		getPackageManager().setComponentEnabledSetting(name,
				enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP);
	}
}
