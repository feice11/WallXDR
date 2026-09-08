package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class RendererProgramManager {
	record ReloadResult(
			@NonNull List<ShaderError> textureErrors,
			@NonNull List<ShaderError> programErrors,
			boolean succeeded) {
		@NonNull
		static ReloadResult success(@NonNull List<ShaderError> textureErrors) {
			return new ReloadResult(List.copyOf(textureErrors), List.of(), true);
		}

		@NonNull
		static ReloadResult failure(
				@NonNull List<ShaderError> textureErrors,
				@NonNull List<ShaderError> programErrors) {
			return new ReloadResult(
					List.copyOf(textureErrors),
					List.copyOf(programErrors),
					false);
		}
	}

	private static final String FULL_SCREEN_VERTEX_SHADER = """
			attribute vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	private static final String FULL_SCREEN_VERTEX_SHADER_3 = """
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	private static final String SURFACE_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			#else
			precision mediump float;
			#endif
			
			uniform vec2 resolution;
			uniform sampler2D frame;
			uniform int hdrOutput;
			uniform int hdrNativeInput;

			vec3 srgbToLinear(vec3 c) {
				vec3 low = c / 12.92;
				vec3 high = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
				vec3 cutoff = step(c, vec3(0.04045));
				return mix(high, low, cutoff);
			}

			vec3 linearToSrgb(vec3 c) {
				vec3 low = c * 12.92;
				vec3 high = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
				vec3 cutoff = step(c, vec3(0.0031308));
				return mix(high, low, cutoff);
			}

			vec3 rec709ToBt2020(vec3 c) {
				return vec3(
					0.6274040 * c.r + 0.3292820 * c.g + 0.0433136 * c.b,
					0.0690970 * c.r + 0.9195400 * c.g + 0.0113612 * c.b,
					0.0163916 * c.r + 0.0880132 * c.g + 0.8955950 * c.b);
			}

			vec3 linearToPq(vec3 linearBt2020) {
				const float SDR_WHITE_NITS = 203.0;
				const float PQ_MAX_NITS = 10000.0;
				const float m1 = 0.1593017578125;
				const float m2 = 78.84375;
				const float c1 = 0.8359375;
				const float c2 = 18.8515625;
				const float c3 = 18.6875;
				vec3 l = clamp(linearBt2020 * (SDR_WHITE_NITS / PQ_MAX_NITS), 0.0, 1.0);
				vec3 p = pow(l, vec3(m1));
				return pow((c1 + c2 * p) / (1.0 + c3 * p), vec3(m2));
			}

			void main(void) {
				vec4 color = texture2D(frame, gl_FragCoord.xy / resolution.xy).rgba;
				if (hdrOutput != 0) {
					vec3 linear709 = hdrNativeInput != 0
							? max(color.rgb, vec3(0.0))
							: srgbToLinear(max(color.rgb, vec3(0.0)));
					color.rgb = linearToPq(rec709ToBt2020(linear709));
				} else if (hdrNativeInput != 0) {
					color.rgb = linearToSrgb(clamp(color.rgb, 0.0, 1.0));
				}
				gl_FragColor = color;
			}
			""";

	private final int maxTextures;

	@Nullable
	private String sourceText;
	@NonNull
	private PreparedShaderSource preparedShaderSource = PreparedShaderSource.empty();
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int version = 2;
	@Nullable
	private GlProgram surfaceProgram;
	@Nullable
	private GlProgram mainProgram;
	@Nullable
	private ProgramBindings surfaceBindings;

	RendererProgramManager(int maxTextures) {
		this.maxTextures = maxTextures;
	}

	void setVersion(int version) {
		this.version = version;
		prepareShaderSource(sourceText);
	}

	void setFragmentShader(@Nullable String source) {
		sourceText = source;
		prepareShaderSource(source);
	}

	boolean hasPreparedShader() {
		var fragmentShader = preparedShaderSource.getFragmentShader();
		return fragmentShader != null && !fragmentShader.getSource().isEmpty();
	}

	@NonNull
	ReloadResult reload(@NonNull Context context, @NonNull GlDevice device) {
		clearPrograms();

		var textureErrors = textureResources.load(context, device);
		var surfaceResult = device.createProgram(
				FULL_SCREEN_VERTEX_SHADER,
				SURFACE_FRAGMENT_SHADER,
				ShaderLineMapping.identity());
		if (!surfaceResult.succeeded() || surfaceResult.getProgram() == null) {
			return ReloadResult.failure(textureErrors, surfaceResult.getInfoLog());
		}

		var fragmentShader = preparedShaderSource.getFragmentShader();
		if (fragmentShader == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, List.of());
		}

		var mainResult = device.createProgram(
				preparedShaderSource.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!mainResult.succeeded() || mainResult.getProgram() == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, mainResult.getInfoLog());
		}

		surfaceProgram = surfaceResult.getProgram();
		mainProgram = mainResult.getProgram();
		surfaceBindings = new ProgramBindings(surfaceProgram);
		return ReloadResult.success(textureErrors);
	}

	void discardContextResources() {
		clearPrograms();
		textureResources.discard();
	}

	@Nullable
	GlProgram getSurfaceProgram() {
		return surfaceProgram;
	}

	@Nullable
	GlProgram getMainProgram() {
		return mainProgram;
	}

	@Nullable
	ProgramBindings getSurfaceBindings() {
		return surfaceBindings;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return preparedShaderSource.getBackBufferParameters();
	}

	float getFTimeMax() {
		return preparedShaderSource.getFTimeMax();
	}

	boolean isHdrNativeShader() {
		return preparedShaderSource.isHdrNative();
	}

	@NonNull
	ShaderTextureResources getTextureResources() {
		return textureResources;
	}

	private void prepareShaderSource(@Nullable String source) {
		preparedShaderSource = ShaderSourcePreparer.prepare(
				source,
				version,
				maxTextures);
		textureResources = ShaderTextureResources.create(
				preparedShaderSource.getSamplers());
	}

	private void clearPrograms() {
		surfaceProgram = null;
		mainProgram = null;
		surfaceBindings = null;
	}
}
