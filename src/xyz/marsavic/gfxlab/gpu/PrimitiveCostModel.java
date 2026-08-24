package xyz.marsavic.gfxlab.gpu;

/** Sphere-normalized costs obtained by the primitive calibration campaign. */
public final class PrimitiveCostModel {
	public static final String CALIBRATION_GPU_NAME = "NVIDIA GeForce RTX 3080";
	public static final String CALIBRATION_GPU_COMPUTE_CAPABILITY = "compute_86";
	public static final String CALIBRATION_NVIDIA_DRIVER_VERSION = "610.47";
	public static final String CALIBRATION_APPLIED_IDENTITY_SHA256 =
			"631fed4c77a8ca2de8966a084677a16e3787319319cef3a6a97948f37841ad72";

	public static final double SPHERE = 1.000000000;
	public static final double BOX = 1.288951704;
	public static final double AFFINE_SPHERE = 2.552743938;
	public static final double AFFINE_BOX = 2.728115282;
	public static final double PLANE = 2.860319327;
	public static final double NODE_AABB = 0.507189194;

	/** Cost of testing both child bounds and ordering the surviving children. */
	public static final double INTERIOR_TRAVERSAL = 1.275576713;

	public static void validate() {
		if (SPHERE != 1.0 || !positive(BOX) || !positive(AFFINE_SPHERE)
				|| !positive(AFFINE_BOX) || !positive(PLANE)
				|| !positive(NODE_AABB) || !positive(INTERIOR_TRAVERSAL)) {
			throw new IllegalStateException("Primitive-cost calibration is invalid.");
		}
	}

	private static boolean positive(double value) {
		return Double.isFinite(value) && value > 0.0;
	}

	private PrimitiveCostModel() { }
}
