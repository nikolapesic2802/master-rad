package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.BvhBuildMode;
import xyz.marsavic.gfxlab.gpu.BvhBuildOptions;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.GpuSceneBuilder;
import xyz.marsavic.gfxlab.gpu.PrimitiveCostModel;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Definition of the eight BVH construction methods used by the studies. */
public final class MethodCatalog {
	public static final List<String> FAMILIES = List.of(
			"uniform", "weighted", "per_type", "weighted_per_type",
			"sbvh", "weighted_sbvh", "sah_rotations", "weighted_sah_rotations");

	public record Edge(String referenceFamily, String candidateFamily) {
		public Edge {
			if (!FAMILIES.contains(referenceFamily) || !FAMILIES.contains(candidateFamily)
					|| referenceFamily.equals(candidateFamily)) {
				throw new IllegalArgumentException("Invalid benchmark comparison edge");
			}
		}
		public String id() { return referenceFamily + "__" + candidateFamily; }
	}

	public static final List<Edge> EDGES = List.of(
			new Edge("uniform", "weighted"),
			new Edge("uniform", "per_type"),
			new Edge("uniform", "weighted_per_type"),
			new Edge("uniform", "sbvh"),
			new Edge("uniform", "weighted_sbvh"),
			new Edge("uniform", "sah_rotations"),
			new Edge("uniform", "weighted_sah_rotations"));

	public record Spec(int ordinal, String family, BvhBuildMode mode) {
		public Spec {
			if (ordinal < 0 || ordinal >= FAMILIES.size()
					|| !FAMILIES.get(ordinal).equals(family) || mode == null) {
				throw new IllegalArgumentException("Invalid benchmark method specification");
			}
		}
		public boolean weighted() { return mode.usesPrimitiveWeights(); }
	}

	public record Method(
			Spec spec, int leafSize, double lambda, String optionKey,
			String packedGeometrySha256, String topologySha256,
			long buildNanos, long wallNanos, GpuScene scene
	) {
		public Method {
			if (spec == null || leafSize < 1 || lambda < 0.0 || !Double.isFinite(lambda)
					|| (spec.weighted() ? !(lambda > 0.0) : lambda != 0.0)
					|| optionKey == null || optionKey.isBlank()
					|| !isSha256(packedGeometrySha256) || !isSha256(topologySha256)
					|| buildNanos <= 0L || wallNanos < buildNanos || scene == null) {
				throw new IllegalArgumentException("Incomplete benchmark built method");
			}
		}
		public String family() { return spec.family(); }
		public int ordinal() { return spec.ordinal(); }
	}

	private MethodCatalog() { }

	public static List<Spec> specs() {
		return List.of(
				new Spec(0, "uniform", BvhBuildMode.UNIFORM_SAH),
				new Spec(1, "weighted", BvhBuildMode.WEIGHTED_SAH),
				new Spec(2, "per_type", BvhBuildMode.PER_TYPE_SAH),
				new Spec(3, "weighted_per_type", BvhBuildMode.PER_TYPE_WEIGHTED_SAH),
				new Spec(4, "sbvh", BvhBuildMode.UNIFORM_SBVH),
				new Spec(5, "weighted_sbvh", BvhBuildMode.WEIGHTED_SBVH),
				new Spec(6, "sah_rotations", BvhBuildMode.SAH_ROTATIONS),
				new Spec(7, "weighted_sah_rotations", BvhBuildMode.WEIGHTED_SAH_ROTATIONS));
	}

	public static BvhBuildConfig calibratedBase() {
		PrimitiveCostModel.validate();
		return config(BvhBuildMode.UNIFORM_SAH, BenchmarkProtocol.LEAF_SIZE, 0.0,
				new BvhBuildConfig(
						BvhBuildMode.UNIFORM_SAH, BenchmarkProtocol.LEAF_SIZE,
						PrimitiveCostModel.INTERIOR_TRAVERSAL, PrimitiveCostModel.SPHERE,
						PrimitiveCostModel.BOX, PrimitiveCostModel.AFFINE_SPHERE,
						PrimitiveCostModel.AFFINE_BOX, 0.0));
	}

	public static List<Method> buildAll(
			Scene source, BvhBuildConfig calibratedBase, int leafSize, double weightedLambda
	) {
		List<Method> result = new ArrayList<>(FAMILIES.size());
		String geometry = null;
		for (Spec spec : specs()) {
			Method method = buildOne(source, calibratedBase, spec, leafSize, weightedLambda);
			if (geometry == null) geometry = method.packedGeometrySha256();
			if (!geometry.equals(method.packedGeometrySha256())) {
				throw new IllegalStateException("benchmark method changed packed geometry");
			}
			result.add(method);
		}
		return List.copyOf(result);
	}

