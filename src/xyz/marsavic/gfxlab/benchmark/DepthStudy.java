package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.GpuLaunchProvenance;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;
import xyz.marsavic.gfxlab.graphics3d.Scene;
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

/** Create-new GI Test maximum-path-depth timing and image-study producer. */
public final class DepthStudy {
	public static final String VERSION = "gfxlab-depth-study";
	public static final String SCENE_ID = "GI_TEST";
	public static final List<Integer> DEPTHS = List.of(0, 2, 4, 6, 8, 10, 12, 16, 20, 24, 28, 32);
	public static final int REFERENCE_DEPTH = 12;
	public static final int IMAGE_REFERENCE_DEPTH = 32;
	public static final int IMAGE_WIDTH = 3_840;
	public static final int IMAGE_HEIGHT = 2_160;
	public static final int IMAGE_SAMPLES_PER_PIXEL = 512;
	public static final long EXPERIMENT_SEED = 750_422_070_796_735_135L;
	public static final int CONTEXTS = 2;
	public static final int BLOCKS_PER_CONTEXT = 2;
	private static final int CONFIGURATION_COUNT = DEPTHS.size();
	private static final int COMPARISON_COUNT = CONFIGURATION_COUNT - 1;
	private static final int TIMING_ROWS_PER_BLOCK = TimingSchedule.Variant.values().length * 2;
	public static final int TOTAL_TIMING_ROWS = COMPARISON_COUNT
			* CONTEXTS * BLOCKS_PER_CONTEXT * TIMING_ROWS_PER_BLOCK;
	public static final int TOTAL_BLOCK_ROWS = COMPARISON_COUNT * CONTEXTS * BLOCKS_PER_CONTEXT;
	private static final int BUILD_ROW_COUNT = 1;
	private static final int MAXIMUM_ALLOWED_DEPTH = GpuRayTracer.MAX_FULL_PATH_DEPTH;
	private static final int TIMEOUT_SECONDS = BenchmarkProtocol.HARD_CHILD_TIMEOUT_SECONDS;
	private static final String DRIVER_PROPERTY = "gfxlab.gpu.driverVersion";
	private static final String RENDER_PIXELS_PROPERTY = "gfxlab.gpu.renderPixelsPerLaunch";
	private static final String STACK_PROPERTY = "gfxlab.gpu.bvhStackSize";
	private static final int RENDER_PIXELS_PER_LAUNCH =
			BenchmarkProtocol.TIMING_RENDER_PIXELS_PER_LAUNCH;
	private static final int STACK_SIZE = BenchmarkProtocol.STACK_CAPACITY;
	private static final int PHYSICAL_LAUNCHES_PER_FRAME =
			BenchmarkProtocol.TIMING_PHYSICAL_LAUNCHES_PER_FRAME;
	private static final Set<String> PLAN_FILES = Set.of(
			"protocol.json", "depths.csv", "manifest.json", "SHA256SUMS.txt");
	private static final Set<String> PLAN_HASHED_FILES = Set.of(
			"protocol.json", "depths.csv", "manifest.json");
	private static final Set<String> CHUNK_FILES = Set.of(
			"build.csv", "timing.csv", "blocks.csv", "manifest.json", "SHA256SUMS.txt");
	private static final Set<String> CHUNK_HASHED_FILES = Set.of(
			"build.csv", "timing.csv", "blocks.csv", "manifest.json");
	private static final Set<String> PLAN_MANIFEST_FIELDS = Set.of(
			"schemaVersion", "protocolVersion", "measurementState", "gpuExecutionStarted",
			"sceneId", "configurationCount", "timingRows", "blockRows",
			"renderPixelsPerLaunch", "bvhStackSize", "physicalKernelLaunchesPerFrame",
			"maximumPhysicalKernelNanosExclusive", "compiledClassesSha256",
			"sourceCommit", "sourceTree", "protocolSha256");
	private static final Set<String> CHUNK_MANIFEST_FIELDS = Set.of(
			"schemaVersion", "protocolVersion", "measurementState", "chunkIndex", "sceneId",
			"buildRows", "timingRows", "blockRows", "elapsedNanos", "deviceName",
			"computeCapability", "driverVersion", "timingPtxSha256",
			"renderPixelsPerLaunch", "bvhStackSize", "physicalKernelLaunchesPerFrame",
			"maximumPhysicalKernelNanosExclusive", "compiledClassesSha256",
			"sourceCommit", "sourceTree", "protocolSha256");
	private static final String BUILD_HEADER =
			"schemaVersion,protocolVersion,sceneId,methodId,leafSize,lambda,primitiveCount,"
			+ "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,bytes,buildNanos,"
			+ "wallNanos,packedGeometrySha256,topologySha256";
	private static final String TIMING_HEADER =
			"schemaVersion,protocolVersion,sceneId,comparisonOrdinal,comparisonId,"
			+ "referenceDepth,candidateDepth,context,blockIndex,position,order,variant,"
			+ "endpointId,topologySha256,maximumPathDepth,measurementSeed,conditioningSeed,"
			+ "conditioningFrameIndex,conditioningUploadNanos,"
			+ "conditioningMaximumPhysicalKernelNanos,conditioningAggregatePhysicalKernelNanos,"
			+ "conditioningTotalNanos,measurementFrameIndex,kernelNanos,"
			+ "maximumPhysicalKernelNanos,uploadNanos,copyNanos,totalNanos";
	private static final String BLOCK_HEADER =
			"schemaVersion,protocolVersion,sceneId,comparisonOrdinal,comparisonId,"
			+ "referenceDepth,candidateDepth,context,blockIndex,order,"
			+ "firstMeasurementSeed,secondMeasurementSeed,ordinaryKernelReductionPercent";

