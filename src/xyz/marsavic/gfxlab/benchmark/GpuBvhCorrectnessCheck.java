package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Hit;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.GalleryOverlapScene;
import xyz.marsavic.gfxlab.playground.SceneCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic correctness check for scene packing, analytic intersections and BVH
 * traversal. The unaccelerated GPU closest-hit path is checked against the CPU
 * scene, then every BVH result is checked against that GPU reference.
 */
public final class GpuBvhCorrectnessCheck {
	private static final double ABSOLUTE_TOLERANCE = 2.0e-3;
	private static final double RELATIVE_TOLERANCE = 2.0e-4;
	private static final int RAY_GRID_WIDTH = 32;
	private static final int RAY_GRID_HEIGHT = 18;
	private static final String DRIVER_PROPERTY = "gfxlab.gpu.driverVersion";
	private static final Set<String> EVIDENCE_FILES = Set.of(
			"correctness.csv", "manifest.json", "SHA256SUMS.txt");

	private record Arguments(
			Path projectRoot,
			Path outputRoot,
			String compiledClassesSha256,
			String sourceCommit,
			String sourceTree
	) { }

	private GpuBvhCorrectnessCheck() { }

	public static void main(String[] rawArguments) throws Exception {
		Arguments arguments = parse(rawArguments);
		BenchmarkProtocol.verify(arguments.projectRoot());
		BenchmarkProtocol.requireTimingRuntimeProperties();
		BenchmarkClassIdentity.requireLiveIdentity(
				GpuBvhCorrectnessCheck.class, arguments.compiledClassesSha256());
		Path output = EvidenceFiles.requireCreateNewOutput(
				arguments.projectRoot(), arguments.outputRoot());
		// The create-new directory reserves this evidence name before either CUDA context exists.
		Files.createDirectory(output);

		List<Scenario> scenarios = scenarios();
		BvhBuildConfig calibratedBase = MethodCatalog.calibratedBase();
		List<Row> rows = new ArrayList<>();
		int failures = 0;
		String deviceName;
		String computeCapability;
		String linearPtxSha256;
		String timingPtxSha256;

		try (GpuRayTracer linearTracer = new GpuRayTracer(1, 1, 1, false, false);
		     GpuRayTracer bvhTracer = new GpuRayTracer(1, 1, 1, true, false)) {
			if (!linearTracer.isAvailable() || !bvhTracer.isAvailable()) {
				throw new IllegalStateException("CUDA trace replay is unavailable");
			}
			if (!linearTracer.deviceInfo().equals(bvhTracer.deviceInfo())) {
				throw new IllegalStateException("Correctness CUDA contexts use different devices");
			}
			deviceName = bvhTracer.deviceInfo().name();
			computeCapability = bvhTracer.deviceInfo().computeCapability();
			linearPtxSha256 = bvhTracerHash(linearTracer, "linear");
			timingPtxSha256 = bvhTracerHash(bvhTracer, "BVH");
			requireCalibrationDevice(deviceName, computeCapability);
			for (Scenario scenario : scenarios) {
				List<MethodCatalog.Method> methods = MethodCatalog.buildAll(
						scenario.scene(), calibratedBase,
						BenchmarkProtocol.LEAF_SIZE, BenchmarkProtocol.WEIGHTED_LAMBDA);
				GpuScene linearScene = methods.get(0).scene();
				CpuResult cpu = traceCpu(scenario.scene(), scenario.rays(), linearScene);
				GpuRayTracer.TraceReplayResult linear = linearTracer.traceReplay(
						linearScene, scenario.rays(), scenario.rayCount(), false);
				Comparison cpuComparison = compareCpu(cpu, linear);
				rows.add(Row.linear(scenario, linearScene, cpuComparison));
				failures += cpuComparison.mismatches();

				for (MethodCatalog.Method method : methods) {
					GpuScene bvhScene = method.scene();
					GpuRayTracer.TraceReplayResult result = bvhTracer.traceReplay(
							bvhScene, scenario.rays(), scenario.rayCount(), true);
					Comparison linearComparison = compareGpu(linear, result);
					int mismatchCount = linearComparison.mismatches();
					if (result.stats().stackOverflows() != 0L
							|| result.stats().maxStackSize() >= BenchmarkProtocol.STACK_CAPACITY) {
						mismatchCount++;
					}
					failures += mismatchCount;
					rows.add(Row.bvh(scenario, method, bvhScene, result, mismatchCount,
							linearComparison.maxAbsoluteError(),
							linearComparison.maxRelativeError(),
							linearComparison.maxNormalAbsoluteError()));
				}
				System.out.printf(Locale.ROOT, "%s: rays=%d CPU/GPU mismatches=%d%n",
						scenario.name(), scenario.rayCount(), cpuComparison.mismatches());
			}
		}

		if (failures != 0) {
			throw new IllegalStateException("BVH correctness gate failed with " + failures
					+ " mismatches");
		}
		int expectedRows = Math.multiplyExact(
				scenarios.size(), MethodCatalog.FAMILIES.size() + 1);
		if (rows.size() != expectedRows) {
			throw new IllegalStateException("Correctness evidence row count differs");
		}
		EvidenceFiles.writeNew(
				output.resolve("correctness.csv"), rowsCsv(rows).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeNew(output.resolve("manifest.json"), manifest(
				arguments, scenarios.size(), rows.size(), deviceName, computeCapability,
				linearPtxSha256, timingPtxSha256).getBytes(StandardCharsets.UTF_8));
		EvidenceFiles.writeSha256Ledger(output);
		EvidenceFiles.verifyRegularFileDirectory(output, EVIDENCE_FILES);
		System.out.println("BVH correctness gate PASS: " + output.toAbsolutePath());
	}

	private static String bvhTracerHash(GpuRayTracer tracer, String label) {
		String value = tracer.compiledPtxSha256();
		if (!EvidenceFiles.isSha256(value)) {
			throw new IllegalStateException("Missing " + label + " correctness PTX identity");
		}
		return value;
	}

	private static void requireCalibrationDevice(String deviceName, String computeCapability) {
		String driver = System.getProperty(DRIVER_PROPERTY, "");
		if (!deviceName.equals(PrimitiveCostModel.CALIBRATION_GPU_NAME)
				|| !computeCapability.equals(PrimitiveCostModel.CALIBRATION_GPU_COMPUTE_CAPABILITY)
				|| !driver.equals(PrimitiveCostModel.CALIBRATION_NVIDIA_DRIVER_VERSION)) {
			throw new IllegalStateException("Correctness GPU differs from the applied calibration");
		}
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<>();
		for (SceneCatalog.ScenePreset preset : SceneCatalog.introductoryPresets()) {
			SceneCatalog.SceneSetup setup = SceneCatalog.create(preset);
			values.add(new Scenario(preset.name(), setup.scene(), rayGrid(setup.camera())));
		}
		SceneCatalog.SceneSetup hero = SceneCatalog.create(SceneCatalog.ScenePreset.LAYERED_HERO_96);
		values.add(new Scenario(SceneCatalog.ScenePreset.LAYERED_HERO_96.name(),
				hero.scene(), rayGrid(hero.camera())));

		GalleryOverlapScene.Setup overlap = GalleryOverlapScene.create();
		values.add(new Scenario("GALLERY_OVERLAP", overlap.scene(),
				rayGrid(overlap.frontCamera())));
		for (int rowOrdinal : List.of(6, 9, 12, 15)) {
			BenchmarkProtocol.PublicationRow row =
					BenchmarkProtocol.publicationRows().get(rowOrdinal);
			BenchmarkWorkloads.Source source = BenchmarkWorkloads.random(
					row, BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID);
			values.add(new Scenario(row.id(), source.scene(), rayGrid(source.camera())));
		}
		return List.copyOf(values);
	}

	private static float[] rayGrid(Camera camera) {
		return BenchmarkRays.primaryGrid(
				camera, RAY_GRID_WIDTH, RAY_GRID_HEIGHT, 0.5, 0.5);
	}

	private static CpuResult traceCpu(Scene scene, float[] rays, GpuScene gpuScene) {
		int count = rays.length / 6;
		int[] hitFlags = new int[count];
		double[] distances = new double[count];
		int[] materialIndices = new int[count];
		double[] normals = new double[count * 3];
		for (int i = 0; i < count; i++) {
			int offset = i * 6;
			Ray ray = Ray.pd(
					Vec3.xyz(rays[offset], rays[offset + 1], rays[offset + 2]),
					Vec3.xyz(rays[offset + 3], rays[offset + 4], rays[offset + 5]));
			Hit hit = scene.solid().firstHit(ray, 1.0e-4);
			boolean found = Double.isFinite(hit.t());
			hitFlags[i] = found ? 1 : 0;
			distances[i] = found ? hit.t() : Double.POSITIVE_INFINITY;
			materialIndices[i] = found
					? gpuScene.materialIndexOf(GpuScene.MaterialData.from(hit.material())) : -1;
			if (found) {
				Vec3 normal = hit.n_();
				normals[i * 3] = normal.x();
				normals[i * 3 + 1] = normal.y();
				normals[i * 3 + 2] = normal.z();
			}
		}
		return new CpuResult(hitFlags, distances, materialIndices, normals);
	}

	private static Comparison compareCpu(CpuResult reference, GpuRayTracer.TraceReplayResult candidate) {
		int mismatches = 0;
		double maxAbsolute = 0.0;
		double maxRelative = 0.0;
		double maxNormalAbsolute = 0.0;
		for (int i = 0; i < reference.hitFlags().length; i++) {
			if (reference.hitFlags()[i] != candidate.hitFlags()[i]) {
				mismatches++;
				continue;
			}
			if (reference.hitFlags()[i] == 0) continue;
			double absolute = Math.abs(reference.distances()[i] - candidate.hitDistances()[i]);
			double scale = Math.max(1.0, Math.max(Math.abs(reference.distances()[i]),
					Math.abs(candidate.hitDistances()[i])));
			double relative = absolute / scale;
			maxAbsolute = Math.max(maxAbsolute, absolute);
			maxRelative = Math.max(maxRelative, relative);
			if (absolute > ABSOLUTE_TOLERANCE + RELATIVE_TOLERANCE * scale) mismatches++;
			if (reference.materialIndices()[i] < 0
					|| reference.materialIndices()[i] != candidate.hitMaterialIndices()[i]) {
				mismatches++;
			}
			boolean normalMismatch = false;
			for (int component = 0; component < 3; component++) {
				double error = Math.abs(reference.normals()[i * 3 + component]
						- candidate.hitNormals()[i * 3 + component]);
				maxNormalAbsolute = Math.max(maxNormalAbsolute, error);
				if (error > 2.0e-3) normalMismatch = true;
			}
			if (normalMismatch) mismatches++;
		}
		return new Comparison(mismatches, maxAbsolute, maxRelative, maxNormalAbsolute);
	}

	private static Comparison compareGpu(GpuRayTracer.TraceReplayResult reference,
	                                     GpuRayTracer.TraceReplayResult candidate) {
		int mismatches = 0;
		double maxAbsolute = 0.0;
		double maxRelative = 0.0;
		double maxNormalAbsolute = 0.0;
		for (int i = 0; i < reference.hitFlags().length; i++) {
			if (reference.hitFlags()[i] != candidate.hitFlags()[i]) {
				mismatches++;
				continue;
			}
			if (reference.hitFlags()[i] == 0) continue;
			double absolute = Math.abs(reference.hitDistances()[i] - candidate.hitDistances()[i]);
			double scale = Math.max(1.0, Math.max(Math.abs(reference.hitDistances()[i]),
					Math.abs(candidate.hitDistances()[i])));
			double relative = absolute / scale;
			maxAbsolute = Math.max(maxAbsolute, absolute);
			maxRelative = Math.max(maxRelative, relative);
			boolean normalMismatch = false;
			for (int component = 0; component < 3; component++) {
				double error = Math.abs(reference.hitNormals()[i * 3 + component]
						- candidate.hitNormals()[i * 3 + component]);
				maxNormalAbsolute = Math.max(maxNormalAbsolute, error);
				if (error > 2.0e-5) normalMismatch = true;
			}
			if (absolute > 1.0e-4 + 1.0e-5 * scale
					|| reference.hitPrimitiveOrders()[i] != candidate.hitPrimitiveOrders()[i]
					|| reference.hitMaterialIndices()[i] != candidate.hitMaterialIndices()[i]
					|| normalMismatch) {
				mismatches++;
			}
		}
		return new Comparison(mismatches, maxAbsolute, maxRelative, maxNormalAbsolute);
	}

	private static String rowsCsv(List<Row> rows) {
		StringBuilder csv = new StringBuilder(Row.HEADER);
		for (Row row : rows) csv.append(row.csv());
		return csv.toString();
	}

	private static String manifest(
			Arguments arguments,
			int scenarioCount,
			int rowCount,
			String deviceName,
			String computeCapability,
			String linearPtxSha256,
			String timingPtxSha256
	) {
		return String.format(Locale.ROOT, """
				{
				  "schemaVersion": 1,
				  "study": "gpu-bvh-correctness",
				  "measurementState": "GPU_CORRECTNESS_PASS",
				  "scenarioCount": %d,
				  "methodCount": %d,
				  "rowCount": %d,
				  "mismatches": 0,
				  "bvhStackSize": %d,
				  "deviceName": %s,
				  "computeCapability": %s,
				  "driverVersion": %s,
				  "linearPtxSha256": "%s",
				  "timingPtxSha256": "%s",
				  "compiledClassesSha256": "%s",
				  "sourceCommit": "%s",
				  "sourceTree": "%s",
				  "calibrationAppliedIdentitySha256": "%s",
				  "calibrationWeights": {
				    "sphere": %.9f,
				    "box": %.9f,
				    "affineSphere": %.9f,
				    "affineBox": %.9f,
				    "plane": %.9f,
				    "nodeAabb": %.9f,
				    "interiorTraversal": %.9f
				  }
				}
				""",
				scenarioCount, MethodCatalog.FAMILIES.size(), rowCount,
				BenchmarkProtocol.STACK_CAPACITY,
				EvidenceFiles.json(deviceName), EvidenceFiles.json(computeCapability),
				EvidenceFiles.json(System.getProperty(DRIVER_PROPERTY, "")),
				linearPtxSha256, timingPtxSha256,
				arguments.compiledClassesSha256(), arguments.sourceCommit(), arguments.sourceTree(),
				PrimitiveCostModel.CALIBRATION_APPLIED_IDENTITY_SHA256,
				PrimitiveCostModel.SPHERE, PrimitiveCostModel.BOX,
				PrimitiveCostModel.AFFINE_SPHERE, PrimitiveCostModel.AFFINE_BOX,
				PrimitiveCostModel.PLANE, PrimitiveCostModel.NODE_AABB,
				PrimitiveCostModel.INTERIOR_TRAVERSAL);
	}

	private static Arguments parse(String[] arguments) {
		Set<String> required = Set.of(
				"project-root", "output-root", "compiled-classes-sha256",
				"source-commit", "source-tree");
		if (arguments == null || arguments.length != required.size() * 2) {
			throw new IllegalArgumentException(
					"GPU correctness requires five named option pairs");
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 0; index < arguments.length; index += 2) {
			String option = arguments[index];
			if (option == null || !option.startsWith("--")
					|| !required.contains(option.substring(2))) {
				throw new IllegalArgumentException("Unknown GPU correctness option: " + option);
			}
			String value = arguments[index + 1] == null ? "" : arguments[index + 1].trim();
			if (value.isEmpty() || values.put(option.substring(2), value) != null) {
				throw new IllegalArgumentException("Missing or duplicate GPU correctness option");
			}
		}
		if (!values.keySet().equals(required)) {
			throw new IllegalArgumentException("GPU correctness options are incomplete");
		}
		return new Arguments(
				Path.of(values.get("project-root")).toAbsolutePath().normalize(),
				Path.of(values.get("output-root")).toAbsolutePath().normalize(),
				requireHex(values.get("compiled-classes-sha256"), 64),
				requireHex(values.get("source-commit"), 40),
				requireHex(values.get("source-tree"), 40));
	}

	private static String requireHex(String value, int length) {
		if (value == null || value.length() != length
				|| !value.matches("[0-9a-f]+")) {
			throw new IllegalArgumentException("Malformed GPU correctness identity");
		}
		return value;
	}

	private record Scenario(String name, Scene scene, float[] rays) {
		int rayCount() { return rays.length / 6; }
	}

	private record CpuResult(
			int[] hitFlags,
			double[] distances,
			int[] materialIndices,
			double[] normals
	) { }
	private record Comparison(
			int mismatches,
			double maxAbsoluteError,
			double maxRelativeError,
			double maxNormalAbsoluteError
	) { }

	private record Row(String scene, String implementation, String formula, int leafSize, double lambda,
	                   int rays, int primitiveCount, int mismatches, double maxAbsoluteError,
	                   double maxRelativeError, double maxNormalAbsoluteError,
	                   long aabbTests, long primitiveTests, long stackOverflows,
	                   long leafNodeVisits, long homogeneousLeafNodeVisits, long mixedLeafNodeVisits,
	                   long maxStackSize, int nodeCount, int leafCount, int maxDepth, long bvhBytes) {
		private static final String HEADER =
				"scene,implementation,formula,leafSize,lambda,rays,primitiveCount,mismatches,"
						+ "maxAbsoluteError,maxRelativeError,maxNormalAbsoluteError,"
						+ "aabbTests,primitiveTests,stackOverflows,leafNodeVisits,"
						+ "homogeneousLeafNodeVisits,mixedLeafNodeVisits,"
						+ "maxStackSize,nodeCount,leafCount,maxDepth,bvhBytes\n";

		static Row linear(Scenario scenario, GpuScene scene, Comparison comparison) {
			return new Row(scenario.name(), "GPU_LINEAR", "none", 0, 0.0, scenario.rayCount(),
					scene.primitiveCount(), comparison.mismatches(), comparison.maxAbsoluteError(),
					comparison.maxRelativeError(), comparison.maxNormalAbsoluteError(),
					0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0L);
		}

		static Row bvh(Scenario scenario, MethodCatalog.Method method, GpuScene scene,
		               GpuRayTracer.TraceReplayResult result, int mismatches,
		               double maxAbsoluteError, double maxRelativeError,
		               double maxNormalAbsoluteError) {
			GpuScene.BvhStats bvh = scene.bvhStats();
			return new Row(scenario.name(), "GPU_BVH", method.family(), method.leafSize(),
					method.lambda(), scenario.rayCount(), scene.primitiveCount(), mismatches,
					maxAbsoluteError, maxRelativeError, maxNormalAbsoluteError,
					result.stats().aabbTests(),
					result.stats().primitiveTests(), result.stats().stackOverflows(),
					result.stats().leafNodeVisits(), result.stats().homogeneousLeafNodeVisits(),
					result.stats().mixedLeafNodeVisits(),
					result.stats().maxStackSize(), bvh.nodeCount(), bvh.leafCount(), bvh.maxDepth(),
					bvh.bytes());
		}

		String csv() {
			return String.format(Locale.ROOT,
					"%s,%s,%s,%d,%.9f,%d,%d,%d,%.12g,%.12g,%.12g,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
					scene, implementation, formula, leafSize, lambda, rays, primitiveCount, mismatches,
					maxAbsoluteError, maxRelativeError, maxNormalAbsoluteError,
					aabbTests, primitiveTests, stackOverflows, leafNodeVisits,
					homogeneousLeafNodeVisits, mixedLeafNodeVisits,
					maxStackSize, nodeCount, leafCount, maxDepth, bvhBytes);
		}
	}
}
