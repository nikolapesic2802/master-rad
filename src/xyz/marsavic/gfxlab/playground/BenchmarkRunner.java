package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Stream;

public final class BenchmarkRunner {
	private static final int DEFAULT_FRAMES = 200;
	private static final int DEFAULT_WARMUP_FRAMES = 20;
	private static final int DEFAULT_WIDTH = 960;
	private static final int DEFAULT_HEIGHT = 540;
	private static final int DEFAULT_MAX_DEPTH = 12;
	private static final int DEFAULT_PROGRESS_EVERY = 20;
	private static final long DEFAULT_SEED = 0xA6A08E5C173D29FL;
	private static final int BENCHMARK_FLUSH_EVERY = 20;

	private BenchmarkRunner() {
	}

	public static void main(String[] args) {
		int frames = args.length > 0 ? Integer.parseInt(args[0]) : readPositiveInt("gfxlab.frames", DEFAULT_FRAMES);
		int warmupFrames = readNonNegativeInt("gfxlab.warmupFrames", DEFAULT_WARMUP_FRAMES);
		int width = readPositiveInt("gfxlab.width", DEFAULT_WIDTH);
		int height = readPositiveInt("gfxlab.height", DEFAULT_HEIGHT);
		int maxDepth = readPositiveInt("gfxlab.maxDepth", DEFAULT_MAX_DEPTH);
		int progressEvery = readPositiveInt("gfxlab.progressEvery", DEFAULT_PROGRESS_EVERY);
		long seed = readLong("gfxlab.seed", DEFAULT_SEED);
		String configuration = String.format(Locale.ROOT,
				"width:%d,height:%d,maxDepth:%d,warmupFrames:%d,measuredFrames:%d,seed:0x%x,java:%s",
				width, height, maxDepth, warmupFrames, frames, seed, System.getProperty("java.version", "unknown"));

		RendererFactory.Version[] versions = configuredVersions();
		SceneCatalog.ScenePreset[] scenes = configuredScenes();
		int failures = 0;

		for (RendererFactory.Version version : versions) {
			for (SceneCatalog.ScenePreset scene : scenes) {
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
		String directory = System.getProperty(
				"gfxlab.benchmarkDir",
				Path.of("benchmarks", "runs").toString()
		);
		return Path.of(directory).resolve(filename);
	}

	private static RendererFactory.Version[] configuredVersions() {
		String configured = firstNonBlank(System.getProperty("gfxlab.versions"), System.getenv("GFXLAB_VERSIONS"));
		RendererFactory.Version[] defaults = RendererFactory.Version.values();
		if (configured == null || configured.isBlank()) {
			return defaults;
		}
		RendererFactory.Version[] parsed = Stream.of(configured.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> s.toUpperCase(Locale.ROOT))
				.flatMap(name -> {
					try {
						return Stream.of(RendererFactory.Version.valueOf(name));
					} catch (IllegalArgumentException ex) {
						System.err.printf("Unknown renderer version '%s', skipping%n", name);
						return Stream.empty();
					}
				})
				.toArray(RendererFactory.Version[]::new);
		if (parsed.length == 0) {
			throw new IllegalArgumentException("No valid renderer version was configured: " + configured);
		}
		return parsed;
	}

	private static SceneCatalog.ScenePreset[] configuredScenes() {
		String configured = firstNonBlank(System.getProperty("gfxlab.scenes"), System.getenv("GFXLAB_SCENES"));
		SceneCatalog.ScenePreset[] defaults = SceneCatalog.ScenePreset.values();
		if (configured == null || configured.isBlank()) {
			return defaults;
		}
		SceneCatalog.ScenePreset[] parsed = Stream.of(configured.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> s.toUpperCase(Locale.ROOT))
				.flatMap(name -> {
					try {
						return Stream.of(SceneCatalog.ScenePreset.valueOf(name));
					} catch (IllegalArgumentException ex) {
						System.err.printf("Unknown scene preset '%s', skipping%n", name);
						return Stream.empty();
					}
				})
				.toArray(SceneCatalog.ScenePreset[]::new);
		if (parsed.length == 0) {
			throw new IllegalArgumentException("No valid scene was configured: " + configured);
		}
		return parsed;
	}

	private static int readNonNegativeInt(String key, int fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) return fallback;
		try {
			int value = Integer.parseInt(raw.trim());
			return value >= 0 ? value : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static int readPositiveInt(String key, int fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			return value > 0 ? value : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static long readLong(String key, long fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Long.decode(raw.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) return a;
		if (b != null && !b.isBlank()) return b;
		return null;
	}
}
