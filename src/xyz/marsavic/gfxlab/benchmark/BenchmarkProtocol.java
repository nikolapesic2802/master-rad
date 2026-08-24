package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Shared protocol inventory for the Hero, overlap, and random studies. */
public final class BenchmarkProtocol {
	public static final int SCHEMA_VERSION = 1;
	public static final String VERSION = "gfxlab-thesis-evaluation";
	public static final Path DOCUMENT = Path.of(
			"benchmarks", "config", "evaluation-protocol.json");
	public static final String DOCUMENT_SHA256 =
			"bb4e8f2620b6832d785b50f62e770dc7fe8605e26188ed5e0f8cf3fa2203c7b1";

	public static final Path LEAF_DOCUMENT = Path.of(
			"benchmarks", "config", "leaf-size-protocol.json");
	public static final String LEAF_DOCUMENT_SHA256 =
			"7c0ca36cea0fbc48e9f2e733ff1b180dccf14c83923daf4cf003d60f7ae3cb5c";
	public static final Path DEPTH_DOCUMENT = Path.of(
			"benchmarks", "config", "depth-protocol.json");
	public static final String DEPTH_DOCUMENT_SHA256 =
			"ce755844941095ab40d48e25023cbebcab2c14b9ec54033976edead708165190";
	public static final Path CONSTRUCTION_DOCUMENT = Path.of(
			"benchmarks", "config", "construction-protocol.json");
	public static final String CONSTRUCTION_DOCUMENT_SHA256 =
			"24d6eccff4b8b46f2dacb141391a89857e33ba5d668458c30c443d739ddba1d3";

	public static final int WIDTH = 1_920;
	public static final int HEIGHT = 1_080;
	public static final int SAMPLES_PER_PIXEL = 1;
	public static final int MAXIMUM_PATH_DEPTH = 12;
	public static final int TIMING_RENDER_PIXELS_PER_LAUNCH = 65_536;
	public static final int TIMING_PHYSICAL_LAUNCHES_PER_FRAME =
			(WIDTH * HEIGHT + TIMING_RENDER_PIXELS_PER_LAUNCH - 1)
					/ TIMING_RENDER_PIXELS_PER_LAUNCH;
	public static final int METRICS_MAXIMUM_PHYSICAL_PIXELS_PER_LAUNCH = 3_072;
	public static final int STACK_CAPACITY = 32;
	public static final long MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE = 250_000_000L;
	public static final String RENDER_PIXELS_PER_LAUNCH_PROPERTY =
			"gfxlab.gpu.renderPixelsPerLaunch";
	public static final String BVH_STACK_CAPACITY_PROPERTY = "gfxlab.gpu.bvhStackSize";

	public static final int LEAF_SIZE = 8;
	public static final double WEIGHTED_LAMBDA = 1.0;
	public static final int TIMING_CONTEXTS = 2;
	public static final int SYMMETRIC_BLOCKS_PER_CONTEXT = 2;
	public static final int METRICS_CONTEXT_ORDINAL = 3;
	public static final int HARD_CHILD_TIMEOUT_SECONDS = 600;

	public static final List<Integer> HERO_SCALES =
			List.of(96, 1_000, 10_000, 100_000, 1_000_000);
	public static final List<Integer> HERO_LEAF_SIZES = List.of(1, 2, 4, 8, 16, 32, 64);
	public static final List<Double> HERO_LAMBDAS = List.of(
			0.0, 0.25, 0.5, 0.75, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0);
	public static final int HERO_REFERENCE_LEAF_SIZE = 8;
	public static final double HERO_REFERENCE_LAMBDA = 0.0;

	public static final List<String> RANDOM_FAMILIES = List.of("F1", "F2", "F3", "F4");
	public static final List<Integer> RANDOM_OBJECT_COUNTS = List.of(100, 1_000, 10_000);
	public static final int FIRST_RANDOM_LAYOUT_ID = 0;
	public static final int LAST_RANDOM_LAYOUT_ID = 99;
	public static final int RANDOM_LAYOUT_COUNT =
			LAST_RANDOM_LAYOUT_ID - FIRST_RANDOM_LAYOUT_ID + 1;
	public static final int RANDOM_WARMUP_LAYOUT_ID = 100;
	public static final long RANDOM_LAYOUT_EXPERIMENT_SEED = 5_641_122_812_212_105_804L;

