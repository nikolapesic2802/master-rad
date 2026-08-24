package xyz.marsavic.gfxlab.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-frame benchmarking helper.
 * Each row corresponds to one logged frame; the row index is the frame id.
 */
public class BenchmarkRecorder implements AutoCloseable {
	private static final String CSV_HEADER =
			"count,totalMs,backendMs,kernelMs,maximumPhysicalKernelMs,copyMs,uploadMs,width,height,cameraSamples,pathSegments,secondaryPathSegments,"
					+ "primitiveTests,aabbTests,rootAabbTests,internalNodeVisits,leafNodeVisits,"
					+ "homogeneousLeafNodeVisits,mixedLeafNodeVisits,"
					+ "sphereTests,boxTests,planeTests,affineSphereTests,affineBoxTests,"
					+ "traversalWork,primitiveTestsPerPathSegment,primitiveTestsPerCameraSample,aabbTestsPerPathSegment,"
					+ "aabbTestsPerCameraSample,rootAabbTestsPerPathSegment,traversalWorkPerPathSegment,traversalWorkPerCameraSample,"
					+ "stackOverflows,maxStackSize\n";

	public record RunMetadata(
			Long sceneBytes,
			Long bvhBytes,
			Long hostBytes,
			Long estimatedDeviceBytes,
			Integer primitiveCount,
			Integer sphereCount,
			Integer boxCount,
			Integer planeCount,
			Integer affineSphereCount,
			Integer affineBoxCount,
			Integer materialCount,
			Integer accelerationNodeCount,
			Integer accelerationReferenceCount,
			Integer leafCount,
			Integer maxTreeDepth,
			Integer leafSize,
			Integer minLeafOccupancy,
			Integer maxLeafOccupancy,
			Double meanLeafOccupancy,
			Double generalizedSahCost,
			Double uniformSahCost,
			Double weightedSahCost,
			String accelerationConfig
	) { }

	public record FrameMetrics(
			long count,
			long totalNanos,
			Long kernelNanos,
			Long maximumPhysicalKernelNanos,
			Long copyNanos,
			Long uploadNanos,
			int width,
			int height,
			Long cameraSamples,
			Long pathSegments,
			Long primitiveTests,
			Long aabbTests,
			Long sphereTests,
			Long boxTests,
			Long planeTests,
			Long affineSphereTests,
			Long affineBoxTests,
			Long rootAabbTests,
			Double traversalWork,
			Long stackOverflows,
			Long maxStackSize,
			Long internalNodeVisits,
			Long leafNodeVisits,
			Long homogeneousLeafNodeVisits,
			Long mixedLeafNodeVisits,
			Long backendNanos
	) {
		public FrameMetrics {
			if (kernelNanos != null && maximumPhysicalKernelNanos != null
					&& (kernelNanos < 0L || maximumPhysicalKernelNanos < 0L
					|| maximumPhysicalKernelNanos > kernelNanos)) {
				throw new IllegalArgumentException(
						"Maximum physical-kernel time must be bounded by total kernel time");
			}
			if (leafNodeVisits != null && homogeneousLeafNodeVisits != null
					&& mixedLeafNodeVisits != null
					&& leafNodeVisits.longValue()
					!= homogeneousLeafNodeVisits.longValue() + mixedLeafNodeVisits.longValue()) {
				throw new IllegalArgumentException(
						"Leaf-kind visits must partition total leaf visits");
			}
		}

		public FrameMetrics(long count, long totalNanos, Long kernelNanos, Long copyNanos, int width, int height,
		                    Long cameraSamples, Long pathSegments, Long primitiveTests, Long aabbTests) {
			this(count, totalNanos, kernelNanos, null, copyNanos, null, width, height,
					cameraSamples, pathSegments, primitiveTests, aabbTests,
					null, null, null, null, null, null, null,
					null, null, null, null, null, null, null);
		}
	}

