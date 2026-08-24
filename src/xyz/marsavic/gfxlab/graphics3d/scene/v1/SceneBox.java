package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Hit;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Box;

public final class SceneBox implements Solid {
	private final Box localBox;
	private final Solid delegate;
	private final Material material;
	private final Vec3 p;
	private final Vec3 q;
	private final Affine transform;
	private final Affine inverse;
	private final Affine inverseTranspose;

	private SceneBox(Box localBox,
	                Solid delegate,
	                Material material,
	                Vec3 p,
	                Vec3 q,
	                Affine transform,
	                Affine inverse,
	                Affine inverseTranspose) {
		this.localBox = localBox;
		this.delegate = delegate;
		this.material = material;
		this.p = p;
		this.q = q;
		this.transform = transform;
		this.inverse = inverse;
		this.inverseTranspose = inverseTranspose;
	}

	public static SceneBox axisAligned(Vec3 center, Vec3 radii, Material material) {
		Box box = Box.fromCenterAndRadii(center, radii).material(material);
		return new SceneBox(box, box, material, box.p(), box.q(), null, null, null);
	}

	public static SceneBox affine(Affine transform, Material material) {
		Box unitBox = Box.fromCenterAndRadii(
				Vec3.ZERO, Vec3.EXYZ).material(material);
		Affine inverse = transform.inverse();
		Affine inverseTranspose = inverse.transposeWithoutTranslation();
		return new SceneBox(unitBox, unitBox.transformed(transform), material,
				null, null, transform, inverse, inverseTranspose);
	}

	public boolean isAffine() {
		return transform != null;
	}

	public Vec3 p() {
		return p;
	}

	public Vec3 q() {
		return q;
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