	private record Arguments(
			String stage, Path projectRoot, Path outputRoot, int chunkIndex,
			String compiledClassesSha256, String sourceCommit, String sourceTree
	) { }

	private DepthStudy() { }

	public static void main(String[] rawArguments) throws Exception {
		Arguments arguments = parse(rawArguments);
		BenchmarkProtocol.verify(arguments.projectRoot());
		verifyInventory();
		BenchmarkClassIdentity.requireLiveIdentity(DepthStudy.class, arguments.compiledClassesSha256());
		switch (arguments.stage()) {
			case "preflight" -> preflight(arguments);
			case "measure-chunk" -> measure(arguments);
			case "render-assets" -> renderAssets(arguments);
			case "finalize" -> finalizeRun(arguments);
			default -> throw new IllegalArgumentException("Unknown benchmark depth stage");
		}
	}

	private static void verifyInventory() {
		if (DEPTHS.stream().filter(value -> value == REFERENCE_DEPTH).count() != 1
				|| DEPTHS.get(0) != GpuRayTracer.MIN_FULL_PATH_DEPTH
				|| DEPTHS.get(DEPTHS.size() - 1) != MAXIMUM_ALLOWED_DEPTH
				|| IMAGE_REFERENCE_DEPTH != MAXIMUM_ALLOWED_DEPTH
				|| IMAGE_WIDTH < 1 || IMAGE_HEIGHT < 1 || IMAGE_SAMPLES_PER_PIXEL < 1
				|| EXPERIMENT_SEED <= 0L) {
			throw new IllegalStateException("benchmark depth cardinality differs");
		}
	}

