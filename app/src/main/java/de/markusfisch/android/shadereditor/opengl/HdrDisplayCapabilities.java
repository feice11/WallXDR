package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import androidx.annotation.NonNull;

import java.util.Arrays;

public final class HdrDisplayCapabilities {
	private final boolean hdr10;
	private final float desiredMaxLuminance;
	private final float desiredMaxAverageLuminance;
	private final float desiredMinLuminance;
	@NonNull
	private final int[] supportedTypes;

	private HdrDisplayCapabilities(
			boolean hdr10,
			float desiredMaxLuminance,
			float desiredMaxAverageLuminance,
			float desiredMinLuminance,
			@NonNull int[] supportedTypes) {
		this.hdr10 = hdr10;
		this.desiredMaxLuminance = desiredMaxLuminance;
		this.desiredMaxAverageLuminance = desiredMaxAverageLuminance;
		this.desiredMinLuminance = desiredMinLuminance;
		this.supportedTypes = supportedTypes;
	}

	@SuppressWarnings("deprecation")
	@NonNull
	public static HdrDisplayCapabilities query(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			return unavailable();
		}

		DisplayManager displayManager = (DisplayManager) context.getSystemService(
				Context.DISPLAY_SERVICE);
		if (displayManager == null) {
			return unavailable();
		}

		Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
		if (display == null) {
			return unavailable();
		}

		Display.HdrCapabilities capabilities = display.getHdrCapabilities();
		if (capabilities == null) {
			return unavailable();
		}

		int[] types = capabilities.getSupportedHdrTypes();
		boolean hdr10 = false;
		for (int type : types) {
			if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
				hdr10 = true;
				break;
			}
		}

		return new HdrDisplayCapabilities(
				hdr10,
				capabilities.getDesiredMaxLuminance(),
				capabilities.getDesiredMaxAverageLuminance(),
				capabilities.getDesiredMinLuminance(),
				types.clone());
	}

	public boolean supportsHdr10() {
		return hdr10;
	}

	public float getDesiredMaxLuminance() {
		return desiredMaxLuminance;
	}

	@NonNull
	public String describe() {
		return "HDR10=" + hdr10 +
				", types=" + Arrays.toString(supportedTypes) +
				", desiredMax=" + desiredMaxLuminance +
				", desiredMaxAverage=" + desiredMaxAverageLuminance +
				", desiredMin=" + desiredMinLuminance;
	}

	@NonNull
	private static HdrDisplayCapabilities unavailable() {
		return new HdrDisplayCapabilities(false, 0f, 0f, 0f, new int[0]);
	}
}
