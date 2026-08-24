package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.cameras.Perspective;
import xyz.marsavic.gfxlab.graphics3d.cameras.TransformedCamera;

public final class SceneSupport {
	private SceneSupport() {
	}

	static Material matte(Color color) {
		return Material.surface(color, Color.BLACK, Color.BLACK, 1.5);
	}

	static Material mirror(Color color) {
		return Material.surface(Color.BLACK, Color.BLACK, color, Color.BLACK, 1.5);
	}

	static Material glass(Color color, double refractiveIndex) {
		return Material.surface(Color.BLACK, Color.BLACK, Color.BLACK, color, refractiveIndex);
	}

	static Material emissive(Color emission) {
		return Material.surface(emission, Color.BLACK, Color.BLACK, Color.BLACK, 1.5);
	}

	static Material emissiveDiffuse(Color emission, Color diffuse) {
		return Material.surface(emission, diffuse, Color.BLACK, Color.BLACK, 1.5);
	}

	static Material surface(Color emission, Color diffuse, Color reflective, Color refractive, double refractiveIndex) {
		return Material.surface(emission, diffuse, reflective, refractive, refractiveIndex);
	}

	static Solid sphere(Vec3 center, double radius, Material material) {
		return SceneSphere.sphere(center, radius, material);
	}

	static Solid ellipsoid(Vec3 center, Vec3 radii, Material material) {
		return SceneSphere.affine(
				Affine.IDENTITY
						.then(Affine.scaling(radii))
						.then(Affine.translation(center)),
				material
		);
	}

	static Solid box(Vec3 center, Vec3 radii, Material material) {
		return SceneBox.axisAligned(center, radii, material);
	}

	static Solid box(Affine transform, Material material) {
		return SceneBox.affine(transform, material);
	}

	static Solid halfSpace(Vec3 point, Vec3 normal, Material material) {
		return SceneHalfSpace.pn(point, normal, material);
	}

	static Affine boxTransform(Vec3 center, Vec3 radii, double rotationX, double rotationY, double rotationZ) {
		return Affine.IDENTITY
				.then(Affine.scaling(radii))
				.then(Affine.rotationAboutX(rotationX))
				.then(Affine.rotationAboutY(rotationY))
				.then(Affine.rotationAboutZ(rotationZ))
				.then(Affine.translation(center));
	}

	public static Camera camera(Vec3 position, double pitch, double yaw) {
		return camera(position, pitch, yaw, 0.0);
	}

	public static Camera camera(Vec3 position, double pitch, double yaw, double roll) {
		return new TransformedCamera(
				new Perspective(1.0 / 3.0),
				Affine.IDENTITY
						.then(Affine.translation(position))
						.then(Affine.rotationAboutX(pitch))
						.then(Affine.rotationAboutY(yaw))
						.then(Affine.rotationAboutZ(roll))
		);
	}

	public static Camera cameraLookAt(Vec3 position, Vec3 target) {
		return cameraLookAt(position, target, 1.0 / 3.0);
	}

	public static Camera cameraLookAt(Vec3 position, Vec3 target, double perspectiveScale) {
		Vec3 forward = target.sub(position).normalized_();
		Vec3 right = Vec3.EY.cross(forward).normalized_();
		Vec3 up = forward.cross(right).normalized_();
		return new TransformedCamera(
				new Perspective(perspectiveScale),
				Affine.IDENTITY
						.then(Affine.unitVectors(right, up, forward))
						.then(Affine.translation(position))
		);
	}
}
