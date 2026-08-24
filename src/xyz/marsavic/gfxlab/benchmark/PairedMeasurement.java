package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes a benchmark same-ray block and validates raw rows before any reduction. */
public final class PairedMeasurement {
	public record Limits(int maximumPathDepth, long maximumPhysicalKernelNanosExclusive,
	                     int contextCount, int physicalLaunchesPerFrame) {
		public Limits {
			if (maximumPathDepth < 0 || maximumPhysicalKernelNanosExclusive <= 0L
					|| contextCount < 1 || contextCount > 64
					|| physicalLaunchesPerFrame < 1) {
				throw new IllegalArgumentException("Invalid benchmark timing limits");
			}
		}
	}

	public record Candidate(String id, String topologySha256, GpuScene scene) {
		public Candidate {
			requireCsvId(id);
			if (!isSha256(topologySha256) || scene == null) {
				throw new IllegalArgumentException("Incomplete benchmark timing candidate");
			}
		}
	}

	public record RawRow(
			int context, int blockIndex, int position, TimingSchedule.Order order,
			TimingSchedule.Variant variant, String endpointId, String topologySha256,
			int maximumPathDepth, long measurementSeed, long conditioningSeed,
			int conditioningFrameIndex, long conditioningUploadNanos,
			long conditioningMaximumPhysicalKernelNanos,
			long conditioningAggregatePhysicalKernelNanos, long conditioningTotalNanos,
			int measurementFrameIndex, long kernelNanos,
			long maximumPhysicalKernelNanos, long uploadNanos, long copyNanos, long totalNanos
	) {
		public RawRow {
			requireCsvId(endpointId);
			if (!isSha256(topologySha256)) {
				throw new IllegalArgumentException("Invalid benchmark timing topology");
			}
			int frame = TimingSchedule.subpairIndex(blockIndex, position);
			long minimumConditioning;
			try {
				minimumConditioning = Math.addExact(
						conditioningUploadNanos, conditioningAggregatePhysicalKernelNanos);
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException("benchmark conditioning accounting overflow", overflow);
			}
			if (context < 1 || blockIndex < 0 || position < 0 || position > 3
					|| order == null || variant == null || variant != order.variantAt(position)
					|| maximumPathDepth < 0 || measurementSeed == conditioningSeed
					|| conditioningFrameIndex != -frame - 1 || measurementFrameIndex != frame
					|| conditioningUploadNanos < 0L
					|| conditioningMaximumPhysicalKernelNanos <= 0L
					|| conditioningAggregatePhysicalKernelNanos
					< conditioningMaximumPhysicalKernelNanos
					|| conditioningTotalNanos < minimumConditioning || kernelNanos <= 0L
					|| maximumPhysicalKernelNanos <= 0L
					|| maximumPhysicalKernelNanos > kernelNanos || uploadNanos != 0L
					|| copyNanos < 0L || totalNanos <= 0L) {
				throw new IllegalArgumentException("Invalid benchmark timing row");
			}
		}
	}

	public record BlockResult(
			int context, int blockIndex, TimingSchedule.Order order,
			long firstMeasurementSeed, long secondMeasurementSeed,
			String referenceId, String candidateId, double ordinaryKernelReductionPercent
	) {
		public BlockResult {
			requireDistinct(referenceId, candidateId);
			if (context < 1 || blockIndex < 0 || order == null
					|| firstMeasurementSeed == secondMeasurementSeed
					|| !Double.isFinite(ordinaryKernelReductionPercent)
					|| ordinaryKernelReductionPercent >= 100.0) {
				throw new IllegalArgumentException("Invalid benchmark block reduction");
			}
		}
	}

	public record Run(List<RawRow> rows, List<BlockResult> blocks) {
		public Run {
			rows = List.copyOf(rows);
			blocks = List.copyOf(blocks);
			if (blocks.isEmpty() || rows.size() != blocks.size() * 4) {
				throw new IllegalArgumentException("benchmark run does not form complete blocks");
			}
		}
	}

	private PairedMeasurement() { }

	public static Run execute(
			GpuRayTracer tracer, float[] pixels, GpuCamera camera, Limits limits,
			Candidate reference, Candidate candidate, int blockCount,
			long experimentSeed, long workloadKey, int comparisonOrdinal, int contextIndex
	) {
		return execute(tracer, pixels, camera, limits, reference,
				limits == null ? -1 : limits.maximumPathDepth(), candidate,
				limits == null ? -1 : limits.maximumPathDepth(), blockCount,
				experimentSeed, workloadKey, comparisonOrdinal, contextIndex);
	}

