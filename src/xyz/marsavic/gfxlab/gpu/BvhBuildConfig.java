package xyz.marsavic.gfxlab.gpu;

import java.util.Locale;

/** Immutable inputs to the conventional or cost-weighted SAH builder. */
public record BvhBuildConfig(
		BvhBuildMode mode,
		int leafSize,
		double traversalWeight,
		double sphereWeight,
		double boxWeight,
		double affineSphereWeight,
		double affineBoxWeight,
		double lambda
) {
	// One source of truth shared by construction, evaluation and reports.
	private static final double DEFAULT_TRAVERSAL_WEIGHT = PrimitiveCostModel.INTERIOR_TRAVERSAL;
	private static final double DEFAULT_BOX_WEIGHT = PrimitiveCostModel.BOX;
	private static final double DEFAULT_AFFINE_SPHERE_WEIGHT = PrimitiveCostModel.AFFINE_SPHERE;
	private static final double DEFAULT_AFFINE_BOX_WEIGHT = PrimitiveCostModel.AFFINE_BOX;
	private static final double DEFAULT_LAMBDA = 1.0;

	public BvhBuildConfig {
		if (mode == null) throw new IllegalArgumentException("BVH build mode is required.");
		if (leafSize < 1) throw new IllegalArgumentException("BVH leaf size must be positive.");
		checkPositive("AABB traversal", traversalWeight);
		checkPositive("sphere", sphereWeight);
		checkPositive("box", boxWeight);
		checkPositive("affine sphere", affineSphereWeight);
		checkPositive("affine box", affineBoxWeight);
		if (lambda < 0.0 || !Double.isFinite(lambda)) {
			throw new IllegalArgumentException("Invalid SAH lambda: " + lambda);
		}
		if (mode.usesPrimitiveWeights() && lambda == 0.0) {
			// Lambda zero is the ordinary objective in the same method family.
			mode = mode.withPrimitiveWeights(false);
			lambda = 0.0;
		} else if (!mode.usesPrimitiveWeights() && lambda != 0.0) {
			throw new IllegalArgumentException(
					"An unweighted BVH mode requires the canonical lambda-zero configuration.");
		} else if (!mode.usesPrimitiveWeights()) {
			lambda = 0.0;
		}
		if (mode.usesPrimitiveWeights()) {
			checkPositive("generalized sphere", 1.0 + lambda * (sphereWeight - 1.0));
			checkPositive("generalized box", 1.0 + lambda * (boxWeight - 1.0));
			checkPositive("generalized affine sphere", 1.0 + lambda * (affineSphereWeight - 1.0));
			checkPositive("generalized affine box", 1.0 + lambda * (affineBoxWeight - 1.0));
		}
	}

	public static BvhBuildConfig fromProperties(BvhBuildMode mode) {
		return new BvhBuildConfig(
				mode,
				readPositiveInt("gfxlab.bvhLeafSize", 8),
				readPositiveDouble("gfxlab.bvh.traversalWeight", DEFAULT_TRAVERSAL_WEIGHT),
				readPositiveDouble("gfxlab.bvh.sphereWeight", PrimitiveCostModel.SPHERE),
				readPositiveDouble("gfxlab.bvh.boxWeight", DEFAULT_BOX_WEIGHT),
				readPositiveDouble("gfxlab.bvh.affineSphereWeight", DEFAULT_AFFINE_SPHERE_WEIGHT),
				readPositiveDouble("gfxlab.bvh.affineBoxWeight", DEFAULT_AFFINE_BOX_WEIGHT),
				mode.usesPrimitiveWeights()
						? readNonNegativeDouble("gfxlab.bvh.lambda", DEFAULT_LAMBDA)
						: 0.0);
	}

	/** q_i(lambda) = 1 + lambda (c_i - 1); lambda zero is uniform SAH. */
	public double constructionWeight(double measuredWeight) {
		return mode.usesPrimitiveWeights() ? 1.0 + lambda * (measuredWeight - 1.0) : 1.0;
	}

	public String summary() {
		return String.format(Locale.ROOT,
				"mode=%s,leafSize=%d,interiorTraversal=%.6f,sphere=%.6f,box=%.6f,affineSphere=%.6f,affineBox=%.6f,lambda=%.6f",
				mode, leafSize, traversalWeight, sphereWeight, boxWeight, affineSphereWeight,
				affineBoxWeight, lambda);
	}

	private static void checkPositive(String label, double weight) {
		if (!(weight > 0.0) || !Double.isFinite(weight)) {
			throw new IllegalArgumentException("Invalid " + label + " weight: " + weight);
		}
	}

	private static int readPositiveInt(String key, int fallback) {
		String raw = System.getProperty(key);
		if (raw == null) return fallback;
		try {
			int value = Integer.parseInt(raw.trim());
			if (value <= 0) {
				throw new IllegalArgumentException(key + " must be positive.");
			}
			return value;
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException(
					key + " must be a positive integer.", error);
		}
	}

	private static double readPositiveDouble(String key, double fallback) {
		String raw = System.getProperty(key);
		if (raw == null) return fallback;
		try {
			double value = Double.parseDouble(raw.trim());
			if (!(value > 0.0) || !Double.isFinite(value)) {
				throw new IllegalArgumentException(
						key + " must be finite and positive.");
			}
			return value;
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException(
					key + " must be a finite positive number.", error);
		}
	}

	private static double readNonNegativeDouble(String key, double fallback) {
		String raw = System.getProperty(key);
		if (raw == null) return fallback;
		try {
			double value = Double.parseDouble(raw.trim());
			if (value < 0.0 || !Double.isFinite(value)) {
				throw new IllegalArgumentException(
						key + " must be finite and nonnegative.");
			}
			return value;
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException(
					key + " must be a finite nonnegative number.", error);
		}
	}
}
