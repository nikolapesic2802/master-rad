package xyz.marsavic.gfxlab.gpu;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneBox;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneHalfSpace;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSphere;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

/**
 * Translates high level scene descriptions into GPU-friendly payloads.
 */
public final class GpuSceneBuilder {
	private GpuSceneBuilder() {
	}

	public static GpuScene from(Scene scene) {
		return from(scene, BvhBuildConfig.fromProperties(BvhBuildMode.UNIFORM_SAH));
	}

	public static GpuScene from(Scene scene, BvhBuildConfig config) {
		return from(scene, config, BvhBuildOptions.defaults());
	}

	public static GpuScene from(Scene scene, BvhBuildConfig config, BvhBuildOptions options) {
		GpuScene gpuScene = new GpuScene(config, options).setBackground(Vec3.xyz(
				scene.colorBackground().r(),
				scene.colorBackground().g(),
				scene.colorBackground().b()
		));
		convertSolid(gpuScene, scene.solid());
		return gpuScene;
	}

	private static void convertSolid(GpuScene gpuScene, Solid solid) {
		if (solid instanceof Group group) {
			for (Solid child : group.solids()) {
				convertSolid(gpuScene, child);
			}
			return;
		}
		if (solid instanceof SceneSphere sphere) {
			if (sphere.isAffine()) {
				AffineData affine = gpuAffine(
						sphere.transform(), sphere.inverse(), "Affine sphere");
				Bounds bounds = sphereBounds(
						affine.forwardLinear(), affine.worldCenter());
				gpuScene.addAffineSphere(
						affine.inverse(), affine.inverseTranspose(), affine.worldCenter(),
						bounds.min(), bounds.max(), materialFrom(sphere.material()));
			} else {
				gpuScene.addSphere(sphere.center(), sphere.radius(), materialFrom(sphere.material()));
			}
			return;
		}
		if (solid instanceof SceneBox box) {
			if (box.isAffine()) {
				AffineData affine = gpuAffine(
						box.transform(), box.inverse(), "Affine box");
				Bounds bounds = boxBounds(
						affine.forwardLinear(), affine.worldCenter());
				gpuScene.addAffineBox(
						affine.inverse(), affine.inverseTranspose(), affine.worldCenter(),
						bounds.min(), bounds.max(), materialFrom(box.material()));
			} else {
				gpuScene.addBox(box.p(), box.q(), materialFrom(box.material()));
			}
			return;
		}
		if (solid instanceof SceneHalfSpace halfSpace) {
			gpuScene.addPlane(halfSpace.point(), halfSpace.normal(), materialFrom(halfSpace.material()));
			return;
		}

		throw new UnsupportedOperationException("Solid type not supported by GPU path tracer: " + solid.getClass().getName());
	}

	private static GpuScene.MaterialData materialFrom(Material material) {
		return GpuScene.MaterialData.from(material);
	}

	private static Bounds sphereBounds(
			xyz.marsavic.gfxlab.graphics3d.Affine forwardLinear,
			Vec3 center
	) {
		Vec3 extents = Vec3.xyz(
				rowLength(forwardLinear.m00(), forwardLinear.m01(), forwardLinear.m02()),
				rowLength(forwardLinear.m10(), forwardLinear.m11(), forwardLinear.m12()),
				rowLength(forwardLinear.m20(), forwardLinear.m21(), forwardLinear.m22()));
		return new Bounds(center.sub(extents), center.add(extents));
	}

	private static Bounds boxBounds(
			xyz.marsavic.gfxlab.graphics3d.Affine forwardLinear,
			Vec3 center
	) {
		return boxBounds(forwardLinear, center, Vec3.ONES);
	}

	private static Bounds boxBounds(
			xyz.marsavic.gfxlab.graphics3d.Affine forwardLinear,
			Vec3 center,
			Vec3 localExtents
	) {
		Vec3 extents = Vec3.xyz(
				rowAbsSum(forwardLinear.m00() * localExtents.x(), forwardLinear.m01() * localExtents.y(),
						forwardLinear.m02() * localExtents.z()),
				rowAbsSum(forwardLinear.m10() * localExtents.x(), forwardLinear.m11() * localExtents.y(),
						forwardLinear.m12() * localExtents.z()),
				rowAbsSum(forwardLinear.m20() * localExtents.x(), forwardLinear.m21() * localExtents.y(),
						forwardLinear.m22() * localExtents.z()));
		return new Bounds(center.sub(extents), center.add(extents));
	}

	private static double rowLength(double a, double b, double c) {
		return Math.sqrt(a * a + b * b + c * c);
	}

	private static double rowAbsSum(double a, double b, double c) {
		return Math.abs(a) + Math.abs(b) + Math.abs(c);
	}

	/**
	 * Affine intersections execute with float matrices on the GPU. Bounds must
	 * enclose that quantized transform, not the original double transform;
	 * otherwise very small primitives can extend beyond their BVH boxes.
	 */
	private static AffineData gpuAffine(
			xyz.marsavic.gfxlab.graphics3d.Affine transform,
			xyz.marsavic.gfxlab.graphics3d.Affine inverse,
			String label
	) {
		xyz.marsavic.gfxlab.graphics3d.Affine packedInverse =
				quantizeLinear(inverse);
		Vec3 packedCenter = quantize(transform.at(Vec3.ZERO));
		AffineData packed = new AffineData(
				packedInverse,
				packedInverse.transposeWithoutTranslation(),
				packedInverse.inverse(),
				packedCenter);
		GpuScene.requireAffineMatrices(
				packed.inverse(), packed.inverseTranspose(), label);
		return packed;
	}

	private static xyz.marsavic.gfxlab.graphics3d.Affine quantizeLinear(
			xyz.marsavic.gfxlab.graphics3d.Affine a
	) {
		return new xyz.marsavic.gfxlab.graphics3d.Affine(
				(float) a.m00(), (float) a.m01(), (float) a.m02(), 0.0,
				(float) a.m10(), (float) a.m11(), (float) a.m12(), 0.0,
				(float) a.m20(), (float) a.m21(), (float) a.m22(), 0.0);
	}

	private static Vec3 quantize(Vec3 value) {
		return Vec3.xyz((float) value.x(), (float) value.y(), (float) value.z());
	}

	private record AffineData(xyz.marsavic.gfxlab.graphics3d.Affine inverse,
	                          xyz.marsavic.gfxlab.graphics3d.Affine inverseTranspose,
	                          xyz.marsavic.gfxlab.graphics3d.Affine forwardLinear,
	                          Vec3 worldCenter) { }
	private record Bounds(Vec3 min, Vec3 max) { }
}