	/**
	 * Executes the same paired schedule when the two endpoints intentionally use
	 * different maximum path depths.  The limit remains the inclusive protocol
	 * ceiling, while each raw row records the depth of the endpoint that ran.
	 */
	public static Run execute(
			GpuRayTracer tracer, float[] pixels, GpuCamera camera, Limits limits,
			Candidate reference, int referenceMaximumPathDepth,
			Candidate candidate, int candidateMaximumPathDepth, int blockCount,
			long experimentSeed, long workloadKey, int comparisonOrdinal, int contextIndex
	) {
		if (tracer == null || pixels == null || camera == null || limits == null
				|| reference == null || candidate == null || blockCount < 1
				|| referenceMaximumPathDepth < 0 || candidateMaximumPathDepth < 0
				|| referenceMaximumPathDepth > limits.maximumPathDepth()
				|| candidateMaximumPathDepth > limits.maximumPathDepth()
				|| contextIndex < 0 || contextIndex >= limits.contextCount()) {
			throw new IllegalArgumentException("Incomplete benchmark timing request");
		}
		requireDistinct(reference.id(), candidate.id());
		List<TimingSchedule.SymmetricBlock> schedule = TimingSchedule.blocks(
				blockCount, experimentSeed, workloadKey, comparisonOrdinal,
				contextIndex, limits.contextCount());
		List<RawRow> rows = new ArrayList<>(blockCount * 4);
		for (TimingSchedule.SymmetricBlock block : schedule) {
			for (int position = 0; position < 4; position++) {
				TimingSchedule.Step conditioning = block.steps().get(position * 2);
				TimingSchedule.Step measurement = block.steps().get(position * 2 + 1);
				Candidate selected = measurement.variant() == TimingSchedule.Variant.A
						? reference : candidate;
				int selectedMaximumPathDepth = measurement.variant() == TimingSchedule.Variant.A
						? referenceMaximumPathDepth : candidateMaximumPathDepth;
				GpuRayTracer.ConditioningStats conditioned = tracer.conditionSteadyStateSample(
						selected.scene(), camera, selectedMaximumPathDepth,
						conditioning.frameIndex(), conditioning.frameSeed());
				if (conditioned.physicalLaunches() != limits.physicalLaunchesPerFrame()) {
					throw new IllegalStateException("benchmark conditioning physical launch count differs");
				}
				tracer.renderSteadyStateSample(pixels, selected.scene(), camera,
						selectedMaximumPathDepth, measurement.frameIndex(), measurement.frameSeed());
				if (tracer.lastPhysicalKernelLaunchCount() != limits.physicalLaunchesPerFrame()) {
					throw new IllegalStateException("benchmark measurement physical launch count differs");
				}
				GpuRayTracer.FrameStats stats = tracer.lastFrameStats();
				RawRow row = new RawRow(contextIndex + 1, block.blockIndex(), position,
						block.order(), measurement.variant(), selected.id(),
						selected.topologySha256(), selectedMaximumPathDepth,
						measurement.frameSeed(), conditioning.frameSeed(),
						conditioning.frameIndex(), conditioned.uploadNanos(),
						conditioned.maximumPhysicalKernelNanos(),
						conditioned.aggregatePhysicalKernelNanos(), conditioned.totalNanos(),
						measurement.frameIndex(), stats.kernelNanos(),
						stats.maximumPhysicalKernelNanos(), stats.uploadNanos(),
						stats.copyNanos(), stats.totalNanos());
				requirePhysicalLimit(row, limits);
				rows.add(row);
			}
		}
		return validateAndReduce(rows, limits, reference.id(), reference.topologySha256(),
				referenceMaximumPathDepth, candidate.id(), candidate.topologySha256(),
				candidateMaximumPathDepth, blockCount, experimentSeed, workloadKey,
				comparisonOrdinal, contextIndex);
	}

