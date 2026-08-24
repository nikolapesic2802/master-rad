package xyz.marsavic.gfxlab.gpu;

/** BVH construction modes compared in the thesis. */
public enum BvhBuildMode {
	UNIFORM_SAH,
	WEIGHTED_SAH,
	PER_TYPE_SAH,
	PER_TYPE_WEIGHTED_SAH,
	UNIFORM_SBVH,
	WEIGHTED_SBVH,
	SAH_ROTATIONS,
	WEIGHTED_SAH_ROTATIONS;

	public boolean usesPrimitiveWeights() {
		return switch (this) {
			case WEIGHTED_SAH, PER_TYPE_WEIGHTED_SAH, WEIGHTED_SBVH,
					WEIGHTED_SAH_ROTATIONS -> true;
			default -> false;
		};
	}

	public BvhBuildMode withPrimitiveWeights(boolean weighted) {
		return switch (this) {
			case UNIFORM_SAH, WEIGHTED_SAH -> weighted ? WEIGHTED_SAH : UNIFORM_SAH;
			case PER_TYPE_SAH, PER_TYPE_WEIGHTED_SAH ->
					weighted ? PER_TYPE_WEIGHTED_SAH : PER_TYPE_SAH;
			case UNIFORM_SBVH, WEIGHTED_SBVH -> weighted ? WEIGHTED_SBVH : UNIFORM_SBVH;
			case SAH_ROTATIONS, WEIGHTED_SAH_ROTATIONS ->
					weighted ? WEIGHTED_SAH_ROTATIONS : SAH_ROTATIONS;
		};
	}

	public boolean usesPerTypeTrees() {
		return this == PER_TYPE_SAH || this == PER_TYPE_WEIGHTED_SAH;
	}

	public boolean usesSpatialSplits() {
		return this == UNIFORM_SBVH || this == WEIGHTED_SBVH;
	}

	public boolean usesRotations() {
		return this == SAH_ROTATIONS || this == WEIGHTED_SAH_ROTATIONS;
	}
}
