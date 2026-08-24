package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.GpuScene;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CPU-only, warmed, order-balanced BVH construction study.
 * This class never creates a renderer or a CUDA context.
 */
public final class ConstructionStudy {
	public static final String VERSION = "gfxlab-construction-study";
	public static final int[] WILLIAMS_BASE = {0, 1, 7, 2, 6, 3, 5, 4};
	public static final int FIXED_WARMUP_ROUNDS = 1;
	public static final int FIXED_MEASURED_ROUNDS = 8;
	public static final int RANDOM_MEASURED_LAYOUTS = BenchmarkProtocol.RANDOM_LAYOUT_COUNT;
	private static final int METHOD_COUNT = MethodCatalog.FAMILIES.size();
	private static final int FIXED_ROW_COUNT = (int) BenchmarkProtocol.publicationRows().stream()
			.filter(row -> row.study() != BenchmarkProtocol.StudyKind.RANDOM).count();
	private static final int RANDOM_ROW_COUNT = BenchmarkProtocol.PUBLICATION_ROW_COUNT
			- FIXED_ROW_COUNT;
	public static final int EXPECTED_WARMUP_ROWS = Math.multiplyExact(METHOD_COUNT,
			Math.addExact(Math.multiplyExact(FIXED_ROW_COUNT, FIXED_WARMUP_ROUNDS),
					RANDOM_ROW_COUNT));
	public static final int EXPECTED_MEASURED_ROWS = Math.multiplyExact(METHOD_COUNT,
			Math.addExact(Math.multiplyExact(FIXED_ROW_COUNT, FIXED_MEASURED_ROUNDS),
					Math.multiplyExact(RANDOM_ROW_COUNT, RANDOM_MEASURED_LAYOUTS)));
	public static final int EXPECTED_TOTAL_ROWS = Math.addExact(
			EXPECTED_WARMUP_ROWS, EXPECTED_MEASURED_ROWS);
	private static final long HARD_RUNTIME_NANOS = 3_600_000_000_000L;
	private static final Set<String> RESULT_FILES = Set.of(
			"raw-builds.csv", "manifest.json", "SHA256SUMS.txt");
	private static final Set<String> HASHED_RESULT_FILES = Set.of(
			"raw-builds.csv", "manifest.json");

	private static final String HEADER =
			"schemaVersion,protocolVersion,buildOrdinal,publicationRowOrdinal,rowId,study,"
			+ "family,objectCount,layoutId,layoutSha256,phase,round,position,methodOrdinal,"
			+ "methodId,mode,leafSize,lambda,optionKey,primitiveCount,activePackedPrimitiveTypeCount,"
			+ "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,duplicateReferenceCount,"
			+ "spatialSplitCount,rotationCount,bytes,buildNanos,wallNanos,"
			+ "packedGeometrySha256,topologySha256";

	private record Arguments(
			Path projectRoot, Path outputRoot, String compiledClassesSha256,
			String sourceCommit, String sourceTree
	) { }

	private static final class Accumulator {
		private final StringBuilder csv = new StringBuilder(2_000_000).append(HEADER).append('\n');
		private final Map<String, String> fixedTopologies = new HashMap<>();
		private int ordinal;
		private int warmups;
		private int measured;

