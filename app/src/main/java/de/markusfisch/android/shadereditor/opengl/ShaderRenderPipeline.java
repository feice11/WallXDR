package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

final class ShaderRenderPipeline {
	private static final String HDR_TAG = "ShaderEditor.HDR";
	private static final int THUMBNAIL_WIDTH = 144;
	private static final int THUMBNAIL_HEIGHT = 144;
	private static final String SURFACE_FRAME = "frame";

	private final float[] thumbnailResolution =
			new float[]{THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT};
	private final float[] drawResolution = new float[2];
	private final TextureParameters thumbnailTextureParameters =
			new TextureParameters(
					GLES20.GL_LINEAR,
					GLES20.GL_LINEAR,
					GLES20.GL_CLAMP_TO_EDGE,
					GLES20.GL_CLAMP_TO_EDGE);
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Mesh fullScreenQuadMesh;
	private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
	private final GlTexture2D[] targetTextures = new GlTexture2D[2];
	private int frontTarget;
	private int backTarget = 1;
	@NonNull
	private RenderTargetFormat targetFormat = RenderTargetFormat.RGBA8;
	@Nullable
	private GlFramebuffer thumbnailFramebuffer;
	@Nullable
	private GlTexture2D thumbnailTexture;

	ShaderRenderPipeline(@NonNull GlDevice device, @NonNull Mesh fullScreenQuadMesh) {
		this.device = device;
		this.fullScreenQuadMesh = fullScreenQuadMesh;
	}

	@NonNull
	List<ShaderError> createContextResources() {
		ArrayList<ShaderError> errors = new ArrayList<>();
		thumbnailTexture = device.createTexture2D();
		device.applyTextureParameters(thumbnailTexture, thumbnailTextureParameters);
		device.allocateTexture2D(
				thumbnailTexture,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT);
		thumbnailFramebuffer = device.createFramebuffer();
		device.attachColor(thumbnailFramebuffer, thumbnailTexture);
		int status = device.checkFramebufferStatus(thumbnailFramebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Thumbnail framebuffer incomplete: 0x" +
							Integer.toHexString(status)));
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void discardContextResources() {
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
		discardTargets();
	}

	void releaseTargets() {
		for (int i = 0; i < framebuffers.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			framebuffers[i] = null;
			device.deleteTexture(targetTextures[i]);
			targetTextures[i] = null;
		}
		frontTarget = 0;
		backTarget = 1;
		targetFormat = RenderTargetFormat.RGBA8;
	}

	boolean hasTargets() {
		return framebuffers[0] != null &&
				framebuffers[1] != null &&
				targetTextures[0] != null &&
				targetTextures[1] != null;
	}

	@NonNull
	List<ShaderError> ensureTargets(
			@NonNull Context context,
			int width,
			int height,
			@NonNull BackBufferParameters parameters,
			boolean preferFp16) {
		ArrayList<ShaderError> errors = new ArrayList<>();
		if (hasTargets()) {
			return errors;
		}

		releaseTargets();
		if (preferFp16) {
			ArrayList<String> fp16Errors = new ArrayList<>();
			if (createTargets(
					context,
					width,
					height,
					parameters,
					RenderTargetFormat.RGBA16F,
					fp16Errors)) {
				targetFormat = RenderTargetFormat.RGBA16F;
				Log.i(HDR_TAG, "Using RGBA16F HDR ping-pong render targets " +
						width + "x" + height);
				device.bindFramebuffer(null);
				return errors;
			}
			Log.w(HDR_TAG, "RGBA16F render target setup failed; " +
					"falling back both ping-pong targets to RGBA8: " +
					fp16Errors);
			releaseTargets();
		}

		ArrayList<String> rgba8Errors = new ArrayList<>();
		if (createTargets(
				context,
				width,
				height,
				parameters,
				RenderTargetFormat.RGBA8,
				rgba8Errors)) {
			targetFormat = RenderTargetFormat.RGBA8;
			if (preferFp16) {
				Log.i(HDR_TAG, "RGBA8 render target fallback active");
			}
		} else {
			releaseTargets();
			for (String message : rgba8Errors) {
				errors.add(ShaderError.createGeneral(message));
			}
		}
		device.bindFramebuffer(null);
		return errors;
	}

	boolean isFp16TargetActive() {
		return hasTargets() && targetFormat == RenderTargetFormat.RGBA16F;
	}