	public static final int PUBLICATION_ROW_COUNT = HERO_SCALES.size() + 1
			+ RANDOM_FAMILIES.size() * RANDOM_OBJECT_COUNTS.size();
	public static final int TOTAL_DIRECT_COMPARISON_COUNT = 8_817;
	public static final int TOTAL_TIMING_ROW_COUNT = 141_072;
	public static final int TOTAL_BLOCK_ROW_COUNT = 35_268;
	public static final int TOTAL_METRIC_ROW_COUNT = 10_023;
	public static final int TOTAL_BUILD_ROW_COUNT = 10_024;
	public static final int EVALUATION_CHUNK_COUNT = 79;

	private static final long TIMING_SEED_DOMAIN = seedDomain("evaluation timing");
	private static final long METRICS_SEED_DOMAIN = seedDomain("evaluation metrics");
	public static final long EVALUATION_SCHEDULE_SEED =
			seedDomain("evaluation paired schedule");
	private static final long MIX = 0x9E3779B97F4A7C15L;

	public enum StudyKind { HERO, OVERLAP, RANDOM }

	/** One displayed row in each 18-by-8 publication matrix. */
	public record PublicationRow(
			int ordinal, String id, StudyKind study, int heroScale,
			String randomFamily, int objectCount
	) {
		public PublicationRow {
			if (ordinal < 0 || ordinal >= PUBLICATION_ROW_COUNT || id == null || id.isBlank()
					|| study == null || id.indexOf(',') >= 0) {
				throw new IllegalArgumentException("Invalid benchmark publication row");
			}
			switch (study) {
				case HERO -> {
					if (!HERO_SCALES.contains(heroScale)
							|| randomFamily != null || objectCount != -1) {
						throw new IllegalArgumentException("Invalid benchmark Hero row");
					}
				}
				case OVERLAP -> {
					if (heroScale != -1 || randomFamily != null || objectCount != 10_000) {
						throw new IllegalArgumentException("Invalid benchmark overlap row");
					}
				}
				case RANDOM -> {
					if (heroScale != -1 || !RANDOM_FAMILIES.contains(randomFamily)
							|| !RANDOM_OBJECT_COUNTS.contains(objectCount)) {
						throw new IllegalArgumentException("Invalid benchmark random row");
					}
				}
			}
		}
	}

	public record HeroCell(int ordinal, int leafSize, double lambda) {
		public HeroCell {
			if (ordinal < 0 || ordinal >= HERO_LEAF_SIZES.size() * HERO_LAMBDAS.size()
					|| !HERO_LEAF_SIZES.contains(leafSize) || !HERO_LAMBDAS.contains(lambda)
					|| ordinal != HERO_LEAF_SIZES.indexOf(leafSize) * HERO_LAMBDAS.size()
							+ HERO_LAMBDAS.indexOf(lambda)) {
				throw new IllegalArgumentException("Invalid benchmark Hero cell");
			}
		}
		public boolean isReference() {
			return leafSize == HERO_REFERENCE_LEAF_SIZE
					&& Double.doubleToLongBits(lambda)
					== Double.doubleToLongBits(HERO_REFERENCE_LAMBDA);
		}
	}

