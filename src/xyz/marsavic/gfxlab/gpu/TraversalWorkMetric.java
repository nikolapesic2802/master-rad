package xyz.marsavic.gfxlab.gpu;

/**
 * Explanatory closest-hit work proxy: one empirical term for every measured
 * AABB test and one for every measured primitive intersection.
 *
 * <p>The metric intentionally excludes shading, path generation, memory
 * behaviour and SIMT divergence. Raw root, node and leaf-kind counters remain
 * useful diagnostics, but are not separate cost terms.</p>
 */
public final class TraversalWorkMetric {
	private TraversalWorkMetric() { }

	public static double cost(
			long aabbTests,
			long sphereTests,
			long boxTests,
			long planeTests,
			long affineSphereTests,
			long affineBoxTests
	) {
		return PrimitiveCostModel.NODE_AABB * aabbTests
				+ PrimitiveCostModel.SPHERE * sphereTests
				+ PrimitiveCostModel.BOX * boxTests
				+ PrimitiveCostModel.PLANE * planeTests
				+ PrimitiveCostModel.AFFINE_SPHERE * affineSphereTests
				+ PrimitiveCostModel.AFFINE_BOX * affineBoxTests;
	}

	public static double cost(GpuRayTracer.FrameStats stats) {
		return cost(
				stats.aabbTests(),
				stats.sphereTests(),
				stats.boxTests(),
				stats.planeTests(),
				stats.affineSphereTests(),
				stats.affineBoxTests());
	}

}
