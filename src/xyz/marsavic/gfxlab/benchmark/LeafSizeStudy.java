package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;
import xyz.marsavic.gfxlab.playground.SceneCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Create-new, time-only benchmark ordinary-SAH leaf-size producer. */
public final class LeafSizeStudy {
	public static final String VERSION = "gfxlab-leaf-size-study";
	public static final List<String> SCENE_IDS = List.of(
			"GI_TEST", "CITY_OF_NIGHT_V1", "SIGNAL_CHAMBER");
	public static final List<Integer> LEAF_SIZES = List.of(1, 2, 4, 8, 16, 32, 64);
	public static final int REFERENCE_LEAF_SIZE = 8;
	public static final int CONTEXTS = 5;
	public static final int BLOCKS_PER_CONTEXT = 2;
	public static final int SCENE_COUNT = SCENE_IDS.size();
	public static final int BUILD_ROWS_PER_SCENE = LEAF_SIZES.size();
	private static final int COMPARISONS_PER_SCENE = LEAF_SIZES.size() - 1;
	private static final int TIMING_ROWS_PER_BLOCK = TimingSchedule.Variant.values().length * 2;
	private static final int TIMING_ROWS_PER_SCENE = COMPARISONS_PER_SCENE
			* CONTEXTS * BLOCKS_PER_CONTEXT * TIMING_ROWS_PER_BLOCK;
	private static final int BLOCK_ROWS_PER_SCENE = COMPARISONS_PER_SCENE
			* CONTEXTS * BLOCKS_PER_CONTEXT;
	public static final int TOTAL_BUILD_ROWS = SCENE_COUNT * BUILD_ROWS_PER_SCENE;
	public static final int TOTAL_TIMING_ROWS = SCENE_COUNT * TIMING_ROWS_PER_SCENE;
	public static final int TOTAL_BLOCK_ROWS = SCENE_COUNT * BLOCK_ROWS_PER_SCENE;
	private static final String DRIVER_PROPERTY = "gfxlab.gpu.driverVersion";
	private static final int TIMEOUT_SECONDS = BenchmarkProtocol.HARD_CHILD_TIMEOUT_SECONDS;
	private static final Set<String> PLAN_FILES = Set.of(
			"protocol.json", "scenes.csv", "manifest.json", "SHA256SUMS.txt");
	private static final Set<String> CHUNK_FILES = Set.of(
			"builds.csv", "timing.csv", "blocks.csv", "manifest.json", "SHA256SUMS.txt");
	private static final String BUILD_HEADER =
			"schemaVersion,protocolVersion,sceneOrdinal,sceneId,leafSize,primitiveCount,"
			+ "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,bytes,buildNanos,"
			+ "wallNanos,packedGeometrySha256,topologySha256";
	private static final String TIMING_HEADER =
			"schemaVersion,protocolVersion,sceneOrdinal,sceneId,comparisonOrdinal,comparisonId,"
			+ "referenceLeafSize,candidateLeafSize,context,blockIndex,position,order,variant,"
			+ "endpointId,topologySha256,maximumPathDepth,measurementSeed,conditioningSeed,"
			+ "conditioningFrameIndex,conditioningUploadNanos,"
			+ "conditioningMaximumPhysicalKernelNanos,conditioningAggregatePhysicalKernelNanos,"
			+ "conditioningTotalNanos,measurementFrameIndex,kernelNanos,"
			+ "maximumPhysicalKernelNanos,uploadNanos,copyNanos,totalNanos";
	private static final String BLOCK_HEADER =
			"schemaVersion,protocolVersion,sceneOrdinal,sceneId,comparisonOrdinal,comparisonId,"
			+ "referenceLeafSize,candidateLeafSize,context,blockIndex,order,"
			+ "firstMeasurementSeed,secondMeasurementSeed,ordinaryKernelReductionPercent";

	private record Arguments(
			String stage, Path projectRoot, Path outputRoot, int sceneIndex,
			String compiledClassesSha256, String sourceCommit, String sourceTree
	) { }

	private record Built(int leafSize, MethodCatalog.Method method) { }

	private LeafSizeStudy() { }

