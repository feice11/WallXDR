package de.markusfisch.android.shadereditor.preference;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WallpaperFrameRateOptions {
	public static final int MIN_FRAME_RATE = 15;
	public static final int DEFAULT_FRAME_RATE = 60;
	private static final int MAX_VSYNC_DIVISOR = 8;
	private static final float INTEGER_RATE_TOLERANCE = 0.02f;

	private WallpaperFrameRateOptions() {
	}

	@NonNull
	public static List<Integer> getSelectableRates(@NonNull Context context) {
		Set<Integer> rates = new LinkedHashSet<>();
		Display display = getDefaultDisplay(context);
		if (display != null) {
			Display.Mode[] modes = display.getSupportedModes();
			for (Display.Mode mode : modes) {
				addDivisors(rates, mode.getRefreshRate());
			}
			addDivisors(rates, display.getRefreshRate());
		}

		if (rates.isEmpty()) {
			rates.add(MIN_FRAME_RATE);
			rates.add(30);
			rates.add(DEFAULT_FRAME_RATE);
		}

		ArrayList<Integer> sorted = new ArrayList<>(rates);
		Collections.sort(sorted);
		return sorted;
	}

	public static int getClosestSupportedRate(@NonNull Context context, int requestedRate) {
		List<Integer> rates = getSelectableRates(context);
		int closest = rates.get(0);
		int bestDistance = Math.abs(closest - requestedRate);
		for (int rate : rates) {
			int distance = Math.abs(rate - requestedRate);
			if (distance < bestDistance ||
					(distance == bestDistance && rate > closest)) {
				closest = rate;
				bestDistance = distance;
			}
		}
		return closest;
	}

	private static void addDivisors(@NonNull Set<Integer> rates, float refreshRate) {
		if (!Float.isFinite(refreshRate) || refreshRate < MIN_FRAME_RATE) {
			return;
		}
		for (int divisor = 1; divisor <= MAX_VSYNC_DIVISOR; ++divisor) {
			float candidate = refreshRate / divisor;
			if (candidate < MIN_FRAME_RATE) {
				break;
			}
			int rounded = Math.round(candidate);
			if (Math.abs(candidate - rounded) <= INTEGER_RATE_TOLERANCE) {
				rates.add(rounded);
			}
		}
	}

	private static Display getDefaultDisplay(@NonNull Context context) {
		DisplayManager displayManager = context.getSystemService(DisplayManager.class);
		return displayManager == null
				? null
				: displayManager.getDisplay(Display.DEFAULT_DISPLAY);
	}
}
