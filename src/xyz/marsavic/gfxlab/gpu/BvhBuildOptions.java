package xyz.marsavic.gfxlab.gpu;

/** Tuning inputs shared by SBVH and the local-rotation builders. */
public record BvhBuildOptions(
		int spatialBins,
		double maxReferenceMultiplier,
		int maxSplitsPerPrimitive,
		int minSpatialReferences,
		double spatialOverlapThreshold,
		int rotationPasses
) {
	public BvhBuildOptions {
		if (spatialBins < 2) throw new IllegalArgumentException("spatialBins must be at least two.");
		if (maxReferenceMultiplier < 1.0 || !Double.isFinite(maxReferenceMultiplier)) {
			throw new IllegalArgumentException(
					"maxReferenceMultiplier must be finite and at least one.");
		}
		if (maxSplitsPerPrimitive < 1) {
			throw new IllegalArgumentException("maxSplitsPerPrimitive must be positive.");
		}
		if (minSpatialReferences < 2) {
			throw new IllegalArgumentException("minSpatialReferences must be at least two.");
		}
		if (spatialOverlapThreshold < 0.0 || !Double.isFinite(spatialOverlapThreshold)) {
			throw new IllegalArgumentException(
					"spatialOverlapThreshold must be finite and non-negative.");
		}
		if (rotationPasses < 1) throw new IllegalArgumentException("rotationPasses must be positive.");
	}

	public static BvhBuildOptions defaults() {
		return new BvhBuildOptions(128, 2.0, 8, 16, 1.0e-5, 4);
	}

}
