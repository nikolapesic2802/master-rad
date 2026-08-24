package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;
import xyz.marsavic.gfxlab.gpu.GpuLaunchProvenance;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

public final class BenchmarkRunner {
	private static final int BENCHMARK_FLUSH_EVERY = 20;

	private BenchmarkRunner() {
	}

	public static void main(String[] args) {
		if (args.length != 0) {
			throw new IllegalArgumentException(
					"BenchmarkRunner accepts no positional arguments; use gfxlab system properties.");
		}
		int frames = requiredPositiveInt("gfxlab.frames");
		int warmupFrames = requiredNonNegativeInt("gfxlab.warmupFrames");
		int width = requiredPositiveInt("gfxlab.width");
		int height = requiredPositiveInt("gfxlab.height");
		int maxDepth = requiredNonNegativeInt("gfxlab.maxDepth");
		int progressEvery = requiredPositiveInt("gfxlab.progressEvery");
		long seed = requiredLong("gfxlab.seed");
		int bvhLeafSize = requiredPositiveInt("gfxlab.bvhLeafSize");
		int benchmarkProcessId = requiredPositiveInt("gfxlab.benchmarkProcessId");
		String phase = requiredProperty("gfxlab.benchmarkPhase");
		if (!phase.equals("timing") && !phase.equals("counters")) {
			throw new IllegalArgumentException(
					"gfxlab.benchmarkPhase must be timing or counters.");
		}
		String baseConfiguration = String.format(Locale.ROOT,
				"phase:%s,processId:%d,width:%d,height:%d,maxDepth:%d,bvhLeafSize:%d,warmupFrames:%d,measuredFrames:%d,seed:0x%x,java:%s",
				phase, benchmarkProcessId, width, height, maxDepth, bvhLeafSize, warmupFrames, frames, seed,
				System.getProperty("java.version", "unknown"));
		RendererFactory.Version[] versions = configuredVersions();
		SceneCatalog.ScenePreset[] scenes = configuredScenes();
		int failures = 0;

		for (RendererFactory.Version version : versions) {
			for (SceneCatalog.ScenePreset scene : scenes) {
				String configuration = baseConfiguration;
				if (version.name().startsWith("GPU_")) {
					int logicalSamples =
							requiredPositiveInt("gfxlab.gpu.samplesPerFrame");
					configuration += String.format(
							Locale.ROOT,
							",logicalSamplesPerFrame:%d,renderPixelsPerLaunch:%d,replayRaysPerLaunch:%d,samplesPerPhysicalKernel:%d,physicalKernelLaunchesPerLogicalFrame:%d,kernelNanosSemantics:%s",
							logicalSamples,
							GpuLaunchProvenance.renderPixelsPerLaunch(),
							GpuLaunchProvenance.replayRaysPerLaunch(),
							GpuLaunchProvenance.SAMPLES_PER_PHYSICAL_KERNEL,
							GpuLaunchProvenance.physicalRenderLaunches(
									width, height, logicalSamples),
							GpuLaunchProvenance.KERNEL_NANOS_SEMANTICS);
				}
				System.out.printf("=== Running %s on scene %s (%d warm-up + %d measured frames, %dx%d) ===%n",
						version.name(), scene.name(), warmupFrames, frames, width, height);
				Path benchmarkFile = buildBenchmarkFile(scene.name().toLowerCase(Locale.ROOT), version.name().toLowerCase(Locale.ROOT));
				try (BenchmarkRecorder recorder = new BenchmarkRecorder(
						benchmarkFile,
						BENCHMARK_FLUSH_EVERY,
						RuntimeInfo.cpuInfo(),
						RuntimeInfo.gpuInfo(),
						version.name().toLowerCase(Locale.ROOT),
						scene.name().toLowerCase(Locale.ROOT),
						configuration
				)) {
					RendererFactory.render(scene, version, frames, warmupFrames, progressEvery, width, height, maxDepth, seed, recorder);
				} catch (Exception ex) {
					System.err.printf("Failed to run %s on %s: %s%n", version.name(), scene.name(), ex.getMessage());
					failures++;
				}
			}
		}
		if (failures > 0) {
			System.exit(2);
		}
	}

	private static Path buildBenchmarkFile(String sceneLabel, String rendererVersionLabel) {
		String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
		String filename = sceneLabel + "-" + rendererVersionLabel + "-" + ts + ".csv";
		String configuredDirectory = requiredProperty("gfxlab.benchmarkDir");
		return Path.of(configuredDirectory).resolve(filename);
	}

	private static RendererFactory.Version[] configuredVersions() {
		String configured = requiredProperty("gfxlab.versions");
		String[] names = splitUnique(configured, "renderer version");
		RendererFactory.Version[] result =
				new RendererFactory.Version[names.length];
		for (int index = 0; index < names.length; index++) {
			try {
				result[index] = RendererFactory.Version.valueOf(
						names[index].toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException error) {
				throw new IllegalArgumentException(
						"Unknown renderer version: " + names[index], error);
			}
		}
		return result;
	}

	private static SceneCatalog.ScenePreset[] configuredScenes() {
		String configured = requiredProperty("gfxlab.scenes");
		String[] names = splitUnique(configured, "scene preset");
		SceneCatalog.ScenePreset[] result =
				new SceneCatalog.ScenePreset[names.length];
		for (int index = 0; index < names.length; index++) {
			try {
				result[index] = SceneCatalog.ScenePreset.valueOf(
						names[index].toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException error) {
				throw new IllegalArgumentException(
						"Unknown scene preset: " + names[index], error);
			}
		}
		return result;
	}

	private static String[] splitUnique(String configured, String label) {
		String[] values = Arrays.stream(configured.split(",", -1))
				.map(String::trim)
				.toArray(String[]::new);
		if (values.length == 0
				|| Arrays.stream(values).anyMatch(String::isEmpty)
				|| Arrays.stream(values).distinct().count() != values.length) {
			throw new IllegalArgumentException(
					"Configured " + label + " list is empty, malformed, or repeated: "
							+ configured);
		}
		return values;
	}

	private static String requiredProperty(String key) {
		String value = System.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(
					"Missing required system property " + key + ".");
		}
		return value.trim();
	}

	private static int requiredNonNegativeInt(String key) {
		String raw = requiredProperty(key);
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < 0) {
				throw new IllegalArgumentException(
						key + " must be nonnegative.");
			}
			return value;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					key + " must be an integer.", ex);
		}
	}

	private static int requiredPositiveInt(String key) {
		String raw = requiredProperty(key);
		try {
			int value = Integer.parseInt(raw.trim());
			if (value <= 0) {
				throw new IllegalArgumentException(
						key + " must be positive.");
			}
			return value;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					key + " must be an integer.", ex);
		}
	}

	private static long requiredLong(String key) {
		String raw = requiredProperty(key);
		try {
			return Long.decode(raw.trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					key + " must be a signed 64-bit integer.", ex);
		}
	}
}