		void add(
				BenchmarkWorkloads.Source source, String phase, int round, int position,
				MethodCatalog.Method method
		) {
			if (!phase.equals("warmup") && !phase.equals("measured")) {
				throw new IllegalArgumentException("Unknown benchmark construction phase");
			}
			validateBuiltMethod(source, method);
			if (source.publicationRow().study() != BenchmarkProtocol.StudyKind.RANDOM) {
				String key = source.publicationRow().id() + "|" + method.family();
				String previous = fixedTopologies.putIfAbsent(key, method.topologySha256());
				if (previous != null && !previous.equals(method.topologySha256())) {
					throw new IllegalStateException("Fixed benchmark topology changed across rounds: " + key);
				}
			}
			GpuScene.BvhStats stats = method.scene().bvhStats();
			csv.append(BenchmarkProtocol.SCHEMA_VERSION).append(',').append(VERSION).append(',')
					.append(ordinal++).append(',').append(source.publicationRow().ordinal()).append(',')
					.append(source.publicationRow().id()).append(',')
					.append(source.publicationRow().study().name()).append(',')
					.append(source.publicationRow().randomFamily() == null
							? "" : source.publicationRow().randomFamily()).append(',')
					.append(source.publicationRow().study() == BenchmarkProtocol.StudyKind.HERO
							? source.publicationRow().heroScale()
							: source.publicationRow().objectCount()).append(',')
					.append(source.layoutId()).append(',')
					.append(source.layoutSha256() == null ? "" : source.layoutSha256()).append(',')
					.append(phase).append(',').append(round).append(',').append(position).append(',')
					.append(method.ordinal()).append(',').append(method.family()).append(',')
					.append(method.spec().mode().name()).append(',').append(method.leafSize()).append(',')
					.append(String.format(Locale.ROOT, "%.9f", method.lambda())).append(',')
					.append(method.optionKey()).append(',').append(source.expectedPrimitiveCount()).append(',')
					.append(source.expectedActivePrimitiveTypeCount()).append(',')
					.append(stats.nodeCount()).append(',').append(stats.rootCount()).append(',')
					.append(stats.leafCount()).append(',').append(stats.maxDepth()).append(',')
					.append(stats.primitiveRefCount()).append(',')
					.append(stats.duplicateReferenceCount()).append(',')
					.append(stats.spatialSplitCount()).append(',').append(stats.rotationCount()).append(',')
					.append(stats.bytes()).append(',').append(method.buildNanos()).append(',')
					.append(method.wallNanos()).append(',').append(method.packedGeometrySha256()).append(',')
					.append(method.topologySha256()).append('\n');
			if (phase.equals("warmup")) warmups++; else measured++;
		}
	}

	private ConstructionStudy() { }

	public static void main(String[] rawArguments) throws Exception {
		Arguments arguments = parse(rawArguments);
		BenchmarkProtocol.verify(arguments.projectRoot());
		BenchmarkClassIdentity.requireLiveIdentity(
				ConstructionStudy.class, arguments.compiledClassesSha256());
		run(arguments);
	}

	public static int[] williamsOrder(int sequence) {
		if (sequence < 0) throw new IllegalArgumentException("Negative Williams sequence");
		int[] result = new int[WILLIAMS_BASE.length];
		for (int position = 0; position < result.length; position++) {
			result[position] = (WILLIAMS_BASE[position] + sequence) % result.length;
		}
		return result;
	}

	public static void verifyOrderDesign() {
		if (MethodCatalog.FAMILIES.size() != WILLIAMS_BASE.length) {
			throw new IllegalStateException("benchmark Williams design needs eight methods");
		}
		int methodCount = WILLIAMS_BASE.length;
		int[][] positions = new int[methodCount][methodCount];
		Set<String> carryovers = new HashSet<>();
		for (int sequence = 0; sequence < methodCount; sequence++) {
			int[] order = williamsOrder(sequence);
			Set<Integer> row = new HashSet<>();
			for (int position = 0; position < order.length; position++) {
				if (!row.add(order[position])) throw new IllegalStateException("Repeated benchmark method");
				positions[order[position]][position]++;
				if (position + 1 < order.length
						&& !carryovers.add(order[position] + ">" + order[position + 1])) {
					throw new IllegalStateException("Repeated benchmark directed carryover");
				}
			}
		}
		for (int[] method : positions) {
			for (int count : method) {
				if (count != 1) throw new IllegalStateException("benchmark position balance differs");
			}
		}
		if (carryovers.size() != methodCount * (methodCount - 1)) {
			throw new IllegalStateException("benchmark carryover balance differs");
		}
		int[][] randomPositions = new int[methodCount][methodCount];
		for (int layout = 0; layout < RANDOM_MEASURED_LAYOUTS; layout++) {
			int[] order = williamsOrder(layout % methodCount);
			for (int position = 0; position < methodCount; position++) {
				randomPositions[order[position]][position]++;
			}
		}
		int minimumPositionCount = RANDOM_MEASURED_LAYOUTS / methodCount;
		int maximumPositionCount = (RANDOM_MEASURED_LAYOUTS + methodCount - 1) / methodCount;
		for (int[] method : randomPositions) {
			for (int count : method) {
				if (count != minimumPositionCount && count != maximumPositionCount) {
					throw new IllegalStateException("benchmark random position count differs");
				}
			}
		}
	}

