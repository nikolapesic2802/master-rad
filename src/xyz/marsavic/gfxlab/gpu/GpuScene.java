package xyz.marsavic.gfxlab.gpu;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact scene description transferred to the CUDA path tracer.
 */
public final class GpuScene {
	public static final double MAX_AFFINE_SPECTRAL_CONDITION = 65_536.0;
	public static final float MIN_PACKED_PRIMITIVE_EXTENT =
			(float) Math.sqrt(Float.MIN_NORMAL);
	private static final double AFFINE_CONDITION_ESTIMATE_RELATIVE_SLACK = 5.0e-4;

	public static final int FLOATS_PER_MATERIAL = 13;           // diffuse(3) + reflective(3) + refractive(3) + emittance(3) + ior
	public static final int FLOATS_PER_SPHERE = 5;              // center.xyz + radius + material index
	public static final int FLOATS_PER_BOX = 7;                 // min.xyz + max.xyz + material index
	public static final int FLOATS_PER_PLANE = 26;
	public static final int FLOATS_PER_AFFINE_SPHERE = 22;      // inverseLinear(9) + worldCenter(3), interleaved + inverseTransposeLinear(9) + material
	public static final int FLOATS_PER_AFFINE_BOX = 22;         // inverseLinear(9) + worldCenter(3), interleaved + inverseTransposeLinear(9) + material
	public static final int FLOATS_PER_BVH_NODE = 6;            // min.xyz + max.xyz
	public static final int INTS_PER_BVH_NODE = 4;              // left/kind, right, primitiveStart, primitiveCount

	private static final int REF_TYPE_MASK = 0xC0000000;
	private static final int REF_INDEX_MASK = 0x3FFFFFFF;
	private static final int REF_TYPE_SPHERE = 0x00000000;
	private static final int REF_TYPE_BOX = 0x40000000;
	private static final int REF_TYPE_AFFINE_SPHERE = 0x80000000;
	private static final int REF_TYPE_AFFINE_BOX = 0xC0000000;
	private static final int BVH_LEAF_KIND_MIXED = -1;
	private static final int BVH_LEAF_KIND_SPHERE = -2;
	private static final int BVH_LEAF_KIND_BOX = -3;
	private static final int BVH_LEAF_KIND_AFFINE_SPHERE = -4;
	private static final int BVH_LEAF_KIND_AFFINE_BOX = -5;

	public record MaterialData(Color diffuse,
	                           Color reflective,
	                           Color refractive,
	                           double refractiveIndex,
	                           Color emittance) {

		public static MaterialData from(Material material) {
			return new MaterialData(
					material.diffuse(),
					material.reflective(),
					material.refractive(),
					material.refractiveIndex(),
					material.emittance()
			);
		}
	}

	private record Sphere(Vec3 center, double radius, int materialIndex) { }
	private record Box(Vec3 min, Vec3 max, int materialIndex) { }
	private record Plane(Vec3 normal, double planeOffset, int materialIndex) { }
	private record AffineSphere(Affine inverse, Affine inverseTranspose, Vec3 worldCenter,
	                            Vec3 boundsMin, Vec3 boundsMax, int materialIndex) { }
	private record AffineBox(Affine inverse, Affine inverseTranspose, Vec3 worldCenter,
	                         Vec3 boundsMin, Vec3 boundsMax, int materialIndex) { }

	private record Aabb(Vec3 min, Vec3 max) {
		Aabb union(Aabb other) { return new Aabb(Vec3.min(min, other.min), Vec3.max(max, other.max)); }
		double surfaceArea() {
			Vec3 d = max.sub(min);
			return 2.0 * (d.x() * d.y() + d.x() * d.z() + d.y() * d.z());
		}
	}

	private record BvhPrimitive(int encodedRef, int order, Aabb bounds, double measuredWeight) {
		Vec3 centroid() { return bounds.min().add(bounds.max()).mul(0.5); }
	}
	private record SpatialReference(
			int encodedRef,
			int order,
			Aabb bounds,
			double measuredWeight,
			int splitDepth
	) {
		Vec3 centroid() { return bounds.min().add(bounds.max()).mul(0.5); }
		SpatialReference clipped(Aabb clippedBounds) {
			return new SpatialReference(
					encodedRef, order, clippedBounds, measuredWeight, splitDepth + 1);
		}
		BvhPrimitive primitive() {
			return new BvhPrimitive(encodedRef, order, bounds, measuredWeight);
		}
	}
	private record BvhNode(Aabb bounds, BvhNode left, BvhNode right, int start, int end) {
		boolean isLeaf() { return left == null; }
	}

	private record BvhSplit(BvhPrimitive[] sorted, int leftCount, double cost) { }
	private record BuildSummary(
			int nodeCount,
			int leafCount,
			int maxDepth,
			int minLeafOccupancy,
			int maxLeafOccupancy,
			int spatialSplitCount,
			int rotationCount
	) { }
	private record TreeBuild(BvhPrimitive[] primitives, BvhNode root, BuildSummary summary) { }

	public record BvhStats(
			int nodeCount,
			int rootCount,
			int leafCount,
			int maxDepth,
			int primitiveRefCount,
			int leafSize,
			String buildMode,
			double generalizedSahCost,
			double uniformSahCost,
			double weightedSahCost,
			long bytes,
			int minLeafOccupancy,
			int maxLeafOccupancy,
			double meanLeafOccupancy,
			long buildNanos,
			int originalPrimitiveCount,
			int duplicateReferenceCount,
			int spatialSplitCount,
			int rotationCount
	) { }

	private record BvhData(
			float[] nodeBounds,
			int[] nodeData,
			int[] primitiveRefs,
			int[] rootIndices,
			BvhStats stats
	) { }

	private final BvhBuildConfig bvhBuildConfig;
	private final BvhBuildOptions bvhBuildOptions;

	private final List<Sphere> spheres = new ArrayList<>();
	private final List<Box> boxes = new ArrayList<>();
	private final List<Plane> planes = new ArrayList<>();
	private final List<AffineSphere> affineSpheres = new ArrayList<>();
	private final List<AffineBox> affineBoxes = new ArrayList<>();
	private final List<MaterialData> materials = new ArrayList<>();
	private final Map<MaterialData, Integer> materialIndex = new LinkedHashMap<>();

	private Vec3 background = Vec3.ZERO;

	private float[] materialDataCache;
	private float[] sphereDataCache;
	private float[] boxDataCache;
	private float[] planeDataCache;
	private float[] affineSphereDataCache;
	private float[] affineBoxDataCache;
	private BvhData bvhDataCache;
	private long revision;

	public GpuScene(BvhBuildConfig bvhBuildConfig, BvhBuildOptions bvhBuildOptions) {
		if (bvhBuildConfig == null) throw new IllegalArgumentException("BVH build config is required.");
		if (bvhBuildOptions == null) throw new IllegalArgumentException("BVH build options are required.");
		this.bvhBuildConfig = bvhBuildConfig;
		this.bvhBuildOptions = bvhBuildOptions;
	}

	public List<Sphere> spheres() {
		return Collections.unmodifiableList(spheres);
	}

	public List<Box> boxes() {
		return Collections.unmodifiableList(boxes);
	}

	public List<Plane> planes() {
		return Collections.unmodifiableList(planes);
	}

	public List<AffineSphere> affineSpheres() {
		return Collections.unmodifiableList(affineSpheres);
	}

	public List<AffineBox> affineBoxes() {
		return Collections.unmodifiableList(affineBoxes);
	}

	public Vec3 background() {
		return background;
	}

	long revision() {
		return revision;
	}

	public GpuScene setBackground(Vec3 color) {
		background = color;
		return this;
	}