	public static void main(String[] rawArguments) throws Exception {
		Arguments arguments = parse(rawArguments);
		BenchmarkProtocol.verify(arguments.projectRoot());
		BenchmarkProtocol.requireTimingRuntimeProperties();
		BenchmarkClassIdentity.requireLiveIdentity(LeafSizeStudy.class, arguments.compiledClassesSha256());
		switch (arguments.stage()) {
			case "preflight" -> preflight(arguments);
			case "measure-chunk" -> measure(arguments);
			case "finalize" -> finalizeRun(arguments);
			default -> throw new IllegalArgumentException("Unknown benchmark leaf stage");
		}
	}

	private static void preflight(Arguments arguments) throws Exception {
		if (arguments.sceneIndex() != -1) throw new IllegalArgumentException("Leaf preflight has no scene");
		Path output = EvidenceFiles.requireCreateNewOutput(
				arguments.projectRoot(), arguments.outputRoot());
		Path partial = output.resolveSibling(output.getFileName() + ".partial-" + UUID.randomUUID());
		Files.createDirectory(partial);
		EvidenceFiles.writeNew(partial.resolve("protocol.json"), Files.readAllBytes(
				arguments.projectRoot().resolve(BenchmarkProtocol.LEAF_DOCUMENT)));
		StringBuilder scenes = new StringBuilder(
				"schemaVersion,sceneOrdinal,sceneId,referenceLeafSize,candidateCount,contexts,blocksPerContext\n");
		for (int index = 0; index < SCENE_IDS.size(); index++) {
			scenes.append(1).append(',').append(index).append(',').append(SCENE_IDS.get(index))
					.append(',').append(REFERENCE_LEAF_SIZE).append(',')
					.append(COMPARISONS_PER_SCENE).append(',').append(CONTEXTS).append(',')
					.append(BLOCKS_PER_CONTEXT).append('\n');
		}
		EvidenceFiles.writeNew(partial.resolve("scenes.csv"), scenes.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"),
				planManifest(arguments).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyDirectory(partial, PLAN_FILES);
		EvidenceFiles.moveAtomic(partial, output);
		System.out.println(output);
	}

	private static void measure(Arguments arguments) throws Exception {
		if (arguments.sceneIndex() < 0 || arguments.sceneIndex() >= SCENE_COUNT) {
			throw new IllegalArgumentException("benchmark leaf scene index must be in [0,"
					+ (SCENE_COUNT - 1) + ']');
		}
		verifyPlan(arguments);
		Path target = arguments.outputRoot().resolve(String.format(Locale.ROOT,
				"chunk-%02d", arguments.sceneIndex()));
		EvidenceFiles.requireUnattempted(arguments.outputRoot(), target);
		Path partial = target.resolveSibling(target.getFileName() + ".attempt-" + UUID.randomUUID());
		Files.createDirectory(partial); // persistent reservation before CUDA
		long started = System.nanoTime();
		SceneCatalog.ScenePreset preset = SceneCatalog.ScenePreset.valueOf(
				SCENE_IDS.get(arguments.sceneIndex()));
		SceneCatalog.SceneSetup setup = SceneCatalog.create(preset);
		BvhBuildConfig base = MethodCatalog.calibratedBase();
		List<Built> built = new ArrayList<>();
		String geometry = null;
		for (int leaf : LEAF_SIZES) {
			MethodCatalog.Method method = MethodCatalog.buildObjectSah(
					setup.scene(), base, leaf, 0.0);
			if (geometry == null) geometry = method.packedGeometrySha256();
			if (!geometry.equals(method.packedGeometrySha256())) {
				throw new IllegalStateException("benchmark leaf builds changed packed geometry");
			}
			built.add(new Built(leaf, method));
		}
		Map<Integer, Built> byLeaf = new LinkedHashMap<>();
		for (Built item : built) {
			if (byLeaf.put(item.leafSize(), item) != null) {
				throw new IllegalStateException("benchmark leaf build identity is duplicated");
			}
		}
		Built reference = byLeaf.get(REFERENCE_LEAF_SIZE);
		if (built.size() != BUILD_ROWS_PER_SCENE || byLeaf.size() != BUILD_ROWS_PER_SCENE
				|| reference == null) {
			throw new IllegalStateException("benchmark leaf build cardinality differs");
		}
		StringBuilder builds = new StringBuilder(BUILD_HEADER).append('\n');
		int buildRows = 0;
		for (Built item : built) {
			builds.append(buildRow(arguments.sceneIndex(), item));
			buildRows++;
		}
		StringBuilder timing = new StringBuilder(TIMING_HEADER).append('\n');
		StringBuilder blocks = new StringBuilder(BLOCK_HEADER).append('\n');
		int timingRows = 0;
		int blockRows = 0;
		String device;
		String capability;
		String ptx;
		List<GpuRayTracer> contexts = openContexts();
			try {
				requireContextIdentity(contexts);
				device = contexts.get(0).deviceInfo().name();
				capability = contexts.get(0).deviceInfo().computeCapability();
				ptx = contexts.get(0).compiledPtxSha256();
				float[] pixels = new float[BenchmarkProtocol.WIDTH * BenchmarkProtocol.HEIGHT * 3];
				GpuCamera camera = BenchmarkRays.gpuCamera(
						setup.camera(), BenchmarkProtocol.WIDTH, BenchmarkProtocol.HEIGHT);
				int comparisonOrdinal = 0;
				for (Built candidate : built) {
					if (candidate.leafSize() == REFERENCE_LEAF_SIZE) continue;
					for (int context = 0; context < CONTEXTS; context++) {
						requireDeadline(started, SCENE_IDS.get(arguments.sceneIndex())
								+ " leaf " + candidate.leafSize());
						PairedMeasurement.Run run = PairedMeasurement.execute(
								contexts.get(context), pixels, camera,
								new PairedMeasurement.Limits(
										BenchmarkProtocol.MAXIMUM_PATH_DEPTH,
										BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE,
										CONTEXTS,
										BenchmarkProtocol.TIMING_PHYSICAL_LAUNCHES_PER_FRAME),
								candidate("leaf_" + REFERENCE_LEAF_SIZE, reference),
								candidate("leaf_" + candidate.leafSize(), candidate),
								BLOCKS_PER_CONTEXT,
								leafSeed(arguments.sceneIndex(), comparisonOrdinal),
								arguments.sceneIndex(), comparisonOrdinal, context);
						for (PairedMeasurement.RawRow raw : run.rows()) {
							timing.append(timingRow(arguments.sceneIndex(), comparisonOrdinal,
									candidate.leafSize(), raw));
							timingRows++;
						}
						for (PairedMeasurement.BlockResult result : run.blocks()) {
							blocks.append(blockRow(arguments.sceneIndex(), comparisonOrdinal,
									candidate.leafSize(), result));
							blockRows++;
						}
					}
					comparisonOrdinal++;
				}
			} finally {
				for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).close();
			}
		if (buildRows != BUILD_ROWS_PER_SCENE || timingRows != TIMING_ROWS_PER_SCENE
				|| blockRows != BLOCK_ROWS_PER_SCENE) {
			throw new IllegalStateException("benchmark leaf chunk cardinality differs");
		}
		EvidenceFiles.writeNew(partial.resolve("builds.csv"), builds.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("timing.csv"), timing.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("blocks.csv"), blocks.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), chunkManifest(
				arguments, buildRows, timingRows, blockRows, System.nanoTime() - started,
				device, capability, ptx).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyDirectory(partial, CHUNK_FILES);
		EvidenceFiles.moveAtomic(partial, target);
		System.out.println(target);
	}