	private static void run(Arguments arguments) throws Exception {
		verifyOrderDesign();
		Path output = EvidenceFiles.requireCreateNewOutput(
				arguments.projectRoot(), arguments.outputRoot());
		Path partial = output.resolveSibling(output.getFileName() + ".partial-" + UUID.randomUUID());
		Files.createDirectory(partial);
		long started = System.nanoTime();
		Accumulator accumulator = new Accumulator();
		BvhBuildConfig base = MethodCatalog.calibratedBase();
		for (BenchmarkProtocol.PublicationRow row : BenchmarkProtocol.publicationRows()) {
			requireDeadline(started, row.id());
			if (row.study() == BenchmarkProtocol.StudyKind.RANDOM) {
				runRandomRow(row, base, accumulator, started);
			} else {
				runFixedRow(row, base, accumulator, started);
			}
		}
		if (accumulator.warmups != EXPECTED_WARMUP_ROWS
				|| accumulator.measured != EXPECTED_MEASURED_ROWS
				|| accumulator.ordinal != EXPECTED_TOTAL_ROWS) {
			throw new IllegalStateException("benchmark construction cardinality differs");
		}
		EvidenceFiles.writeNew(partial.resolve("raw-builds.csv"), accumulator.csv.toString());
		long elapsed = System.nanoTime() - started;
		String manifest = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"protocolVersion\": \"" + VERSION + "\",\n"
				+ "  \"measurementState\": \"CPU_MEASURED\",\n"
				+ "  \"cudaPermitted\": false,\n"
				+ "  \"estimand\": \"internal CPU BVH constructor interval\",\n"
				+ "  \"warmupRows\": " + accumulator.warmups + ",\n"
				+ "  \"measuredRows\": " + accumulator.measured + ",\n"
				+ "  \"totalRows\": " + accumulator.ordinal + ",\n"
				+ "  \"publicationRows\": " + BenchmarkProtocol.PUBLICATION_ROW_COUNT + ",\n"
				+ "  \"methods\": " + METHOD_COUNT + ",\n"
				+ "  \"elapsedNanos\": " + elapsed + ",\n"
				+ "  \"compiledClassesSha256\": \"" + arguments.compiledClassesSha256() + "\",\n"
				+ "  \"sourceCommit\": \"" + arguments.sourceCommit() + "\",\n"
				+ "  \"sourceTree\": \"" + arguments.sourceTree() + "\",\n"
				+ "  \"protocolSha256\": \"" + BenchmarkProtocol.CONSTRUCTION_DOCUMENT_SHA256 + "\"\n"
				+ "}\n";
		EvidenceFiles.writeNew(partial.resolve("manifest.json"), manifest);
		EvidenceFiles.writeSha256Ledger(partial);
		verifyPartial(partial, arguments, accumulator.ordinal);
		EvidenceFiles.moveAtomic(partial, output);
		System.out.println(output);
	}

	private static void runFixedRow(
			BenchmarkProtocol.PublicationRow row, BvhBuildConfig base,
			Accumulator accumulator, long started
	) {
		BenchmarkWorkloads.Source source = BenchmarkWorkloads.fixed(row);
		for (int warmup = 0; warmup < FIXED_WARMUP_ROUNDS; warmup++) {
			buildSequence(source, base, "warmup", warmup, williamsOrder(warmup), accumulator);
		}
		for (int round = 0; round < FIXED_MEASURED_ROUNDS; round++) {
			requireDeadline(started, row.id() + " measured round " + round);
			buildSequence(source, base, "measured", round, williamsOrder(round), accumulator);
		}
	}

	private static void runRandomRow(
			BenchmarkProtocol.PublicationRow row, BvhBuildConfig base,
			Accumulator accumulator, long started
	) {
		BenchmarkWorkloads.Source warmup = BenchmarkWorkloads.random(row, BenchmarkProtocol.RANDOM_WARMUP_LAYOUT_ID);
		buildSequence(warmup, base, "warmup", 0, williamsOrder(0), accumulator);
		for (int layoutId = BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID;
				layoutId <= BenchmarkProtocol.LAST_RANDOM_LAYOUT_ID; layoutId++) {
			requireDeadline(started, row.id() + " layout " + layoutId);
			BenchmarkWorkloads.Source source = BenchmarkWorkloads.random(row, layoutId);
			buildSequence(source, base, "measured",
					layoutId - BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID,
					williamsOrder((layoutId - BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID)
							% WILLIAMS_BASE.length),
					accumulator);
		}
	}

