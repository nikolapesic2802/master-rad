package xyz.marsavic.gfxlab.gpu;

import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.*;
import jcuda.nvrtc.JNvrtc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static jcuda.driver.JCudaDriver.*;

/**
 * Measures relative CUDA intersection cost for the primitive types used by the
 * thesis. Each process owns one CUDA context and writes one context result.
 * Setup and operation launches are paired, and every operation is paired with
 * a local sphere measurement before ratios are aggregated across ray profiles.
 */
public final class PrimitiveCostBenchmark {
	private static final int BLOCKS = 256;
	private static final int THREADS = 256;
	private static final int ITERATIONS = 2048;
	private static final int OPERATION_COPIES = 8;
	private static final int SUBLAUNCHES = 32;
	private static final double MAX_SUBLAUNCH_MS = 250.0;
	private static final int WARMUPS = 4;
	private static final int REPEATS = 12;
	private static final int PROFILES = 3;
	private static final int WORKLOAD_RECORDS = 4096;
	private static final int FLOATS_PER_RAY = 6;
	private static final int FLOATS_PER_SPHERE = 5;
	private static final int FLOATS_PER_BOX = 7;
	private static final int FLOATS_PER_AFFINE = 22;
	private static final int FLOATS_PER_PLANE = 26;
	private static final int FLOATS_PER_NODE_AABB = 6;
	private static final int FLOATS_PER_NODE_PAIR = 12;
	private static final int FLOATS_PER_MATERIAL = 13;
	private static final int MATERIAL_RECORDS = WORKLOAD_RECORDS;
	private static final String[] LABELS = {
			"setup", "sphere", "box", "affineSphere", "affineBox", "nodeAabb",
			"interiorTraversal", "plane"
	};