	/** A GPU chunk reservation. Hero ranges index the 76 non-anchor candidates. */
	public record EvaluationChunk(
			int index, StudyKind study, int publicationRowOrdinal,
			int firstLayoutId, int lastLayoutId,
			int firstHeroCandidate, int lastHeroCandidate, boolean includesCore
	) {
		public EvaluationChunk {
			if (index < 0 || index >= EVALUATION_CHUNK_COUNT || study == null
					|| publicationRowOrdinal < 0 || publicationRowOrdinal >= PUBLICATION_ROW_COUNT) {
				throw new IllegalArgumentException("Invalid benchmark chunk identity");
			}
			if (study == StudyKind.RANDOM) {
				if (firstLayoutId < FIRST_RANDOM_LAYOUT_ID
						|| lastLayoutId > LAST_RANDOM_LAYOUT_ID || firstLayoutId > lastLayoutId
						|| firstHeroCandidate != -1 || lastHeroCandidate != -1 || includesCore) {
					throw new IllegalArgumentException("Invalid benchmark random chunk");
				}
			} else if (study == StudyKind.HERO) {
				if (firstLayoutId != -1 || lastLayoutId != -1 || firstHeroCandidate < 0
						|| lastHeroCandidate >= heroSensitivityCandidates().size()
						|| firstHeroCandidate > lastHeroCandidate) {
					throw new IllegalArgumentException("Invalid benchmark Hero chunk");
				}
			} else if (firstLayoutId != -1 || lastLayoutId != -1
					|| firstHeroCandidate != -1 || lastHeroCandidate != -1 || !includesCore) {
				throw new IllegalArgumentException("Invalid benchmark overlap chunk");
			}
		}
		public int randomLayoutCount() {
			return study == StudyKind.RANDOM ? lastLayoutId - firstLayoutId + 1 : 0;
		}
		public int heroCandidateCount() {
			return study == StudyKind.HERO ? lastHeroCandidate - firstHeroCandidate + 1 : 0;
		}
	}

	private BenchmarkProtocol() { }

	public static List<PublicationRow> publicationRows() {
		List<PublicationRow> rows = new ArrayList<>(PUBLICATION_ROW_COUNT);
		for (int scale : HERO_SCALES) {
			rows.add(new PublicationRow(rows.size(), "hero-" + compact(scale),
					StudyKind.HERO, scale, null, -1));
		}
		rows.add(new PublicationRow(rows.size(), "overlap-10k",
				StudyKind.OVERLAP, -1, null, 10_000));
		for (String family : RANDOM_FAMILIES) {
			for (int count : RANDOM_OBJECT_COUNTS) {
				rows.add(new PublicationRow(rows.size(), family + "-" + compact(count),
						StudyKind.RANDOM, -1, family, count));
			}
		}
		return List.copyOf(rows);
	}

	public static List<HeroCell> heroCells() {
		List<HeroCell> cells = new ArrayList<>(77);
		for (int leaf : HERO_LEAF_SIZES) {
			for (double lambda : HERO_LAMBDAS) {
				cells.add(new HeroCell(cells.size(), leaf, lambda));
			}
		}
		return List.copyOf(cells);
	}

	public static List<HeroCell> heroSensitivityCandidates() {
		return heroCells().stream().filter(cell -> !cell.isReference()).toList();
	}

	public static List<EvaluationChunk> evaluationChunks() {
		List<EvaluationChunk> chunks = new ArrayList<>(EVALUATION_CHUNK_COUNT);
		for (int row = 0; row < 4; row++) {
			chunks.add(new EvaluationChunk(chunks.size(), StudyKind.HERO, row,
					-1, -1, 0, 75, true));
		}
		chunks.add(new EvaluationChunk(chunks.size(), StudyKind.HERO, 4,
				-1, -1, 0, 37, true));
		chunks.add(new EvaluationChunk(chunks.size(), StudyKind.HERO, 4,
				-1, -1, 38, 75, false));
		chunks.add(new EvaluationChunk(chunks.size(), StudyKind.OVERLAP, 5,
				-1, -1, -1, -1, true));
		for (int row = 6; row < PUBLICATION_ROW_COUNT; row++) {
			int count = publicationRows().get(row).objectCount();
			int layoutsPerChunk = count == 10_000 ? 10 : 25;
			for (int first = FIRST_RANDOM_LAYOUT_ID;
					first <= LAST_RANDOM_LAYOUT_ID; first += layoutsPerChunk) {
				chunks.add(new EvaluationChunk(chunks.size(), StudyKind.RANDOM, row,
						first, Math.min(LAST_RANDOM_LAYOUT_ID, first + layoutsPerChunk - 1),
						-1, -1, false));
			}
		}
		return List.copyOf(chunks);
	}