	private static void finalizeRun(Arguments arguments) throws Exception {
		if (arguments.sceneIndex() != -1) throw new IllegalArgumentException("Leaf finalize has no scene");
		verifyPlan(arguments);
		Path manifestPath = arguments.outputRoot().resolve("final-manifest.json");
		Path hashesPath = arguments.outputRoot().resolve("FINAL_SHA256SUMS.txt");
		if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(hashesPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("benchmark leaf finalization already exists/was attempted");
		}
		Set<String> devices = new HashSet<>();
		Set<String> capabilities = new HashSet<>();
		Set<String> drivers = new HashSet<>();
		Set<String> ptx = new HashSet<>();
		int buildRows = 0;
		StringBuilder chunkSums = new StringBuilder();
		for (int scene = 0; scene < SCENE_COUNT; scene++) {
			Path chunk = arguments.outputRoot().resolve(String.format(Locale.ROOT, "chunk-%02d", scene));
			EvidenceFiles.verifyDirectory(chunk, CHUNK_FILES);
			EvidenceFiles.verifySha256Ledger(chunk);
			String manifest = Files.readString(chunk.resolve("manifest.json"), StandardCharsets.UTF_8);
			requireIdentity(manifest, arguments, "GPU_MEASURED");
			if (integer(manifest, "sceneOrdinal") != scene
					|| integer(manifest, "timingRows") != TIMING_ROWS_PER_SCENE
					|| integer(manifest, "blockRows") != BLOCK_ROWS_PER_SCENE
					|| integer(manifest, "buildRows") != BUILD_ROWS_PER_SCENE) {
				throw new IllegalStateException("benchmark leaf chunk manifest differs");
			}
			devices.add(string(manifest, "deviceName"));
			capabilities.add(string(manifest, "computeCapability"));
			drivers.add(string(manifest, "driverVersion"));
			ptx.add(string(manifest, "timingPtxSha256"));
			buildRows += integer(manifest, "buildRows");
			chunkSums.append(EvidenceFiles.sha256(
					Files.readAllBytes(chunk.resolve("SHA256SUMS.txt"))))
					.append("  ").append(chunk.getFileName()).append("/SHA256SUMS.txt\n");
		}
		if (buildRows != TOTAL_BUILD_ROWS || devices.size() != 1 || capabilities.size() != 1
				|| drivers.size() != 1 || ptx.size() != 1) {
			throw new IllegalStateException("benchmark leaf GPU identity differs across scenes");
		}
		String finalManifest = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"sceneCount\": " + SCENE_COUNT + ",\n"
				+ "  \"configurationCount\": " + TOTAL_BUILD_ROWS + ",\n"
				+ "  \"buildRows\": " + TOTAL_BUILD_ROWS + ",\n"
				+ "  \"timingRows\": " + TOTAL_TIMING_ROWS + ",\n"
				+ "  \"blockRows\": " + TOTAL_BLOCK_ROWS + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(devices.iterator().next()) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(
						capabilities.iterator().next()) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(drivers.iterator().next()) + ",\n"
				+ "  \"timingPtxSha256\": \"" + ptx.iterator().next() + "\",\n"
				+ identityFields(arguments)
				+ "}\n";
		EvidenceFiles.writeNew(manifestPath, finalManifest.getBytes(StandardCharsets.UTF_8));
		String sums = EvidenceFiles.sha256(Files.readAllBytes(manifestPath))
				+ "  final-manifest.json\n"
				+ EvidenceFiles.sha256(Files.readAllBytes(
						arguments.outputRoot().resolve("SHA256SUMS.txt")))
				+ "  SHA256SUMS.txt\n" + chunkSums;
		EvidenceFiles.writeNew(hashesPath, sums.getBytes(StandardCharsets.UTF_8));
		System.out.println(arguments.outputRoot());
	}

