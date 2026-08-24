package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Hit;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.HalfSpace;

public final class SceneHalfSpace implements Solid {
	private final HalfSpace halfSpace;
	private final Vec3 point;
	private final Vec3 normal;
	private final Material material;

	private SceneHalfSpace(HalfSpace halfSpace, Vec3 point, Vec3 normal, Material material) {
		this.halfSpace = halfSpace;
		this.point = point;
		this.normal = normal;
		this.material = material;
	}

	public static SceneHalfSpace pn(Vec3 point, Vec3 normal, Material material) {
		return new SceneHalfSpace(HalfSpace.pn(point, normal, material), point, normal.normalized_(), material);
	}

	public Vec3 point() {
		return point;
	}

	public Vec3 normal() {
		return normal;
	}

	public Material material() {
		return material;
	}

	@Override
	public Hit firstHit(Ray ray, double afterTime) {
		return halfSpace.firstHit(ray, afterTime);
	}
}