	private static void buildSequence(
			BenchmarkWorkloads.Source source, BvhBuildConfig base, String phase,
			int round, int[] order, Accumulator accumulator
	) {
		String geometry = null;
		for (int position = 0; position < order.length; position++) {
			MethodCatalog.Spec spec = MethodCatalog.specs().get(order[position]);
			MethodCatalog.Method method = MethodCatalog.buildOne(
					source.scene(), base, spec, BenchmarkProtocol.LEAF_SIZE, BenchmarkProtocol.WEIGHTED_LAMBDA);
			if (geometry == null) geometry = method.packedGeometrySha256();
			if (!geometry.equals(method.packedGeometrySha256())) {
				throw new IllegalStateException("benchmark methods changed packed workload geometry");
			}
			accumulator.add(source, phase, round, position, method);
		}
	}

	private static void validateBuiltMethod(
			BenchmarkWorkloads.Source source, MethodCatalog.Method method
	) {
		GpuScene.BvhStats stats = method.scene().bvhStats();
		int requiredRoots = MethodCatalog.isPerType(method.family())
				? source.expectedActivePrimitiveTypeCount() : 1;
		long maximumReferences = MethodCatalog.isSbvh(method.family())
				? Math.multiplyExact((long) source.expectedPrimitiveCount(), 2L)
				: source.expectedPrimitiveCount();
		if (method.scene().primitiveCount() != source.expectedPrimitiveCount()
				|| stats.originalPrimitiveCount() != source.expectedPrimitiveCount()
				|| stats.rootCount() != requiredRoots || stats.nodeCount() < stats.rootCount()
				|| stats.leafCount() < stats.rootCount() || stats.maxDepth() < 0
				|| stats.maxDepth() >= BenchmarkProtocol.STACK_CAPACITY
				|| stats.primitiveRefCount() < source.expectedPrimitiveCount()
				|| stats.primitiveRefCount() > maximumReferences
				|| (!MethodCatalog.isSbvh(method.family())
						&& (stats.duplicateReferenceCount() != 0
						|| stats.primitiveRefCount() != source.expectedPrimitiveCount()))
				|| stats.bytes() <= 0L || stats.buildNanos() != method.buildNanos()) {
			throw new IllegalStateException("benchmark construction safety differs: "
					+ source.publicationRow().id() + " " + method.family());
		}
	}

	private static Arguments parse(String[] arguments) {
		if (arguments == null || arguments.length != 10) {
			throw new IllegalArgumentException("benchmark construction requires five named options");
		}
		Set<String> allowed = Set.of("project-root", "output-root", "compiled-classes-sha256",
				"source-commit", "source-tree");
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String option = arguments[index];
			if (!option.startsWith("--") || !allowed.contains(option.substring(2))) {
				throw new IllegalArgumentException("Unknown benchmark construction option: " + option);
			}
			String value = arguments[index + 1].trim();
			if (value.isEmpty() || values.put(option.substring(2), value) != null) {
				throw new IllegalArgumentException("Missing or duplicate benchmark option: " + option);
			}
		}
		if (!values.keySet().equals(allowed)) throw new IllegalArgumentException("benchmark options incomplete");
		Path root = Path.of(values.get("project-root")).toAbsolutePath().normalize();
		Path output = Path.of(values.get("output-root")).toAbsolutePath().normalize();
		String classes = sha(values.get("compiled-classes-sha256"), 64, "classes");
		String commit = sha(values.get("source-commit"), 40, "commit");
		String tree = sha(values.get("source-tree"), 40, "tree");
		return new Arguments(root, output, classes, commit, tree);
	}

	private static void requireDeadline(long started, String operation) {
		if (System.nanoTime() - started >= HARD_RUNTIME_NANOS) {
			throw new IllegalStateException("benchmark construction deadline reached before " + operation);
		}
	}

	private static void verifyPartial(Path directory, Arguments arguments, int rows)
			throws Exception {
		EvidenceFiles.verifyDirectory(directory, RESULT_FILES);
		if (rows != EXPECTED_TOTAL_ROWS
				|| !BenchmarkClassIdentity.recompute(ConstructionStudy.class)
				.equals(arguments.compiledClassesSha256())) {
			throw new IllegalStateException("benchmark construction artifact inventory differs");
		}
		Set<String> hashed = EvidenceFiles.verifySha256Ledger(directory);
		if (!hashed.equals(HASHED_RESULT_FILES)) {
			throw new IllegalStateException("benchmark SHA256SUMS inventory differs");
		}
	}

	private static String sha(String value, int length, String label) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{" + length + "}")) {
			throw new IllegalArgumentException("Malformed benchmark " + label + " identity");
		}
		return normalized;
	}

}