	public GpuScene addSphere(Vec3 center, double radius, MaterialData material) {
		requireFinite(center, "Sphere center");
		requireFloatRepresentable(center, "Sphere center");
		float packedRadius = (float) radius;
		if (!(radius > 0.0) || !Double.isFinite(radius)
				|| !(packedRadius >= MIN_PACKED_PRIMITIVE_EXTENT)
				|| !Float.isFinite(packedRadius)) {
			throw new IllegalArgumentException(
					"Sphere radius must remain finite and at least "
							+ MIN_PACKED_PRIMITIVE_EXTENT + " in the GPU float payload.");
		}
		requirePackedCenteredBounds(
				center, packedRadius, packedRadius, packedRadius, "Sphere");
		spheres.add(new Sphere(center, radius, registerMaterial(material)));
		invalidateCaches();
		return this;
	}

	public GpuScene addBox(Vec3 min, Vec3 max, MaterialData material) {
		requireFinite(min, "Box endpoint");
		requireFinite(max, "Box endpoint");
		Vec3 canonicalMin = Vec3.min(min, max);
		Vec3 canonicalMax = Vec3.max(min, max);
		requireNondegenerateFloatBounds(canonicalMin, canonicalMax, "Box");
		boxes.add(new Box(canonicalMin, canonicalMax,
				registerMaterial(material)));
		invalidateCaches();
		return this;
	}

	public GpuScene addPlane(Vec3 point, Vec3 normal, MaterialData material) {
		requireFinite(point, "Plane point");
		requireFinite(normal, "Plane normal");
		requireFloatRepresentable(point, "Plane point");
		requireFloatRepresentable(normal, "Plane normal");
		double normalLengthSquared = normal.lengthSquared();
		if (!(normalLengthSquared > 0.0) || !Double.isFinite(normalLengthSquared)) {
			throw new IllegalArgumentException("Plane normal must be non-zero.");
		}
		Vec3 normalUnit = normal.normalized_();
		double planeOffset = -normalUnit.dot(point);
		if (!Float.isFinite((float) planeOffset)) {
			throw new IllegalArgumentException(
					"Plane offset must remain finite in the GPU float payload.");
		}
		planes.add(new Plane(normalUnit, planeOffset, registerMaterial(material)));
		invalidateCaches();
		return this;
	}

	private static void requireFinite(Vec3 value, String label) {
		if (!Double.isFinite(value.x())
				|| !Double.isFinite(value.y())
				|| !Double.isFinite(value.z())) {
			throw new IllegalArgumentException(label + " must be finite.");
		}
	}

	private static void requireFloatRepresentable(Vec3 value, String label) {
		if (!Float.isFinite((float) value.x())
				|| !Float.isFinite((float) value.y())
				|| !Float.isFinite((float) value.z())) {
			throw new IllegalArgumentException(
					label + " must remain finite in the GPU float payload.");
		}
	}

	private static Vec3 packVec3(Vec3 value, String label) {
		requireFinite(value, label);
		requireFloatRepresentable(value, label);
		return Vec3.xyz(
				(float) value.x(),
				(float) value.y(),
				(float) value.z());
	}

	private static void requireNondegenerateFloatBounds(
			Vec3 min,
			Vec3 max,
			String label
	) {
		requireFinite(min, label + " minimum bound");
		requireFinite(max, label + " maximum bound");
		requireFloatRepresentable(min, label + " minimum bound");
		requireFloatRepresentable(max, label + " maximum bound");
		if (!((float) min.x() < (float) max.x())
				|| !((float) min.y() < (float) max.y())
				|| !((float) min.z() < (float) max.z())
				|| !((float) max.x() - (float) min.x() >= MIN_PACKED_PRIMITIVE_EXTENT)
				|| !((float) max.y() - (float) min.y() >= MIN_PACKED_PRIMITIVE_EXTENT)
				|| !((float) max.z() - (float) min.z() >= MIN_PACKED_PRIMITIVE_EXTENT)) {
			throw new IllegalArgumentException(
					label + " bounds must retain a supported positive extent on every axis "
							+ "after float packing.");
		}
	}

	private static void requirePackedCenteredBounds(
			Vec3 center,
			float extentX,
			float extentY,
			float extentZ,
			String label
	) {
		float cx = (float) center.x();
		float cy = (float) center.y();
		float cz = (float) center.z();
		Vec3 min = Vec3.xyz(cx - extentX, cy - extentY, cz - extentZ);
		Vec3 max = Vec3.xyz(cx + extentX, cy + extentY, cz + extentZ);
		requireNondegenerateFloatBounds(min, max, label);
	}

	private static void requireAffinePayload(
			Affine inverse,
			Affine inverseTranspose,
			Vec3 worldCenter,
			Vec3 boundsMin,
			Vec3 boundsMax,
			String label
	) {
		requireAffineMatrices(inverse, inverseTranspose, label);
		requireFinite(worldCenter, label + " world center");
		requireFloatRepresentable(worldCenter, label + " world center");
		Vec3 canonicalMin = Vec3.min(boundsMin, boundsMax);
		Vec3 canonicalMax = Vec3.max(boundsMin, boundsMax);
		requireNondegenerateFloatBounds(canonicalMin, canonicalMax, label);
		if ((float) worldCenter.x() < (float) canonicalMin.x()
				|| (float) worldCenter.x() > (float) canonicalMax.x()
				|| (float) worldCenter.y() < (float) canonicalMin.y()
				|| (float) worldCenter.y() > (float) canonicalMax.y()
				|| (float) worldCenter.z() < (float) canonicalMin.z()
				|| (float) worldCenter.z() > (float) canonicalMax.z()) {
			throw new IllegalArgumentException(
					label + " bounds must enclose the packed world center.");
		}
	}

	static void requireAffineMatrices(
			Affine inverse,
			Affine inverseTranspose,
			String label
	) {
		double condition = requireAffineMatricesCore(inverse, inverseTranspose, label);
		if (condition > MAX_AFFINE_SPECTRAL_CONDITION
				* (1.0 + AFFINE_CONDITION_ESTIMATE_RELATIVE_SLACK)) {
			throw new IllegalArgumentException(
					label + " exceeds the empirically validated float conditioning limit (kappa_2 <= "
							+ MAX_AFFINE_SPECTRAL_CONDITION + ").");
		}
	}

	private static double requireAffineMatricesCore(
			Affine inverse,
			Affine inverseTranspose,
			String label
	) {
		float[] inverseLinear = packedLinear(inverse, label + " inverse matrix");
		float[] normalLinear = packedLinear(
				inverseTranspose, label + " normal matrix");
		double determinant = determinant3(inverseLinear);
		if (determinant == 0.0 || !Double.isFinite(determinant)) {
			throw new IllegalArgumentException(
					label + " inverse matrix must be nonsingular after float packing.");
		}
		int[] transpose = {0, 3, 6, 1, 4, 7, 2, 5, 8};
		for (int i = 0; i < transpose.length; i++) {
			if (normalLinear[i] != inverseLinear[transpose[i]]) {
				throw new IllegalArgumentException(
						label + " normal matrix must be the transpose of its inverse matrix.");
			}
		}
		double condition = spectralConditionNumber(inverseLinear);
		if (!Double.isFinite(condition)) {
			throw new IllegalArgumentException(label + " has an invalid condition number");
		}
		return condition;
	}

	private static float[] packedLinear(Affine matrix, String label) {
		float[] values = {
				(float) matrix.m00(), (float) matrix.m01(), (float) matrix.m02(),
				(float) matrix.m10(), (float) matrix.m11(), (float) matrix.m12(),
				(float) matrix.m20(), (float) matrix.m21(), (float) matrix.m22()
		};
		for (float value : values) {
			if (!Float.isFinite(value)) {
				throw new IllegalArgumentException(
						label + " must remain finite in the GPU float payload.");
			}
		}
		return values;
	}

	private static double determinant3(float[] matrix) {
		return (double) matrix[0]
				* ((double) matrix[4] * matrix[8] - (double) matrix[5] * matrix[7])
				- (double) matrix[1]
				* ((double) matrix[3] * matrix[8] - (double) matrix[5] * matrix[6])
				+ (double) matrix[2]
				* ((double) matrix[3] * matrix[7] - (double) matrix[4] * matrix[6]);
	}

