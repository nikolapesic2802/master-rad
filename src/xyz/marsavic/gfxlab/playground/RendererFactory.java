package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;
import xyz.marsavic.gfxlab.gpu.BvhBuildMode;

public final class RendererFactory {

	public enum Version {
		CPU_LINEAR,
		CPU_BVH,
		GPU_LINEAR,
		GPU_BVH,
		GPU_WEIGHTED_BVH
	}

	private RendererFactory() {
	}

	public static HeadlessCpuRenderer.RenderedImage render(SceneCatalog.ScenePreset preset,
	                                                       Version version,
	                                                       int frames,
	                                                       int warmupFrames,
	                                                       int progressEvery,
	                                                       int width,
	                                                       int height,
	                                                       int maxDepth,
	                                                       long seed,
	                                                       BenchmarkRecorder recorder) {
		SceneCatalog.SceneSetup setup = SceneCatalog.create(preset);
		return switch (version) {
			case CPU_LINEAR -> new HeadlessCpuRenderer(setup.scene(), setup.camera(), width, height, maxDepth, seed)
					.render(frames, warmupFrames, progressEvery, recorder);
			case CPU_BVH -> new HeadlessCpuBvhRenderer(setup.scene(), setup.camera(), width, height, maxDepth, seed)
					.render(frames, warmupFrames, progressEvery, recorder);
			case GPU_LINEAR -> new HeadlessGpuRenderer(setup.scene(), setup.camera(), width, height, maxDepth, seed)
					.render(frames, warmupFrames, progressEvery, recorder);
			case GPU_BVH -> new HeadlessGpuRenderer(setup.scene(), setup.camera(), width, height, maxDepth, seed, true)
					.render(frames, warmupFrames, progressEvery, recorder);
			case GPU_WEIGHTED_BVH -> new HeadlessGpuRenderer(
					setup.scene(), setup.camera(), width, height, maxDepth, seed,
					BvhBuildMode.WEIGHTED_SAH
			).render(frames, warmupFrames, progressEvery, recorder);
		};
	}

	public static int samplesPerFrame(Version version) {
		return switch (version) {
			case CPU_LINEAR, CPU_BVH -> 1;
			case GPU_LINEAR, GPU_BVH, GPU_WEIGHTED_BVH -> 8;
		};
	}

	public static int effectiveSamplesPerFrame(Version version) {
		int defaultValue = samplesPerFrame(version);
		if (version == Version.CPU_LINEAR || version == Version.CPU_BVH) {
			return defaultValue;
		}
		String raw = System.getProperty("gfxlab.gpu.samplesPerFrame");
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value > 0) {
				return value;
			}
		} catch (NumberFormatException ignored) {
			// Rejected below with the same diagnostic as a non-positive value.
		}
		throw new IllegalArgumentException(
				"gfxlab.gpu.samplesPerFrame must be a positive integer: " + raw);
	}
}
