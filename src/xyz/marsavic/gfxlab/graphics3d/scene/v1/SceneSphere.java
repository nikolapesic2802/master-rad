package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Hit;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Ball;

public final class SceneSphere implements Solid {
	private final Ball localBall;
	private final Solid delegate;
	private final Material material;
	private final Vec3 center;
	private final double radius;
	private final Affine transform;
	private final Affine inverse;
	private final Affine inverseTranspose;

	private SceneSphere(Ball localBall,
	                   Solid delegate,
	                   Material material,
	                   Vec3 center,
	                   double radius,
	                   Affine transform,
	                   Affine inverse,
	                   Affine inverseTranspose) {
		this.localBall = localBall;
		this.delegate = delegate;
		this.material = material;
		this.center = center;
		this.radius = radius;
		this.transform = transform;
		this.inverse = inverse;
		this.inverseTranspose = inverseTranspose;
	}

	public static SceneSphere sphere(Vec3 center, double radius, Material material) {
		Ball ball = Ball.cr(center, radius, material);
		return new SceneSphere(ball, ball, material, center, radius, null, null, null);
	}

	public static SceneSphere affine(Affine transform, Material material) {
		Ball unitBall = Ball.cr(Vec3.ZERO, 1.0, material);
		Affine inverse = transform.inverse();
		Affine inverseTranspose = inverse.transposeWithoutTranslation();
		return new SceneSphere(unitBall, unitBall.transformed(transform), material, null, 0.0, transform, inverse, inverseTranspose);
	}

	public boolean isAffine() {
		return transform != null;
	}

	public Vec3 center() {
		return center;
	}

	public double radius() {
		return radius;
	}

	public Material material() {
		return material;
	}

	public Affine transform() {
		return transform;
	}

	public Affine inverse() {
		return inverse;
	}

	public Affine inverseTranspose() {
		return inverseTranspose;
	}

	@Override
	public Hit firstHit(Ray ray, double afterTime) {
		return delegate.firstHit(ray, afterTime);
	}
}