	private static double spectralConditionNumber(float[] matrix) {
		double scale = 0.0;
		for (float value : matrix) {
			scale = Math.max(scale, Math.abs(value));
		}
		if (!(scale > 0.0) || !Double.isFinite(scale)) {
			return Double.POSITIVE_INFINITY;
		}
		double[] a = new double[matrix.length];
		for (int i = 0; i < matrix.length; i++) {
			a[i] = matrix[i] / scale;
		}
		double s00 = a[0] * a[0] + a[3] * a[3] + a[6] * a[6];
		double s01 = a[0] * a[1] + a[3] * a[4] + a[6] * a[7];
		double s02 = a[0] * a[2] + a[3] * a[5] + a[6] * a[8];
		double s11 = a[1] * a[1] + a[4] * a[4] + a[7] * a[7];
		double s12 = a[1] * a[2] + a[4] * a[5] + a[7] * a[8];
		double s22 = a[2] * a[2] + a[5] * a[5] + a[8] * a[8];
		double offDiagonalSquared = s01 * s01 + s02 * s02 + s12 * s12;
		double largest;
		double smallest;
		if (offDiagonalSquared == 0.0) {
			largest = Math.max(s00, Math.max(s11, s22));
			smallest = Math.min(s00, Math.min(s11, s22));
		} else {
			double mean = (s00 + s11 + s22) / 3.0;
			double variance = (s00 - mean) * (s00 - mean)
					+ (s11 - mean) * (s11 - mean)
					+ (s22 - mean) * (s22 - mean)
					+ 2.0 * offDiagonalSquared;
			double p = Math.sqrt(variance / 6.0);
			double b00 = (s00 - mean) / p;
			double b01 = s01 / p;
			double b02 = s02 / p;
			double b11 = (s11 - mean) / p;
			double b12 = s12 / p;
			double b22 = (s22 - mean) / p;
			double determinant = b00 * (b11 * b22 - b12 * b12)
					- b01 * (b01 * b22 - b12 * b02)
					+ b02 * (b01 * b12 - b11 * b02);
			double angle = Math.acos(Math.max(-1.0, Math.min(1.0, determinant / 2.0))) / 3.0;
			largest = mean + 2.0 * p * Math.cos(angle);
			smallest = mean + 2.0 * p * Math.cos(angle + 2.0 * Math.PI / 3.0);
		}
		if (!(smallest > 0.0) || !Double.isFinite(largest)) {
			return Double.POSITIVE_INFINITY;
		}
		return Math.sqrt(largest / smallest);
	}

	public GpuScene addAffineSphere(Affine inverse, Affine inverseTranspose, Vec3 worldCenter,
	                                Vec3 boundsMin, Vec3 boundsMax, MaterialData material) {
		Vec3 packedCenter = packVec3(worldCenter, "Affine sphere world center");
		requireAffinePayload(inverse, inverseTranspose, packedCenter, boundsMin, boundsMax,
				"Affine sphere");
		affineSpheres.add(new AffineSphere(
				inverse, inverseTranspose, packedCenter, Vec3.min(boundsMin, boundsMax),
				Vec3.max(boundsMin, boundsMax), registerMaterial(material)));
		invalidateCaches();
		return this;
	}

	public GpuScene addAffineBox(Affine inverse, Affine inverseTranspose, Vec3 worldCenter,
	                             Vec3 boundsMin, Vec3 boundsMax, MaterialData material) {
		Vec3 packedCenter = packVec3(worldCenter, "Affine box world center");
		requireAffinePayload(inverse, inverseTranspose, packedCenter, boundsMin, boundsMax,
				"Affine box");
		affineBoxes.add(new AffineBox(
				inverse, inverseTranspose, packedCenter, Vec3.min(boundsMin, boundsMax),
				Vec3.max(boundsMin, boundsMax), registerMaterial(material)));
		invalidateCaches();
		return this;
	}

	public GpuScene addAffineSphere(Affine inverse, Affine inverseTranspose,
	                                Vec3 boundsMin, Vec3 boundsMax, MaterialData material) {
		return addAffineSphere(inverse, inverseTranspose, affineWorldCenter(inverse),
				boundsMin, boundsMax, material);
	}

	public GpuScene addAffineBox(Affine inverse, Affine inverseTranspose,
	                             Vec3 boundsMin, Vec3 boundsMax, MaterialData material) {
		return addAffineBox(inverse, inverseTranspose, affineWorldCenter(inverse),
				boundsMin, boundsMax, material);
	}

	public int materialCount() {
		return materials.size();
	}

	public int materialIndexOf(MaterialData material) {
		Integer index = materialIndex.get(material);
		return index == null ? -1 : index;
	}

	public int primitiveCount() {
		return spheres.size() + boxes.size() + planes.size()
				+ affineSpheres.size() + affineBoxes.size();
	}

	public float[] materialData() {
		if (materialDataCache == null) {
			float[] data = new float[materials.size() * FLOATS_PER_MATERIAL];
			for (int i = 0; i < materials.size(); i++) {
				writeMaterial(data, i * FLOATS_PER_MATERIAL, materials.get(i));
			}
			materialDataCache = data;
		}
		return materialDataCache;
	}

	public float[] sphereData() {
		if (sphereDataCache == null) {
			float[] data = new float[spheres.size() * FLOATS_PER_SPHERE];
			for (int i = 0; i < spheres.size(); i++) {
				Sphere sphere = spheres.get(i);
				int offset = i * FLOATS_PER_SPHERE;
				writeVec3(data, offset, sphere.center());
				data[offset + 3] = (float) sphere.radius();
				data[offset + 4] = sphere.materialIndex();
			}
			sphereDataCache = data;
		}
		return sphereDataCache;
	}

	public float[] boxData() {
		if (boxDataCache == null) {
			float[] data = new float[boxes.size() * FLOATS_PER_BOX];
			for (int i = 0; i < boxes.size(); i++) {
				Box box = boxes.get(i);
				int offset = i * FLOATS_PER_BOX;
				writeVec3(data, offset, box.min());
				writeVec3(data, offset + 3, box.max());
				data[offset + 6] = box.materialIndex();
			}
			boxDataCache = data;
		}
		return boxDataCache;
	}

	public float[] planeData() {
		if (planeDataCache == null) {
			float[] data = new float[planes.size() * FLOATS_PER_PLANE];
			for (int i = 0; i < planes.size(); i++) {
				Plane plane = planes.get(i);
				int offset = i * FLOATS_PER_PLANE;
				writeVec3(data, offset, plane.normal());
				data[offset + 3] = (float) plane.planeOffset();
				int gridOffset = offset + 18;
				data[gridOffset] = 0.0f;
				data[gridOffset + 1] = 0.0f;
				data[gridOffset + 2] = 0.0f;
				data[gridOffset + 3] = 0.0f;
				data[gridOffset + 4] = 0.0f;
				data[gridOffset + 5] = plane.materialIndex();
				data[gridOffset + 6] = -1.0f;
				data[gridOffset + 7] = -1.0f;
			}
			planeDataCache = data;
		}
		return planeDataCache;
	}

	public float[] affineSphereData() {
		if (affineSphereDataCache == null) {
			float[] data = new float[affineSpheres.size() * FLOATS_PER_AFFINE_SPHERE];
			for (int i = 0; i < affineSpheres.size(); i++) {
				AffineSphere sphere = affineSpheres.get(i);
				int offset = i * FLOATS_PER_AFFINE_SPHERE;
				writeObjectRelativeAffine(
						data, offset, sphere.inverse(), sphere.worldCenter());
				writeAffineLinear(data, offset + 12, sphere.inverseTranspose());
				data[offset + 21] = sphere.materialIndex();
			}
			affineSphereDataCache = data;
		}
		return affineSphereDataCache;
	}

