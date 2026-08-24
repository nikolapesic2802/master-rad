package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;

public final class RendererFactory {

	public enum Version {
		CPU_LINEAR
	}

	private RendererFactory() {
	}

	public static HeadlessCpuRenderer.RenderedImage render(
			SceneCatalog.ScenePreset preset,
			Version version,
			int frames,
			int warmupFrames,
			int progressEvery,
			int width,
			int height,
			int maxDepth,
			long seed,
			BenchmarkRecorder recorder
	) {
		SceneCatalog.SceneSetup setup = SceneCatalog.create(preset);
		return new HeadlessCpuRenderer(
				setup.scene(), setup.camera(), width, height, maxDepth, seed
		).render(frames, warmupFrames, progressEvery, recorder);
	}

	public static int samplesPerFrame(Version version) {
		return 1;
	}
}
