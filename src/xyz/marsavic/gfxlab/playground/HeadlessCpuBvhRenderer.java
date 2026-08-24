package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.geometry.Vector;
import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;
import xyz.marsavic.gfxlab.benchmark.SceneBenchmarkMetrics;
import xyz.marsavic.gfxlab.benchmark.TraceCounters;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.bvh.BvhSolid;
import xyz.marsavic.gfxlab.graphics3d.raytracers.PathTracer;

import java.util.stream.IntStream;

final class HeadlessCpuBvhRenderer {
	private final Scene scene;
	private final Camera camera;
	private final int width;
	private final int height;
	private final int maxDepth;
	private final long seed;

	HeadlessCpuBvhRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed) {
		this.scene = scene;
		this.camera = camera;
		this.width = width;
		this.height = height;
		this.maxDepth = maxDepth;
		this.seed = seed;
	}

	HeadlessCpuRenderer.RenderedImage render(int frames, int warmupFrames, int progressEvery, BenchmarkRecorder recorder) {
		int leafSize = Math.max(1, Integer.getInteger("gfxlab.bvhLeafSize", 4));
		BvhSolid bvhSolid = BvhSolid.build(scene.solid(), leafSize);
		Scene tracedScene = new SceneWithSolid(scene, bvhSolid);
		PathTracer tracer = new PathTracer(tracedScene, camera, maxDepth);
		double[] sums = new double[width * height * 3];
		double[] warmupSums = warmupFrames > 0 ? new double[width * height * 3] : sums;
		SceneBenchmarkMetrics sceneMetrics = recorder == null ? null : SceneBenchmarkMetrics.fromScene(scene);
		if (recorder != null) {
			recorder.setRunMetadata(new BenchmarkRecorder.RunMetadata(
					sceneMetrics.sceneBytes(),
					bvhSolid.stats().hostBytes(),
					(long) sums.length * Double.BYTES + bvhSolid.stats().hostBytes(),
					null,
					sceneMetrics.primitiveCount(),
					sceneMetrics.sphereCount(),
					sceneMetrics.boxCount(),
					sceneMetrics.planeCount(),
					sceneMetrics.affineSphereCount(),
					sceneMetrics.affineBoxCount(),
					sceneMetrics.materialCount(),
					bvhSolid.stats().nodeCount(),
					bvhSolid.stats().boundedPrimitiveCount(),
					bvhSolid.stats().leafCount(),
					bvhSolid.stats().maxDepth(),
					bvhSolid.stats().leafSize(),
					null,
					null,
					null,
					bvhSolid.stats().sahCost(),
					bvhSolid.stats().sahCost(),
					null,
					"mode=UNIFORM_SAH,leafSize=" + bvhSolid.stats().leafSize()
			));
		}

		int totalFrames = Math.max(0, warmupFrames) + Math.max(0, frames);
		boolean collectMetrics = Boolean.getBoolean("gfxlab.cpu.collectMetrics");
		for (int frame = 0; frame < totalFrames; frame++) {
			boolean measured = frame >= warmupFrames;
			TraceCounters traceCounters = measured && recorder != null && collectMetrics ? new TraceCounters() : null;
			if (traceCounters != null) {
				tracer.setBenchmarkCounters(traceCounters, 0);
			}
			int frameIndex = frame;
			long frameStart = System.nanoTime();
			double[] target = measured ? sums : warmupSums;
			IntStream.range(0, height).parallel().forEach(y -> renderRow(tracer, target, frameIndex, y));
			long totalNanos = System.nanoTime() - frameStart;
			if (measured && recorder != null) {
				if (traceCounters == null) {
					recorder.record(1, width, height, totalNanos, null, null);
				} else {
					TraceCounters.Snapshot snapshot = traceCounters.snapshot();
					recorder.record(new BenchmarkRecorder.FrameMetrics(
							1,
							totalNanos,
							null,
							null,
							null,
							null,
							width,
							height,
							snapshot.primaryRays(),
							snapshot.rays(),
							snapshot.primitiveTests(),
							snapshot.aabbTests(),
							null, null, null, null, null,
							null, null, null, null,
							snapshot.internalNodeVisits(), snapshot.leafNodeVisits(),
							null, null,
							null
					));
				}
			}
			tracer.setBenchmarkCounters(null, 0);
			int measuredFrame = frame - warmupFrames + 1;
			if (measured && progressEvery > 0 && measuredFrame % progressEvery == 0) {
				System.out.printf("  progress %d/%d%n", measuredFrame, frames);
			}
		}

		return new HeadlessCpuRenderer.RenderedImage(width, height, Math.max(frames, 1), sums);
	}

	private void renderRow(PathTracer tracer, double[] sums, int frame, int y) {
		double aspect = width / (double) height;
		for (int x = 0; x < width; x++) {
			double sensorX = (-1.0 + 2.0 * (x + sample01(frame, x, y, 0)) / width) * aspect;
			double sensorY = 1.0 - 2.0 * (y + sample01(frame, x, y, 1)) / height;
			Color color = tracer.at(0.0, Vector.xy(sensorX, sensorY));

			int index = (y * width + x) * 3;
			sums[index] += color.r();
			sums[index + 1] += color.g();
			sums[index + 2] += color.b();
		}
	}

	private double sample01(int frame, int x, int y, int dimension) {
		long key = seed
				^ ((long) frame * 0x9E3779B97F4A7C15L)
				^ ((long) x * 0xC2B2AE3D27D4EB4FL)
				^ ((long) y * 0x165667B19E3779F9L)
				^ ((long) dimension * 0xD6E8FEB86659FD93L);
		return ((mix64(key) >>> 11) * 0x1.0p-53);
	}

	private static long mix64(long z) {
		z += 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private record SceneWithSolid(Scene delegate, Solid solid) implements Scene {
		@Override
		public Color colorBackground() {
			return delegate.colorBackground();
		}
	}
}