	public float[] affineBoxData() {
		if (affineBoxDataCache == null) {
			float[] data = new float[affineBoxes.size() * FLOATS_PER_AFFINE_BOX];
			for (int i = 0; i < affineBoxes.size(); i++) {
				AffineBox box = affineBoxes.get(i);
				int offset = i * FLOATS_PER_AFFINE_BOX;
				writeObjectRelativeAffine(
						data, offset, box.inverse(), box.worldCenter());
				writeAffineLinear(data, offset + 12, box.inverseTranspose());
				data[offset + 21] = box.materialIndex();
			}
			affineBoxDataCache = data;
		}
		return affineBoxDataCache;
	}

	public float[] bvhNodeBoundsData() { return bvhData().nodeBounds(); }
	public int[] bvhNodeData() { return bvhData().nodeData(); }
	public int[] bvhPrimitiveRefs() { return bvhData().primitiveRefs(); }
	public int[] bvhRootIndices() { return bvhData().rootIndices(); }
	public BvhStats bvhStats() { return bvhData().stats(); }

	private BvhData bvhData() {
		if (bvhDataCache == null) bvhDataCache = buildBvhData();
		return bvhDataCache;
	}

	private BvhData buildBvhData() {
		long buildStarted = System.nanoTime();
		int leafSize = bvhBuildConfig.leafSize();
		int count = spheres.size() + boxes.size() + affineSpheres.size() + affineBoxes.size();
		if (count == 0) {
			return new BvhData(new float[0], new int[0], new int[0], new int[0],
					new BvhStats(0, 0, 0, 0, 0, leafSize, bvhBuildConfig.mode().name(),
							0.0, 0.0, 0.0, 0L, 0, 0, 0.0,
							System.nanoTime() - buildStarted, 0, 0, 0,
							0));
		}

		BvhPrimitive[] primitives = new BvhPrimitive[count];
		int write = 0;
		int order = 0;
		for (int i = 0; i < spheres.size(); i++, order++) {
			Sphere sphere = spheres.get(i);
			primitives[write++] = new BvhPrimitive(encodePrimitiveRef(REF_TYPE_SPHERE, i), order,
					conservativeSphereBounds(sphere.center(), sphere.radius()), bvhBuildConfig.sphereWeight());
		}
		for (int i = 0; i < boxes.size(); i++, order++) {
			Box box = boxes.get(i);
			primitives[write++] = new BvhPrimitive(encodePrimitiveRef(REF_TYPE_BOX, i), order,
					conservativePackedBounds(box.min(), box.max()),
					bvhBuildConfig.boxWeight());
		}
		for (int i = 0; i < affineSpheres.size(); i++, order++) {
			AffineSphere primitive = affineSpheres.get(i);
			primitives[write++] = new BvhPrimitive(encodePrimitiveRef(REF_TYPE_AFFINE_SPHERE, i), order,
					conservativeBounds(primitive.boundsMin(), primitive.boundsMax()), bvhBuildConfig.affineSphereWeight());
		}
		for (int i = 0; i < affineBoxes.size(); i++, order++) {
			AffineBox primitive = affineBoxes.get(i);
			primitives[write++] = new BvhPrimitive(encodePrimitiveRef(REF_TYPE_AFFINE_BOX, i), order,
					conservativeBounds(primitive.boundsMin(), primitive.boundsMax()), bvhBuildConfig.affineBoxWeight());
		}
		List<TreeBuild> trees = buildTrees(primitives);
		int nodeCount = trees.stream().mapToInt(tree -> tree.summary().nodeCount()).sum();
		int leafCount = trees.stream().mapToInt(tree -> tree.summary().leafCount()).sum();
		int maxDepth = trees.stream().mapToInt(tree -> tree.summary().maxDepth()).max().orElse(0);
		int referenceCount = trees.stream().mapToInt(tree -> tree.primitives().length).sum();
		if (nodeCount != 2 * leafCount - trees.size()) {
			throw new IllegalStateException(
					"A binary BVH forest must contain 2 * leafCount - rootCount nodes.");
		}
		float[] nodeBounds = new float[nodeCount * FLOATS_PER_BVH_NODE];
		int[] nodeData = new int[nodeCount * INTS_PER_BVH_NODE];
		int[] refs = new int[referenceCount];
		int[] roots = new int[trees.size()];
		int[] nextNode = {0};
		int[] nextRef = {0};
		double uniformCost = 0.0;
		double weightedSahCost = 0.0;
		double generalizedCost = 0.0;
		for (int i = 0; i < trees.size(); i++) {
			TreeBuild tree = trees.get(i);
			roots[i] = flattenBvh(
					tree.root(), tree.primitives(), nodeBounds, nodeData, refs, nextNode, nextRef);
			uniformCost += sahCost(
					tree.root(), tree.primitives(), false, bvhBuildConfig.traversalWeight());
			weightedSahCost += sahCost(
					tree.root(), tree.primitives(), true, bvhBuildConfig.traversalWeight());
			generalizedCost += constructionSahCost(
					tree.root(), tree.primitives(), bvhBuildConfig);
		}
		if (roots[0] != 0 || nextNode[0] != nodeCount || nextRef[0] != refs.length) {
			throw new IllegalStateException("Packed BVH traversal payload is incomplete.");
		}
		long bytes = (long) nodeBounds.length * Float.BYTES
				+ (long) (nodeData.length + refs.length + roots.length) * Integer.BYTES;
		int minLeafOccupancy = trees.stream()
				.mapToInt(tree -> tree.summary().minLeafOccupancy()).min().orElse(0);
		int maxLeafOccupancy = trees.stream()
				.mapToInt(tree -> tree.summary().maxLeafOccupancy()).max().orElse(0);
		double meanLeafOccupancy = leafCount == 0 ? 0.0 : refs.length / (double) leafCount;
		int spatialSplitCount = trees.stream()
				.mapToInt(tree -> tree.summary().spatialSplitCount()).sum();
		int rotationCount = trees.stream()
				.mapToInt(tree -> tree.summary().rotationCount()).sum();
		return new BvhData(nodeBounds, nodeData, refs, roots,
				new BvhStats(nodeCount, roots.length, leafCount, maxDepth, refs.length, leafSize,
						bvhBuildConfig.mode().name(), generalizedCost, uniformCost, weightedSahCost,
						bytes, minLeafOccupancy, maxLeafOccupancy, meanLeafOccupancy,
						System.nanoTime() - buildStarted, primitives.length,
						refs.length - primitives.length, spatialSplitCount,
						rotationCount));
	}

	private List<TreeBuild> buildTrees(BvhPrimitive[] primitives) {
		if (bvhBuildConfig.mode().usesSpatialSplits()) {
			return List.of(new SpatialBuildState(
					primitives, bvhBuildConfig, bvhBuildOptions).buildTree());
		}
		if (bvhBuildConfig.mode().usesPerTypeTrees()) {
			List<TreeBuild> trees = new ArrayList<>(4);
			for (int type : new int[]{
					REF_TYPE_SPHERE, REF_TYPE_BOX,
					REF_TYPE_AFFINE_SPHERE, REF_TYPE_AFFINE_BOX}) {
				BvhPrimitive[] partition = Arrays.stream(primitives)
						.filter(primitive -> (primitive.encodedRef() & REF_TYPE_MASK) == type)
						.toArray(BvhPrimitive[]::new);
				if (partition.length > 0) trees.add(buildObjectTree(partition));
			}
			return trees;
		}
		return List.of(buildObjectTree(primitives));
	}

	private TreeBuild buildObjectTree(BvhPrimitive[] primitives) {
		BvhBuildState state = new BvhBuildState(primitives, bvhBuildConfig);
		BvhNode root = state.build(0, primitives.length, 0);
		int rotations = 0;
		if (bvhBuildConfig.mode().usesRotations()) {
			RotationResult result = BvhRotator.optimize(
					root, primitives, bvhBuildConfig, bvhBuildOptions.rotationPasses());
			root = result.root();
			rotations = result.rotations();
		}
		return new TreeBuild(primitives, root, summarizeTree(root, 0, rotations));
	}

