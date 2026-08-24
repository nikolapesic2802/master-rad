package xyz.marsavic.gfxlab.graphics3d.raytracers;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.benchmark.TraceCounters;
import xyz.marsavic.gfxlab.graphics3d.*;
import xyz.marsavic.random.sampling.Sampler;
import xyz.marsavic.utils.Hashing;


public class PathTracer extends RayTracer {

	private static final double EPSILON = 1e-9;
	private static final long seed = 0x68EFD508E309A865L;

	private final int maxDepth;
	private volatile TraceCounters benchmarkCounters;
	private volatile int fallbackPrimitiveTestsPerRay;

	public PathTracer(Scene scene, Camera camera, int maxDepth) {
		super(scene, camera);
		this.maxDepth = maxDepth;
	}

	public void setBenchmarkCounters(TraceCounters counters, int fallbackPrimitiveTestsPerRay) {
		this.benchmarkCounters = counters;
		this.fallbackPrimitiveTestsPerRay = Math.max(0, fallbackPrimitiveTestsPerRay);
	}

	@Override
	protected Color sample(Ray ray) {
		TraceCounters counters = benchmarkCounters;
		if (counters != null) counters.recordPrimaryRay();
		return radiance(ray, maxDepth, new Sampler(Hashing.mix(seed, ray)));
	}

	private Color radiance(Ray ray, int depthRemaining, Sampler sampler) {
		if (depthRemaining <= 0) return Color.BLACK;

		TraceStatsSolid tracedSolid = scene.solid() instanceof TraceStatsSolid stats ? stats : null;
		if (tracedSolid != null) tracedSolid.resetTraceStats();
		Hit hit = scene.solid().firstHit(ray, EPSILON);
		TraceCounters counters = benchmarkCounters;
		if (counters != null) {
			long primitiveTests = tracedSolid == null ? fallbackPrimitiveTestsPerRay : tracedSolid.lastPrimitiveTests();
			long aabbTests = tracedSolid == null ? 0L : tracedSolid.lastAabbTests();
			long internalNodeVisits = tracedSolid == null ? 0L : tracedSolid.lastInternalNodeVisits();
			long leafNodeVisits = tracedSolid == null ? 0L : tracedSolid.lastLeafNodeVisits();
			counters.recordRay(primitiveTests, aabbTests, internalNodeVisits, leafNodeVisits);
		}
		if (hit.t() == Double.POSITIVE_INFINITY) {
			return scene.colorBackground();
		}

		Material material = hit.material();
		Color result = material.emittance();

		Vec3 i = ray.d().inverse();                 // Incoming direction
		Vec3 n_ = hit.n_();                         // Normalized normal to the body surface at the hit point
		BSDF.Result bsdfResult = material.bsdf().sample(sampler, n_, i);

		if (bsdfResult.color().notZero()) {
			Vec3 p = ray.at(hit.t());               // Point of collision
			Ray rayScattered = Ray.pd(p, bsdfResult.out());
			Color rO = radiance(rayScattered, depthRemaining - 1, sampler);
			Color rI = rO.mul(bsdfResult.color());
			result = result.add(rI);
		}

		return result;
	}

}
