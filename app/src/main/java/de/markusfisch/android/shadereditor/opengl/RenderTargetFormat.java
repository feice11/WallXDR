package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;

/** Texture storage formats used by internal render targets. */
enum RenderTargetFormat {
	RGBA8(GLES20.GL_RGBA, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE),
	RGBA16F(GLES30.GL_RGBA16F, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT);

	final int internalFormat;
	final int format;
	final int type;

	RenderTargetFormat(int internalFormat, int format, int type) {
		this.internalFormat = internalFormat;
		this.format = format;
		this.type = type;
	}
}