	public static Run validateAndReduce(
			List<RawRow> rawRows, Limits limits,
			String referenceId, String referenceTopologySha256,
			int referenceMaximumPathDepth,
			String candidateId, String candidateTopologySha256,
			int candidateMaximumPathDepth, int blockCount,
			long experimentSeed, long workloadKey, int comparisonOrdinal, int contextIndex
	) {
		requireDistinct(referenceId, candidateId);
		if (!isSha256(referenceTopologySha256) || !isSha256(candidateTopologySha256)
				|| rawRows == null || limits == null || rawRows.size() != blockCount * 4
				|| referenceMaximumPathDepth < 0 || candidateMaximumPathDepth < 0
				|| referenceMaximumPathDepth > limits.maximumPathDepth()
				|| candidateMaximumPathDepth > limits.maximumPathDepth()
				|| contextIndex < 0 || contextIndex >= limits.contextCount()) {
			throw new IllegalArgumentException("Invalid benchmark reduction request");
		}
		List<RawRow> rows = List.copyOf(rawRows);
		List<TimingSchedule.SymmetricBlock> expected = TimingSchedule.blocks(
				blockCount, experimentSeed, workloadKey, comparisonOrdinal,
				contextIndex, limits.contextCount());
		Set<Long> seeds = new HashSet<>();
		List<BlockResult> blocks = new ArrayList<>(blockCount);
		for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
			List<RawRow> blockRows = rows.subList(blockIndex * 4, blockIndex * 4 + 4);
			TimingSchedule.SymmetricBlock block = expected.get(blockIndex);
			List<RawRow> references = new ArrayList<>(2);
			List<RawRow> candidates = new ArrayList<>(2);
			for (int position = 0; position < 4; position++) {
				RawRow row = blockRows.get(position);
				TimingSchedule.Variant variant = block.order().variantAt(position);
				String endpoint = variant == TimingSchedule.Variant.A
						? referenceId : candidateId;
				String topology = variant == TimingSchedule.Variant.A
						? referenceTopologySha256 : candidateTopologySha256;
				int maximumPathDepth = variant == TimingSchedule.Variant.A
						? referenceMaximumPathDepth : candidateMaximumPathDepth;
				if (row.context() != contextIndex + 1 || row.blockIndex() != blockIndex
						|| row.position() != position || row.order() != block.order()
						|| row.variant() != variant || !row.endpointId().equals(endpoint)
						|| !row.topologySha256().equals(topology)
						|| row.maximumPathDepth() != maximumPathDepth
						|| row.measurementSeed() != block.measurementSeedAt(position)
						|| row.conditioningSeed() != block.conditioningSeedAt(position)) {
					throw new IllegalStateException("benchmark raw timing coordinate differs");
				}
				requirePhysicalLimit(row, limits);
				(variant == TimingSchedule.Variant.A ? references : candidates).add(row);
			}
			if (references.size() != 2 || candidates.size() != 2
					|| blockRows.get(0).measurementSeed() != blockRows.get(1).measurementSeed()
					|| blockRows.get(2).measurementSeed() != blockRows.get(3).measurementSeed()
					|| !seeds.add(block.firstMeasurementSeed())
					|| !seeds.add(block.secondMeasurementSeed())
					|| !seeds.add(block.firstConditioningSeed())
					|| !seeds.add(block.secondConditioningSeed())) {
				throw new IllegalStateException("benchmark same-ray block differs");
			}
			blocks.add(new BlockResult(contextIndex + 1, blockIndex, block.order(),
					block.firstMeasurementSeed(), block.secondMeasurementSeed(),
					referenceId, candidateId, ordinaryKernelReductionPercent(
							references.get(0).kernelNanos(), references.get(1).kernelNanos(),
							candidates.get(0).kernelNanos(), candidates.get(1).kernelNanos())));
		}
		return new Run(rows, blocks);
	}

	/** Positive means that B used less kernel time than A. */
	public static double ordinaryKernelReductionPercent(
			long firstReferenceNanos, long secondReferenceNanos,
			long firstCandidateNanos, long secondCandidateNanos
	) {
		if (firstReferenceNanos <= 0L || secondReferenceNanos <= 0L
				|| firstCandidateNanos <= 0L || secondCandidateNanos <= 0L) {
			throw new IllegalArgumentException("benchmark kernel times must be positive");
		}
		double firstRatio = (double) firstCandidateNanos / firstReferenceNanos;
		double secondRatio = (double) secondCandidateNanos / secondReferenceNanos;
		return 100.0 * (1.0 - Math.sqrt(firstRatio * secondRatio));
	}

	public static double ordinaryWorkReductionPercent(double referenceWork, double candidateWork) {
		if (!(referenceWork > 0.0) || !(candidateWork >= 0.0)
				|| !Double.isFinite(referenceWork) || !Double.isFinite(candidateWork)) {
			throw new IllegalArgumentException("Invalid benchmark modeled work values");
		}
		return 100.0 * (1.0 - candidateWork / referenceWork);
	}

	private static void requirePhysicalLimit(RawRow row, Limits limits) {
		long maximumConditioningAggregate;
		try {
			maximumConditioningAggregate = Math.multiplyExact(
					(long) limits.physicalLaunchesPerFrame(),
					row.conditioningMaximumPhysicalKernelNanos());
		} catch (ArithmeticException overflow) {
			throw new IllegalStateException("benchmark conditioning launch accounting overflow", overflow);
		}
		if (row.conditioningMaximumPhysicalKernelNanos()
				>= limits.maximumPhysicalKernelNanosExclusive()
				|| row.maximumPhysicalKernelNanos()
				>= limits.maximumPhysicalKernelNanosExclusive()
				|| row.conditioningAggregatePhysicalKernelNanos()
				> maximumConditioningAggregate) {
			throw new IllegalStateException("benchmark physical kernel or launch accounting differs");
		}
	}

	private static void requireDistinct(String referenceId, String candidateId) {
		requireCsvId(referenceId);
		requireCsvId(candidateId);
		if (referenceId.equals(candidateId)) {
			throw new IllegalArgumentException("benchmark endpoints must differ");
		}
	}

	private static void requireCsvId(String value) {
		if (value == null || value.isBlank() || value.indexOf(',') >= 0
				|| value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("Invalid benchmark CSV-safe identifier");
		}
	}

	private static boolean isSha256(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}
}
