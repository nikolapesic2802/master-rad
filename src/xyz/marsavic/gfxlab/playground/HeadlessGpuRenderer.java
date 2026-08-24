package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.geometry.Vector;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;
import xyz.marsavic.gfxlab.benchmark.SceneBenchmarkMetrics;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.cameras.TransformedCamera;
import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;
import xyz.marsavic.gfxlab.gpu.BvhBuildMode;
import xyz.marsavic.gfxlab.gpu.GpuRayTracer;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.GpuSceneBuilder;
import xyz.marsavic.gfxlab.gpu.TraversalWorkMetric;

final class HeadlessGpuRenderer {

	private final Scene scene;
	private final Camera camera;
	private final int width;
	private final int height;
	private final int maxDepth;
	private final long seed;
	private final int samplesPerFrame;
	private final boolean bvhTraversal;
	private final BvhBuildMode bvhBuildMode;

	HeadlessGpuRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed) {
		this(scene, camera, width, height, maxDepth, seed, false);
	}

	HeadlessGpuRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed, boolean bvhTraversal) {
		this(scene, camera, width, height, maxDepth, seed, bvhTraversal, BvhBuildMode.UNIFORM_SAH);
	}

	HeadlessGpuRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed, BvhBuildMode bvhBuildMode) {
		this(scene, camera, width, height, maxDepth, seed, true, bvhBuildMode);
	}

	private HeadlessGpuRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed,
	                            boolean bvhTraversal, BvhBuildMode bvhBuildMode) {
		GpuRayTracer.validateFullPathDepth(maxDepth);
		this.scene = scene;
		this.camera = camera;
		this.width = width;
		this.height = height;
		this.maxDepth = maxDepth;
		this.seed = seed;
		this.bvhTraversal = bvhTraversal;
		this.bvhBuildMode = bvhBuildMode;
		this.samplesPerFrame = RendererFactory.effectiveSamplesPerFrame(
				RendererFactory.Version.GPU_LINEAR);
	}

	HeadlessCpuRenderer.RenderedImage render(int frames, int warmupFrames, int progressEvery, BenchmarkRecorder recorder) {
		BvhBuildConfig buildConfig = BvhBuildConfig.fromProperties(bvhBuildMode);
		GpuScene gpuScene = GpuSceneBuilder.from(scene, buildConfig);
		GpuScene.BvhStats bvhStats = bvhTraversal ? gpuScene.bvhStats() : null;
		GpuCamera gpuCamera = buildCamera(camera);
		float[] frame = new float[width * height * 3];
		double[] sums = new double[width * height * 3];
		if (recorder != null) {
			SceneBenchmarkMetrics metrics = SceneBenchmarkMetrics.fromGpuScene(gpuScene);
			long bvhBytes = bvhStats == null ? 0L : bvhStats.bytes();
			long hostBytes = (long) sums.length * Double.BYTES + metrics.sceneBytes() + bvhBytes;
			long estimatedDeviceBytes = (long) frame.length * Float.BYTES + metrics.sceneBytes() + bvhBytes
					+ 14L * Long.BYTES;
			recorder.setRunMetadata(new BenchmarkRecorder.RunMetadata(
					metrics.sceneBytes(), bvhBytes, hostBytes,
					estimatedDeviceBytes,
					metrics.primitiveCount(), metrics.sphereCount(), metrics.boxCount(), metrics.planeCount(),
					metrics.affineSphereCount(), metrics.affineBoxCount(), metrics.materialCount(),
					bvhStats == null ? 0 : bvhStats.nodeCount(),
					bvhStats == null ? 0 : bvhStats.primitiveRefCount(),
					bvhStats == null ? 0 : bvhStats.leafCount(),
					bvhStats == null ? 0 : bvhStats.maxDepth(), bvhStats == null ? null : bvhStats.leafSize(),
					bvhStats == null ? null : bvhStats.minLeafOccupancy(),
					bvhStats == null ? null : bvhStats.maxLeafOccupancy(),
					bvhStats == null ? null : bvhStats.meanLeafOccupancy(),
					bvhStats == null ? null : bvhStats.generalizedSahCost(),
					bvhStats == null ? null : bvhStats.uniformSahCost(),
					bvhStats == null ? null : bvhStats.weightedSahCost(),
					bvhTraversal ? buildConfig.summary() : "mode=LINEAR"));
		}

		try (GpuRayTracer tracer = new GpuRayTracer(width, height, samplesPerFrame, bvhTraversal)) {
			if (!tracer.isAvailable()) {
				throw new IllegalStateException("CUDA path tracer is unavailable");
			}
			if (recorder != null) {
				recorder.setCompiledPtxSha256(tracer.compiledPtxSha256());
			}

			int totalFrames = Math.max(0, warmupFrames) + Math.max(0, frames);
			for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
				long frameSeed = mix64(seed ^ ((long) frameIndex * 0x9E3779B97F4A7C15L));
				long rendererWallStart = System.nanoTime();
				tracer.renderSample(frame, gpuScene, gpuCamera, maxDepth, frameIndex, frameSeed);
				boolean measured = frameIndex >= warmupFrames;
				if (measured) {
					accumulate(frame, sums);
				}
				long rendererWallNanos = System.nanoTime() - rendererWallStart;

				GpuRayTracer.FrameStats stats = tracer.lastFrameStats();
				if (measured && warmupFrames > 0 && stats.uploadNanos() != 0L) {
					throw new IllegalStateException(
							"Measured steady-state GPU frame unexpectedly uploaded scene data");
				}
				if (measured && recorder != null) {
					boolean hasMetrics = Boolean.getBoolean("gfxlab.gpu.collectMetrics");
					long primaryRays = (long) width * height * samplesPerFrame;
					double traversalWork = TraversalWorkMetric.cost(stats);
					recorder.record(new BenchmarkRecorder.FrameMetrics(
							samplesPerFrame, rendererWallNanos, stats.kernelNanos(),
							stats.maximumPhysicalKernelNanos(), stats.copyNanos(),
							stats.uploadNanos(), width, height,
							hasMetrics ? primaryRays : null,
							hasMetrics ? stats.rays() : null,
							hasMetrics ? stats.primitiveTests() : null,
							hasMetrics ? stats.aabbTests() : null,
							hasMetrics ? stats.sphereTests() : null,
							hasMetrics ? stats.boxTests() : null,
							hasMetrics ? stats.planeTests() : null,
							hasMetrics ? stats.affineSphereTests() : null,
							hasMetrics ? stats.affineBoxTests() : null,
							hasMetrics ? rootAabbTests(stats) : null,
							hasMetrics ? traversalWork : null,
							hasMetrics ? stats.stackOverflows() : null,
							hasMetrics ? stats.maxStackSize() : null,
							hasMetrics ? stats.internalNodeVisits() : null,
							hasMetrics ? stats.leafNodeVisits() : null,
							hasMetrics ? stats.homogeneousLeafNodeVisits() : null,
							hasMetrics ? stats.mixedLeafNodeVisits() : null,
							stats.totalNanos()));
				}
				int measuredFrame = frameIndex - warmupFrames + 1;
				if (measured && progressEvery > 0 && measuredFrame % progressEvery == 0) {
					System.out.printf("  progress %d/%d%n", measuredFrame, frames);
				}
			}
		}

		return new HeadlessCpuRenderer.RenderedImage(width, height, Math.max(1, frames * samplesPerFrame), sums);
	}

	private static long rootAabbTests(GpuRayTracer.FrameStats stats) {
		long treeEntryTests = stats.aabbTests() - 2L * stats.internalNodeVisits();
		if (treeEntryTests < 0L) {
			throw new IllegalStateException("AABB and interior-node counters are inconsistent");
		}
		return treeEntryTests;
	}

	private void accumulate(float[] frame, double[] sums) {
		for (int i = 0; i < frame.length; i++) {
			sums[i] += frame[i] * samplesPerFrame;
		}
	}

	private GpuCamera buildCamera(Camera camera) {
		double centerX = (width - 1) / 2.0;
		double centerY = (height - 1) / 2.0;

		Vec3 sensorCenter = pixelToSensor(centerX + 0.5, centerY + 0.5);
		Ray centerRay = deterministicRay(camera, Vector.xy(sensorCenter.x(), sensorCenter.y()));
		Vec3 sensorRight = pixelToSensor(Math.min(width - 1, centerX + 1.0) + 0.5, centerY + 0.5);
		Ray rightRay = deterministicRay(camera, Vector.xy(sensorRight.x(), sensorRight.y()));
		Vec3 sensorUp = pixelToSensor(centerX + 0.5, Math.min(height - 1, centerY + 1.0) + 0.5);
		Ray upRay = deterministicRay(camera, Vector.xy(sensorUp.x(), sensorUp.y()));

		double deltaU = nonZero(sensorRight.x() - sensorCenter.x());
		double deltaV = nonZero(sensorUp.y() - sensorCenter.y());
		Vec3 right = rightRay.d().sub(centerRay.d()).div(deltaU);
		Vec3 up = upRay.d().sub(centerRay.d()).div(deltaV);
		Vec3 forward = centerRay.d().sub(right.mul(sensorCenter.x())).sub(up.mul(sensorCenter.y()));

		return new GpuCamera(centerRay.p(), forward, right, up);
	}

	private Vec3 pixelToSensor(double x, double y) {
		double aspect = width / (double) height;
		return Vec3.xyz((-1.0 + 2.0 * x / width) * aspect, 1.0 - 2.0 * y / height, 0.0);
	}

	private static double nonZero(double value) {
		return Math.abs(value) < 1.0e-9 ? Math.copySign(1.0e-9, value == 0.0 ? 1.0 : value) : value;
	}

	private static Ray deterministicRay(Camera camera, Vector sensorPosition) {
		if (camera instanceof TransformedCamera transformed) {
			return transformed.transformation().at(deterministicRay(transformed.source(), sensorPosition));
		}
		return camera.exitingRay(sensorPosition);
	}

	private static long mix64(long z) {
		z += 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

}