	private static void preflight(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != -1) throw new IllegalArgumentException("Depth preflight has no chunk");
		Path output = EvidenceFiles.requireCreateNewOutput(
				arguments.projectRoot(), arguments.outputRoot());
		Path partial = output.resolveSibling(output.getFileName() + ".partial-" + UUID.randomUUID());
		Files.createDirectory(partial);
		EvidenceFiles.writeNew(partial.resolve("protocol.json"), Files.readAllBytes(
				arguments.projectRoot().resolve(BenchmarkProtocol.DEPTH_DOCUMENT)));
		StringBuilder depths = new StringBuilder(
				"schemaVersion,depthOrdinal,maximumPathDepth,isReference,comparisonOrdinal\n");
		int comparison = 0;
		for (int ordinal = 0; ordinal < DEPTHS.size(); ordinal++) {
			int depth = DEPTHS.get(ordinal);
			depths.append(1).append(',').append(ordinal).append(',').append(depth).append(',')
					.append(depth == REFERENCE_DEPTH).append(',')
					.append(depth == REFERENCE_DEPTH ? -1 : comparison++).append('\n');
		}
		EvidenceFiles.writeNew(partial.resolve("depths.csv"), depths.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"),
				planManifest(arguments).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyRegularFileDirectory(partial, PLAN_FILES);
		EvidenceFiles.verifySha256Ledger(partial, PLAN_HASHED_FILES);
		EvidenceFiles.moveAtomic(partial, output);
		System.out.println(output);
	}

