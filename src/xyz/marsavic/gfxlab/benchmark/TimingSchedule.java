package xyz.marsavic.gfxlab.benchmark;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic ABBA/BAAB schedule shared by every benchmark paired timing study. */
public final class TimingSchedule {
	private static final long WORKLOAD_NAMESPACE = 0x9E3779B97F4A7C15L;
	private static final long COMPARISON_NAMESPACE = 0xD1B54A32D192ED03L;
	private static final long CONTEXT_NAMESPACE = 0x94D049BB133111EBL;
	private static final long SUBPAIR_NAMESPACE = 0xA0761D6478BD642FL;
	private static final long CONDITIONING_NAMESPACE = 0xE7037ED1A0B428DBL;

	public enum Variant { A, B }
	public enum Phase { CONDITIONING, MEASUREMENT }
	public enum Order {
		ABBA(Variant.A, Variant.B, Variant.B, Variant.A),
		BAAB(Variant.B, Variant.A, Variant.A, Variant.B);

		private final List<Variant> variants;
		Order(Variant... variants) { this.variants = List.of(variants); }
		public Variant variantAt(int position) {
			requirePosition(position);
			return variants.get(position);
		}
		public Order opposite() { return this == ABBA ? BAAB : ABBA; }
	}

	public record Step(
			int blockIndex, int position, Order order, Variant variant, Phase phase,
			int frameIndex, long frameSeed
	) {
		public Step {
			if (blockIndex < 0 || position < 0 || position > 3 || order == null
					|| variant == null || phase == null || variant != order.variantAt(position)
					|| phase == Phase.CONDITIONING && frameIndex >= 0
					|| phase == Phase.MEASUREMENT && frameIndex < 0) {
				throw new IllegalArgumentException("Invalid benchmark timing step");
			}
		}
	}

	public record SymmetricBlock(
			int blockIndex, Order order, long firstMeasurementSeed,
			long secondMeasurementSeed, long firstConditioningSeed,
			long secondConditioningSeed, List<Step> steps
	) {
		public SymmetricBlock {
			steps = List.copyOf(steps);
			Set<Long> seeds = Set.of(firstMeasurementSeed, secondMeasurementSeed,
					firstConditioningSeed, secondConditioningSeed);
			if (blockIndex < 0 || order == null || steps.size() != 8 || seeds.size() != 4) {
				throw new IllegalArgumentException("Invalid benchmark timing block");
			}
			for (int position = 0; position < 4; position++) {
				int subpair = subpairIndex(blockIndex, position);
				long measured = position < 2 ? firstMeasurementSeed : secondMeasurementSeed;
				long conditioned = position < 2 ? firstConditioningSeed : secondConditioningSeed;
				Variant variant = order.variantAt(position);
				if (!steps.get(position * 2).equals(new Step(blockIndex, position, order,
						variant, Phase.CONDITIONING, -subpair - 1, conditioned))
						|| !steps.get(position * 2 + 1).equals(new Step(blockIndex, position,
						order, variant, Phase.MEASUREMENT, subpair, measured))) {
					throw new IllegalArgumentException("benchmark block execution order differs");
				}
			}
		}
		public long measurementSeedAt(int position) {
			requirePosition(position);
			return position < 2 ? firstMeasurementSeed : secondMeasurementSeed;
		}
		public long conditioningSeedAt(int position) {
			requirePosition(position);
			return position < 2 ? firstConditioningSeed : secondConditioningSeed;
		}
	}

	private TimingSchedule() { }

	public static List<SymmetricBlock> blocks(
			int blockCount, long experimentSeed, long workloadKey,
			int comparisonOrdinal, int contextIndex, int contextCount
	) {
		if (blockCount < 1 || workloadKey < 0L || comparisonOrdinal < 0
				|| contextCount < 1 || contextCount > 64
				|| contextIndex < 0 || contextIndex >= contextCount) {
			throw new IllegalArgumentException("Invalid benchmark schedule request");
		}
		List<SymmetricBlock> result = new ArrayList<>(blockCount);
		boolean abbaFirst = ((workloadKey + comparisonOrdinal + contextIndex) & 1L) == 0L;
		for (int block = 0; block < blockCount; block++) {
			Order order = ((block & 1) == 0) == abbaFirst ? Order.ABBA : Order.BAAB;
			int firstSubpair = Math.multiplyExact(block, 2);
			int secondSubpair = firstSubpair + 1;
			long firstMeasured = seed(experimentSeed, workloadKey, comparisonOrdinal,
					contextIndex, firstSubpair);
			long secondMeasured = seed(experimentSeed, workloadKey, comparisonOrdinal,
					contextIndex, secondSubpair);
			long firstConditioned = mix64(firstMeasured ^ CONDITIONING_NAMESPACE);
			long secondConditioned = mix64(secondMeasured ^ CONDITIONING_NAMESPACE);
			List<Step> steps = new ArrayList<>(8);
			for (int position = 0; position < 4; position++) {
				int subpair = position < 2 ? firstSubpair : secondSubpair;
				long measured = position < 2 ? firstMeasured : secondMeasured;
				long conditioned = position < 2 ? firstConditioned : secondConditioned;
				Variant variant = order.variantAt(position);
				steps.add(new Step(block, position, order, variant,
						Phase.CONDITIONING, -subpair - 1, conditioned));
				steps.add(new Step(block, position, order, variant,
						Phase.MEASUREMENT, subpair, measured));
			}
			result.add(new SymmetricBlock(block, order, firstMeasured, secondMeasured,
					firstConditioned, secondConditioned, steps));
		}
		Set<Long> allSeeds = new HashSet<>();
		for (SymmetricBlock block : result) {
			for (long seed : List.of(block.firstMeasurementSeed(), block.secondMeasurementSeed(),
					block.firstConditioningSeed(), block.secondConditioningSeed())) {
				if (!allSeeds.add(seed)) throw new IllegalStateException("benchmark schedule seed collision");
			}
		}
		return List.copyOf(result);
	}

	public static int subpairIndex(int blockIndex, int position) {
		if (blockIndex < 0) throw new IllegalArgumentException("Negative benchmark block index");
		requirePosition(position);
		return Math.addExact(Math.multiplyExact(blockIndex, 2), position / 2);
	}

	private static long seed(
			long experimentSeed, long workloadKey, int comparisonOrdinal,
			int contextIndex, int subpairIndex
	) {
		long value = experimentSeed;
		value ^= (workloadKey + 1L) * WORKLOAD_NAMESPACE;
		value ^= ((long) comparisonOrdinal + 1L) * COMPARISON_NAMESPACE;
		value ^= ((long) contextIndex + 1L) * CONTEXT_NAMESPACE;
		value ^= ((long) subpairIndex + 1L) * SUBPAIR_NAMESPACE;
		return mix64(value);
	}

	static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	private static void requirePosition(int position) {
		if (position < 0 || position > 3) {
			throw new IllegalArgumentException("benchmark position must be in [0,3]");
		}
	}
}