	void renderMainPass(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program) {
		GlFramebuffer framebuffer = framebuffers[frontTarget];
		GlTexture2D targetTexture = targetTextures[frontTarget];
		if (framebuffer == null || targetTexture == null) {
			return;
		}

		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, targetTexture.getWidth(), targetTexture.getHeight());
		device.applyBindings(bindings);
		device.draw(fullScreenQuadMesh, program);
	}

	void renderSurfacePass(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			int surfaceWidth,
			int surfaceHeight,
			boolean hdrOutput,
			boolean hdrNativeInput) {
		drawSurface(
				targetTextures[frontTarget],
				surfaceWidth,
				surfaceHeight,
				null,
				surfaceBindings,
				surfaceProgram,
				hdrOutput,
				hdrNativeInput);
	}

	@Nullable
	GlTexture2D getBackTexture() {
		return targetTextures[backTarget];
	}

	void swapTargets() {
		int target = frontTarget;
		frontTarget = backTarget;
		backTarget = target;
	}

	@Nullable
	byte[] captureThumbnail(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			boolean hdrNativeInput) {
		if (thumbnailFramebuffer == null || targetTextures[frontTarget] == null) {
			return null;
		}

		drawSurface(
				targetTextures[frontTarget],
				(int) thumbnailResolution[0],
				(int) thumbnailResolution[1],
				thumbnailFramebuffer,
				surfaceBindings,
				surfaceProgram,
				false,
				hdrNativeInput);

		final int pixels = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
		final int[] rgba = new int[pixels];
		final IntBuffer buffer = IntBuffer.wrap(rgba);
		device.readPixels(
				0,
				0,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT,
				buffer);
		device.bindFramebuffer(null);

		int[] argb = new int[pixels];
		for (int y = 0; y < THUMBNAIL_HEIGHT; ++y) {
			for (int x = 0; x < THUMBNAIL_WIDTH; ++x) {
				int srcIdx = y * THUMBNAIL_WIDTH + x;
				int destIdx = (THUMBNAIL_HEIGHT - y - 1) * THUMBNAIL_WIDTH + x;
				int pixel = rgba[srcIdx];
				argb[destIdx] = 0xff000000
						| ((pixel << 16) & 0x00ff0000)
						| (pixel & 0x0000ff00)
						| ((pixel >> 16) & 0x000000ff);
			}
		}

		try (var out = new ByteArrayOutputStream()) {
			Bitmap.createBitmap(
					argb,
					THUMBNAIL_WIDTH,
					THUMBNAIL_HEIGHT,
					Bitmap.Config.ARGB_8888).compress(
					Bitmap.CompressFormat.PNG,
					100,
					out);
			return out.toByteArray();
		} catch (OutOfMemoryError | IllegalArgumentException | IOException e) {
			return null;
		}
	}

	private void drawSurface(
			@Nullable GlTexture2D sourceTexture,
			int drawWidth,
			int drawHeight,
			@Nullable GlFramebuffer targetFramebuffer,
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			boolean hdrOutput,
			boolean hdrNativeInput) {
		if (sourceTexture == null) {
			return;
		}

		device.bindFramebuffer(targetFramebuffer);
		device.setViewport(0, 0, drawWidth, drawHeight);
		drawResolution[0] = drawWidth;
		drawResolution[1] = drawHeight;
		surfaceBindings.clear();
		surfaceBindings.setFloat2(
				ShaderRenderer.UNIFORM_RESOLUTION,
				drawResolution);
		surfaceBindings.setTexture(SURFACE_FRAME, sourceTexture);
		surfaceBindings.setInt("hdrOutput", hdrOutput ? 1 : 0);
		surfaceBindings.setInt("hdrNativeInput", hdrNativeInput ? 1 : 0);
		device.applyBindings(surfaceBindings);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		device.draw(fullScreenQuadMesh, surfaceProgram);
	}

	private void discardTargets() {
		framebuffers[0] = null;
		framebuffers[1] = null;
		targetTextures[0] = null;
		targetTextures[1] = null;
		frontTarget = 0;
		backTarget = 1;
		targetFormat = RenderTargetFormat.RGBA8;
	}

	private boolean createTargets(
			@NonNull Context context,
			int width,
			int height,
			@NonNull BackBufferParameters parameters,
			@NonNull RenderTargetFormat format,
			@NonNull List<String> errors) {
		boolean frontComplete = createTarget(
				context,
				frontTarget,
				width,
				height,
				parameters,
				format,
				errors);
		boolean backComplete = createTarget(
				context,
				backTarget,
				width,
				height,
				parameters,
				format,
				errors);
		return frontComplete && backComplete;
	}

	private boolean createTarget(
			@NonNull Context context,
			int index,
			int width,
			int height,
			@NonNull BackBufferParameters parameters,
			@NonNull RenderTargetFormat format,
			@NonNull List<String> errors) {
		GlTexture2D texture = device.createTexture2D();
		targetTextures[index] = texture;

		Bitmap bitmap = parameters.getPresetBitmap(context, width, height);
		String uploadError = null;
		if (format == RenderTargetFormat.RGBA16F) {
			device.allocateTexture2D(texture, width, height, format);
			if (bitmap != null) {
				uploadError = device.uploadTexture2DSubImage(texture, bitmap, true);
			}
		} else if (bitmap != null) {
			uploadError = device.uploadTexture2D(texture, bitmap, true);
		} else {
			device.allocateTexture2D(texture, width, height, format);
		}
		if (uploadError != null) {
			errors.add("Render target " + index + " (" + format + "): " + uploadError);
		}
		if (bitmap != null) {
			bitmap.recycle();
		}

		device.applyTextureParameters(texture, parameters);
		device.generateMipmap(texture);

		GlFramebuffer framebuffer = device.createFramebuffer();
		framebuffers[index] = framebuffer;
		device.attachColor(framebuffer, texture);
		int status = device.checkFramebufferStatus(framebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add("Render target " + index + " (" + format +
					") framebuffer incomplete: 0x" + Integer.toHexString(status));
		}

		if (bitmap == null) {
			device.bindFramebuffer(framebuffer);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
		}
		return uploadError == null && status == GLES20.GL_FRAMEBUFFER_COMPLETE;
	}
}