	private record FrameSample(
			long count,
			double totalMs,
			Double backendMs,
			Double kernelMs,
			Double maximumPhysicalKernelMs,
			Double copyMs,
			Double uploadMs,
			int width,
			int height,
			Long cameraSamples,
			Long pathSegments,
			Long primitiveTests,
			Long aabbTests,
			Long sphereTests,
			Long boxTests,
			Long planeTests,
			Long affineSphereTests,
			Long affineBoxTests,
			Long rootAabbTests,
			Double traversalWork,
			Long stackOverflows,
			Long maxStackSize,
			Long internalNodeVisits,
			Long leafNodeVisits,
			Long homogeneousLeafNodeVisits,
			Long mixedLeafNodeVisits
	) {
		String toCsv() {
			Long secondaryPathSegments = cameraSamples == null || pathSegments == null
					? null : Math.max(0L, pathSegments - cameraSamples);
			Double primitiveTestsPerPathSegment = ratio(primitiveTests, pathSegments);
			Double primitiveTestsPerCameraSample = ratio(primitiveTests, cameraSamples);
			Double aabbTestsPerPathSegment = ratio(aabbTests, pathSegments);
			Double aabbTestsPerCameraSample = ratio(aabbTests, cameraSamples);
			Double rootTestsPerPathSegment = ratio(rootAabbTests, pathSegments);
			Double traversalWorkPerPathSegment = ratio(traversalWork, pathSegments);
			Double traversalWorkPerCameraSample = ratio(traversalWork, cameraSamples);
			return String.join(",",
					Long.toString(count),
					String.format(Locale.ROOT, "%.4f", totalMs),
					format(backendMs),
					format(kernelMs),
					format(maximumPhysicalKernelMs),
					format(copyMs),
					format(uploadMs),
					Integer.toString(width),
					Integer.toString(height),
					format(cameraSamples),
					format(pathSegments),
					format(secondaryPathSegments),
					format(primitiveTests),
					format(aabbTests),
					format(rootAabbTests),
					format(internalNodeVisits),
					format(leafNodeVisits),
					format(homogeneousLeafNodeVisits),
					format(mixedLeafNodeVisits),
					format(sphereTests),
					format(boxTests),
					format(planeTests),
					format(affineSphereTests),
					format(affineBoxTests),
					format(traversalWork),
					format(primitiveTestsPerPathSegment),
					format(primitiveTestsPerCameraSample),
					format(aabbTestsPerPathSegment),
					format(aabbTestsPerCameraSample),
					format(rootTestsPerPathSegment),
					format(traversalWorkPerPathSegment),
					format(traversalWorkPerCameraSample),
					format(stackOverflows),
					format(maxStackSize)) + System.lineSeparator();
		}

		private static Double ratio(Long numerator, Long denominator) {
			return numerator == null || denominator == null || denominator == 0 ? null : numerator / (double) denominator;
		}

		private static Double ratio(Double numerator, Long denominator) {
			return numerator == null || denominator == null || denominator == 0 ? null : numerator / denominator;
		}

		private static String format(Object value) {
			if (value == null) return "";
			return value instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : value.toString();
		}
	}

	private final Path outputFile;
	private final int flushEvery;
	private final String cpuInfo;
	private final String gpuInfo;
	private final String version;
	private final String sceneName;
	private final String configuration;
	private final List<FrameSample> buffer = new ArrayList<>();
	private boolean headerWritten;
	private RunMetadata runMetadata;
	private String compiledPtxSha256;

	public BenchmarkRecorder(Path outputFile, int flushEvery, String cpuInfo, String gpuInfo, String version, String sceneName,
	                         String configuration) {
		this.outputFile = outputFile;
		this.flushEvery = Math.max(1, flushEvery);
		this.cpuInfo = cpuInfo == null ? "" : cpuInfo;
		this.gpuInfo = gpuInfo == null ? "" : gpuInfo;
		this.version = version == null ? "" : version;
		this.sceneName = sceneName == null ? "" : sceneName;
		this.configuration = configuration == null ? "" : configuration;
	}

	public synchronized void record(long countPerFrame, int width, int height, long totalNanos, Long kernelNanos, Long copyNanos) {
		record(new FrameMetrics(countPerFrame, totalNanos, kernelNanos, copyNanos, width, height,
				null, null, null, null));
	}

	public synchronized void record(FrameMetrics metrics) {
		FrameSample sample = new FrameSample(metrics.count(), nanosToMs(metrics.totalNanos()),
				nanosToMs(metrics.backendNanos()),
				nanosToMs(metrics.kernelNanos()),
				nanosToMs(metrics.maximumPhysicalKernelNanos()),
				nanosToMs(metrics.copyNanos()), nanosToMs(metrics.uploadNanos()),
				metrics.width(), metrics.height(),
				metrics.cameraSamples(), metrics.pathSegments(), metrics.primitiveTests(), metrics.aabbTests(),
				metrics.sphereTests(), metrics.boxTests(), metrics.planeTests(),
				metrics.affineSphereTests(), metrics.affineBoxTests(), metrics.rootAabbTests(),
				metrics.traversalWork(), metrics.stackOverflows(), metrics.maxStackSize(),
				metrics.internalNodeVisits(), metrics.leafNodeVisits(),
				metrics.homogeneousLeafNodeVisits(), metrics.mixedLeafNodeVisits());
		buffer.add(sample);

		printSample(sample);
		if (outputFile != null && buffer.size() >= flushEvery) {
			flush();
		}
	}

	public synchronized void setRunMetadata(RunMetadata runMetadata) {
		if (headerWritten) {
			throw new IllegalStateException("Run metadata must be set before the first benchmark row is flushed.");
		}
		this.runMetadata = runMetadata;
	}

	public synchronized void setCompiledPtxSha256(String compiledPtxSha256) {
		if (headerWritten) {
			throw new IllegalStateException(
					"Compiled PTX hash must be set before the first benchmark row is flushed.");
		}
		if (compiledPtxSha256 == null
				|| !compiledPtxSha256.matches("[0-9a-fA-F]{64}")) {
			throw new IllegalArgumentException(
					"Compiled PTX hash must be a 64-digit SHA-256 value.");
		}
		this.compiledPtxSha256 = compiledPtxSha256.toLowerCase(Locale.ROOT);
	}