	public static EvaluationChunk chunk(int index) {
		if (index < 0 || index >= EVALUATION_CHUNK_COUNT) {
			throw new IllegalArgumentException("benchmark chunk index must be in [0,78]");
		}
		return evaluationChunks().get(index);
	}

	public static int directComparisons(EvaluationChunk chunk) {
		return switch (chunk.study()) {
			case HERO -> chunk.heroCandidateCount()
					// leaf=8/lambda=1 is already the uniform__weighted edge.
					+ (chunk.includesCore() ? MethodCatalog.EDGES.size() - 1 : 0);
			case OVERLAP -> MethodCatalog.EDGES.size();
			case RANDOM -> chunk.randomLayoutCount() * MethodCatalog.EDGES.size();
		};
	}

	public static int timingRows(EvaluationChunk chunk) {
		return Math.multiplyExact(directComparisons(chunk),
				TIMING_CONTEXTS * SYMMETRIC_BLOCKS_PER_CONTEXT * 4);
	}

	public static int blockRows(EvaluationChunk chunk) {
		return Math.multiplyExact(directComparisons(chunk),
				TIMING_CONTEXTS * SYMMETRIC_BLOCKS_PER_CONTEXT);
	}

	public static int metricRows(EvaluationChunk chunk) {
		return switch (chunk.study()) {
			case HERO -> chunk.heroCandidateCount() + (chunk.includesCore() ? 7 : 0);
			case OVERLAP -> MethodCatalog.FAMILIES.size();
			case RANDOM -> chunk.randomLayoutCount() * MethodCatalog.FAMILIES.size();
		};
	}

	public static int buildRows(EvaluationChunk chunk) {
		return switch (chunk.study()) {
			case HERO -> chunk.heroCandidateCount() + (chunk.includesCore() ? 7 : 1);
			case OVERLAP -> MethodCatalog.FAMILIES.size();
			case RANDOM -> chunk.randomLayoutCount() * MethodCatalog.FAMILIES.size();
		};
	}

	public static long workloadKey(PublicationRow row, int layoutId) {
		if (row.study() != StudyKind.RANDOM) {
			if (layoutId != -1) throw new IllegalArgumentException("Fixed workload has no layout");
			return row.ordinal();
		}
		if (layoutId < FIRST_RANDOM_LAYOUT_ID || layoutId > LAST_RANDOM_LAYOUT_ID) {
			throw new IllegalArgumentException("Unknown benchmark random layout");
		}
		return 6L + (row.ordinal() - 6L) * RANDOM_LAYOUT_COUNT
				+ layoutId - FIRST_RANDOM_LAYOUT_ID;
	}

	public static long timingSeed(long workloadKey, int comparisonOrdinal) {
		return seed(TIMING_SEED_DOMAIN, workloadKey, comparisonOrdinal);
	}

	public static long metricsSeed(long workloadKey) {
		return seed(METRICS_SEED_DOMAIN, workloadKey, 0);
	}

	public static void requireTimingRuntimeProperties() {
		int pixelsPerLaunch = requiredIntegerProperty(RENDER_PIXELS_PER_LAUNCH_PROPERTY);
		int stackCapacity = requiredIntegerProperty(BVH_STACK_CAPACITY_PROPERTY);
		if (pixelsPerLaunch != TIMING_RENDER_PIXELS_PER_LAUNCH
				|| stackCapacity != STACK_CAPACITY) {
			throw new IllegalStateException(
					"benchmark render partition or BVH stack property differs from the protocol");
		}
		int tileWidth = Math.min(WIDTH, pixelsPerLaunch);
		int tileHeight = Math.min(HEIGHT, Math.max(1, pixelsPerLaunch / tileWidth));
		int tileColumns = 1 + (WIDTH - 1) / tileWidth;
		int tileRows = 1 + (HEIGHT - 1) / tileHeight;
		int physicalLaunches = Math.multiplyExact(
				Math.multiplyExact(tileColumns, tileRows), SAMPLES_PER_PIXEL);
		if (physicalLaunches != TIMING_PHYSICAL_LAUNCHES_PER_FRAME) {
			throw new IllegalStateException(
					"benchmark physical render-launch count differs from the protocol");
		}
	}