	private static void measure(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != 0) {
			throw new IllegalArgumentException("benchmark depth chunk index must be 0");
		}
		verifyPlan(arguments);
		requireRuntimeConfiguration();
		Path target = arguments.outputRoot().resolve("chunk-00");
		EvidenceFiles.requireUnattempted(arguments.outputRoot(), target);
		Path partial = target.resolveSibling(target.getFileName() + ".attempt-" + UUID.randomUUID());
		Files.createDirectory(partial);
		long started = System.nanoTime();
		SceneCatalog.SceneSetup setup = SceneCatalog.create(SceneCatalog.ScenePreset.GI_TEST);
		MethodCatalog.Method method = buildUniformMethod(setup.scene());
		PairedMeasurement.Candidate reference = new PairedMeasurement.Candidate(
				"depth_" + REFERENCE_DEPTH, method.topologySha256(), method.scene());
		StringBuilder build = new StringBuilder(BUILD_HEADER).append('\n').append(buildRow(method));
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
				GpuCamera camera = depthCamera(setup);
				int comparisonOrdinal = 0;
				for (int depth : DEPTHS) {
					if (depth == REFERENCE_DEPTH) continue;
					PairedMeasurement.Candidate candidate = new PairedMeasurement.Candidate(
							"depth_" + depth, method.topologySha256(), method.scene());
					for (int context = 0; context < CONTEXTS; context++) {
						requireDeadline(started, "depth " + depth);
						PairedMeasurement.Run run = PairedMeasurement.execute(
								contexts.get(context), pixels, camera,
								new PairedMeasurement.Limits(
										MAXIMUM_ALLOWED_DEPTH,
										BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE,
										CONTEXTS, PHYSICAL_LAUNCHES_PER_FRAME),
								reference, REFERENCE_DEPTH, candidate, depth,
								BLOCKS_PER_CONTEXT, EXPERIMENT_SEED,
								30_000L, comparisonOrdinal, context);
						requireDeadline(started, "completion of depth " + depth);
						for (PairedMeasurement.RawRow row : run.rows()) {
							timing.append(timingRow(comparisonOrdinal, depth, row));
							timingRows++;
						}
						for (PairedMeasurement.BlockResult row : run.blocks()) {
							blocks.append(blockRow(comparisonOrdinal, depth, row));
							blockRows++;
						}
					}
					comparisonOrdinal++;
				}
			} finally {
				for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).close();
			}
		if (timingRows != TOTAL_TIMING_ROWS || blockRows != TOTAL_BLOCK_ROWS) {
			throw new IllegalStateException("benchmark depth chunk cardinality differs");
		}
		EvidenceFiles.writeNew(partial.resolve("build.csv"), build.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("timing.csv"), timing.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("blocks.csv"), blocks.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), chunkManifest(arguments, timingRows, blockRows,
				System.nanoTime() - started, device, capability, ptx).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyRegularFileDirectory(partial, CHUNK_FILES);
		EvidenceFiles.verifySha256Ledger(partial, CHUNK_HASHED_FILES);
		requireDeadline(started, "chunk promotion");
		EvidenceFiles.moveAtomic(partial, target);
		System.out.println(target);
	}

	private static void renderAssets(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != -1) {
			throw new IllegalArgumentException("Depth render-assets has no chunk");
		}
		verifyPlan(arguments);
		requireRuntimeConfiguration();
		DepthPresentationStudy.run(arguments.projectRoot(), arguments.outputRoot(),
				arguments.compiledClassesSha256(), arguments.sourceCommit(), arguments.sourceTree());
	}

	private static void finalizeRun(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != -1) throw new IllegalArgumentException("Depth finalize has no chunk");
		verifyPlan(arguments);
		Path manifestPath = arguments.outputRoot().resolve("final-manifest.json");
		Path hashesPath = arguments.outputRoot().resolve("FINAL_SHA256SUMS.txt");
		if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(hashesPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("benchmark depth finalization already exists or was attempted");
		}
		Path chunk = arguments.outputRoot().resolve("chunk-00");
		EvidenceFiles.verifyRegularFileDirectory(chunk, CHUNK_FILES);
		EvidenceFiles.verifySha256Ledger(chunk, CHUNK_HASHED_FILES);
		String manifest = Files.readString(chunk.resolve("manifest.json"), StandardCharsets.UTF_8);
		requireExactJsonFields(manifest, CHUNK_MANIFEST_FIELDS, "chunk");
		requireIdentity(manifest, arguments, "GPU_MEASURED");
		requireRuntimeManifest(manifest);
		requireGpuManifest(manifest);
		if (integer(manifest, "schemaVersion") != 1
				|| !string(manifest, "sceneId").equals(SCENE_ID)
				|| integer(manifest, "chunkIndex") != 0
				|| integer(manifest, "buildRows") != BUILD_ROW_COUNT
				|| integer(manifest, "timingRows") != TOTAL_TIMING_ROWS
				|| integer(manifest, "blockRows") != TOTAL_BLOCK_ROWS
				|| longInteger(manifest, "elapsedNanos") <= 0L
				|| longInteger(manifest, "elapsedNanos")
				>= TIMEOUT_SECONDS * 1_000_000_000L) {
			throw new IllegalStateException("benchmark depth chunk manifest differs");
		}
		DepthPresentationStudy.BuildIdentity presentation = DepthPresentationStudy.verifyPackage(
				arguments.projectRoot(), arguments.outputRoot().resolve("presentation"),
				arguments.compiledClassesSha256(), arguments.sourceCommit(), arguments.sourceTree());
		List<String> buildLines = Files.readAllLines(chunk.resolve("build.csv"), StandardCharsets.UTF_8);
		String[] buildFields = buildLines.size() == 2 ? buildLines.get(1).split(",", -1) : new String[0];
		if (buildFields.length != 17
				|| !buildFields[15].equals(presentation.packedGeometrySha256())
				|| !buildFields[16].equals(presentation.topologySha256())) {
			throw new IllegalStateException("Depth timing and image studies used different BVH topology");
		}
		String finalManifest = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"sceneId\": \"" + SCENE_ID + "\",\n"
				+ "  \"configurationCount\": " + CONFIGURATION_COUNT + ",\n"
				+ "  \"timingRows\": " + TOTAL_TIMING_ROWS + ",\n"
				+ "  \"blockRows\": " + TOTAL_BLOCK_ROWS + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(string(manifest, "deviceName")) + ",\n"
				+ "  \"computeCapability\": "
				+ EvidenceFiles.json(string(manifest, "computeCapability")) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(
						string(manifest, "driverVersion")) + ",\n"
				+ "  \"timingPtxSha256\": \"" + string(manifest, "timingPtxSha256") + "\",\n"
				+ runtimeFields()
				+ identityFields(arguments)
				+ "}\n";
		EvidenceFiles.writeAtomicNew(manifestPath, finalManifest.getBytes(StandardCharsets.UTF_8));
		String sums = EvidenceFiles.sha256(Files.readAllBytes(manifestPath))
				+ "  final-manifest.json\n"
				+ EvidenceFiles.sha256(Files.readAllBytes(
						arguments.outputRoot().resolve("SHA256SUMS.txt")))
				+ "  SHA256SUMS.txt\n"
				+ EvidenceFiles.sha256(Files.readAllBytes(chunk.resolve("SHA256SUMS.txt")))
				+ "  chunk-00/SHA256SUMS.txt\n"
				+ EvidenceFiles.sha256(Files.readAllBytes(arguments.outputRoot().resolve(
						"presentation/SHA256SUMS.txt")))
				+ "  presentation/SHA256SUMS.txt\n";
		EvidenceFiles.writeAtomicNew(hashesPath, sums.getBytes(StandardCharsets.UTF_8));
		System.out.println(arguments.outputRoot());
	}

	private static List<GpuRayTracer> openContexts() {
		List<GpuRayTracer> result = new ArrayList<>();
		try {
			for (int index = 0; index < CONTEXTS; index++) {
				GpuRayTracer tracer = new GpuRayTracer(BenchmarkProtocol.WIDTH, BenchmarkProtocol.HEIGHT,
						BenchmarkProtocol.SAMPLES_PER_PIXEL, true, false);
				if (tracer.collectsMetrics()) throw new IllegalStateException("Depth context is instrumented");
				result.add(tracer);
			}
			return result;
		} catch (RuntimeException | Error failure) {
			for (int index = result.size() - 1; index >= 0; index--) result.get(index).close();
			throw failure;
		}
	}

	private static void requireContextIdentity(List<GpuRayTracer> contexts) {
		if (contexts.size() != CONTEXTS) throw new IllegalStateException("Depth context count differs");
		GpuRayTracer.DeviceInfo device = null;
		String ptx = null;
		for (int index = 0; index < contexts.size(); index++) {
			GpuRayTracer tracer = contexts.get(index);
			if (!tracer.isAvailable()) throw new IllegalStateException("CUDA unavailable for benchmark depth");
			if (index == 0) {
				device = tracer.deviceInfo();
				ptx = tracer.compiledPtxSha256();
			} else if (!tracer.deviceInfo().equals(device)
					|| !tracer.compiledPtxSha256().equals(ptx)) {
				throw new IllegalStateException("benchmark depth context identity differs");
			}
		}
		if (device == null || !EvidenceFiles.isSha256(ptx)
				|| !device.name().equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !device.computeCapability().equals(
						PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !System.getProperty(DRIVER_PROPERTY, "").equals(
						PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)) {
			throw new IllegalStateException("benchmark depth GPU or calibration identity differs");
		}
	}

	static void requireRuntimeConfiguration() {
		if (!System.getProperty(RENDER_PIXELS_PROPERTY, "").equals(
				Integer.toString(RENDER_PIXELS_PER_LAUNCH))
				|| GpuLaunchProvenance.renderPixelsPerLaunch() != RENDER_PIXELS_PER_LAUNCH
				|| !System.getProperty(STACK_PROPERTY, "").equals(Integer.toString(STACK_SIZE))) {
			throw new IllegalStateException("benchmark depth launch or BVH stack configuration differs");
		}
	}

	static void requireUniformMethod(MethodCatalog.Method method) {
		if (method == null || !method.family().equals("uniform")
				|| method.ordinal() != 0 || method.leafSize() != BenchmarkProtocol.LEAF_SIZE
				|| Double.doubleToLongBits(method.lambda()) != Double.doubleToLongBits(0.0)
				|| method.scene().bvhStats().maxDepth() >= STACK_SIZE
				|| !method.optionKey().equals(MethodCatalog.optionKey(BenchmarkProtocol.LEAF_SIZE, 0.0))) {
			throw new IllegalStateException("benchmark depth built method differs from the protocol");
		}
	}

	static MethodCatalog.Method buildUniformMethod(Scene scene) {
		MethodCatalog.Method method = MethodCatalog.buildObjectSah(
				scene, MethodCatalog.calibratedBase(), BenchmarkProtocol.LEAF_SIZE, 0.0);
		requireUniformMethod(method);
		return method;
	}

	static GpuCamera depthCamera(SceneCatalog.SceneSetup setup) {
		return BenchmarkRays.gpuCamera(setup.camera(), IMAGE_WIDTH, IMAGE_HEIGHT);
	}

	private static String buildRow(MethodCatalog.Method method) {
		requireUniformMethod(method);
		GpuScene.BvhStats stats = method.scene().bvhStats();
		return BenchmarkProtocol.SCHEMA_VERSION + "," + VERSION + ',' + SCENE_ID
				+ ",uniform," + BenchmarkProtocol.LEAF_SIZE + ",0.0,"
				+ method.scene().primitiveCount() + ',' + stats.nodeCount() + ','
				+ stats.rootCount() + ',' + stats.leafCount() + ',' + stats.maxDepth() + ','
				+ stats.primitiveRefCount() + ',' + stats.bytes() + ',' + method.buildNanos() + ','
				+ method.wallNanos() + ',' + method.packedGeometrySha256() + ','
				+ method.topologySha256() + '\n';
	}

	private static String timingRow(
			int comparison, int depth, PairedMeasurement.RawRow row
	) {
		return prefix(comparison, depth) + ',' + row.context() + ',' + row.blockIndex() + ','
				+ row.position() + ',' + row.order() + ',' + row.variant() + ',' + row.endpointId()
				+ ',' + row.topologySha256() + ',' + row.maximumPathDepth() + ','
				+ row.measurementSeed() + ',' + row.conditioningSeed() + ','
				+ row.conditioningFrameIndex() + ',' + row.conditioningUploadNanos() + ','
				+ row.conditioningMaximumPhysicalKernelNanos() + ','
				+ row.conditioningAggregatePhysicalKernelNanos() + ',' + row.conditioningTotalNanos()
				+ ',' + row.measurementFrameIndex() + ',' + row.kernelNanos() + ','
				+ row.maximumPhysicalKernelNanos() + ',' + row.uploadNanos() + ','
				+ row.copyNanos() + ',' + row.totalNanos() + '\n';
	}

	private static String blockRow(
			int comparison, int depth, PairedMeasurement.BlockResult row
	) {
		return prefix(comparison, depth) + ',' + row.context() + ',' + row.blockIndex() + ','
				+ row.order() + ',' + row.firstMeasurementSeed() + ',' + row.secondMeasurementSeed()
				+ ',' + String.format(Locale.ROOT, "%.12f", row.ordinaryKernelReductionPercent()) + '\n';
	}

	private static String prefix(int comparison, int depth) {
		return "1," + VERSION + ',' + SCENE_ID + ',' + comparison + ",depth_"
				+ REFERENCE_DEPTH + "__depth_" + depth + ',' + REFERENCE_DEPTH + ',' + depth;
	}

	private static String planManifest(Arguments arguments) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"PLAN\",\n"
				+ "  \"gpuExecutionStarted\": false,\n"
				+ "  \"sceneId\": \"" + SCENE_ID + "\",\n"
				+ "  \"configurationCount\": " + CONFIGURATION_COUNT + ",\n"
				+ "  \"timingRows\": " + TOTAL_TIMING_ROWS + ",\n"
				+ "  \"blockRows\": " + TOTAL_BLOCK_ROWS + ",\n"
				+ runtimeFields()
				+ identityFields(arguments)
				+ "}\n";
	}

	private static String chunkManifest(
			Arguments arguments, int timingRows, int blockRows, long elapsed,
			String device, String capability, String ptx
	) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"chunkIndex\": 0,\n"
				+ "  \"sceneId\": \"" + SCENE_ID + "\",\n"
				+ "  \"buildRows\": " + BUILD_ROW_COUNT + ",\n"
				+ "  \"timingRows\": " + timingRows + ",\n"
				+ "  \"blockRows\": " + blockRows + ",\n"
				+ "  \"elapsedNanos\": " + elapsed + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(device) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(capability) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(
						System.getProperty(DRIVER_PROPERTY, "")) + ",\n"
				+ "  \"timingPtxSha256\": \"" + ptx + "\",\n"
				+ runtimeFields()
				+ identityFields(arguments)
				+ "}\n";
	}

	private static String identityFields(Arguments arguments) {
		return "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.DEPTH_DOCUMENT_SHA256 + "\"\n";
	}

	private static String runtimeFields() {
		return "  \"renderPixelsPerLaunch\": " + RENDER_PIXELS_PER_LAUNCH + ",\n"
				+ "  \"bvhStackSize\": " + STACK_SIZE + ",\n"
				+ "  \"physicalKernelLaunchesPerFrame\": " + PHYSICAL_LAUNCHES_PER_FRAME + ",\n"
				+ "  \"maximumPhysicalKernelNanosExclusive\": "
				+ BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE + ",\n";
	}

	private static Arguments parse(String[] arguments) {
		if (arguments == null || arguments.length < 12 || (arguments.length & 1) != 0) {
			throw new IllegalArgumentException("benchmark depth requires named option pairs");
		}
		Set<String> allowed = Set.of("stage", "project-root", "output-root", "chunk-index",
				"compiled-classes-sha256", "source-commit", "source-tree");
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String option = arguments[index];
			if (!option.startsWith("--") || !allowed.contains(option.substring(2))) {
				throw new IllegalArgumentException("Unknown benchmark depth option: " + option);
			}
			String value = arguments[index + 1].trim();
			if (value.isEmpty() || values.put(option.substring(2), value) != null) {
				throw new IllegalArgumentException("Missing or duplicate benchmark depth option");
			}
		}
		Set<String> required = Set.of("stage", "project-root", "output-root",
				"compiled-classes-sha256", "source-commit", "source-tree");
		if (!values.keySet().containsAll(required)) throw new IllegalArgumentException("Depth options incomplete");
		String stage = values.get("stage");
		int chunk = values.containsKey("chunk-index") ? Integer.parseInt(values.get("chunk-index")) : -1;
		if (stage.equals("measure-chunk") != values.containsKey("chunk-index")) {
			throw new IllegalArgumentException("Only benchmark depth measure-chunk accepts chunk-index");
		}
		return new Arguments(stage,
				Path.of(values.get("project-root")).toAbsolutePath().normalize(),
				Path.of(values.get("output-root")).toAbsolutePath().normalize(), chunk,
				sha(values.get("compiled-classes-sha256"), 64),
				sha(values.get("source-commit"), 40), sha(values.get("source-tree"), 40));
	}

	private static void verifyPlan(Arguments arguments) throws Exception {
		EvidenceFiles.verifyRegularFileDirectory(arguments.outputRoot(), PLAN_FILES, path -> {
			String name = path.getFileName().toString();
			return name.equals("chunk-00") || name.equals("presentation")
					|| name.equals("final-manifest.json")
					|| name.equals("FINAL_SHA256SUMS.txt");
		});
		EvidenceFiles.verifySha256Ledger(arguments.outputRoot(), PLAN_HASHED_FILES);
		String manifest = Files.readString(arguments.outputRoot().resolve("manifest.json"),
				StandardCharsets.UTF_8);
		requireExactJsonFields(manifest, PLAN_MANIFEST_FIELDS, "plan");
		requireIdentity(manifest, arguments, "PLAN");
		requireRuntimeManifest(manifest);
		if (integer(manifest, "schemaVersion") != 1
				|| booleanValue(manifest, "gpuExecutionStarted")
				|| !string(manifest, "sceneId").equals(SCENE_ID)
				|| integer(manifest, "configurationCount") != CONFIGURATION_COUNT
				|| integer(manifest, "timingRows") != TOTAL_TIMING_ROWS
				|| integer(manifest, "blockRows") != TOTAL_BLOCK_ROWS) {
			throw new IllegalStateException("benchmark depth plan cardinality differs");
		}
	}

	private static void requireRuntimeManifest(String manifest) {
		if (integer(manifest, "renderPixelsPerLaunch") != RENDER_PIXELS_PER_LAUNCH
				|| integer(manifest, "bvhStackSize") != STACK_SIZE
				|| integer(manifest, "physicalKernelLaunchesPerFrame")
				!= PHYSICAL_LAUNCHES_PER_FRAME
				|| integer(manifest, "maximumPhysicalKernelNanosExclusive")
				!= BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE) {
			throw new IllegalStateException("benchmark depth runtime manifest differs");
		}
	}

	private static void requireGpuManifest(String manifest) {
		if (!string(manifest, "deviceName").equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !string(manifest, "computeCapability").equals(
						PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !string(manifest, "driverVersion").equals(
						PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)
				|| !EvidenceFiles.isSha256(string(manifest, "timingPtxSha256"))) {
			throw new IllegalStateException("benchmark depth GPU manifest identity differs");
		}
	}

	private static void requireExactJsonFields(
			String json, Set<String> expected, String label
	) {
		Matcher matcher = Pattern.compile("\\\"([A-Za-z][A-Za-z0-9]*)\\\"\\s*:").matcher(json);
		Set<String> actual = new HashSet<>();
		while (matcher.find()) {
			if (!actual.add(matcher.group(1))) {
				throw new IllegalStateException("Duplicate benchmark depth " + label + " manifest field");
			}
		}
		if (!actual.equals(expected)) {
			throw new IllegalStateException("benchmark depth " + label + " manifest fields differ");
		}
	}

	private static void requireIdentity(String manifest, Arguments arguments, String state) {
		if (!string(manifest, "protocolVersion").equals(VERSION)
				|| !string(manifest, "measurementState").equals(state)
				|| !string(manifest, "compiledClassesSha256").equals(arguments.compiledClassesSha256())
				|| !string(manifest, "sourceCommit").equals(arguments.sourceCommit())
				|| !string(manifest, "sourceTree").equals(arguments.sourceTree())
				|| !string(manifest, "protocolSha256").equals(BenchmarkProtocol.DEPTH_DOCUMENT_SHA256)) {
			throw new IllegalStateException("benchmark depth manifest identity differs");
		}
	}

	private static void requireDeadline(long started, String operation) {
		if (System.nanoTime() - started >= TIMEOUT_SECONDS * 1_000_000_000L) {
			throw new IllegalStateException("benchmark depth deadline reached before " + operation);
		}
	}

	static String string(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark depth JSON string: " + key);
		return matcher.group(1);
	}

	static int integer(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*([0-9]+)").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark depth JSON integer: " + key);
		return Integer.parseInt(matcher.group(1));
	}

	static long longInteger(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*([0-9]+)").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark depth JSON long: " + key);
		return Long.parseLong(matcher.group(1));
	}

	private static boolean booleanValue(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*(true|false)").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark depth JSON boolean: " + key);
		return Boolean.parseBoolean(matcher.group(1));
	}

	private static String sha(String value, int length) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{" + length + "}")) {
			throw new IllegalArgumentException("Malformed benchmark depth source identity");
		}
		return normalized;
	}

}
