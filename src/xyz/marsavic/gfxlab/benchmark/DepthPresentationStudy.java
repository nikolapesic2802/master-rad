package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.GpuLaunchProvenance;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;
import xyz.marsavic.gfxlab.playground.SceneCatalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Renders the fixed-exposure images used by the path-depth figure. */
final class DepthPresentationStudy {
	private static final int CHANNELS = 3;
	private static final int RENDER_PIXELS_PER_LAUNCH = 65_536;
	private static final int TILE_WIDTH = Math.min(DepthStudy.IMAGE_WIDTH, RENDER_PIXELS_PER_LAUNCH);
	private static final int TILE_HEIGHT = Math.min(DepthStudy.IMAGE_HEIGHT,
			Math.max(1, RENDER_PIXELS_PER_LAUNCH / TILE_WIDTH));
	private static final int PHYSICAL_LAUNCHES_PER_FRAME =
			ceilDiv(DepthStudy.IMAGE_WIDTH, TILE_WIDTH)
					* ceilDiv(DepthStudy.IMAGE_HEIGHT, TILE_HEIGHT);
	private static final double TONE_MAP_PRE_FACTOR = 0x1p-4;
	private static final List<Integer> STRIP_DEPTHS = List.of(0, 2, 4, 8, 12, 20, 32);
	private static final String DRIVER_PROPERTY = "gfxlab.gpu.driverVersion";
	private static final String SIMILARITY_HEADER =
			"schemaVersion,depthOrdinal,maximumPathDepth,referenceDepth,similarityPercent,"
			+ "meanAbsoluteChannelDifference,sourceImageSha256,referenceImageSha256";
	private static final String STRIP_HEADER =
			"panelOrdinal,maximumPathDepth,image,sourceImageSha256";

	static record BuildIdentity(String packedGeometrySha256, String topologySha256) { }

	private record ImageResult(
			int depth, String fileName, String sha256,
			double meanAbsoluteChannelDifference, double similarityPercent
	) { }

	private DepthPresentationStudy() { }

	static void run(
			Path projectRoot, Path outputRoot, String compiledClassesSha256,
			String sourceCommit, String sourceTree
	) throws Exception {
		Path target = outputRoot.resolve("presentation");
		EvidenceFiles.requireUnattempted(outputRoot, target);
		Path partial = target.resolveSibling(target.getFileName() + ".attempt-" + UUID.randomUUID());
		Files.createDirectory(partial);
		long started = System.nanoTime();

		SceneCatalog.SceneSetup setup = SceneCatalog.create(SceneCatalog.ScenePreset.GI_TEST);
		MethodCatalog.Method method = DepthStudy.buildUniformMethod(setup.scene());
		GpuCamera camera = DepthStudy.depthCamera(setup);
		Map<Integer, ImageResult> results = new LinkedHashMap<>();
		String deviceName;
		String computeCapability;
		String ptxSha256;
		double postFactor;

		float[] frame = new float[Math.multiplyExact(
				Math.multiplyExact(DepthStudy.IMAGE_WIDTH, DepthStudy.IMAGE_HEIGHT), CHANNELS)];
		double[] sums = new double[frame.length];
		try (GpuRayTracer tracer = new GpuRayTracer(
				DepthStudy.IMAGE_WIDTH, DepthStudy.IMAGE_HEIGHT, 1, true, false)) {
			requireGpuIdentity(tracer);
			deviceName = tracer.deviceInfo().name();
			computeCapability = tracer.deviceInfo().computeCapability();
			ptxSha256 = tracer.compiledPtxSha256();

			renderDepth(tracer, method, camera, DepthStudy.IMAGE_REFERENCE_DEPTH, frame, sums);
			postFactor = toneMapPostFactor(sums);
			BufferedImage referenceImage = toneMap(sums, postFactor);
			int[] referenceRgb = rgb(referenceImage);
			ImageResult reference = writeImage(
					partial, DepthStudy.IMAGE_REFERENCE_DEPTH, referenceImage, referenceRgb);
			results.put(reference.depth(), reference);

			for (int depth : DepthStudy.DEPTHS) {
				if (depth == DepthStudy.IMAGE_REFERENCE_DEPTH) continue;
				renderDepth(tracer, method, camera, depth, frame, sums);
				BufferedImage image = toneMap(sums, postFactor);
				results.put(depth, writeImage(partial, depth, image, referenceRgb));
			}
		}

		List<ImageResult> ordered = DepthStudy.DEPTHS.stream().map(results::get).toList();
		if (ordered.stream().anyMatch(result -> result == null)
				|| results.size() != DepthStudy.DEPTHS.size()) {
			throw new IllegalStateException("Depth render inventory differs");
		}
		String referenceHash = results.get(DepthStudy.IMAGE_REFERENCE_DEPTH).sha256();
		EvidenceFiles.writeNew(partial.resolve("depth-similarity.csv"),
				similarityCsv(ordered, referenceHash).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("render-strip.csv"),
				renderStripCsv(results).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), manifest(
				method, postFactor, System.nanoTime() - started, deviceName, computeCapability,
				ptxSha256, compiledClassesSha256, sourceCommit, sourceTree)
				.getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		verifyPackage(projectRoot, partial, compiledClassesSha256, sourceCommit, sourceTree);
		EvidenceFiles.moveAtomic(partial, target);
		System.out.println(target);
	}

