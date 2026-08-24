package xyz.marsavic.gfxlab.graphics3d.solids;


import xyz.marsavic.functions.F1;
import xyz.marsavic.geometry.Vector;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.*;
import xyz.marsavic.utils.Numeric;


public class Box implements Solid {

	private final Vec3 p, q;
	private final F1<Material, Vector> mapMaterial;


	private Box(Vec3 p, Vec3 q, F1<Material, Vector> mapMaterial) {
		this.p = p;
		this.q = q;
		this.mapMaterial = mapMaterial;
	}


	private Box(Vec3 p, Vec3 q) {
		this(p, q, Material.MATTE);
	}

	public static Box fromCenterAndRadii(Vec3 center, Vec3 radii) {
		if (!isFinite(center)
				|| !isFinite(radii)
				|| !(radii.x() > 0.0)
				|| !(radii.y() > 0.0)
				|| !(radii.z() > 0.0)) {
			throw new IllegalArgumentException(
					"Box center must be finite and radii must be finite and positive.");
		}
		return new Box(center.sub(radii), center.add(radii));
	}

	private static boolean isFinite(Vec3 value) {
		return Double.isFinite(value.x())
				&& Double.isFinite(value.y())
				&& Double.isFinite(value.z());
	}


	public Box material(F1<Material, Vector> map) {
		return new Box(p, q, map);
	}


	public Vec3 p() {
		return p;
	}


	public Vec3 q() {
		return q;
	}


	public Vec3 d() {
		return q.sub(p);
	}


	public Vec3 c() {
		return p.add(q).div(2);
	}


	public Vec3 r() {
		return d().div(2);
	}


	public boolean contains(Vec3 o) {
		return o.sub(p).sign().sub(q.sub(o).sign()).allZero();
	}


	@Override
	public Hit firstHit(Ray ray, double afterTime) {
		double tEnter = Double.NEGATIVE_INFINITY;
		double tExit = Double.POSITIVE_INFINITY;
		int enterAxis = -1;
		int exitAxis = -1;

		for (int axis = 0; axis < 3; axis++) {
			double origin = ray.p().get(axis);
			double direction = ray.d().get(axis);
			double min = Math.min(p.get(axis), q.get(axis));
			double max = Math.max(p.get(axis), q.get(axis));

			if (direction == 0.0) {
				if (origin < min || origin > max) {
					return Hit.AtInfinity.axisAlignedOut(ray.d());
				}
				continue;
			}

			double t0 = (min - origin) / direction;
			double t1 = (max - origin) / direction;
			if (t0 > t1) {
				double swap = t0;
				t0 = t1;
				t1 = swap;
			}
			if (t0 > tEnter) {
				tEnter = t0;
				enterAxis = axis;
			}
			if (t1 < tExit) {
				tExit = t1;
				exitAxis = axis;
			}
			if (tEnter >= tExit) {
				return Hit.AtInfinity.axisAlignedOut(ray.d());
			}
		}

		if (enterAxis >= 0 && tEnter > afterTime) {
			return new HitBox(tEnter,
					Vec3.E[enterAxis].mul(-Numeric.sign(ray.d().get(enterAxis))));
		}
		if (exitAxis >= 0 && tExit > afterTime) {
			return new HitBox(tExit,
					Vec3.E[exitAxis].mul(Numeric.sign(ray.d().get(exitAxis))));
		}
		return Hit.AtInfinity.axisAlignedOut(ray.d());
	}


	final class HitBox implements Hit {
		private final double t;
		private final Vec3 n_;


		HitBox(double t, Vec3 n_) {
			this.t = t;
			this.n_ = n_;
		}

		@Override public double t() { return t; }
		@Override public Vec3 n_() { return n_; }
		@Override public Vec3 n() { return n_; }

		@Override
		public Material material() {
			return Box.this.mapMaterial.at(uv());
		}

		@Override
		public Vector uv() {
			return Vector.ZERO;
		}
	}


}
