package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;
import xyz.marsavic.gfxlab.gpu.TraversalWorkMetric;

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

/**
 * Create-new producer for the unified benchmark Hero, Overlap, and random campaign.
 * GPU work is reachable only through the explicit {@code measure-chunk} stage.
 */
public final class EvaluationStudy {
	private static final String DRIVER_PROPERTY = "gfxlab.gpu.driverVersion";
	private static final int CHUNK_TIMEOUT_SECONDS =
			BenchmarkProtocol.HARD_CHILD_TIMEOUT_SECONDS;
	private static final Set<String> PLAN_FILES = Set.of(
			"protocol.json", "methods.csv", "comparisons.csv", "chunks.csv",
			"analyze.py", "manifest.json", "SHA256SUMS.txt");
	private static final Set<String> CHUNK_FILES = Set.of(
			"builds.csv", "timing.csv", "blocks.csv", "metrics.csv",
			"manifest.json", "SHA256SUMS.txt");

	private static final String BUILD_HEADER =
			"schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
			+ "objectCount,layoutId,endpointId,methodId,mode,leafSize,lambda,optionKey,"
			+ "primitiveCount,nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,"
			+ "duplicateReferenceCount,spatialSplitCount,rotationCount,bytes,buildNanos,"
			+ "wallNanos,packedGeometrySha256,topologySha256";
	private static final String TIMING_HEADER =
			"schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
			+ "objectCount,layoutId,comparisonOrdinal,comparisonId,referenceId,candidateId,"
			+ "context,blockIndex,position,order,variant,endpointId,topologySha256,"
			+ "maximumPathDepth,measurementSeed,conditioningSeed,conditioningFrameIndex,"
			+ "conditioningUploadNanos,conditioningMaximumPhysicalKernelNanos,"
			+ "conditioningAggregatePhysicalKernelNanos,conditioningTotalNanos,"
			+ "measurementFrameIndex,kernelNanos,maximumPhysicalKernelNanos,uploadNanos,"
			+ "copyNanos,totalNanos";
	private static final String BLOCK_HEADER =
			"schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
			+ "objectCount,layoutId,comparisonOrdinal,comparisonId,referenceId,candidateId,"
			+ "context,blockIndex,order,firstMeasurementSeed,secondMeasurementSeed,"
			+ "ordinaryKernelReductionPercent";
	private static final String METRICS_HEADER =
			"schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
			+ "objectCount,layoutId,methodId,metricsContext,frameSeed,kernelNanos,"
			+ "maximumPhysicalKernelNanos,rays,aabbTests,primitiveTests,sphereTests,"
			+ "boxTests,planeTests,affineSphereTests,affineBoxTests,internalNodeVisits,"
			+ "leafNodeVisits,stackOverflows,maxStackSize,modeledWork,topologySha256";

	private record Arguments(
			String stage, Path projectRoot, Path outputRoot, int chunkIndex,
			String compiledClassesSha256, String sourceCommit, String sourceTree
	) { }

	private record Endpoint(String id, String methodId, MethodCatalog.Method method) {
		Endpoint {
			requireCsvId(id);
			requireCsvId(methodId);
			if (method == null) throw new IllegalArgumentException("Missing benchmark endpoint method");
		}
	}

	private record Comparison(int ordinal, String id, Endpoint reference, Endpoint candidate) {
		Comparison {
			requireCsvId(id);
			if (ordinal < 0 || reference == null || candidate == null
					|| reference.id().equals(candidate.id())) {
				throw new IllegalArgumentException("Invalid benchmark comparison");
			}
		}
	}

	private record Inventory(
			List<Endpoint> built, List<Endpoint> metrics, List<Comparison> comparisons
	) {
		Inventory {
			built = List.copyOf(built);
			metrics = List.copyOf(metrics);
			comparisons = List.copyOf(comparisons);
			if (built.isEmpty() || metrics.isEmpty() || comparisons.isEmpty()) {
				throw new IllegalArgumentException("Incomplete benchmark workload inventory");
			}
		}
	}

	private record Contexts(
			GpuRayTracer timing1, GpuRayTracer timing2, GpuRayTracer metrics
	) implements AutoCloseable {
		Contexts {
			if (timing1 == null || timing2 == null || metrics == null
					|| timing1 == timing2 || timing1 == metrics || timing2 == metrics
					|| timing1.collectsMetrics() || timing2.collectsMetrics()
					|| !metrics.collectsMetrics()) {
				throw new IllegalArgumentException("benchmark needs two timing contexts and one metrics context");
			}
		}
		GpuRayTracer timing(int index) {
			return switch (index) {
				case 0 -> timing1;
				case 1 -> timing2;
				default -> throw new IllegalArgumentException("Unknown benchmark timing context");
			};
		}
		@Override public void close() {
			metrics.close();
			timing2.close();
			timing1.close();
		}
	}

	private static final class PropertyScope implements AutoCloseable {
		private final String name;
		private final String prior;
		PropertyScope(String name, String value) {
			this.name = name;
			prior = System.getProperty(name);
			System.setProperty(name, value);
		}
		@Override public void close() {
			if (prior == null) System.clearProperty(name); else System.setProperty(name, prior);
		}
	}

	private static final class Payload {
		final StringBuilder builds = new StringBuilder(BUILD_HEADER).append('\n');
		final StringBuilder timing = new StringBuilder(TIMING_HEADER).append('\n');
		final StringBuilder blocks = new StringBuilder(BLOCK_HEADER).append('\n');
		final StringBuilder metrics = new StringBuilder(METRICS_HEADER).append('\n');
		int buildRows;
		int timingRows;
		int blockRows;
		int metricRows;
	}