	private void printSample(FrameSample sample) {
		String backendPart = sample.backendMs == null ? "" : String.format(Locale.ROOT, ", backend=%.3f ms", sample.backendMs);
		String kernelPart = sample.kernelMs == null ? "" : String.format(Locale.ROOT, ", kernel=%.3f ms", sample.kernelMs);
		String maximumKernelPart = sample.maximumPhysicalKernelMs == null ? ""
				: String.format(Locale.ROOT, ", max-kernel=%.3f ms", sample.maximumPhysicalKernelMs);
		String copyPart = sample.copyMs == null ? "" : String.format(Locale.ROOT, ", copy=%.3f ms", sample.copyMs);
		String uploadPart = sample.uploadMs == null ? "" : String.format(Locale.ROOT, ", upload=%.3f ms", sample.uploadMs);
		String primitivePart = sample.primitiveTests == null || sample.pathSegments == null || sample.pathSegments == 0 ? ""
				: String.format(Locale.ROOT, ", prim/segment=%.2f", sample.primitiveTests / (double) sample.pathSegments);
		String nodePart = sample.aabbTests == null || sample.pathSegments == null || sample.pathSegments == 0 ? ""
				: String.format(Locale.ROOT, ", aabb/segment=%.2f", sample.aabbTests / (double) sample.pathSegments);
		System.out.printf(Locale.ROOT,
				"[BENCH]: count=%d total=%.3f ms%s%s%s%s%s%s%s (%dx%d)%n",
				sample.count,
				sample.totalMs,
				backendPart,
				kernelPart,
				maximumKernelPart,
				copyPart,
				uploadPart,
				primitivePart,
				nodePart,
				sample.width,
				sample.height);
	}

	private static Double nanosToMs(Long nanos) {
		return nanos == null ? null : nanos / 1_000_000.0;
	}

	public synchronized void flush() {
		if (outputFile == null || buffer.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(outputFile.getParent());
			if (!headerWritten) {
				String header = buildHeader();
				Files.writeString(outputFile, header, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				headerWritten = true;
			}
			StringBuilder sb = new StringBuilder();
			for (FrameSample sample : buffer) {
				sb.append(sample.toCsv());
			}
			Files.writeString(outputFile, sb.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			buffer.clear();
		} catch (IOException ex) {
			System.err.println("Failed to write benchmark data: " + ex.getMessage());
		}
	}

	private String buildHeader() {
		StringBuilder header = new StringBuilder("# cpu=").append(cpuInfo)
				.append("; gpu=").append(gpuInfo)
				.append("; version=").append(version)
				.append("; scene=").append(sceneName)
				.append("; config=").append(configuration)
				.append("; metricSchemaVersion=5");
		if (runMetadata != null) {
			appendMetadata(header, "sceneBytes", runMetadata.sceneBytes());
			appendMetadata(header, "bvhBytes", runMetadata.bvhBytes());
			appendMetadata(header, "hostBytes", runMetadata.hostBytes());
			appendMetadata(header, "estimatedDeviceBytes", runMetadata.estimatedDeviceBytes());
			appendMetadata(header, "primitiveCount", runMetadata.primitiveCount());
			appendMetadata(header, "sphereCount", runMetadata.sphereCount());
			appendMetadata(header, "boxCount", runMetadata.boxCount());
			appendMetadata(header, "planeCount", runMetadata.planeCount());
			appendMetadata(header, "affineSphereCount", runMetadata.affineSphereCount());
			appendMetadata(header, "affineBoxCount", runMetadata.affineBoxCount());
			appendMetadata(header, "materialCount", runMetadata.materialCount());
			appendMetadata(header, "accelerationNodeCount", runMetadata.accelerationNodeCount());
			appendMetadata(header, "accelerationReferenceCount", runMetadata.accelerationReferenceCount());
			appendMetadata(header, "leafCount", runMetadata.leafCount());
			appendMetadata(header, "maxTreeDepth", runMetadata.maxTreeDepth());
			appendMetadata(header, "leafSize", runMetadata.leafSize());
			appendMetadata(header, "minLeafOccupancy", runMetadata.minLeafOccupancy());
			appendMetadata(header, "maxLeafOccupancy", runMetadata.maxLeafOccupancy());
			appendMetadata(header, "meanLeafOccupancy", runMetadata.meanLeafOccupancy());
			appendMetadata(header, "generalizedSahCost", runMetadata.generalizedSahCost());
			appendMetadata(header, "uniformSahCost", runMetadata.uniformSahCost());
			appendMetadata(header, "weightedSahCost", runMetadata.weightedSahCost());
			appendMetadata(header, "accelerationConfig", runMetadata.accelerationConfig());
		}
		appendMetadata(header, "compiledPtxSha256", compiledPtxSha256);
		return header.append('\n').append(CSV_HEADER).toString();
	}

	private static void appendMetadata(StringBuilder header, String key, Object value) {
		if (value != null) header.append("; ").append(key).append('=').append(value);
	}

	@Override
	public void close() {
		flush();
	}
}