	private static BuildSummary summarizeTree(BvhNode root, int spatialSplits, int rotations) {
		TreeSummaryAccumulator summary = new TreeSummaryAccumulator();
		summarizeTree(root, 0, summary);
		return new BuildSummary(summary.nodes, summary.leaves, summary.maxDepth,
				summary.minLeafOccupancy == Integer.MAX_VALUE ? 0 : summary.minLeafOccupancy,
				summary.maxLeafOccupancy, spatialSplits, rotations);
	}

	private static void summarizeTree(BvhNode node, int depth, TreeSummaryAccumulator summary) {
		summary.nodes++;
		summary.maxDepth = Math.max(summary.maxDepth, depth);
		if (node.isLeaf()) {
			int occupancy = node.end() - node.start();
			summary.leaves++;
			summary.minLeafOccupancy = Math.min(summary.minLeafOccupancy, occupancy);
			summary.maxLeafOccupancy = Math.max(summary.maxLeafOccupancy, occupancy);
			return;
		}
		summarizeTree(node.left(), depth + 1, summary);
		summarizeTree(node.right(), depth + 1, summary);
	}

	private static final class TreeSummaryAccumulator {
		private int nodes;
		private int leaves;
		private int maxDepth;
		private int minLeafOccupancy = Integer.MAX_VALUE;
		private int maxLeafOccupancy;
	}

	private static int encodePrimitiveRef(int type, int index) {
		if ((type & ~REF_TYPE_MASK) != 0 || (index & ~REF_INDEX_MASK) != 0) {
			throw new IllegalArgumentException("Packed primitive type or index exceeds its assigned bits.");
		}
		return type | index;
	}

	private static int flattenBvh(BvhNode node, BvhPrimitive[] primitives, float[] nodeBounds,
	                              int[] nodeData, int[] refs, int[] nextNode, int[] nextRef) {
		int index = nextNode[0]++;
		int boundsOffset = index * FLOATS_PER_BVH_NODE;
		writeVec3(nodeBounds, boundsOffset, node.bounds().min());
		writeVec3(nodeBounds, boundsOffset + 3, node.bounds().max());
		int dataOffset = index * INTS_PER_BVH_NODE;
		if (node.isLeaf()) {
			nodeData[dataOffset] = leafKind(primitives, node.start(), node.end());
			nodeData[dataOffset + 1] = -1;
			nodeData[dataOffset + 2] = nextRef[0];
			nodeData[dataOffset + 3] = node.end() - node.start();
			for (int i = node.start(); i < node.end(); i++) refs[nextRef[0]++] = primitives[i].encodedRef();
			return index;
		}
		int left = flattenBvh(node.left(), primitives, nodeBounds, nodeData, refs, nextNode, nextRef);
		int right = flattenBvh(node.right(), primitives, nodeBounds, nodeData, refs, nextNode, nextRef);
		nodeData[dataOffset] = left;
		nodeData[dataOffset + 1] = right;
		nodeData[dataOffset + 2] = -1;
		nodeData[dataOffset + 3] = 0;
		return index;
	}

	private static int leafKind(BvhPrimitive[] primitives, int start, int end) {
		int type = primitives[start].encodedRef() & REF_TYPE_MASK;
		for (int i = start + 1; i < end; i++) {
			if ((primitives[i].encodedRef() & REF_TYPE_MASK) != type) return BVH_LEAF_KIND_MIXED;
		}
		return switch (type) {
			case REF_TYPE_SPHERE -> BVH_LEAF_KIND_SPHERE;
			case REF_TYPE_BOX -> BVH_LEAF_KIND_BOX;
			case REF_TYPE_AFFINE_SPHERE -> BVH_LEAF_KIND_AFFINE_SPHERE;
			case REF_TYPE_AFFINE_BOX -> BVH_LEAF_KIND_AFFINE_BOX;
			default -> throw new IllegalStateException("Unknown packed primitive type.");
		};
	}

	private static double sahCost(BvhNode node, BvhPrimitive[] primitives, boolean measuredWeights, double traversalWeight) {
		if (node.isLeaf()) {
			double cost = 0.0;
			for (int i = node.start(); i < node.end(); i++) cost += measuredWeights ? primitives[i].measuredWeight() : 1.0;
			return cost;
		}
		double area = node.bounds().surfaceArea();
		if (!(area > 0.0)) return sahCost(node.left(), primitives, measuredWeights, traversalWeight)
				+ sahCost(node.right(), primitives, measuredWeights, traversalWeight);
		return traversalWeight
				+ node.left().bounds().surfaceArea() / area * sahCost(node.left(), primitives, measuredWeights, traversalWeight)
				+ node.right().bounds().surfaceArea() / area * sahCost(node.right(), primitives, measuredWeights, traversalWeight);
	}

	private static double constructionSahCost(BvhNode node, BvhPrimitive[] primitives, BvhBuildConfig config) {
		if (node.isLeaf()) {
			double cost = 0.0;
			for (int i = node.start(); i < node.end(); i++) {
				cost += config.constructionWeight(primitives[i].measuredWeight());
			}
			return cost;
		}
		double area = node.bounds().surfaceArea();
		if (!(area > 0.0)) return constructionSahCost(node.left(), primitives, config)
				+ constructionSahCost(node.right(), primitives, config);
		return config.traversalWeight()
				+ node.left().bounds().surfaceArea() / area * constructionSahCost(node.left(), primitives, config)
				+ node.right().bounds().surfaceArea() / area * constructionSahCost(node.right(), primitives, config);
	}

	private static Aabb conservativeBounds(Vec3 min, Vec3 max) {
		return new Aabb(
				Vec3.xyz(nextDown2((float) min.x()),
						nextDown2((float) min.y()),
						nextDown2((float) min.z())),
				Vec3.xyz(nextUp2((float) max.x()),
						nextUp2((float) max.y()),
						nextUp2((float) max.z())));
	}

	private int registerMaterial(MaterialData material) {
		Integer existing = materialIndex.get(material);
		if (existing != null) {
			return existing;
		}
		int index = materials.size();
		materials.add(material);
		materialIndex.put(material, index);
		return index;
	}

	private void invalidateCaches() {
		revision++;
		materialDataCache = null;
		sphereDataCache = null;
		boxDataCache = null;
		planeDataCache = null;
		affineSphereDataCache = null;
		affineBoxDataCache = null;
		bvhDataCache = null;
	}