	private static void renderDepth(
			GpuRayTracer tracer, MethodCatalog.Method method, GpuCamera camera,
			int depth, float[] frame, double[] sums
	) {
		Arrays.fill(sums, 0.0);
		for (int sample = 0; sample < DepthStudy.IMAGE_SAMPLES_PER_PIXEL; sample++) {
			long frameSeed = TimingSchedule.mix64(DepthStudy.EXPERIMENT_SEED
					^ ((long) sample * 0x9E3779B97F4A7C15L));
			tracer.renderSample(frame, method.scene(), camera, depth, sample, frameSeed);
			for (int index = 0; index < frame.length; index++) sums[index] += frame[index];
			if ((sample + 1) % 64 == 0) {
				System.out.printf(Locale.ROOT, "depth %d: %d/%d samples%n",
						depth, sample + 1, DepthStudy.IMAGE_SAMPLES_PER_PIXEL);
			}
		}
		if (tracer.lastPhysicalKernelLaunchCount() != PHYSICAL_LAUNCHES_PER_FRAME
				|| tracer.lastFrameStats().uploadNanos() != 0L) {
			throw new IllegalStateException("Depth render launch or residency protocol differs");
		}
	}

	private static ImageResult writeImage(
			Path directory, int depth, BufferedImage image, int[] referenceRgb
	) throws Exception {
		String fileName = String.format(Locale.ROOT, "depth-%02d.png", depth);
		Path path = directory.resolve(fileName);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
				|| !ImageIO.write(image, "png", path.toFile())) {
			throw new IOException("Could not write depth image: " + fileName);
		}
		int[] sourceRgb = rgb(image);
		long absoluteError = 0L;
		for (int index = 0; index < sourceRgb.length; index++) {
			int source = sourceRgb[index];
			int reference = referenceRgb[index];
			absoluteError += Math.abs((source >>> 16 & 0xff) - (reference >>> 16 & 0xff));
			absoluteError += Math.abs((source >>> 8 & 0xff) - (reference >>> 8 & 0xff));
			absoluteError += Math.abs((source & 0xff) - (reference & 0xff));
		}
		double mean = (double) absoluteError
				/ Math.multiplyExact((long) sourceRgb.length, CHANNELS);
		return new ImageResult(depth, fileName, EvidenceFiles.sha256(Files.readAllBytes(path)),
				mean, 100.0 * (1.0 - mean / 255.0));
	}

	private static double toneMapPostFactor(double[] sums) {
		double maximum = 0.0;
		for (int index = 0; index < sums.length; index += CHANNELS) {
			double red = sums[index] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
			double green = sums[index + 1] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
			double blue = sums[index + 2] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
			double scale = luminanceScale(red, green, blue);
			maximum = Math.max(maximum, Math.max(red, Math.max(green, blue)) * scale);
		}
		return maximum > 0.0 ? 1.0 / maximum : 1.0;
	}

	private static BufferedImage toneMap(double[] sums, double postFactor) {
		BufferedImage image = new BufferedImage(
				DepthStudy.IMAGE_WIDTH, DepthStudy.IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < DepthStudy.IMAGE_HEIGHT; y++) {
			for (int x = 0; x < DepthStudy.IMAGE_WIDTH; x++) {
				int index = (y * DepthStudy.IMAGE_WIDTH + x) * CHANNELS;
				double red = sums[index] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
				double green = sums[index + 1] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
				double blue = sums[index + 2] / DepthStudy.IMAGE_SAMPLES_PER_PIXEL;
				double scale = luminanceScale(red, green, blue) * postFactor;
				image.setRGB(x, y, 0xff000000
						| Color.valueToByteClamp(red * scale) << 16
						| Color.valueToByteClamp(green * scale) << 8
						| Color.valueToByteClamp(blue * scale));
			}
		}
		return image;
	}

	private static double luminanceScale(double red, double green, double blue) {
		double luminance = 0.212655 * red + 0.715158 * green + 0.072187 * blue;
		if (luminance <= 0.0) return 0.0;
		double preMapped = luminance * TONE_MAP_PRE_FACTOR;
		return (1.0 - 1.0 / (1.0 + preMapped)) / luminance;
	}

	private static int[] rgb(BufferedImage image) {
		return image.getRGB(0, 0, DepthStudy.IMAGE_WIDTH, DepthStudy.IMAGE_HEIGHT,
				null, 0, DepthStudy.IMAGE_WIDTH);
	}

	private static String similarityCsv(List<ImageResult> results, String referenceHash) {
		StringBuilder csv = new StringBuilder(SIMILARITY_HEADER).append('\n');
		for (int ordinal = 0; ordinal < results.size(); ordinal++) {
			ImageResult result = results.get(ordinal);
			csv.append("1,").append(ordinal).append(',').append(result.depth()).append(',')
					.append(DepthStudy.IMAGE_REFERENCE_DEPTH).append(',')
					.append(String.format(Locale.ROOT, "%.12f", result.similarityPercent()))
					.append(',').append(String.format(Locale.ROOT, "%.12f",
							result.meanAbsoluteChannelDifference()))
					.append(',').append(result.sha256()).append(',').append(referenceHash).append('\n');
		}
		return csv.toString();
	}

	private static String renderStripCsv(Map<Integer, ImageResult> results) {
		StringBuilder csv = new StringBuilder(STRIP_HEADER).append('\n');
		for (int ordinal = 0; ordinal < STRIP_DEPTHS.size(); ordinal++) {
			ImageResult result = results.get(STRIP_DEPTHS.get(ordinal));
			csv.append(ordinal).append(',').append(result.depth()).append(',')
					.append(result.fileName()).append(',').append(result.sha256()).append('\n');
		}
		return csv.toString();
	}

	private static String manifest(
			MethodCatalog.Method method, double postFactor, long elapsedNanos,
			String deviceName, String computeCapability, String ptxSha256,
			String compiledClassesSha256, String sourceCommit, String sourceTree
	) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": " + EvidenceFiles.json(DepthStudy.VERSION) + ",\n"
				+ "  \"purpose\": \"GI Test path-depth image similarity and render-strip inputs\",\n"
				+ "  \"sceneId\": \"GI_TEST\",\n"
				+ "  \"methodId\": \"uniform\",\n"
				+ "  \"bvhBuildMode\": \"UNIFORM_SAH\",\n"
				+ "  \"leafSize\": 8,\n"
				+ "  \"lambda\": 0.0,\n"
				+ "  \"packedGeometrySha256\": "
				+ EvidenceFiles.json(method.packedGeometrySha256()) + ",\n"
				+ "  \"topologySha256\": " + EvidenceFiles.json(method.topologySha256()) + ",\n"
				+ "  \"width\": 3840,\n"
				+ "  \"height\": 2160,\n"
				+ "  \"samplesPerPixel\": 512,\n"
				+ "  \"referenceDepth\": 32,\n"
				+ "  \"experimentSeed\": " + DepthStudy.EXPERIMENT_SEED + ",\n"
				+ "  \"seedHex\": \"0x0A6A08E5C173D29F\",\n"
				+ "  \"toneMapPreFactor\": " + TONE_MAP_PRE_FACTOR + ",\n"
				+ "  \"toneMapPostFactor\": " + postFactor + ",\n"
				+ "  \"toneMapFormula\": "
				+ EvidenceFiles.json(
						"sRGB after scale = (1 - 1 / (1 + luminance / 16)) / luminance and one common reference post-factor")
				+ ",\n"
				+ "  \"comparisonDomain\": \"fixed-exposure tone-mapped 8-bit RGB\",\n"
				+ "  \"similarityPercentFormula\": "
				+ EvidenceFiles.json(
						"100 * (1 - mean(abs(RGB_depth - RGB_depth32)) / 255)") + ",\n"
				+ "  \"renderStripDepths\": [0, 2, 4, 8, 12, 20, 32],\n"
				+ "  \"renderPixelsPerLaunch\": 65536,\n"
				+ "  \"physicalKernelLaunchesPerFrame\": " + PHYSICAL_LAUNCHES_PER_FRAME + ",\n"
				+ "  \"bvhStackSize\": 32,\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(deviceName) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(computeCapability) + ",\n"
				+ "  \"driverVersion\": "
				+ EvidenceFiles.json(System.getProperty(DRIVER_PROPERTY, "")) + ",\n"
				+ "  \"presentationPtxSha256\": " + EvidenceFiles.json(ptxSha256) + ",\n"
				+ "  \"imageCount\": 12,\n"
				+ "  \"elapsedNanos\": " + elapsedNanos + ",\n"
				+ "  \"compiledClassesSha256\": " + EvidenceFiles.json(compiledClassesSha256) + ",\n"
				+ "  \"sourceCommit\": " + EvidenceFiles.json(sourceCommit) + ",\n"
				+ "  \"sourceTree\": " + EvidenceFiles.json(sourceTree) + ",\n"
				+ "  \"protocolSha256\": "
				+ EvidenceFiles.json(BenchmarkProtocol.DEPTH_DOCUMENT_SHA256) + "\n"
				+ "}\n";
	}

	static BuildIdentity verifyPackage(
			Path projectRoot, Path directory, String compiledClassesSha256,
			String sourceCommit, String sourceTree
	) throws Exception {
		Set<String> files = expectedFiles();
		Set<String> hashed = new HashSet<>(files);
		hashed.remove("SHA256SUMS.txt");
		EvidenceFiles.verifyRegularFileDirectory(directory, files);
		EvidenceFiles.verifySha256Ledger(directory, hashed);

		String manifest = Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8);
		String geometry = DepthStudy.string(manifest, "packedGeometrySha256");
		String topology = DepthStudy.string(manifest, "topologySha256");
		if (!directory.toAbsolutePath().normalize().startsWith(projectRoot.toRealPath())
				|| DepthStudy.integer(manifest, "schemaVersion") != 1
				|| !DepthStudy.string(manifest, "protocolVersion").equals(DepthStudy.VERSION)
				|| !DepthStudy.string(manifest, "methodId").equals("uniform")
				|| !DepthStudy.string(manifest, "bvhBuildMode").equals("UNIFORM_SAH")
				|| DepthStudy.integer(manifest, "leafSize") != BenchmarkProtocol.LEAF_SIZE
				|| DepthStudy.integer(manifest, "width") != DepthStudy.IMAGE_WIDTH
				|| DepthStudy.integer(manifest, "height") != DepthStudy.IMAGE_HEIGHT
				|| DepthStudy.integer(manifest, "samplesPerPixel") != DepthStudy.IMAGE_SAMPLES_PER_PIXEL
				|| DepthStudy.integer(manifest, "referenceDepth") != DepthStudy.IMAGE_REFERENCE_DEPTH
				|| DepthStudy.longInteger(manifest, "experimentSeed") != DepthStudy.EXPERIMENT_SEED
				|| DepthStudy.integer(manifest, "physicalKernelLaunchesPerFrame")
				!= PHYSICAL_LAUNCHES_PER_FRAME
				|| !DepthStudy.string(manifest, "compiledClassesSha256").equals(compiledClassesSha256)
				|| !DepthStudy.string(manifest, "sourceCommit").equals(sourceCommit)
				|| !DepthStudy.string(manifest, "sourceTree").equals(sourceTree)
				|| !DepthStudy.string(manifest, "protocolSha256")
				.equals(BenchmarkProtocol.DEPTH_DOCUMENT_SHA256)
				|| !DepthStudy.string(manifest, "deviceName")
				.equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !DepthStudy.string(manifest, "computeCapability")
				.equals(PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !DepthStudy.string(manifest, "driverVersion")
				.equals(PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)
				|| !EvidenceFiles.isSha256(DepthStudy.string(manifest, "presentationPtxSha256"))
				|| !EvidenceFiles.isSha256(geometry) || !EvidenceFiles.isSha256(topology)) {
			throw new IllegalStateException("Depth presentation manifest differs");
		}
		List<String> similarity = Files.readAllLines(
				directory.resolve("depth-similarity.csv"), StandardCharsets.UTF_8);
		List<String> strip = Files.readAllLines(
				directory.resolve("render-strip.csv"), StandardCharsets.UTF_8);
		if (similarity.size() != DepthStudy.DEPTHS.size() + 1
				|| !similarity.get(0).equals(SIMILARITY_HEADER)
				|| strip.size() != STRIP_DEPTHS.size() + 1 || !strip.get(0).equals(STRIP_HEADER)) {
			throw new IllegalStateException("Depth presentation CSV inventory differs");
		}
		return new BuildIdentity(geometry, topology);
	}

	private static void requireGpuIdentity(GpuRayTracer tracer) {
		if (!tracer.isAvailable() || tracer.collectsMetrics()
				|| !tracer.deviceInfo().name().equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !tracer.deviceInfo().computeCapability().equals(
						PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !System.getProperty(DRIVER_PROPERTY, "").equals(
						PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)
				|| !EvidenceFiles.isSha256(tracer.compiledPtxSha256())
				|| GpuLaunchProvenance.renderPixelsPerLaunch() != RENDER_PIXELS_PER_LAUNCH
				|| !System.getProperty("gfxlab.gpu.bvhStackSize", "").equals("32")) {
			throw new IllegalStateException("Depth presentation GPU configuration differs");
		}
	}

	private static Set<String> expectedFiles() {
		Set<String> result = new HashSet<>(Set.of(
				"depth-similarity.csv", "render-strip.csv", "manifest.json", "SHA256SUMS.txt"));
		for (int depth : DepthStudy.DEPTHS) {
			result.add(String.format(Locale.ROOT, "depth-%02d.png", depth));
		}
		return Set.copyOf(result);
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}
}