	private static List<GpuRayTracer> openContexts() {
		List<GpuRayTracer> result = new ArrayList<>();
		try {
			for (int index = 0; index < CONTEXTS; index++) {
				GpuRayTracer tracer = new GpuRayTracer(BenchmarkProtocol.WIDTH, BenchmarkProtocol.HEIGHT,
						BenchmarkProtocol.SAMPLES_PER_PIXEL, true, false);
				if (tracer.collectsMetrics()) throw new IllegalStateException("Leaf context is instrumented");
				result.add(tracer);
			}
			return result;
		} catch (RuntimeException | Error failure) {
			for (int index = result.size() - 1; index >= 0; index--) result.get(index).close();
			throw failure;
		}
	}

	private static void requireContextIdentity(List<GpuRayTracer> contexts) {
		if (contexts.size() != CONTEXTS) throw new IllegalStateException("Leaf context count differs");
		GpuRayTracer.DeviceInfo device = null;
		String ptx = null;
		for (int index = 0; index < contexts.size(); index++) {
			GpuRayTracer tracer = contexts.get(index);
			if (!tracer.isAvailable()) throw new IllegalStateException("CUDA unavailable for benchmark leaf");
			if (index == 0) {
				device = tracer.deviceInfo();
				ptx = tracer.compiledPtxSha256();
			} else if (!tracer.deviceInfo().equals(device)
					|| !tracer.compiledPtxSha256().equals(ptx)) {
				throw new IllegalStateException("benchmark leaf context identity differs");
			}
		}
		if (device == null || !EvidenceFiles.isSha256(ptx)
				|| !device.name().equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !device.computeCapability().equals(
						PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !System.getProperty(DRIVER_PROPERTY, "").equals(
						PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)) {
			throw new IllegalStateException("benchmark leaf GPU/calibration identity differs");
		}
	}

	private static PairedMeasurement.Candidate candidate(String id, Built built) {
		return new PairedMeasurement.Candidate(
				id, built.method().topologySha256(), built.method().scene());
	}

	private static String buildRow(int scene, Built item) {
		GpuScene.BvhStats stats = item.method().scene().bvhStats();
		return "1," + VERSION + ',' + scene + ',' + SCENE_IDS.get(scene) + ','
				+ item.leafSize() + ',' + item.method().scene().primitiveCount() + ','
				+ stats.nodeCount() + ',' + stats.rootCount() + ',' + stats.leafCount() + ','
				+ stats.maxDepth() + ',' + stats.primitiveRefCount() + ',' + stats.bytes() + ','
				+ item.method().buildNanos() + ',' + item.method().wallNanos() + ','
				+ item.method().packedGeometrySha256() + ',' + item.method().topologySha256() + '\n';
	}

	private static String timingRow(
			int scene, int comparison, int leaf, PairedMeasurement.RawRow row
	) {
		return leafPrefix(scene, comparison, leaf) + ',' + row.context() + ',' + row.blockIndex()
				+ ',' + row.position() + ',' + row.order() + ',' + row.variant() + ','
				+ row.endpointId() + ',' + row.topologySha256() + ',' + row.maximumPathDepth()
				+ ',' + row.measurementSeed() + ',' + row.conditioningSeed() + ','
				+ row.conditioningFrameIndex() + ',' + row.conditioningUploadNanos() + ','
				+ row.conditioningMaximumPhysicalKernelNanos() + ','
				+ row.conditioningAggregatePhysicalKernelNanos() + ',' + row.conditioningTotalNanos()
				+ ',' + row.measurementFrameIndex() + ',' + row.kernelNanos() + ','
				+ row.maximumPhysicalKernelNanos() + ',' + row.uploadNanos() + ','
				+ row.copyNanos() + ',' + row.totalNanos() + '\n';
	}

	private static String blockRow(
			int scene, int comparison, int leaf, PairedMeasurement.BlockResult row
	) {
		return leafPrefix(scene, comparison, leaf) + ',' + row.context() + ',' + row.blockIndex()
				+ ',' + row.order() + ',' + row.firstMeasurementSeed() + ','
				+ row.secondMeasurementSeed() + ','
				+ String.format(Locale.ROOT, "%.12f", row.ordinaryKernelReductionPercent()) + '\n';
	}

	private static String leafPrefix(int scene, int comparison, int leaf) {
		return "1," + VERSION + ',' + scene + ',' + SCENE_IDS.get(scene) + ',' + comparison
				+ ",leaf_" + REFERENCE_LEAF_SIZE + "__leaf_" + leaf + ','
				+ REFERENCE_LEAF_SIZE + ',' + leaf;
	}

	private static long leafSeed(int scene, int comparison) {
		return BenchmarkProtocol.timingSeed(20_000L + scene, comparison);
	}

	private static String planManifest(Arguments arguments) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"PLAN\",\n"
				+ "  \"gpuExecutionStarted\": false,\n"
				+ "  \"sceneCount\": " + SCENE_COUNT + ",\n"
				+ "  \"configurationCount\": " + TOTAL_BUILD_ROWS + ",\n"
				+ "  \"buildRows\": " + TOTAL_BUILD_ROWS + ",\n"
				+ "  \"timingRows\": " + TOTAL_TIMING_ROWS + ",\n"
				+ "  \"blockRows\": " + TOTAL_BLOCK_ROWS + ",\n"
				+ identityFields(arguments)
				+ "}\n";
	}