	private PrimitiveCostBenchmark() { }

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			throw new IllegalArgumentException(
					"Usage: PrimitiveCostBenchmark <output.json> <context-index> <run-manifest-sha256>");
		}
		Path output = Path.of(args[0]).toAbsolutePath();
		int contextIndex = Integer.parseInt(args[1]);
		String runManifestSha256 = args[2].toLowerCase(Locale.ROOT);
		if (contextIndex < 1 || contextIndex > 5) {
			throw new IllegalArgumentException("Context index must be in 1..5");
		}
		if (!runManifestSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Run-manifest SHA-256 must contain 64 hexadecimal digits");
		}
		if (Files.exists(output)) {
			throw new IllegalStateException(
					"Refusing to overwrite primitive-cost evidence: " + output);
		}
		GpuRayTracer.ensureNativeLibrariesPresent();
		JCudaDriver.setExceptionsEnabled(true);
		JNvrtc.setExceptionsEnabled(true);
		cuInit(0);
		CUdevice device = new CUdevice();
		cuDeviceGet(device, 0);
		String deviceName = deviceName(device);
		String computeCapability = computeCapability(device);
		int[] nvrtcMajor = new int[1];
		int[] nvrtcMinor = new int[1];
		JNvrtc.nvrtcVersion(nvrtcMajor, nvrtcMinor);
		String nvrtcVersion = nvrtcMajor[0] + "." + nvrtcMinor[0];

		CUcontext context = new CUcontext();
		CUmodule module = new CUmodule();
		CUdeviceptr outputBuffer = new CUdeviceptr();
		CUdeviceptr workloadBuffer = new CUdeviceptr();
		boolean contextCreated = false;
		boolean moduleLoaded = false;
		boolean outputAllocated = false;
		boolean workloadAllocated = false;
		try {
			cuCtxCreate(context, 0, device);
			contextCreated = true;
			String cudaSource = GpuKernelSources.PATH_TRACER + benchmarkKernel();
			String ptx = GpuRayTracer.compileToPtx(cudaSource, computeCapability);
			cuModuleLoadData(module, (ptx + "\0").getBytes(StandardCharsets.UTF_8));
			moduleLoaded = true;
			CUfunction function = new CUfunction();
			cuModuleGetFunction(function, module, "primitiveCostKernel");
			cuMemAlloc(outputBuffer, (long) BLOCKS * THREADS * Sizeof.FLOAT);
			outputAllocated = true;
			float[] workload = buildWorkload();
			byte[] workloadArtifact = workloadBytes(workload);
			cuMemAlloc(workloadBuffer, (long) workload.length * Sizeof.FLOAT);
			workloadAllocated = true;
			cuMemcpyHtoD(
					workloadBuffer, Pointer.to(workload), (long) workload.length * Sizeof.FLOAT);

			int[] operationOrder = operationOrder(contextIndex);
			Map<String, List<RelativeSample>> pairedRuns = new LinkedHashMap<>();
			for (int profile = 0; profile < PROFILES; profile++) {
				for (int type : operationOrder) {
					for (int warmup = 0; warmup < WARMUPS; warmup++) {
						runRelativeSample(
								function, outputBuffer, workloadBuffer, type, profile, warmup);
					}
					List<RelativeSample> samples = new ArrayList<>();
					for (int repeat = 0; repeat < REPEATS; repeat++) {
						samples.add(runRelativeSample(
								function, outputBuffer, workloadBuffer, type, profile, repeat));
					}
					pairedRuns.put(key(profile, type), samples);
				}
			}

			String json = toJson(
					runManifestSha256,
					deviceName, computeCapability, nvrtcVersion,
					GpuRayTracer.lastCompiledPtxSha256(),
					sha256(cudaSource.getBytes(StandardCharsets.UTF_8)), sha256(workloadArtifact),
					workloadArtifact.length,
					contextIndex, operationOrder, pairedRuns);
			if (output.getParent() != null) Files.createDirectories(output.getParent());
			Files.writeString(
					output,
					json,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);
			System.out.println(json);
			System.out.println("Wrote " + output.toAbsolutePath());
		} finally {
			if (workloadAllocated) cuMemFree(workloadBuffer);
			if (outputAllocated) cuMemFree(outputBuffer);
			if (moduleLoaded) cuModuleUnload(module);
			if (contextCreated) cuCtxDestroy(context);
		}
	}

	private static double runChunk(
			CUfunction function,
			CUdeviceptr output,
			CUdeviceptr workload,
			int type,
			int profile,
			int iterationOffset,
			int iterations
	) {
		Pointer parameters = Pointer.to(
				Pointer.to(new int[]{type}),
				Pointer.to(new int[]{profile}),
				Pointer.to(new int[]{iterationOffset}),
				Pointer.to(new int[]{iterations}),
				Pointer.to(new int[]{OPERATION_COPIES}),
				Pointer.to(workload),
				Pointer.to(new int[]{WORKLOAD_RECORDS}),
				Pointer.to(output));
		CUevent start = new CUevent();
		CUevent end = new CUevent();
		cuEventCreate(start, 0);
		cuEventCreate(end, 0);
		try {
			cuEventRecord(start, null);
			cuLaunchKernel(function, BLOCKS, 1, 1, THREADS, 1, 1, 0, null, parameters, null);
			cuEventRecord(end, null);
			cuEventSynchronize(end);
			float[] elapsedMs = new float[1];
			cuEventElapsedTime(elapsedMs, start, end);
			if (!(elapsedMs[0] >= 0.0f) || elapsedMs[0] > MAX_SUBLAUNCH_MS) {
				throw new IllegalStateException(String.format(
						Locale.ROOT,
						"Calibration sublaunch exceeded the %.3f ms safety limit: "
								+ "type=%d profile=%d iterationOffset=%d iterations=%d elapsed=%.3f ms",
						MAX_SUBLAUNCH_MS, type, profile, iterationOffset, iterations, elapsedMs[0]));
			}
			return elapsedMs[0];
		} finally {
			cuEventDestroy(start);
			cuEventDestroy(end);
		}
	}

	private static PairSample runPair(
			CUfunction function,
			CUdeviceptr output,
			CUdeviceptr workload,
			int type,
			int profile,
			int repeat
	) {
		// Alternating the first launch limits bias from short-term clock drift.
		boolean startsWithSetup = (repeat & 1) == 0;
		int iterationsPerSublaunch = ITERATIONS / SUBLAUNCHES;
		double setupElapsedMs = 0.0;
		double operationElapsedMs = 0.0;
		for (int sublaunch = 0; sublaunch < SUBLAUNCHES; sublaunch++) {
			int iterationOffset = sublaunch * iterationsPerSublaunch;
			boolean setupFirst = ((sublaunch & 1) == 0) == startsWithSetup;
			if (setupFirst) {
				setupElapsedMs += runChunk(
						function, output, workload, 0, profile,
						iterationOffset, iterationsPerSublaunch);
				operationElapsedMs += runChunk(
						function, output, workload, type, profile,
						iterationOffset, iterationsPerSublaunch);
			} else {
				operationElapsedMs += runChunk(
						function, output, workload, type, profile,
						iterationOffset, iterationsPerSublaunch);
				setupElapsedMs += runChunk(
						function, output, workload, 0, profile,
						iterationOffset, iterationsPerSublaunch);
			}
		}
		double tests = (double) BLOCKS * THREADS * ITERATIONS * OPERATION_COPIES;
		double setup = setupElapsedMs * 1_000_000.0 / tests;
		double operation = operationElapsedMs * 1_000_000.0 / tests;
		return new PairSample(
				repeat,
				startsWithSetup ? "setup_operation" : "operation_setup",
				setup,
				operation,
				operation - setup);
	}

	private static RelativeSample runRelativeSample(
			CUfunction function,
			CUdeviceptr output,
			CUdeviceptr workload,
			int type,
			int profile,
			int repeat
	) {
		boolean operationFirst = (repeat & 1) == 0;
		PairSample operation;
		PairSample sphere;
		if (operationFirst) {
			operation = runPair(function, output, workload, type, profile, repeat);
			sphere = runPair(function, output, workload, 1, profile, repeat + 1);
		} else {
			sphere = runPair(function, output, workload, 1, profile, repeat + 1);
			operation = runPair(function, output, workload, type, profile, repeat);
		}
		return new RelativeSample(
				repeat, operationFirst ? "operation_sphere" : "sphere_operation",
				operation, sphere);
	}

	private static int[] operationOrder(int contextIndex) {
		// A different rotation and direction is used by every measured context.
		int operationCount = LABELS.length - 1;
		int[] result = new int[operationCount];
		int offset = Math.floorMod(contextIndex - 1, operationCount);
		boolean reverse = (contextIndex & 1) == 0;
		for (int position = 0; position < operationCount; position++) {
			int step = reverse ? -position : position;
			result[position] = 1 + Math.floorMod(offset + step, operationCount);
		}
		return result;
	}

	private static double median(List<Double> values) {
		List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
		if (sorted.isEmpty()) throw new IllegalArgumentException("Cannot take the median of no values");
		int middle = sorted.size() / 2;
		return (sorted.size() & 1) == 0
				? 0.5 * (sorted.get(middle - 1) + sorted.get(middle))
				: sorted.get(middle);
	}

	private static String toJson(
			String runManifestSha256,
			String device,
			String capability,
			String nvrtcVersion,
			String ptxSha256,
			String cudaSourceSha256,
			String workloadSha256,
			int workloadBytes,
			int contextIndex,
			int[] operationOrder,
			Map<String, List<RelativeSample>> pairedRuns
	) {
		StringBuilder out = new StringBuilder();
		out.append("{\n  \"schemaVersion\": 1,\n")
				.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n")
				.append("  \"runManifestSha256\": \"").append(runManifestSha256).append("\",\n")
				.append("  \"gpu\": {\"name\": \"").append(escape(device)).append("\", \"computeCapability\": \"")
				.append(capability).append("\"},\n")
				.append("  \"nvrtcVersion\": \"").append(escape(nvrtcVersion)).append("\",\n")
				.append("  \"compiledPtxSha256\": \"").append(escape(ptxSha256)).append("\",\n")
				.append("  \"compiledCudaSourceSha256\": \"").append(cudaSourceSha256).append("\",\n")
				.append("  \"workloadSha256\": \"").append(workloadSha256).append("\",\n")
				.append("  \"workloadEncoding\": \"big-endian int32 length then IEEE-754 float32 raw bits\",\n")
				.append("  \"workloadBytes\": ").append(workloadBytes).append(",\n")
				.append("  \"resultObservability\": \"noinline operation wrapper and full HitInfo consumer\",\n")
				.append("  \"boxAndNodeAabbBoundsMatched\": true,\n")
				.append("  \"nodeAabbRecordFloats\": ").append(FLOATS_PER_NODE_AABB).append(",\n")
				.append("  \"interiorTraversalRecordFloats\": ").append(FLOATS_PER_NODE_PAIR).append(",\n")
				.append("  \"materialRecords\": ").append(MATERIAL_RECORDS).append(",\n")
				.append("  \"contextIndex\": ").append(contextIndex).append(",\n")
				.append("  \"observationNanosSemantics\": \"sum of immediately synchronized per-sublaunch CUDA-event intervals; excludes host launch and synchronization gaps\",\n")
				.append("  \"blocks\": ").append(BLOCKS).append(",\n")
				.append("  \"threadsPerBlock\": ").append(THREADS).append(",\n")
				.append("  \"iterationsPerThread\": ").append(ITERATIONS).append(",\n")
				.append("  \"operationCopiesPerIteration\": ").append(OPERATION_COPIES).append(",\n")
				.append("  \"sublaunchesPerObservation\": ").append(SUBLAUNCHES).append(",\n")
				.append("  \"iterationsPerSublaunch\": ").append(ITERATIONS / SUBLAUNCHES).append(",\n")
				.append("  \"operationBodiesPerSublaunch\": ")
				.append((long) BLOCKS * THREADS * (ITERATIONS / SUBLAUNCHES) * OPERATION_COPIES)
				.append(",\n")
				.append("  \"operationBodiesPerObservation\": ")
				.append((long) BLOCKS * THREADS * ITERATIONS * OPERATION_COPIES).append(",\n")
				.append("  \"maximumAllowedSublaunchMs\": ").append(format(MAX_SUBLAUNCH_MS)).append(",\n")
				.append("  \"sublaunchOrder\": \"alternating-within-pair\",\n")
				.append("  \"pairOrderMeaning\": \"first-sublaunch-in-alternating-pair\",\n")
				.append("  \"globalWorkloadRecords\": ").append(WORKLOAD_RECORDS).append(",\n")
				.append("  \"raysPreNormalized\": true,\n")
				.append("  \"primitiveRecordsFromGlobalMemory\": true,\n")
				.append("  \"localSphereControlPerOperation\": true,\n")
				.append("  \"warmupPairsPerOperation\": ").append(WARMUPS).append(",\n")
				.append("  \"measuredPairsPerOperation\": ").append(REPEATS).append(",\n")
				.append("  \"pairStartOrderCounts\": {\"setup_operation\": ")
				.append(REPEATS / 2)
				.append(", \"operation_setup\": ")
				.append(REPEATS / 2)
				.append("},\n")
				.append("  \"operationOrder\": [");
		for (int index = 0; index < operationOrder.length; index++) {
			if (index > 0) out.append(", ");
			out.append("\"").append(LABELS[operationOrder[index]]).append("\"");
		}
		out.append("],\n")
				.append("  \"profiles\": [\n");
		List<List<Double>> ratiosByType = new ArrayList<>();
		for (int type = 0; type < LABELS.length; type++) ratiosByType.add(new ArrayList<>());
		for (int profile = 0; profile < PROFILES; profile++) {
			List<RelativeSample> sphereSamples = pairedRuns.get(key(profile, 1));
			double sphereNet = requirePositiveFinite(
					sphereNetMedian(sphereSamples),
					"profile " + profile + " local sphere paired-net median");
			out.append("    {\"id\": ").append(profile)
					.append(", \"spherePairedNetMedianNsPerTest\": ")
					.append(format(sphereNet)).append(", \"primitiveCosts\": {");
			for (int type = 1; type < LABELS.length; type++) {
				List<RelativeSample> samples = pairedRuns.get(key(profile, type));
				double net = requirePositiveFinite(
						operationNetMedian(samples),
						"profile " + profile + " " + LABELS[type] + " paired-net median");
				double localSphereNet = requirePositiveFinite(
						sphereNetMedian(samples),
						"profile " + profile + " " + LABELS[type]
								+ " local sphere paired-net median");
				double ratio = type == 1 ? 1.0 : net / localSphereNet;
				ratiosByType.get(type).add(ratio);
				out.append(type == 1 ? "" : ", ").append("\"").append(LABELS[type]).append("\": ")
						.append("{\"medianNetNsPerTest\": ").append(format(net))
						.append(", \"localSphereMedianNetNsPerTest\": ")
						.append(format(localSphereNet))
						.append(", \"relativeWeight\": ").append(format(ratio))
						.append(", \"pairedRuns\": [");
				for (int repeat = 0; repeat < samples.size(); repeat++) {
					RelativeSample sample = samples.get(repeat);
					PairSample pair = sample.operation();
					PairSample localSphere = sample.sphere();
					if (repeat > 0) out.append(", ");
					out.append("{\"repeat\": ").append(sample.repeat())
							.append(", \"outerOrder\": \"").append(sample.outerOrder())
							.append("\", \"operationPairOrder\": \"").append(pair.order())
							.append("\", \"setupNsPerTest\": ").append(format(pair.setupNsPerTest()))
							.append(", \"operationNsPerTest\": ").append(format(pair.operationNsPerTest()))
							.append(", \"netNsPerTest\": ").append(format(pair.netNsPerTest()))
							.append(", \"spherePairOrder\": \"").append(localSphere.order())
							.append("\", \"sphereSetupNsPerTest\": ")
							.append(format(localSphere.setupNsPerTest()))
							.append(", \"sphereOperationNsPerTest\": ")
							.append(format(localSphere.operationNsPerTest()))
							.append(", \"sphereNetNsPerTest\": ")
							.append(format(localSphere.netNsPerTest()))
							.append("}");
				}
				out.append("]}");
			}
			out.append("}}")
					.append(profile + 1 == PROFILES ? "\n" : ",\n");
		}
		out.append("  ],\n  \"recommendedWeights\": {");
		for (int type = 1; type < LABELS.length; type++) {
			out.append(type == 1 ? "" : ", ").append("\"").append(LABELS[type]).append("\": ")
					.append(format(median(ratiosByType.get(type))));
		}
		return out.append("}\n}\n").toString();
	}

	private static double operationNetMedian(List<RelativeSample> samples) {
		return median(samples.stream().map(sample -> sample.operation().netNsPerTest()).toList());
	}

	private static double sphereNetMedian(List<RelativeSample> samples) {
		return median(samples.stream().map(sample -> sample.sphere().netNsPerTest()).toList());
	}

	private static double requirePositiveFinite(double value, String label) {
		if (!Double.isFinite(value) || value <= 0.0) {
			throw new IllegalStateException(label + " must be finite and positive, measured " + value);
		}
		return value;
	}

	private static String key(int profile, int type) {
		return profile + ":" + LABELS[type];
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.9f", value);
	}

	private record PairSample(
			int repeat,
			String order,
			double setupNsPerTest,
			double operationNsPerTest,
			double netNsPerTest
	) { }

	private record RelativeSample(
			int repeat,
			String outerOrder,
			PairSample operation,
			PairSample sphere
	) { }

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static byte[] workloadBytes(float[] values) {
		ByteBuffer bytes = ByteBuffer.allocate(
				Math.addExact(Integer.BYTES, Math.multiplyExact(values.length, Integer.BYTES)))
				.order(ByteOrder.BIG_ENDIAN);
		bytes.putInt(values.length);
		for (float value : values) bytes.putInt(Float.floatToRawIntBits(value));
		return bytes.array();
	}

	private static String deviceName(CUdevice device) {
		byte[] bytes = new byte[256];
		cuDeviceGetName(bytes, bytes.length, device);
		int length = 0;
		while (length < bytes.length && bytes[length] != 0) length++;
		return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
	}

	private static String computeCapability(CUdevice device) {
		int[] major = new int[1];
		int[] minor = new int[1];
		cuDeviceGetAttribute(major, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device);
		cuDeviceGetAttribute(minor, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device);
		return "compute_" + major[0] + minor[0];
	}

	private static float[] buildWorkload() {
		if (Integer.bitCount(WORKLOAD_RECORDS) != 1) {
			throw new IllegalStateException("Calibration workload size must be a power of two");
		}
		int rayOffset = 0;
		int sphereOffset = rayOffset + PROFILES * WORKLOAD_RECORDS * FLOATS_PER_RAY;
		int boxOffset = sphereOffset + WORKLOAD_RECORDS * FLOATS_PER_SPHERE;
		int affineOffset = boxOffset + WORKLOAD_RECORDS * FLOATS_PER_BOX;
		int planeOffset = affineOffset + WORKLOAD_RECORDS * FLOATS_PER_AFFINE;
		int nodeAabbOffset = planeOffset + WORKLOAD_RECORDS * FLOATS_PER_PLANE;
		int nodePairOffset = nodeAabbOffset + WORKLOAD_RECORDS * FLOATS_PER_NODE_AABB;
		int materialOffset = nodePairOffset + WORKLOAD_RECORDS * FLOATS_PER_NODE_PAIR;
		float[] data = new float[materialOffset + MATERIAL_RECORDS * FLOATS_PER_MATERIAL];

		for (int profile = 0; profile < PROFILES; profile++) {
			for (int record = 0; record < WORKLOAD_RECORDS; record++) {
				int sample = hash32(record ^ (profile * 0x6D2B79F5));
				float x = signedUnit(sample);
				float y = signedUnit(hash32(sample ^ 0x51ED270B));
				float z = signedUnit(hash32(sample ^ 0xA54FF53A));
				double ox = 0.0;
				double oy = 0.0;
				double oz = -3.0;
				double dx;
				double dy;
				double dz = 1.0;
				if (profile == 0) {
					dx = x * 0.00075;
					dy = y * 0.00075;
				} else if (profile == 1) {
					dx = x * 0.36;
					dy = y * 0.24 - 0.12;
				} else {
					ox = x * 0.68;
					oy = y * 0.68;
					oz += z * 0.20;
					dx = x * 0.055;
					dy = y * 0.055;
				}
				double inverseLength = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
				int offset = rayOffset
						+ (profile * WORKLOAD_RECORDS + record) * FLOATS_PER_RAY;
				data[offset] = (float) ox;
				data[offset + 1] = (float) oy;
				data[offset + 2] = (float) oz;
				data[offset + 3] = (float) (dx * inverseLength);
				data[offset + 4] = (float) (dy * inverseLength);
				data[offset + 5] = (float) (dz * inverseLength);
			}
		}

		for (int record = 0; record < WORKLOAD_RECORDS; record++) {
			int sample = hash32(record * 0x9E3779B9);
			float x = signedUnit(sample);
			float y = signedUnit(hash32(sample ^ 0x243F6A88));
			float z = signedUnit(hash32(sample ^ 0xB7E15162));
			float cx = x * 0.28f;
			float cy = y * 0.22f;
			float cz = z * 0.18f + 0.35f;

			int sphere = sphereOffset + record * FLOATS_PER_SPHERE;
			data[sphere] = cx;
			data[sphere + 1] = cy;
			data[sphere + 2] = cz;
			data[sphere + 3] = 0.58f + 0.34f * unit(hash32(sample ^ 0x13198A2E));
			data[sphere + 4] = record;

			float hx = 0.52f + 0.35f * unit(hash32(sample ^ 0x03707344));
			float hy = 0.52f + 0.35f * unit(hash32(sample ^ 0xA4093822));
			float hz = 0.52f + 0.35f * unit(hash32(sample ^ 0x299F31D0));
			int box = boxOffset + record * FLOATS_PER_BOX;
			data[box] = cx - hx;
			data[box + 1] = cy - hy;
			data[box + 2] = cz - hz;
			data[box + 3] = cx + hx;
			data[box + 4] = cy + hy;
			data[box + 5] = cz + hz;
			data[box + 6] = record;

			int nodeAabb = nodeAabbOffset + record * FLOATS_PER_NODE_AABB;
			System.arraycopy(data, box, data, nodeAabb, FLOATS_PER_NODE_AABB);

			writeAffineRecord(data, affineOffset + record * FLOATS_PER_AFFINE,
					sample, cx, cy, cz, record);
			writePlaneRecord(data, planeOffset + record * FLOATS_PER_PLANE,
					sample, cx, cy, record);
			writeNodePair(data, nodePairOffset + record * FLOATS_PER_NODE_PAIR,
					sample, cx, cy, cz, hx, hy, hz);
			writeMaterialRecord(data, materialOffset + record * FLOATS_PER_MATERIAL,
					sample);
		}
		return data;
	}

	private static void writeAffineRecord(
			float[] data,
			int offset,
			int sample,
			float cx,
			float cy,
			float cz,
			int materialIndex
	) {
		double angle = signedUnit(hash32(sample ^ 0x452821E6)) * 0.85;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double sx = 0.62 + 0.78 * unit(hash32(sample ^ 0x38D01377));
		double sy = 0.62 + 0.78 * unit(hash32(sample ^ 0xBE5466CF));
		double sz = 0.62 + 0.78 * unit(hash32(sample ^ 0x34E90C6C));
		float i00 = (float) (cos / sx);
		float i01 = (float) (sin / sx);
		float i10 = (float) (-sin / sy);
		float i11 = (float) (cos / sy);
		float i22 = (float) (1.0 / sz);
		data[offset] = i00;
		data[offset + 1] = i01;
		data[offset + 2] = 0.0f;
		data[offset + 3] = cx;
		data[offset + 4] = i10;
		data[offset + 5] = i11;
		data[offset + 6] = 0.0f;
		data[offset + 7] = cy;
		data[offset + 8] = 0.0f;
		data[offset + 9] = 0.0f;
		data[offset + 10] = i22;
		data[offset + 11] = cz;
		data[offset + 12] = i00;
		data[offset + 13] = i10;
		data[offset + 14] = 0.0f;
		data[offset + 15] = i01;
		data[offset + 16] = i11;
		data[offset + 17] = 0.0f;
		data[offset + 18] = 0.0f;
		data[offset + 19] = 0.0f;
		data[offset + 20] = i22;
		data[offset + 21] = materialIndex;
	}

	private static void writePlaneRecord(
			float[] data,
			int offset,
			int sample,
			float cx,
			float cy,
			int materialIndex
	) {
		double nx = signedUnit(hash32(sample ^ 0xC0AC29B7)) * 0.12;
		double ny = signedUnit(hash32(sample ^ 0xC97C50DD)) * 0.12;
		double nz = -1.0;
		double inverseLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
		float normalX = (float) (nx * inverseLength);
		float normalY = (float) (ny * inverseLength);
		float normalZ = (float) (nz * inverseLength);
		float pointZ = 0.15f * signedUnit(hash32(sample ^ 0x3F84D5B5));
		data[offset] = normalX;
		data[offset + 1] = normalY;
		data[offset + 2] = normalZ;
		data[offset + 3] = -(normalX * cx + normalY * cy + normalZ * pointZ);
		data[offset + 4] = cx;
		data[offset + 5] = cy;
		data[offset + 6] = pointZ;
		data[offset + 18] = 0.0f;
		data[offset + 23] = materialIndex;
		data[offset + 24] = -1.0f;
		data[offset + 25] = -1.0f;
	}

	private static void writeNodePair(
			float[] data,
			int offset,
			int sample,
			float cx,
			float cy,
			float cz,
			float hx,
			float hy,
			float hz
	) {
		float spread = 0.04f * signedUnit(hash32(sample ^ 0x9216D5D9));
		float split = Math.max(-0.75f * hx, Math.min(0.75f * hx, spread));
		data[offset] = cx - hx;
		data[offset + 1] = cy - hy;
		data[offset + 2] = cz - hz;
		data[offset + 3] = cx + split;
		data[offset + 4] = cy + hy;
		data[offset + 5] = cz + hz;
		data[offset + 6] = cx + split;
		data[offset + 7] = cy - hy;
		data[offset + 8] = cz - hz;
		data[offset + 9] = cx + hx;
		data[offset + 10] = cy + hy;
		data[offset + 11] = cz + hz;
	}

	private static void writeMaterialRecord(float[] data, int offset, int sample) {
		data[offset] = 0.35f + 0.55f * unit(hash32(sample ^ 0xD1310BA6));
		data[offset + 1] = 0.35f + 0.55f * unit(hash32(sample ^ 0x98DFB5AC));
		data[offset + 2] = 0.35f + 0.55f * unit(hash32(sample ^ 0x2FFD72DB));
		data[offset + 3] = 0.02f + 0.18f * unit(hash32(sample ^ 0xD01ADFB7));
		data[offset + 4] = 0.02f + 0.18f * unit(hash32(sample ^ 0xB8E1AFED));
		data[offset + 5] = 0.02f + 0.18f * unit(hash32(sample ^ 0x6A267E96));
		data[offset + 6] = 0.01f + 0.09f * unit(hash32(sample ^ 0xBA7C9045));
		data[offset + 7] = 0.01f + 0.09f * unit(hash32(sample ^ 0xF12C7F99));
		data[offset + 8] = 0.01f + 0.09f * unit(hash32(sample ^ 0x24A19947));
		data[offset + 9] = 0.01f * unit(hash32(sample ^ 0xB3916CF7));
		data[offset + 10] = 0.01f * unit(hash32(sample ^ 0x0801F2E2));
		data[offset + 11] = 0.01f * unit(hash32(sample ^ 0x858EFC16));
		data[offset + 12] = 1.1f + 0.7f * unit(hash32(sample ^ 0x636920D8));
	}

	private static int hash32(int value) {
		value ^= value >>> 17;
		value *= 0xED5AD4BB;
		value ^= value >>> 11;
		value *= 0xAC4C1B51;
		value ^= value >>> 15;
		value *= 0x31848BAB;
		value ^= value >>> 14;
		return value;
	}

	private static float unit(int value) {
		return (value >>> 8) * 0x1p-24f;
	}

	private static float signedUnit(int value) {
		return 2.0f * unit(value) - 1.0f;
	}

	private static String benchmarkKernel() {
		return """

template<int TYPE>
__device__ __noinline__ HitInfo primitiveCostOperation(
		const float *spheres, const float *boxes, const float *affines,
		const float *planes, const float *nodeBounds, const float *nodePairs,
		const float *materials, int recordIndex, float3 origin, float3 dir) {
	HitInfo hit{};
	// A dynamic baseline makes the return ABI and complete-result consumer the
	// same for setup and measured operations. The generous finite t bound is
	// above every generated calibration primitive.
	hit.hit = 1;
	hit.t = 64.0f + fabsf(origin.x) + fabsf(origin.y) + fabsf(origin.z);
	hit.primitiveOrder = recordIndex;
	hit.materialIndex = recordIndex;
	hit.position = origin;
	hit.normal = dir;
	hit.material.diffuse = make_vec(origin.x + 0.31f, origin.y + 0.37f, origin.z + 0.41f);
	hit.material.reflective = make_vec(dir.x + 0.43f, dir.y + 0.47f, dir.z + 0.53f);
	hit.material.refractive = make_vec(origin.x + dir.x, origin.y + dir.y, origin.z + dir.z);
	hit.material.emittance = make_vec(origin.x - dir.x, origin.y - dir.y, origin.z - dir.z);
	hit.material.refractiveIndex = 1.0f + fabsf(dir.z);
	if (TYPE == 1) traceSphere(spheres + recordIndex * 5, 1, materials, origin, dir, 0, &hit);
	else if (TYPE == 2) traceBox(boxes + recordIndex * 7, 1, materials, origin, dir, 0, &hit);
	else if (TYPE == 3) traceAffineSphere(affines + recordIndex * 22, 1, materials, origin, dir, 0, &hit);
	else if (TYPE == 4) traceAffineBox(affines + recordIndex * 22, 1, materials, origin, dir, 0, &hit);
	else if (TYPE == 5) {
		float entry = bvhEntryDistance(nodeBounds + recordIndex * 6, 0, origin, dir, hit.t);
		if (isfinite(entry)) {
			hit.hit = 1;
			hit.t = entry;
		}
	} else if (TYPE == 6) {
		const float *pairBounds = nodePairs + recordIndex * 12;
		float leftEntry = bvhEntryDistance(pairBounds, 0, origin, dir, hit.t);
		float rightEntry = bvhEntryDistance(pairBounds, 1, origin, dir, hit.t);
		if (rightEntry < leftEntry) {
			float tmp = leftEntry;
			leftEntry = rightEntry;
			rightEntry = tmp;
		}
		int nodeStack[2];
		float entryStack[2];
		int stackSize = 0;
		if (isfinite(rightEntry)) {
			nodeStack[stackSize] = 1;
			entryStack[stackSize++] = rightEntry;
		}
		if (isfinite(leftEntry)) {
			nodeStack[stackSize] = 0;
			entryStack[stackSize++] = leftEntry;
		}
		if (stackSize > 0) {
			hit.hit = 1;
			hit.t = entryStack[stackSize - 1];
			hit.primitiveOrder = nodeStack[stackSize - 1];
		}
	} else if (TYPE == 7) {
		tracePlane(planes + recordIndex * 26, 1, materials, origin, dir, 0, &hit);
	}
	return hit;
}

__device__ inline float primitiveCostConsume(const HitInfo &hit) {
	// Consume every result field used by tracePath. Keeping this identical for
	// TYPE=0 and measured operations subtracts wrapper/consumer overhead while
	// preventing NVRTC from deleting material, position, normal, or ID work.
	return hit.t * 0.000001f
			+ hit.position.x * 0.000003f + hit.position.y * 0.000005f
			+ hit.position.z * 0.000007f + hit.normal.x * 0.000011f
			+ hit.normal.y * 0.000013f + hit.normal.z * 0.000017f
			+ hit.material.diffuse.x * 0.000019f + hit.material.diffuse.y * 0.000023f
			+ hit.material.diffuse.z * 0.000029f + hit.material.reflective.x * 0.000031f
			+ hit.material.reflective.y * 0.000037f + hit.material.reflective.z * 0.000041f
			+ hit.material.refractive.x * 0.000043f + hit.material.refractive.y * 0.000047f
			+ hit.material.refractive.z * 0.000053f + hit.material.emittance.x * 0.000059f
			+ hit.material.emittance.y * 0.000061f + hit.material.emittance.z * 0.000067f
			+ hit.material.refractiveIndex * 0.000071f
			+ (float) hit.hit * 0.000073f + (float) hit.primitiveOrder * 0.0000001f
			+ (float) hit.materialIndex * 0.0000003f;
}

template<int TYPE>
__device__ inline float primitiveCostLoop(int tid, int profile, int iterationOffset, int iterations,
		int operationCopies, const float *workload, int recordCount) {
	int rayOffset = 0;
	int sphereOffset = rayOffset + 3 * recordCount * 6;
	int boxOffset = sphereOffset + recordCount * 5;
	int affineOffset = boxOffset + recordCount * 7;
	int planeOffset = affineOffset + recordCount * 22;
	int nodeAabbOffset = planeOffset + recordCount * 26;
	int nodePairOffset = nodeAabbOffset + recordCount * 6;
	int materialOffset = nodePairOffset + recordCount * 12;
	const float *rays = workload + rayOffset;
	const float *spheres = workload + sphereOffset;
	const float *boxes = workload + boxOffset;
	const float *affines = workload + affineOffset;
	const float *planes = workload + planeOffset;
	const float *nodeBounds = workload + nodeAabbOffset;
	const float *nodePairs = workload + nodePairOffset;
	const float *materials = workload + materialOffset;
	float accumulator = 0.0f;
	for (int i = 0; i < iterations; i++) {
		int globalIteration = iterationOffset + i;
		unsigned int sample = hash_u32((unsigned int)(globalIteration * 0x9E3779B9u + tid));
		int rayIndex = (int)(sample & (unsigned int)(recordCount - 1));
		int rayBase = (profile * recordCount + rayIndex) * 6;
		float3 origin = make_vec(
				rays[rayBase + 0], rays[rayBase + 1], rays[rayBase + 2]);
		float3 dir = make_vec(
				rays[rayBase + 3], rays[rayBase + 4], rays[rayBase + 5]);
		for (int operationCopy = 0; operationCopy < operationCopies; operationCopy++) {
			unsigned int recordSample =
					hash_u32(sample ^ ((unsigned int)(operationCopy + 1) * 0x85EBCA6Bu));
			int recordIndex = (int)(recordSample & (unsigned int)(recordCount - 1));
			HitInfo hit = primitiveCostOperation<TYPE>(
					spheres, boxes, affines, planes, nodeBounds, nodePairs,
					materials, recordIndex, origin, dir);
			accumulator += origin.x * 0.00013f + origin.y * 0.00017f
					+ origin.z * 0.00019f + dir.x * 0.00023f
					+ dir.y * 0.00029f + dir.z * 0.00031f
					+ (recordIndex & 7) * 0.0000001f
					+ primitiveCostConsume(hit);
		}
	}
	return accumulator;
}

extern "C"
__global__ void primitiveCostKernel(
		int type, int profile, int iterationOffset, int iterations, int operationCopies,
		const float *workload, int recordCount, float *output) {
	int tid = blockIdx.x * blockDim.x + threadIdx.x;
	float value;
	switch (type) {
		case 0: value = primitiveCostLoop<0>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 1: value = primitiveCostLoop<1>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 2: value = primitiveCostLoop<2>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 3: value = primitiveCostLoop<3>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 4: value = primitiveCostLoop<4>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 5: value = primitiveCostLoop<5>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 6: value = primitiveCostLoop<6>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		case 7: value = primitiveCostLoop<7>(
				tid, profile, iterationOffset, iterations, operationCopies, workload, recordCount); break;
		default: value = 0.0f; break;
	}
	output[tid] = value;
}
""";
	}
}