	public static void verify(Path projectRoot) throws IOException {
		PrimitiveCostModel.validate();
		requireDocument(projectRoot, DOCUMENT, DOCUMENT_SHA256);
		requireDocument(projectRoot, LEAF_DOCUMENT, LEAF_DOCUMENT_SHA256);
		requireDocument(projectRoot, DEPTH_DOCUMENT, DEPTH_DOCUMENT_SHA256);
		requireDocument(projectRoot, CONSTRUCTION_DOCUMENT, CONSTRUCTION_DOCUMENT_SHA256);
		List<EvaluationChunk> chunks = evaluationChunks();
		Set<Integer> chunkIds = new HashSet<>();
		int comparisons = 0;
		int timing = 0;
		int blocks = 0;
		int metrics = 0;
		int builds = 0;
		for (EvaluationChunk chunk : chunks) {
			if (!chunkIds.add(chunk.index())) throw new IllegalStateException("Duplicate benchmark chunk");
			comparisons = Math.addExact(comparisons, directComparisons(chunk));
			timing = Math.addExact(timing, timingRows(chunk));
			blocks = Math.addExact(blocks, blockRows(chunk));
			metrics = Math.addExact(metrics, metricRows(chunk));
			builds = Math.addExact(builds, buildRows(chunk));
		}
		List<HeroCell> sensitivity = heroSensitivityCandidates();
		long weightedCoreCells = sensitivity.stream().filter(cell -> cell.leafSize() == LEAF_SIZE
				&& Double.doubleToLongBits(cell.lambda())
				== Double.doubleToLongBits(WEIGHTED_LAMBDA)).count();
		int expectedHeroCells = Math.multiplyExact(HERO_LEAF_SIZES.size(), HERO_LAMBDAS.size());
		if (publicationRows().size() != PUBLICATION_ROW_COUNT
				|| heroCells().size() != expectedHeroCells
				|| sensitivity.size() != expectedHeroCells - 1
				|| weightedCoreCells != 1
				|| chunks.size() != EVALUATION_CHUNK_COUNT
				|| chunkIds.size() != EVALUATION_CHUNK_COUNT
				|| comparisons != TOTAL_DIRECT_COMPARISON_COUNT
				|| timing != TOTAL_TIMING_ROW_COUNT || blocks != TOTAL_BLOCK_ROW_COUNT
				|| metrics != TOTAL_METRIC_ROW_COUNT
				|| builds != TOTAL_BUILD_ROW_COUNT
				|| MethodCatalog.FAMILIES.size() != 8
				|| MethodCatalog.EDGES.size() != 7
				|| RANDOM_WARMUP_LAYOUT_ID != LAST_RANDOM_LAYOUT_ID + 1) {
			throw new IllegalStateException("benchmark protocol inventory differs");
		}
	}

	private static void requireDocument(Path projectRoot, Path relative, String expected)
			throws IOException {
		Path root = projectRoot.toAbsolutePath().normalize();
		Path file = root.resolve(relative).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file)
				|| !sha256(Files.readAllBytes(file)).equals(expected)) {
			throw new IllegalStateException("benchmark protocol document differs: " + relative);
		}
	}

	private static long seed(long namespace, long first, long second) {
		long value = namespace ^ Long.rotateLeft((first + 1L) * MIX, 17)
				^ Long.rotateLeft((second + 1L) * MIX, 41);
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return value == 0L ? namespace : value;
	}

	private static long seedDomain(String label) {
		long value = 1_125_899_906_842_597L;
		for (int index = 0; index < label.length(); index++) {
			value = 31L * value + label.charAt(index);
		}
		return value;
	}

	private static int requiredIntegerProperty(String name) {
		String value = System.getProperty(name);
		if (value == null) {
			throw new IllegalStateException("Missing benchmark runtime property: " + name);
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException malformed) {
			throw new IllegalStateException("Malformed benchmark runtime property: " + name,
					malformed);
		}
	}

	private static String compact(int value) {
		return switch (value) {
			case 1_000 -> "1k";
			case 10_000 -> "10k";
			case 100_000 -> "100k";
			case 1_000_000 -> "1m";
			default -> Integer.toString(value);
		};
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}
}