	private static String chunkManifest(
			Arguments arguments, int buildRows, int timingRows, int blockRows, long elapsed,
			String device, String capability, String ptx
	) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"sceneOrdinal\": " + arguments.sceneIndex() + ",\n"
				+ "  \"sceneId\": \"" + SCENE_IDS.get(arguments.sceneIndex()) + "\",\n"
				+ "  \"buildRows\": " + buildRows + ",\n"
				+ "  \"timingRows\": " + timingRows + ",\n"
				+ "  \"blockRows\": " + blockRows + ",\n"
				+ "  \"elapsedNanos\": " + elapsed + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(device) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(capability) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(
						System.getProperty(DRIVER_PROPERTY, "")) + ",\n"
				+ "  \"timingPtxSha256\": \"" + ptx + "\",\n"
				+ identityFields(arguments)
				+ "}\n";
	}

	private static String identityFields(Arguments arguments) {
		return "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.LEAF_DOCUMENT_SHA256 + "\"\n";
	}

	private static Arguments parse(String[] arguments) {
		if (arguments == null || arguments.length < 12 || (arguments.length & 1) != 0) {
			throw new IllegalArgumentException("benchmark leaf requires named option pairs");
		}
		Set<String> allowed = Set.of("stage", "project-root", "output-root", "chunk-index",
				"compiled-classes-sha256", "source-commit", "source-tree");
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String option = arguments[index];
			if (!option.startsWith("--") || !allowed.contains(option.substring(2))) {
				throw new IllegalArgumentException("Unknown benchmark leaf option: " + option);
			}
			String value = arguments[index + 1].trim();
			if (value.isEmpty() || values.put(option.substring(2), value) != null) {
				throw new IllegalArgumentException("Missing/duplicate benchmark leaf option");
			}
		}
		Set<String> required = Set.of("stage", "project-root", "output-root",
				"compiled-classes-sha256", "source-commit", "source-tree");
		if (!values.keySet().containsAll(required)) throw new IllegalArgumentException("Leaf options incomplete");
		String stage = values.get("stage");
		int scene = values.containsKey("chunk-index") ? Integer.parseInt(values.get("chunk-index")) : -1;
		if (stage.equals("measure-chunk") != values.containsKey("chunk-index")) {
			throw new IllegalArgumentException("Only benchmark leaf measure-chunk accepts chunk-index");
		}
		return new Arguments(stage,
				Path.of(values.get("project-root")).toAbsolutePath().normalize(),
				Path.of(values.get("output-root")).toAbsolutePath().normalize(), scene,
				sha(values.get("compiled-classes-sha256"), 64),
				sha(values.get("source-commit"), 40), sha(values.get("source-tree"), 40));
	}

	private static void verifyPlan(Arguments arguments) throws Exception {
		EvidenceFiles.verifyDirectory(arguments.outputRoot(), PLAN_FILES, path -> {
			String name = path.getFileName().toString();
			return isSceneChunkName(name)
					|| name.equals("final-manifest.json")
					|| name.equals("FINAL_SHA256SUMS.txt");
		});
		EvidenceFiles.verifySha256Ledger(arguments.outputRoot());
		String manifest = Files.readString(arguments.outputRoot().resolve("manifest.json"),
				StandardCharsets.UTF_8);
		requireIdentity(manifest, arguments, "PLAN");
		if (integer(manifest, "sceneCount") != SCENE_COUNT
				|| integer(manifest, "configurationCount") != TOTAL_BUILD_ROWS
				|| integer(manifest, "buildRows") != TOTAL_BUILD_ROWS
				|| integer(manifest, "timingRows") != TOTAL_TIMING_ROWS
				|| integer(manifest, "blockRows") != TOTAL_BLOCK_ROWS) {
			throw new IllegalStateException("benchmark leaf plan cardinality differs");
		}
	}

	private static boolean isSceneChunkName(String name) {
		return name.matches("chunk-[0-9]{2}")
				&& Integer.parseInt(name.substring(6)) < SCENE_COUNT;
	}

	private static void requireIdentity(String manifest, Arguments arguments, String state) {
		if (!string(manifest, "protocolVersion").equals(VERSION)
				|| !string(manifest, "measurementState").equals(state)
				|| !string(manifest, "compiledClassesSha256").equals(arguments.compiledClassesSha256())
				|| !string(manifest, "sourceCommit").equals(arguments.sourceCommit())
				|| !string(manifest, "sourceTree").equals(arguments.sourceTree())
				|| !string(manifest, "protocolSha256").equals(BenchmarkProtocol.LEAF_DOCUMENT_SHA256)) {
			throw new IllegalStateException("benchmark leaf manifest identity differs");
		}
	}

	private static void requireDeadline(long started, String operation) {
		if (System.nanoTime() - started >= TIMEOUT_SECONDS * 1_000_000_000L) {
			throw new IllegalStateException("benchmark leaf deadline reached before " + operation);
		}
	}

	private static String string(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark leaf JSON string: " + key);
		return matcher.group(1);
	}

	private static int integer(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*([0-9]+)").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark leaf JSON integer: " + key);
		return Integer.parseInt(matcher.group(1));
	}

	private static String sha(String value, int length) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{" + length + "}")) {
			throw new IllegalArgumentException("Malformed benchmark leaf source identity");
		}
		return normalized;
	}

}