	/**
	 * Ordinary primitives are intersected from separately quantized float
	 * centers and extents. Their BVH bounds must enclose arithmetic on those
	 * packed values, not a once-rounded double center-plus-extent expression.
	 */
	private static Aabb conservativeSphereBounds(Vec3 center, double radius) {
		float cx = (float) center.x();
		float cy = (float) center.y();
		float cz = (float) center.z();
		float r = (float) radius;
		return conservativeFloatBounds(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
	}

	private static Aabb conservativePackedBounds(Vec3 min, Vec3 max) {
		return conservativeFloatBounds((float) min.x(), (float) min.y(), (float) min.z(),
				(float) max.x(), (float) max.y(), (float) max.z());
	}

	private static Aabb conservativeFloatBounds(float minX, float minY, float minZ,
	                                            float maxX, float maxY, float maxZ) {
		return new Aabb(
				Vec3.xyz(nextDown2(minX), nextDown2(minY), nextDown2(minZ)),
				Vec3.xyz(nextUp2(maxX), nextUp2(maxY), nextUp2(maxZ)));
	}

	private static float nextDown2(float value) {
		return Math.nextDown(Math.nextDown(value));
	}

	private static float nextUp2(float value) {
		return Math.nextUp(Math.nextUp(value));
	}

	private static void writeVec3(float[] data, int offset, Vec3 v) {
		data[offset] = (float) v.x();
		data[offset + 1] = (float) v.y();
		data[offset + 2] = (float) v.z();
	}

	private static Vec3 affineWorldCenter(Affine inverse) {
		Vec3 center = inverse.inverse().at(Vec3.ZERO);
		requireFinite(center, "Affine world center");
		requireFloatRepresentable(center, "Affine world center");
		return center;
	}

	/**
	 * Stores the inverse linear transform with the world-space object center in
	 * the three former translation slots. The kernel evaluates M * (p - center),
	 * avoiding catastrophic cancellation between M*p and a large translation
	 * for small affine instances far from the world origin.
	 */
	private static void writeObjectRelativeAffine(float[] data, int offset, Affine affine, Vec3 center) {
		data[offset] = (float) affine.m00();
		data[offset + 1] = (float) affine.m01();
		data[offset + 2] = (float) affine.m02();
		data[offset + 3] = (float) center.x();
		data[offset + 4] = (float) affine.m10();
		data[offset + 5] = (float) affine.m11();
		data[offset + 6] = (float) affine.m12();
		data[offset + 7] = (float) center.y();
		data[offset + 8] = (float) affine.m20();
		data[offset + 9] = (float) affine.m21();
		data[offset + 10] = (float) affine.m22();
		data[offset + 11] = (float) center.z();
	}

	private static void writeAffineLinear(float[] data, int offset, Affine affine) {
		data[offset] = (float) affine.m00();
		data[offset + 1] = (float) affine.m01();
		data[offset + 2] = (float) affine.m02();
		data[offset + 3] = (float) affine.m10();
		data[offset + 4] = (float) affine.m11();
		data[offset + 5] = (float) affine.m12();
		data[offset + 6] = (float) affine.m20();
		data[offset + 7] = (float) affine.m21();
		data[offset + 8] = (float) affine.m22();
	}

	private static void writeMaterial(float[] data, int offset, MaterialData material) {
		writeColor(data, offset, material.diffuse());
		writeColor(data, offset + 3, material.reflective());
		writeColor(data, offset + 6, material.refractive());
		writeColor(data, offset + 9, material.emittance());
		data[offset + 12] = (float) material.refractiveIndex();
	}

	private static void writeColor(float[] data, int offset, Color color) {
		data[offset] = (float) color.r();
		data[offset + 1] = (float) color.g();
		data[offset + 2] = (float) color.b();
	}

	private record RotationResult(BvhNode root, int rotations) { }
	private record RotationPassResult(BvhNode root, int rotations) { }

	/** Deterministic Kensler-style local hill climbing over a completed SAH tree. */
	private static final class BvhRotator {
		private static final double IMPROVEMENT_EPSILON = 1.0e-12;

		private static RotationResult optimize(
				BvhNode root,
				BvhPrimitive[] primitives,
				BvhBuildConfig config,
				int passes
		) {
			int total = 0;
			for (int pass = 0; pass < passes; pass++) {
				RotationPassResult result = optimizePass(root, primitives, config);
				root = result.root();
				total += result.rotations();
				if (result.rotations() == 0) break;
			}
			return new RotationResult(root, total);
		}

		private static RotationPassResult optimizePass(
				BvhNode node,
				BvhPrimitive[] primitives,
				BvhBuildConfig config
		) {
			if (node.isLeaf()) return new RotationPassResult(node, 0);
			RotationPassResult left = optimizePass(node.left(), primitives, config);
			RotationPassResult right = optimizePass(node.right(), primitives, config);
			BvhNode current = join(left.root(), right.root());
			BvhNode best = current;
			double bestCost = areaCost(current, primitives, config);

			if (!current.left().isLeaf()) {
				BvhNode a = current.left().left();
				BvhNode b = current.left().right();
				BvhNode c = current.right();
				BvhNode candidate = join(a, join(b, c));
				double cost = areaCost(candidate, primitives, config);
				if (cost < bestCost - IMPROVEMENT_EPSILON) {
					best = candidate;
					bestCost = cost;
				}
				candidate = join(b, join(a, c));
				cost = areaCost(candidate, primitives, config);
				if (cost < bestCost - IMPROVEMENT_EPSILON) {
					best = candidate;
					bestCost = cost;
				}
			}
			if (!current.right().isLeaf()) {
				BvhNode a = current.left();
				BvhNode b = current.right().left();
				BvhNode c = current.right().right();
				BvhNode candidate = join(join(a, c), b);
				double cost = areaCost(candidate, primitives, config);
				if (cost < bestCost - IMPROVEMENT_EPSILON) {
					best = candidate;
					bestCost = cost;
				}
				candidate = join(join(a, b), c);
				cost = areaCost(candidate, primitives, config);
				if (cost < bestCost - IMPROVEMENT_EPSILON) best = candidate;
			}
			int rotations = left.rotations() + right.rotations()
					+ (best == current ? 0 : 1);
			return new RotationPassResult(best, rotations);
		}

		private static BvhNode join(BvhNode left, BvhNode right) {
			return new BvhNode(
					left.bounds().union(right.bounds()), left, right, -1, -1);
		}

		private static double areaCost(
				BvhNode node,
				BvhPrimitive[] primitives,
				BvhBuildConfig config
		) {
			if (node.isLeaf()) {
				double weights = 0.0;
				for (int i = node.start(); i < node.end(); i++) {
					weights += config.constructionWeight(primitives[i].measuredWeight());
				}
				return node.bounds().surfaceArea() * weights;
			}
			return node.bounds().surfaceArea() * config.traversalWeight()
					+ areaCost(node.left(), primitives, config)
					+ areaCost(node.right(), primitives, config);
		}
	}

	private record SpatialObjectSplit(
			SpatialReference[] sorted,
			int leftCount,
			double cost,
			Aabb leftBounds,
			Aabb rightBounds
	) { }
	private record SpatialPlane(int axis, double position, double estimatedCost) { }
	private record SpatialPartition(
			SpatialReference[] left,
			SpatialReference[] right,
			int duplicateCount
	) { }
	private record SplitChoice(
			SpatialReference original,
			SpatialReference left,
			SpatialReference right
	) { }

	/**
	 * SBVH-inspired builder following Stich et al.: at each eligible node it
	 * compares an object split with a binned spatial split, duplicates clipped
	 * references under a global budget and greedily unsplits references when the
	 * object placement has the lower objective.
	 */
	private static final class SpatialBuildState {
		private static final double EPSILON = 1.0e-12;
		private final BvhBuildConfig config;
		private final BvhBuildOptions options;
		private final int maximumReferenceCount;
		private final SpatialReference[] initial;
		private final List<BvhPrimitive> ordered = new ArrayList<>();
		private int currentReferenceCount;
		private int spatialSplitCount;

		private SpatialBuildState(
				BvhPrimitive[] primitives,
				BvhBuildConfig config,
				BvhBuildOptions options
		) {
			this.config = config;
			this.options = options;
			this.currentReferenceCount = primitives.length;
			this.maximumReferenceCount = Math.max(primitives.length,
					(int) Math.floor(primitives.length * options.maxReferenceMultiplier()));
			this.initial = new SpatialReference[primitives.length];
			for (int i = 0; i < primitives.length; i++) {
				BvhPrimitive primitive = primitives[i];
				initial[i] = new SpatialReference(
						primitive.encodedRef(), primitive.order(), primitive.bounds(),
						primitive.measuredWeight(), 0);
			}
		}

		private TreeBuild buildTree() {
			BvhNode root = build(initial.clone(), 0);
			BvhPrimitive[] references = ordered.toArray(BvhPrimitive[]::new);
			return new TreeBuild(
					references, root, summarizeTree(root, spatialSplitCount, 0));
		}

		private BvhNode build(SpatialReference[] references, int depth) {
			Aabb bounds = bounds(references);
			if (references.length == 1) return leaf(bounds, references);
			SpatialObjectSplit objectSplit = bestObjectSplit(references, bounds);
			SpatialPartition spatial = null;
			double spatialCost = Double.POSITIVE_INFINITY;
			if (shouldAttemptSpatial(references, bounds, objectSplit)) {
				SpatialPlane plane = bestSpatialPlane(references, bounds);
				if (plane != null) {
					SpatialPartition candidate = partitionSpatial(references, plane);
					if (candidate != null
							&& currentReferenceCount + candidate.duplicateCount()
							<= maximumReferenceCount) {
						spatialCost = splitCost(candidate.left(), candidate.right(), bounds);
						spatial = candidate;
					}
				}
			}

			double leafCost = bounds.surfaceArea() * weightSum(references);
			boolean chooseSpatial = spatial != null && spatialCost < objectSplit.cost();
			double bestCost = chooseSpatial ? spatialCost : objectSplit.cost();
			if (references.length <= config.leafSize() && !(bestCost < leafCost)) {
				return leaf(bounds, references);
			}

			SpatialReference[] left;
			SpatialReference[] right;
			if (chooseSpatial) {
				left = spatial.left();
				right = spatial.right();
				currentReferenceCount += spatial.duplicateCount();
				spatialSplitCount++;
			} else {
				left = Arrays.copyOfRange(
						objectSplit.sorted(), 0, objectSplit.leftCount());
				right = Arrays.copyOfRange(
						objectSplit.sorted(), objectSplit.leftCount(), objectSplit.sorted().length);
			}
			BvhNode leftNode = build(left, depth + 1);
			BvhNode rightNode = build(right, depth + 1);
			return new BvhNode(bounds, leftNode, rightNode, -1, -1);
		}

		private BvhNode leaf(Aabb bounds, SpatialReference[] references) {
			int start = ordered.size();
			for (SpatialReference reference : references) ordered.add(reference.primitive());
			return new BvhNode(bounds, null, null, start, ordered.size());
		}

		private SpatialObjectSplit bestObjectSplit(
				SpatialReference[] references,
				Aabb nodeBounds
		) {
			SpatialObjectSplit best = null;
			for (int axis = 0; axis < 3; axis++) {
				int sortAxis = axis;
				SpatialReference[] sorted = references.clone();
				Arrays.sort(sorted, (a, b) -> {
					int cmp = Double.compare(
							a.centroid().get(sortAxis), b.centroid().get(sortAxis));
					return cmp != 0 ? cmp : Integer.compare(a.order(), b.order());
				});
				int count = sorted.length;
				Aabb[] prefix = new Aabb[count];
				Aabb[] suffix = new Aabb[count];
				double[] prefixWeights = new double[count];
				double[] suffixWeights = new double[count];
				prefix[0] = sorted[0].bounds();
				prefixWeights[0] = objectiveWeight(sorted[0]);
				for (int i = 1; i < count; i++) {
					prefix[i] = prefix[i - 1].union(sorted[i].bounds());
					prefixWeights[i] = prefixWeights[i - 1] + objectiveWeight(sorted[i]);
				}
				suffix[count - 1] = sorted[count - 1].bounds();
				suffixWeights[count - 1] = objectiveWeight(sorted[count - 1]);
				for (int i = count - 2; i >= 0; i--) {
					suffix[i] = suffix[i + 1].union(sorted[i].bounds());
					suffixWeights[i] = suffixWeights[i + 1] + objectiveWeight(sorted[i]);
				}
				for (int i = 1; i < count; i++) {
					double cost = nodeBounds.surfaceArea() * config.traversalWeight()
							+ prefix[i - 1].surfaceArea() * prefixWeights[i - 1]
							+ suffix[i].surfaceArea() * suffixWeights[i];
					if (best == null || cost < best.cost()) {
						best = new SpatialObjectSplit(
								sorted, i, cost, prefix[i - 1], suffix[i]);
					}
				}
			}
			return best;
		}

		private boolean shouldAttemptSpatial(
				SpatialReference[] references,
				Aabb nodeBounds,
				SpatialObjectSplit objectSplit
		) {
			if (references.length < options.minSpatialReferences()
					|| currentReferenceCount >= maximumReferenceCount) return false;
			Aabb overlap = intersection(objectSplit.leftBounds(), objectSplit.rightBounds());
			double overlapArea = overlap == null ? 0.0 : overlap.surfaceArea();
			return overlapArea / Math.max(nodeBounds.surfaceArea(), 1.0e-30)
					> options.spatialOverlapThreshold();
		}

		private SpatialPlane bestSpatialPlane(
				SpatialReference[] references,
				Aabb nodeBounds
		) {
			SpatialPlane best = null;
			for (int axis = 0; axis < 3; axis++) {
				double lo = nodeBounds.min().get(axis);
				double hi = nodeBounds.max().get(axis);
				double extent = hi - lo;
				if (!(extent > EPSILON)) continue;
				for (int bin = 1; bin < options.spatialBins(); bin++) {
					double plane = lo + extent * bin / options.spatialBins();
					Aabb leftBounds = null;
					Aabb rightBounds = null;
					double leftWeight = 0.0;
					double rightWeight = 0.0;
					int duplicates = 0;
					for (SpatialReference reference : references) {
						if (reference.bounds().max().get(axis) <= plane + EPSILON) {
							leftBounds = union(leftBounds, reference.bounds());
							leftWeight += objectiveWeight(reference);
						} else if (reference.bounds().min().get(axis) >= plane - EPSILON) {
							rightBounds = union(rightBounds, reference.bounds());
							rightWeight += objectiveWeight(reference);
						} else if (reference.splitDepth() < options.maxSplitsPerPrimitive()) {
							leftBounds = union(
									leftBounds, clipMax(reference.bounds(), axis, plane));
							rightBounds = union(
									rightBounds, clipMin(reference.bounds(), axis, plane));
							double weight = objectiveWeight(reference);
							leftWeight += weight;
							rightWeight += weight;
							duplicates++;
						} else if (reference.centroid().get(axis) <= plane) {
							leftBounds = union(leftBounds, reference.bounds());
							leftWeight += objectiveWeight(reference);
						} else {
							rightBounds = union(rightBounds, reference.bounds());
							rightWeight += objectiveWeight(reference);
						}
					}
					if (leftBounds == null || rightBounds == null
							|| currentReferenceCount + duplicates > maximumReferenceCount) continue;
					double cost = nodeBounds.surfaceArea() * config.traversalWeight()
							+ leftBounds.surfaceArea() * leftWeight
							+ rightBounds.surfaceArea() * rightWeight;
					if (best == null || cost < best.estimatedCost()) {
						best = new SpatialPlane(axis, plane, cost);
					}
				}
			}
			return best;
		}

		private SpatialPartition partitionSpatial(
				SpatialReference[] references,
				SpatialPlane plane
		) {
			List<SpatialReference> left = new ArrayList<>();
			List<SpatialReference> right = new ArrayList<>();
			List<SplitChoice> choices = new ArrayList<>();
			for (SpatialReference reference : references) {
				double lo = reference.bounds().min().get(plane.axis());
				double hi = reference.bounds().max().get(plane.axis());
				if (hi <= plane.position() + EPSILON) {
					left.add(reference);
				} else if (lo >= plane.position() - EPSILON) {
					right.add(reference);
				} else if (reference.splitDepth() < options.maxSplitsPerPrimitive()) {
					SpatialReference leftPart = reference.clipped(
							clipMax(reference.bounds(), plane.axis(), plane.position()));
					SpatialReference rightPart = reference.clipped(
							clipMin(reference.bounds(), plane.axis(), plane.position()));
					left.add(leftPart);
					right.add(rightPart);
					choices.add(new SplitChoice(reference, leftPart, rightPart));
				} else if (reference.centroid().get(plane.axis()) <= plane.position()) {
					left.add(reference);
				} else {
					right.add(reference);
				}
			}
			if (left.isEmpty() || right.isEmpty()) return null;

			for (SplitChoice choice : choices) {
				Aabb leftBounds = bounds(left);
				Aabb rightBounds = bounds(right);
				double leftWeight = weightSum(left);
				double rightWeight = weightSum(right);
				double weight = objectiveWeight(choice.original());
				double splitCost = leftBounds.surfaceArea() * leftWeight
						+ rightBounds.surfaceArea() * rightWeight;
				double leftOnlyCost = leftBounds.union(choice.original().bounds()).surfaceArea()
						* leftWeight
						+ rightBounds.surfaceArea() * Math.max(0.0, rightWeight - weight);
				double rightOnlyCost = leftBounds.surfaceArea()
						* Math.max(0.0, leftWeight - weight)
						+ rightBounds.union(choice.original().bounds()).surfaceArea()
						* rightWeight;
				if (leftOnlyCost < splitCost && leftOnlyCost <= rightOnlyCost
						&& right.size() > 1) {
					left.remove(choice.left());
					right.remove(choice.right());
					left.add(choice.original());
				} else if (rightOnlyCost < splitCost && left.size() > 1) {
					left.remove(choice.left());
					right.remove(choice.right());
					right.add(choice.original());
				}
			}
			int duplicates = left.size() + right.size() - references.length;
			return new SpatialPartition(
					left.toArray(SpatialReference[]::new),
					right.toArray(SpatialReference[]::new), duplicates);
		}

		private double splitCost(
				SpatialReference[] left,
				SpatialReference[] right,
				Aabb nodeBounds
		) {
			return nodeBounds.surfaceArea() * config.traversalWeight()
					+ bounds(left).surfaceArea() * weightSum(left)
					+ bounds(right).surfaceArea() * weightSum(right);
		}

		private double weightSum(SpatialReference[] references) {
			double sum = 0.0;
			for (SpatialReference reference : references) sum += objectiveWeight(reference);
			return sum;
		}

		private double weightSum(List<SpatialReference> references) {
			double sum = 0.0;
			for (SpatialReference reference : references) sum += objectiveWeight(reference);
			return sum;
		}

		private double objectiveWeight(SpatialReference reference) {
			return config.constructionWeight(reference.measuredWeight());
		}

		private static Aabb bounds(SpatialReference[] references) {
			Aabb bounds = references[0].bounds();
			for (int i = 1; i < references.length; i++) {
				bounds = bounds.union(references[i].bounds());
			}
			return bounds;
		}

		private static Aabb bounds(List<SpatialReference> references) {
			Aabb bounds = references.get(0).bounds();
			for (int i = 1; i < references.size(); i++) {
				bounds = bounds.union(references.get(i).bounds());
			}
			return bounds;
		}

		private static Aabb union(Aabb a, Aabb b) {
			return a == null ? b : a.union(b);
		}

		private static Aabb intersection(Aabb a, Aabb b) {
			Vec3 min = Vec3.max(a.min(), b.min());
			Vec3 max = Vec3.min(a.max(), b.max());
			if (max.x() <= min.x() || max.y() <= min.y() || max.z() <= min.z()) return null;
			return new Aabb(min, max);
		}

		private static Aabb clipMax(Aabb bounds, int axis, double value) {
			return new Aabb(bounds.min(), withAxis(
					bounds.max(), axis, Math.min(bounds.max().get(axis), value)));
		}

		private static Aabb clipMin(Aabb bounds, int axis, double value) {
			return new Aabb(withAxis(
					bounds.min(), axis, Math.max(bounds.min().get(axis), value)), bounds.max());
		}

		private static Vec3 withAxis(Vec3 value, int axis, double replacement) {
			return switch (axis) {
				case 0 -> Vec3.xyz(replacement, value.y(), value.z());
				case 1 -> Vec3.xyz(value.x(), replacement, value.z());
				default -> Vec3.xyz(value.x(), value.y(), replacement);
			};
		}
	}

	private static final class BvhBuildState {
		private static final double SPLIT_EPSILON = 1.0e-12;
		private final BvhPrimitive[] primitives;
		private final BvhBuildConfig config;

		private BvhBuildState(BvhPrimitive[] primitives, BvhBuildConfig config) {
			this.primitives = primitives;
			this.config = config;
		}

		private BvhNode build(int start, int end, int depth) {
			Aabb bounds = primitives[start].bounds();
			Vec3 firstCentroid = primitives[start].centroid();
			Aabb centroidBounds = new Aabb(firstCentroid, firstCentroid);
			for (int i = start + 1; i < end; i++) {
				bounds = bounds.union(primitives[i].bounds());
				Vec3 centroid = primitives[i].centroid();
				centroidBounds = new Aabb(Vec3.min(centroidBounds.min(), centroid), Vec3.max(centroidBounds.max(), centroid));
			}

			int count = end - start;
			if (count == 1) return leaf(bounds, start, end);
			BvhSplit split = bestSplit(start, end, bounds, centroidBounds);
			if (split == null) return leaf(bounds, start, end);
			double leafCost = bounds.surfaceArea() * weightSum(primitives, start, end);
			if (count <= config.leafSize() && !(split.cost() < leafCost)) {
				return leaf(bounds, start, end);
			}
			System.arraycopy(split.sorted(), 0, primitives, start, count);
			int mid = start + split.leftCount();
			BvhNode left = build(start, mid, depth + 1);
			BvhNode right = build(mid, end, depth + 1);
			return new BvhNode(bounds, left, right, -1, -1);
		}

		private BvhNode leaf(Aabb bounds, int start, int end) {
			return new BvhNode(bounds, null, null, start, end);
		}

		private BvhSplit bestSplit(int start, int end, Aabb bounds, Aabb centroidBounds) {
			int count = end - start;
			double nodeArea = bounds.surfaceArea();
			BvhSplit best = null;
			for (int axis = 0; axis < 3; axis++) {
				if (!(centroidBounds.max().get(axis) - centroidBounds.min().get(axis) > SPLIT_EPSILON)) continue;
				int splitAxis = axis;
				BvhPrimitive[] sorted = Arrays.copyOfRange(primitives, start, end);
				Arrays.sort(sorted, (a, b) -> {
					int cmp = Double.compare(a.centroid().get(splitAxis), b.centroid().get(splitAxis));
					return cmp != 0 ? cmp : Integer.compare(a.order(), b.order());
				});
				Aabb[] prefix = new Aabb[count];
				Aabb[] suffix = new Aabb[count];
				double[] prefixWeight = new double[count];
				double[] suffixWeight = new double[count];
				prefix[0] = sorted[0].bounds();
				prefixWeight[0] = objectiveWeight(sorted[0]);
				for (int i = 1; i < count; i++) {
					prefix[i] = prefix[i - 1].union(sorted[i].bounds());
					prefixWeight[i] = prefixWeight[i - 1] + objectiveWeight(sorted[i]);
				}
				suffix[count - 1] = sorted[count - 1].bounds();
				suffixWeight[count - 1] = objectiveWeight(sorted[count - 1]);
				for (int i = count - 2; i >= 0; i--) {
					suffix[i] = suffix[i + 1].union(sorted[i].bounds());
					suffixWeight[i] = suffixWeight[i + 1] + objectiveWeight(sorted[i]);
				}
				for (int i = 1; i < count; i++) {
					double cost = nodeArea * config.traversalWeight() + prefix[i - 1].surfaceArea() * prefixWeight[i - 1]
							+ suffix[i].surfaceArea() * suffixWeight[i];
					if (best == null || cost < best.cost()) best = new BvhSplit(sorted, i, cost);
				}
			}
			return best;
		}

		private double weightSum(BvhPrimitive[] values, int start, int end) {
			double sum = 0.0;
			for (int i = start; i < end; i++) sum += objectiveWeight(values[i]);
			return sum;
		}

		private double objectiveWeight(BvhPrimitive primitive) {
			return config.constructionWeight(primitive.measuredWeight());
		}
	}
}