	private EvaluationStudy() { }

	public static void main(String[] rawArguments) throws Exception {
		Arguments arguments = parse(rawArguments);
		BenchmarkProtocol.verify(arguments.projectRoot());
		BenchmarkProtocol.requireTimingRuntimeProperties();
		BenchmarkClassIdentity.requireLiveIdentity(
				EvaluationStudy.class, arguments.compiledClassesSha256());
		switch (arguments.stage()) {
			case "preflight" -> runPreflight(arguments);
			case "measure-chunk" -> runChunk(arguments);
			case "finalize" -> runFinalize(arguments);
			default -> throw new IllegalArgumentException("Unknown benchmark evaluation stage");
		}
	}

	private static void runPreflight(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != -1) throw new IllegalArgumentException("Preflight has no chunk");
		Path output = EvidenceFiles.requireCreateNewOutput(
				arguments.projectRoot(), arguments.outputRoot());
		Path partial = output.resolveSibling(output.getFileName() + ".partial-" + UUID.randomUUID());
		Files.createDirectory(partial);
		byte[] analyzer = Files.readAllBytes(
				arguments.projectRoot().resolve("benchmarks/analyze_results.py"));
		EvidenceFiles.writeNew(partial.resolve("protocol.json"), Files.readAllBytes(
				arguments.projectRoot().resolve(BenchmarkProtocol.DOCUMENT)));
		EvidenceFiles.writeNew(partial.resolve("analyze.py"), analyzer);
		EvidenceFiles.writeNew(partial.resolve("methods.csv"), methodsCsv().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("comparisons.csv"), comparisonsCsv().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("chunks.csv"), chunksCsv().getBytes(StandardCharsets.UTF_8));
		String manifest = planManifest(arguments, EvidenceFiles.sha256(analyzer));
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyDirectory(partial, PLAN_FILES);
		EvidenceFiles.moveAtomic(partial, output);
		System.out.println(output);
	}

	private static void runChunk(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() < 0 || arguments.chunkIndex() >= BenchmarkProtocol.EVALUATION_CHUNK_COUNT) {
			throw new IllegalArgumentException("benchmark measure-chunk requires one index in [0,"
					+ (BenchmarkProtocol.EVALUATION_CHUNK_COUNT - 1) + ']');
		}
		String analyzerSha256 = verifyPlan(arguments);
		BenchmarkProtocol.EvaluationChunk chunk = BenchmarkProtocol.chunk(arguments.chunkIndex());
		Path target = arguments.outputRoot().resolve(String.format(Locale.ROOT,
				"chunk-%02d", chunk.index()));
		EvidenceFiles.requireUnattempted(arguments.outputRoot(), target);
		Path partial = target.resolveSibling(target.getFileName() + ".attempt-" + UUID.randomUUID());
		// Persistent reservation is deliberately created before any CUDA context.
		Files.createDirectory(partial);
		long started = System.nanoTime();
		Payload payload = new Payload();
		String deviceName;
		String computeCapability;
		String timingPtx;
		String metricsPtx;
		try (Contexts contexts = openContexts()) {
			requireContextIdentity(contexts);
			deviceName = contexts.timing1().deviceInfo().name();
			computeCapability = contexts.timing1().deviceInfo().computeCapability();
			timingPtx = contexts.timing1().compiledPtxSha256();
			metricsPtx = contexts.metrics().compiledPtxSha256();
			float[][] timingPixels = {
				new float[BenchmarkProtocol.WIDTH * BenchmarkProtocol.HEIGHT * 3],
				new float[BenchmarkProtocol.WIDTH * BenchmarkProtocol.HEIGHT * 3],
			};
			float[] metricPixels = new float[BenchmarkProtocol.WIDTH * BenchmarkProtocol.HEIGHT * 3];
			BenchmarkProtocol.PublicationRow row = BenchmarkProtocol.publicationRows()
					.get(chunk.publicationRowOrdinal());
			if (chunk.study() == BenchmarkProtocol.StudyKind.RANDOM) {
				for (int layout = chunk.firstLayoutId(); layout <= chunk.lastLayoutId(); layout++) {
					requireDeadline(started, row.id() + " layout " + layout);
					runWorkload(chunk, BenchmarkWorkloads.random(row, layout), contexts,
							timingPixels, metricPixels, payload, started);
				}
			} else {
				runWorkload(chunk, BenchmarkWorkloads.fixed(row), contexts,
						timingPixels, metricPixels, payload, started);
			}
		}
		if (payload.buildRows != BenchmarkProtocol.buildRows(chunk)
				|| payload.timingRows != BenchmarkProtocol.timingRows(chunk)
				|| payload.blockRows != BenchmarkProtocol.blockRows(chunk)
				|| payload.metricRows != BenchmarkProtocol.metricRows(chunk)) {
			throw new IllegalStateException("benchmark chunk output cardinality differs");
		}
		EvidenceFiles.writeNew(partial.resolve("builds.csv"), payload.builds.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("timing.csv"), payload.timing.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("blocks.csv"), payload.blocks.toString().getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(partial.resolve("metrics.csv"), payload.metrics.toString().getBytes(StandardCharsets.UTF_8));
		String manifest = chunkManifest(arguments, chunk, payload, System.nanoTime() - started,
				deviceName, computeCapability, timingPtx, metricsPtx, analyzerSha256);
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(partial);
		EvidenceFiles.verifyDirectory(partial, CHUNK_FILES);
		EvidenceFiles.moveAtomic(partial, target);
		System.out.println(target);
	}

	private static void runWorkload(
			BenchmarkProtocol.EvaluationChunk chunk, BenchmarkWorkloads.Source source, Contexts contexts,
			float[][] timingPixels, float[] metricPixels, Payload payload, long started
	) {
		BvhBuildConfig base = MethodCatalog.calibratedBase();
		Inventory inventory = inventory(chunk, source, base);
		for (Endpoint endpoint : inventory.built()) {
			payload.builds.append(buildRow(source, endpoint));
			payload.buildRows++;
		}
		GpuCamera camera = BenchmarkRays.gpuCamera(
				source.camera(), BenchmarkProtocol.WIDTH, BenchmarkProtocol.HEIGHT);
		long workloadKey = BenchmarkProtocol.workloadKey(source.publicationRow(), source.layoutId());
		try (PropertyScope ignored = new PropertyScope(
				BenchmarkProtocol.RENDER_PIXELS_PER_LAUNCH_PROPERTY,
				Integer.toString(BenchmarkProtocol.METRICS_MAXIMUM_PHYSICAL_PIXELS_PER_LAUNCH))) {
			for (Endpoint endpoint : inventory.metrics()) {
				requireDeadline(started, source.publicationRow().id() + " metrics " + endpoint.id());
				long seed = BenchmarkProtocol.metricsSeed(workloadKey);
				contexts.metrics().renderSample(metricPixels, endpoint.method().scene(), camera,
						BenchmarkProtocol.MAXIMUM_PATH_DEPTH, 0, seed);
				GpuRayTracer.FrameStats stats = contexts.metrics().lastFrameStats();
				requireSafeMetrics(stats, endpoint.id());
				payload.metrics.append(metricsRow(source, endpoint, seed, stats));
				payload.metricRows++;
			}
		}
		if (Integer.getInteger(BenchmarkProtocol.RENDER_PIXELS_PER_LAUNCH_PROPERTY, -1)
				!= BenchmarkProtocol.TIMING_RENDER_PIXELS_PER_LAUNCH) {
			throw new IllegalStateException("benchmark timing launch partition was not restored");
		}
		for (Comparison comparison : inventory.comparisons()) {
			for (int context = 0; context < BenchmarkProtocol.TIMING_CONTEXTS; context++) {
				requireDeadline(started, source.publicationRow().id() + " timing " + comparison.id());
				PairedMeasurement.Run run = PairedMeasurement.execute(
						contexts.timing(context), timingPixels[context], camera,
						new PairedMeasurement.Limits(
								BenchmarkProtocol.MAXIMUM_PATH_DEPTH,
								BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE,
								BenchmarkProtocol.TIMING_CONTEXTS,
								BenchmarkProtocol.TIMING_PHYSICAL_LAUNCHES_PER_FRAME),
						timingCandidate(comparison.reference()),
						timingCandidate(comparison.candidate()),
						BenchmarkProtocol.SYMMETRIC_BLOCKS_PER_CONTEXT,
						BenchmarkProtocol.timingSeed(workloadKey, comparison.ordinal()),
						workloadKey, comparison.ordinal(), context);
				for (PairedMeasurement.RawRow raw : run.rows()) {
					payload.timing.append(timingRow(source, comparison, raw));
					payload.timingRows++;
				}
				for (PairedMeasurement.BlockResult block : run.blocks()) {
					payload.blocks.append(blockRow(source, comparison, block));
					payload.blockRows++;
				}
			}
		}
	}

	private static Inventory inventory(
			BenchmarkProtocol.EvaluationChunk chunk, BenchmarkWorkloads.Source source, BvhBuildConfig base
	) {
		if (chunk.study() != source.publicationRow().study()) {
			throw new IllegalArgumentException("benchmark chunk/workload study differs");
		}
		if (chunk.study() != BenchmarkProtocol.StudyKind.HERO) {
			Map<String, Endpoint> endpoints = new LinkedHashMap<>();
			for (MethodCatalog.Method method : MethodCatalog.buildAll(
					source.scene(), base, BenchmarkProtocol.LEAF_SIZE, BenchmarkProtocol.WEIGHTED_LAMBDA)) {
				putEndpoint(endpoints, new Endpoint(method.family(), method.family(), method));
			}
			List<Comparison> comparisons = new ArrayList<>();
			for (int ordinal = 0; ordinal < MethodCatalog.EDGES.size(); ordinal++) {
				MethodCatalog.Edge edge = MethodCatalog.EDGES.get(ordinal);
				comparisons.add(new Comparison(ordinal, edge.id(),
						endpoints.get(edge.referenceFamily()), endpoints.get(edge.candidateFamily())));
			}
			return new Inventory(new ArrayList<>(endpoints.values()),
					new ArrayList<>(endpoints.values()), comparisons);
		}

		Map<String, Endpoint> built = new LinkedHashMap<>();
		Map<String, Endpoint> metrics = new LinkedHashMap<>();
		Map<String, Endpoint> core = new LinkedHashMap<>();
		if (chunk.includesCore()) {
			for (MethodCatalog.Method method : MethodCatalog.buildAll(
					source.scene(), base, BenchmarkProtocol.LEAF_SIZE, BenchmarkProtocol.WEIGHTED_LAMBDA)) {
				Endpoint endpoint = new Endpoint(method.family(), method.family(), method);
				putEndpoint(core, endpoint);
				putEndpoint(built, endpoint);
				putEndpoint(metrics, endpoint);
			}
		} else {
			MethodCatalog.Method anchor = MethodCatalog.buildObjectSah(
					source.scene(), base, BenchmarkProtocol.LEAF_SIZE, 0.0);
			putEndpoint(built, new Endpoint("uniform", "uniform", anchor));
		}
		Endpoint reference = built.get("uniform");
		List<BenchmarkProtocol.HeroCell> candidates = BenchmarkProtocol.heroSensitivityCandidates();
		List<Comparison> comparisons = new ArrayList<>();
		for (int ordinal = chunk.firstHeroCandidate(); ordinal <= chunk.lastHeroCandidate(); ordinal++) {
			BenchmarkProtocol.HeroCell cell = candidates.get(ordinal);
			boolean coreWeighted = cell.leafSize() == BenchmarkProtocol.LEAF_SIZE
					&& Double.doubleToLongBits(cell.lambda())
					== Double.doubleToLongBits(BenchmarkProtocol.WEIGHTED_LAMBDA);
			Endpoint endpoint;
			if (coreWeighted && chunk.includesCore()) {
				endpoint = core.get("weighted");
			} else {
				MethodCatalog.Method method = MethodCatalog.buildObjectSah(
						source.scene(), base, cell.leafSize(), cell.lambda());
				String id = coreWeighted ? "weighted" : heroEndpointId(cell);
				endpoint = new Endpoint(id, id, method);
				putEndpoint(built, endpoint);
			}
			putEndpoint(metrics, endpoint);
			if (!reference.method().packedGeometrySha256()
					.equals(endpoint.method().packedGeometrySha256())) {
				throw new IllegalStateException(
						"benchmark Hero sensitivity candidate changed packed geometry");
			}
			comparisons.add(new Comparison(ordinal,
					coreWeighted ? "uniform__weighted" : "uniform__" + endpoint.id(),
					reference, endpoint));
		}
		if (chunk.includesCore()) {
			for (int edgeOrdinal = 1; edgeOrdinal < MethodCatalog.EDGES.size(); edgeOrdinal++) {
				MethodCatalog.Edge edge = MethodCatalog.EDGES.get(edgeOrdinal);
				comparisons.add(new Comparison(candidates.size() - 1 + edgeOrdinal, edge.id(),
						core.get(edge.referenceFamily()), core.get(edge.candidateFamily())));
			}
		}
		if (comparisons.size() != BenchmarkProtocol.directComparisons(chunk)
				|| metrics.size() != BenchmarkProtocol.metricRows(chunk)) {
			throw new IllegalStateException("benchmark Hero weighted-edge deduplication differs");
		}
		return new Inventory(new ArrayList<>(built.values()),
				new ArrayList<>(metrics.values()), comparisons);
	}

	private static void putEndpoint(Map<String, Endpoint> target, Endpoint endpoint) {
		Endpoint previous = target.putIfAbsent(endpoint.id(), endpoint);
		if (previous != null && (!previous.method().topologySha256()
				.equals(endpoint.method().topologySha256())
				|| !previous.method().packedGeometrySha256()
				.equals(endpoint.method().packedGeometrySha256()))) {
			throw new IllegalStateException("benchmark endpoint identity aliases different data");
		}
	}

	private static String heroEndpointId(BenchmarkProtocol.HeroCell cell) {
		return "sensitivity_leaf_" + cell.leafSize() + "_lambda_"
				+ String.format(Locale.ROOT, "%.2f", cell.lambda()).replace('.', '_');
	}

	private static PairedMeasurement.Candidate timingCandidate(Endpoint endpoint) {
		return new PairedMeasurement.Candidate(endpoint.id(),
				endpoint.method().topologySha256(), endpoint.method().scene());
	}

	private static Contexts openContexts() {
		GpuRayTracer timing1 = null;
		GpuRayTracer timing2 = null;
		GpuRayTracer metrics = null;
		try {
			timing1 = tracer(false);
			timing2 = tracer(false);
			metrics = tracer(true);
			return new Contexts(timing1, timing2, metrics);
		} catch (RuntimeException | Error failure) {
			if (metrics != null) metrics.close();
			if (timing2 != null) timing2.close();
			if (timing1 != null) timing1.close();
			throw failure;
		}
	}

	private static GpuRayTracer tracer(boolean collectMetrics) {
		return new GpuRayTracer(BenchmarkProtocol.WIDTH, BenchmarkProtocol.HEIGHT,
				BenchmarkProtocol.SAMPLES_PER_PIXEL, true, collectMetrics);
	}

	private static void requireContextIdentity(Contexts contexts) {
		for (GpuRayTracer tracer : List.of(contexts.timing1(), contexts.timing2(), contexts.metrics())) {
			if (!tracer.isAvailable()) throw new IllegalStateException("CUDA unavailable for benchmark");
		}
		GpuRayTracer.DeviceInfo device = contexts.timing1().deviceInfo();
		if (!contexts.timing2().deviceInfo().equals(device)
				|| !contexts.metrics().deviceInfo().equals(device)) {
			throw new IllegalStateException("benchmark CUDA device differs between contexts");
		}
		String timingPtx = contexts.timing1().compiledPtxSha256();
		String metricsPtx = contexts.metrics().compiledPtxSha256();
		if (!EvidenceFiles.isSha256(timingPtx) || !EvidenceFiles.isSha256(metricsPtx)
				|| !contexts.timing2().compiledPtxSha256().equals(timingPtx)
				|| metricsPtx.equals(timingPtx)
				|| !device.name().equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !device.computeCapability().equals(
						PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !System.getProperty(DRIVER_PROPERTY, "").equals(
						PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)) {
			throw new IllegalStateException("benchmark CUDA/PTX/calibration identity differs");
		}
	}

	private static void requireSafeMetrics(GpuRayTracer.FrameStats stats, String id) {
		if (stats == null || stats.rays() <= 0L || stats.kernelNanos() <= 0L
				|| stats.maximumPhysicalKernelNanos() <= 0L
				|| stats.maximumPhysicalKernelNanos()
				>= BenchmarkProtocol.MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE
				|| stats.stackOverflows() != 0L || stats.maxStackSize() >= BenchmarkProtocol.STACK_CAPACITY) {
			throw new IllegalStateException("benchmark metric safety failed: " + id);
		}
	}

	private static void runFinalize(Arguments arguments) throws Exception {
		if (arguments.chunkIndex() != -1) throw new IllegalArgumentException("Finalize has no chunk");
		String analyzerSha256 = verifyPlan(arguments);
		Path finalManifest = arguments.outputRoot().resolve("final-manifest.json");
		Path finalHashes = arguments.outputRoot().resolve("FINAL_SHA256SUMS.txt");
		if (Files.exists(finalManifest, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(finalHashes, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("benchmark evaluation root is already finalized or attempted");
		}
		int timingRows = 0;
		int blockRows = 0;
		int metricRows = 0;
		int buildRows = 0;
		Set<String> devices = new HashSet<>();
		Set<String> capabilities = new HashSet<>();
		Set<String> drivers = new HashSet<>();
		Set<String> timingPtx = new HashSet<>();
		Set<String> metricsPtx = new HashSet<>();
		StringBuilder chunkHashes = new StringBuilder();
		for (BenchmarkProtocol.EvaluationChunk chunk : BenchmarkProtocol.evaluationChunks()) {
			Path directory = arguments.outputRoot().resolve(String.format(Locale.ROOT,
					"chunk-%02d", chunk.index()));
			EvidenceFiles.verifyDirectory(directory, CHUNK_FILES);
			EvidenceFiles.verifySha256Ledger(directory);
			String manifest = Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8);
			requireManifestIdentity(manifest, arguments, "GPU_MEASURED", analyzerSha256);
			if (jsonLong(manifest, "chunkIndex") != chunk.index()
					|| jsonLong(manifest, "buildRows") != BenchmarkProtocol.buildRows(chunk)
					|| jsonLong(manifest, "timingRows") != BenchmarkProtocol.timingRows(chunk)
					|| jsonLong(manifest, "blockRows") != BenchmarkProtocol.blockRows(chunk)
					|| jsonLong(manifest, "metricRows") != BenchmarkProtocol.metricRows(chunk)) {
				throw new IllegalStateException("benchmark chunk manifest cardinality differs");
			}
			timingRows += BenchmarkProtocol.timingRows(chunk);
			blockRows += BenchmarkProtocol.blockRows(chunk);
			metricRows += BenchmarkProtocol.metricRows(chunk);
			buildRows += BenchmarkProtocol.buildRows(chunk);
			devices.add(jsonString(manifest, "deviceName"));
			capabilities.add(jsonString(manifest, "computeCapability"));
			drivers.add(jsonString(manifest, "driverVersion"));
			timingPtx.add(jsonString(manifest, "timingPtxSha256"));
			metricsPtx.add(jsonString(manifest, "metricsPtxSha256"));
			chunkHashes.append(EvidenceFiles.sha256(
					Files.readAllBytes(directory.resolve("SHA256SUMS.txt"))))
					.append("  ").append(directory.getFileName()).append("/SHA256SUMS.txt\n");
		}
		if (timingRows != BenchmarkProtocol.TOTAL_TIMING_ROW_COUNT
				|| blockRows != BenchmarkProtocol.TOTAL_BLOCK_ROW_COUNT
				|| metricRows != BenchmarkProtocol.TOTAL_METRIC_ROW_COUNT
				|| buildRows != BenchmarkProtocol.TOTAL_BUILD_ROW_COUNT
				|| devices.size() != 1 || capabilities.size() != 1 || drivers.size() != 1
				|| timingPtx.size() != 1 || metricsPtx.size() != 1) {
			throw new IllegalStateException("benchmark finalized campaign identity/cardinality differs");
		}
		String manifest = finalManifest(arguments, devices.iterator().next(),
				capabilities.iterator().next(), drivers.iterator().next(),
				timingPtx.iterator().next(), metricsPtx.iterator().next(), analyzerSha256);
		EvidenceFiles.writeNew(finalManifest, manifest.getBytes(StandardCharsets.UTF_8));
		String sums = EvidenceFiles.sha256(Files.readAllBytes(finalManifest))
				+ "  final-manifest.json\n"
				+ EvidenceFiles.sha256(Files.readAllBytes(
						arguments.outputRoot().resolve("SHA256SUMS.txt")))
				+ "  SHA256SUMS.txt\n" + chunkHashes;
		EvidenceFiles.writeNew(finalHashes, sums.getBytes(StandardCharsets.UTF_8));
		System.out.println(arguments.outputRoot());
	}

	private static String buildRow(BenchmarkWorkloads.Source source, Endpoint endpoint) {
		MethodCatalog.Method method = endpoint.method();
		GpuScene.BvhStats stats = method.scene().bvhStats();
		return prefix(source) + ',' + endpoint.id() + ',' + endpoint.methodId() + ','
				+ method.spec().mode() + ',' + method.leafSize() + ','
				+ String.format(Locale.ROOT, "%.9f", method.lambda()) + ',' + method.optionKey() + ','
				+ source.expectedPrimitiveCount() + ',' + stats.nodeCount() + ',' + stats.rootCount()
				+ ',' + stats.leafCount() + ',' + stats.maxDepth() + ',' + stats.primitiveRefCount()
				+ ',' + stats.duplicateReferenceCount() + ',' + stats.spatialSplitCount() + ','
				+ stats.rotationCount() + ',' + stats.bytes() + ',' + method.buildNanos() + ','
				+ method.wallNanos() + ',' + method.packedGeometrySha256() + ','
				+ method.topologySha256() + '\n';
	}

	private static String timingRow(
			BenchmarkWorkloads.Source source, Comparison comparison, PairedMeasurement.RawRow row
	) {
		return prefix(source) + ',' + comparison.ordinal() + ',' + comparison.id() + ','
				+ comparison.reference().id() + ',' + comparison.candidate().id() + ','
				+ row.context() + ',' + row.blockIndex() + ',' + row.position() + ',' + row.order()
				+ ',' + row.variant() + ',' + row.endpointId() + ',' + row.topologySha256() + ','
				+ row.maximumPathDepth() + ',' + row.measurementSeed() + ',' + row.conditioningSeed()
				+ ',' + row.conditioningFrameIndex() + ',' + row.conditioningUploadNanos() + ','
				+ row.conditioningMaximumPhysicalKernelNanos() + ','
				+ row.conditioningAggregatePhysicalKernelNanos() + ',' + row.conditioningTotalNanos()
				+ ',' + row.measurementFrameIndex() + ',' + row.kernelNanos() + ','
				+ row.maximumPhysicalKernelNanos() + ',' + row.uploadNanos() + ',' + row.copyNanos()
				+ ',' + row.totalNanos() + '\n';
	}

	private static String blockRow(
			BenchmarkWorkloads.Source source, Comparison comparison, PairedMeasurement.BlockResult row
	) {
		return prefix(source) + ',' + comparison.ordinal() + ',' + comparison.id() + ','
				+ comparison.reference().id() + ',' + comparison.candidate().id() + ','
				+ row.context() + ',' + row.blockIndex() + ',' + row.order() + ','
				+ row.firstMeasurementSeed() + ',' + row.secondMeasurementSeed() + ','
				+ String.format(Locale.ROOT, "%.12f", row.ordinaryKernelReductionPercent()) + '\n';
	}

	private static String metricsRow(
			BenchmarkWorkloads.Source source, Endpoint endpoint, long seed, GpuRayTracer.FrameStats stats
	) {
		return prefix(source) + ',' + endpoint.methodId() + ','
				+ BenchmarkProtocol.METRICS_CONTEXT_ORDINAL + ',' + seed + ',' + stats.kernelNanos() + ','
				+ stats.maximumPhysicalKernelNanos() + ',' + stats.rays() + ',' + stats.aabbTests()
				+ ',' + stats.primitiveTests() + ',' + stats.sphereTests() + ',' + stats.boxTests()
				+ ',' + stats.planeTests() + ',' + stats.affineSphereTests() + ','
				+ stats.affineBoxTests() + ',' + stats.internalNodeVisits() + ','
				+ stats.leafNodeVisits() + ',' + stats.stackOverflows() + ',' + stats.maxStackSize()
				+ ',' + String.format(Locale.ROOT, "%.9f", TraversalWorkMetric.cost(stats)) + ','
				+ endpoint.method().topologySha256() + '\n';
	}

	private static String prefix(BenchmarkWorkloads.Source source) {
		BenchmarkProtocol.PublicationRow row = source.publicationRow();
		String family = row.randomFamily() == null ? "" : row.randomFamily();
		int count = row.study() == BenchmarkProtocol.StudyKind.HERO
				? row.heroScale() : row.objectCount();
		return BenchmarkProtocol.SCHEMA_VERSION + "," + BenchmarkProtocol.VERSION + "," + row.study()
				+ ',' + row.ordinal() + ',' + row.id() + ',' + family + ',' + count + ','
				+ source.layoutId();
	}

	private static String methodsCsv() {
		StringBuilder result = new StringBuilder("schemaVersion,methodOrdinal,methodId,mode,weighted\n");
		for (MethodCatalog.Spec spec : MethodCatalog.specs()) {
			result.append(1).append(',').append(spec.ordinal()).append(',').append(spec.family())
					.append(',').append(spec.mode()).append(',').append(spec.weighted()).append('\n');
		}
		return result.toString();
	}

	private static String comparisonsCsv() {
		StringBuilder result = new StringBuilder(
				"schemaVersion,edgeOrdinal,comparisonId,referenceId,candidateId\n");
		for (int index = 0; index < MethodCatalog.EDGES.size(); index++) {
			MethodCatalog.Edge edge = MethodCatalog.EDGES.get(index);
			result.append(1).append(',').append(index).append(',').append(edge.id()).append(',')
					.append(edge.referenceFamily()).append(',').append(edge.candidateFamily()).append('\n');
		}
		return result.toString();
	}

	private static String chunksCsv() {
		StringBuilder result = new StringBuilder(
				"schemaVersion,chunkIndex,study,rowId,firstLayoutId,lastLayoutId,"
				+ "firstHeroCandidate,lastHeroCandidate,includesCore,directComparisons,"
				+ "buildRows,timingRows,blockRows,metricRows,hardTimeoutSeconds\n");
		for (BenchmarkProtocol.EvaluationChunk chunk : BenchmarkProtocol.evaluationChunks()) {
			BenchmarkProtocol.PublicationRow row = BenchmarkProtocol.publicationRows()
					.get(chunk.publicationRowOrdinal());
			result.append(1).append(',').append(chunk.index()).append(',').append(chunk.study())
					.append(',').append(row.id()).append(',').append(chunk.firstLayoutId()).append(',')
					.append(chunk.lastLayoutId()).append(',').append(chunk.firstHeroCandidate())
					.append(',').append(chunk.lastHeroCandidate()).append(',').append(chunk.includesCore())
					.append(',').append(BenchmarkProtocol.directComparisons(chunk)).append(',')
					.append(BenchmarkProtocol.buildRows(chunk)).append(',')
					.append(BenchmarkProtocol.timingRows(chunk)).append(',')
					.append(BenchmarkProtocol.blockRows(chunk)).append(',')
					.append(BenchmarkProtocol.metricRows(chunk)).append(',')
					.append(CHUNK_TIMEOUT_SECONDS).append('\n');
		}
		return result.toString();
	}

	private static String planManifest(Arguments arguments, String analyzerSha256) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + BenchmarkProtocol.VERSION + "\",\n"
				+ "  \"measurementState\": \"PLAN\",\n"
				+ "  \"gpuExecutionStarted\": false,\n"
				+ "  \"chunkCount\": " + BenchmarkProtocol.EVALUATION_CHUNK_COUNT + ",\n"
				+ "  \"directComparisons\": "
				+ BenchmarkProtocol.TOTAL_DIRECT_COMPARISON_COUNT + ",\n"
				+ "  \"buildRows\": " + BenchmarkProtocol.TOTAL_BUILD_ROW_COUNT + ",\n"
				+ "  \"timingRows\": " + BenchmarkProtocol.TOTAL_TIMING_ROW_COUNT + ",\n"
				+ "  \"blockRows\": " + BenchmarkProtocol.TOTAL_BLOCK_ROW_COUNT + ",\n"
				+ "  \"metricRows\": " + BenchmarkProtocol.TOTAL_METRIC_ROW_COUNT + ",\n"
				+ "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.DOCUMENT_SHA256 + "\",\n"
				+ "  \"analyzerSha256\": \"" + analyzerSha256 + "\"\n"
				+ "}\n";
	}

	private static String chunkManifest(
			Arguments arguments, BenchmarkProtocol.EvaluationChunk chunk, Payload payload,
			long elapsedNanos, String device, String capability, String timingPtx, String metricsPtx,
			String analyzerSha256
	) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + BenchmarkProtocol.VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"chunkIndex\": " + chunk.index() + ",\n"
				+ "  \"buildRows\": " + payload.buildRows + ",\n"
				+ "  \"timingRows\": " + payload.timingRows + ",\n"
				+ "  \"blockRows\": " + payload.blockRows + ",\n"
				+ "  \"metricRows\": " + payload.metricRows + ",\n"
				+ "  \"elapsedNanos\": " + elapsedNanos + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(device) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(capability) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(
						System.getProperty(DRIVER_PROPERTY, "")) + ",\n"
				+ "  \"timingPtxSha256\": \"" + timingPtx + "\",\n"
				+ "  \"metricsPtxSha256\": \"" + metricsPtx + "\",\n"
				+ "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.DOCUMENT_SHA256 + "\",\n"
				+ "  \"analyzerSha256\": \"" + analyzerSha256 + "\"\n"
				+ "}\n";
	}

	private static String finalManifest(
			Arguments arguments, String device, String capability, String driver,
			String timingPtx, String metricsPtx, String analyzerSha256
	) {
		return "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + BenchmarkProtocol.VERSION + "\",\n"
				+ "  \"measurementState\": \"GPU_MEASURED\",\n"
				+ "  \"chunkCount\": " + BenchmarkProtocol.EVALUATION_CHUNK_COUNT + ",\n"
				+ "  \"directComparisons\": "
				+ BenchmarkProtocol.TOTAL_DIRECT_COMPARISON_COUNT + ",\n"
				+ "  \"buildRows\": " + BenchmarkProtocol.TOTAL_BUILD_ROW_COUNT + ",\n"
				+ "  \"timingRows\": " + BenchmarkProtocol.TOTAL_TIMING_ROW_COUNT + ",\n"
				+ "  \"blockRows\": " + BenchmarkProtocol.TOTAL_BLOCK_ROW_COUNT + ",\n"
				+ "  \"metricRows\": " + BenchmarkProtocol.TOTAL_METRIC_ROW_COUNT + ",\n"
				+ "  \"deviceName\": " + EvidenceFiles.json(device) + ",\n"
				+ "  \"computeCapability\": " + EvidenceFiles.json(capability) + ",\n"
				+ "  \"driverVersion\": " + EvidenceFiles.json(driver) + ",\n"
				+ "  \"timingPtxSha256\": \"" + timingPtx + "\",\n"
				+ "  \"metricsPtxSha256\": \"" + metricsPtx + "\",\n"
				+ "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.DOCUMENT_SHA256 + "\",\n"
				+ "  \"analyzerSha256\": \"" + analyzerSha256 + "\"\n"
				+ "}\n";
	}

	private static Arguments parse(String[] arguments) {
		if (arguments == null || arguments.length < 12 || (arguments.length & 1) != 0) {
			throw new IllegalArgumentException("benchmark evaluation requires named option pairs");
		}
		Set<String> allowed = Set.of("stage", "project-root", "output-root", "chunk-index",
				"compiled-classes-sha256", "source-commit", "source-tree");
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String option = arguments[index];
			if (!option.startsWith("--") || !allowed.contains(option.substring(2))) {
				throw new IllegalArgumentException("Unknown benchmark evaluation option: " + option);
			}
			String value = arguments[index + 1].trim();
			if (value.isEmpty() || values.put(option.substring(2), value) != null) {
				throw new IllegalArgumentException("Missing/duplicate benchmark evaluation option");
			}
		}
		Set<String> required = Set.of("stage", "project-root", "output-root",
				"compiled-classes-sha256", "source-commit", "source-tree");
		if (!values.keySet().containsAll(required)) throw new IllegalArgumentException("benchmark options incomplete");
		String stage = values.get("stage");
		int chunk = values.containsKey("chunk-index") ? Integer.parseInt(values.get("chunk-index")) : -1;
		if (stage.equals("measure-chunk") != values.containsKey("chunk-index")) {
			throw new IllegalArgumentException("Only benchmark measure-chunk accepts chunk-index");
		}
		return new Arguments(stage,
				Path.of(values.get("project-root")).toAbsolutePath().normalize(),
				Path.of(values.get("output-root")).toAbsolutePath().normalize(), chunk,
				requireSha(values.get("compiled-classes-sha256"), 64),
				requireSha(values.get("source-commit"), 40),
				requireSha(values.get("source-tree"), 40));
	}

	private static String verifyPlan(Arguments arguments) throws Exception {
		EvidenceFiles.verifyDirectory(arguments.outputRoot(), PLAN_FILES, path -> {
			String name = path.getFileName().toString();
			if (name.equals("final-manifest.json") || name.equals("FINAL_SHA256SUMS.txt")) {
				return true;
			}
			if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
					|| !name.matches("chunk-[0-9]{2}")) {
				return false;
			}
			return Integer.parseInt(name.substring(6)) < BenchmarkProtocol.EVALUATION_CHUNK_COUNT;
		});
		EvidenceFiles.verifySha256Ledger(arguments.outputRoot());
		String manifest = Files.readString(arguments.outputRoot().resolve("manifest.json"),
				StandardCharsets.UTF_8);
		String analyzerSha256 = EvidenceFiles.sha256(Files.readAllBytes(
				arguments.outputRoot().resolve("analyze.py")));
		requireManifestIdentity(manifest, arguments, "PLAN", analyzerSha256);
		if (jsonLong(manifest, "chunkCount") != BenchmarkProtocol.EVALUATION_CHUNK_COUNT
				|| jsonLong(manifest, "directComparisons")
				!= BenchmarkProtocol.TOTAL_DIRECT_COMPARISON_COUNT
				|| jsonLong(manifest, "buildRows") != BenchmarkProtocol.TOTAL_BUILD_ROW_COUNT
				|| jsonLong(manifest, "timingRows") != BenchmarkProtocol.TOTAL_TIMING_ROW_COUNT
				|| jsonLong(manifest, "blockRows") != BenchmarkProtocol.TOTAL_BLOCK_ROW_COUNT
				|| jsonLong(manifest, "metricRows") != BenchmarkProtocol.TOTAL_METRIC_ROW_COUNT) {
			throw new IllegalStateException("benchmark plan cardinality differs");
		}
		return analyzerSha256;
	}

	private static void requireManifestIdentity(
			String manifest, Arguments arguments, String state, String analyzerSha256
	) {
		if (!jsonString(manifest, "protocolVersion").equals(BenchmarkProtocol.VERSION)
				|| !jsonString(manifest, "measurementState").equals(state)
				|| !jsonString(manifest, "compiledClassesSha256")
				.equals(arguments.compiledClassesSha256())
				|| !jsonString(manifest, "sourceCommit").equals(arguments.sourceCommit())
				|| !jsonString(manifest, "sourceTree").equals(arguments.sourceTree())
				|| !jsonString(manifest, "protocolSha256").equals(BenchmarkProtocol.DOCUMENT_SHA256)
				|| !jsonString(manifest, "analyzerSha256").equals(analyzerSha256)) {
			throw new IllegalStateException("benchmark manifest source/protocol identity differs");
		}
	}

	private static void requireDeadline(long started, String operation) {
		if (System.nanoTime() - started >= CHUNK_TIMEOUT_SECONDS * 1_000_000_000L) {
			throw new IllegalStateException("benchmark chunk deadline reached before " + operation);
		}
	}

	private static String jsonString(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark JSON string: " + key);
		return matcher.group(1);
	}

	private static long jsonLong(String json, String key) {
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
				+ "\\\"\\s*:\\s*([0-9]+)").matcher(json);
		if (!matcher.find()) throw new IllegalStateException("Missing benchmark JSON integer: " + key);
		return Long.parseLong(matcher.group(1));
	}

	private static String requireSha(String value, int length) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{" + length + "}")) {
			throw new IllegalArgumentException("Malformed benchmark source identity");
		}
		return normalized;
	}

	private static void requireCsvId(String value) {
		if (value == null || value.isBlank() || value.indexOf(',') >= 0
				|| value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("Invalid benchmark CSV identifier");
		}
	}

}