	public static Method buildOne(
			Scene source, BvhBuildConfig calibratedBase, Spec spec,
			int leafSize, double weightedLambda
	) {
		if (source == null || calibratedBase == null || spec == null || leafSize < 1
				|| weightedLambda < 0.0 || !Double.isFinite(weightedLambda)
				|| (spec.weighted() && weightedLambda == 0.0)) {
			throw new IllegalArgumentException("Invalid benchmark construction request");
		}
		double lambda = spec.weighted() ? weightedLambda : 0.0;
		BvhBuildConfig config = config(spec.mode(), leafSize, lambda, calibratedBase);
		BvhBuildOptions options = coreOptions();
		long started = System.nanoTime();
		GpuScene scene = GpuSceneBuilder.from(source, config, options);
		GpuScene.BvhStats stats = scene.bvhStats();
		long wall = System.nanoTime() - started;
		if (wall <= 0L || stats.buildNanos() <= 0L || wall < stats.buildNanos()) {
			throw new IllegalStateException("benchmark construction clock accounting differs");
		}
		return new Method(spec, leafSize, lambda, optionKey(leafSize, lambda, options),
				packedGeometrySha256(scene), topologySha256(scene),
				stats.buildNanos(), wall, scene);
	}

	/** Builds one object-SAH sensitivity cell; lambda zero selects ordinary SAH. */
	public static Method buildObjectSah(
			Scene source, BvhBuildConfig calibratedBase, int leafSize, double lambda
	) {
		if (source == null || calibratedBase == null || leafSize < 1
				|| lambda < 0.0 || !Double.isFinite(lambda)) {
			throw new IllegalArgumentException("Invalid benchmark object-SAH sensitivity request");
		}
		Spec spec = specs().get(lambda == 0.0 ? 0 : 1);
		return buildOne(source, calibratedBase, spec, leafSize, lambda);
	}

	public static BvhBuildOptions coreOptions() {
		return BvhBuildOptions.defaults();
	}

	public static String optionKey(int leafSize, double lambda) {
		return optionKey(leafSize, lambda, coreOptions());
	}

	private static String optionKey(int leafSize, double lambda, BvhBuildOptions options) {
		return String.format(Locale.ROOT,
				"leaf=%d|lambda=%.6f|spatialBins=%d|referenceMultiplier=%.6f|"
						+ "maxSplits=%d|minSpatialReferences=%d|overlap=%.8f|rotations=%d",
				leafSize, lambda, options.spatialBins(), options.maxReferenceMultiplier(),
				options.maxSplitsPerPrimitive(), options.minSpatialReferences(),
				options.spatialOverlapThreshold(), options.rotationPasses());
	}

	public static String topologySha256(GpuScene scene) {
		if (scene == null) throw new IllegalArgumentException("benchmark topology scene is required");
		MessageDigest digest = sha256Digest();
		update(digest, scene.bvhNodeBoundsData());
		update(digest, scene.bvhNodeData());
		update(digest, scene.bvhPrimitiveRefs());
		update(digest, scene.bvhRootIndices());
		return HexFormat.of().formatHex(digest.digest());
	}

	/** Hash domain: all packed primitive arrays, planes, and materials. */
	public static String packedGeometrySha256(GpuScene scene) {
		if (scene == null) throw new IllegalArgumentException("benchmark geometry scene is required");
		MessageDigest digest = sha256Digest();
		updateSection(digest, 1, scene.sphereData());
		updateSection(digest, 2, scene.boxData());
		updateSection(digest, 3, scene.affineSphereData());
		updateSection(digest, 4, scene.affineBoxData());
		updateSection(digest, 5, scene.planeData());
		updateSection(digest, 6, scene.materialData());
		return HexFormat.of().formatHex(digest.digest());
	}

	public static boolean isPerType(String family) {
		return family.equals("per_type") || family.equals("weighted_per_type");
	}

	public static boolean isSbvh(String family) {
		return family.equals("sbvh") || family.equals("weighted_sbvh");
	}

	private static BvhBuildConfig config(
			BvhBuildMode mode, int leafSize, double lambda, BvhBuildConfig base
	) {
		return new BvhBuildConfig(mode, leafSize, base.traversalWeight(),
				base.sphereWeight(), base.boxWeight(), base.affineSphereWeight(),
				base.affineBoxWeight(), lambda);
	}

	private static void updateSection(MessageDigest digest, int tag, float[] values) {
		updateInt(digest, tag);
		update(digest, values);
	}

	private static void update(MessageDigest digest, float[] values) {
		updateInt(digest, values.length);
		for (float value : values) updateInt(digest, Float.floatToRawIntBits(value));
	}

	private static void update(MessageDigest digest, int[] values) {
		updateInt(digest, values.length);
		for (int value : values) updateInt(digest, value);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private static boolean isSha256(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}
}
