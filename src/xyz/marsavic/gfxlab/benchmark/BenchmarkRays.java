package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.geometry.Vector;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.gpu.GpuCamera;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.cameras.TransformedCamera;

/** Deterministic camera-ray grids shared by comparative benchmarks. */
public final class BenchmarkRays {
	private BenchmarkRays() { }

	public static float[] primaryGrid(Camera camera, int width, int height, double phaseX, double phaseY) {
		float[] data = new float[checkedRayFloatElements(width, height)];
		int write = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				Vec3 sensor = sensor(x + phaseX, y + phaseY, width, height);
				Ray ray = ray(camera, Vector.xy(sensor.x(), sensor.y()));
				write = put(data, write, ray.p());
				write = put(data, write, ray.d());
			}
		}
		return data;
	}

	public static GpuCamera gpuCamera(Camera camera, int width, int height) {
		double centerX = (width - 1) / 2.0;
		double centerY = (height - 1) / 2.0;
		Vec3 center = sensor(centerX + 0.5, centerY + 0.5, width, height);
		Ray centerRay = ray(camera, Vector.xy(center.x(), center.y()));
		Vec3 rightSensor = sensor(Math.min(width - 1, centerX + 1.0) + 0.5,
				centerY + 0.5, width, height);
		Vec3 upSensor = sensor(centerX + 0.5,
				Math.min(height - 1, centerY + 1.0) + 0.5, width, height);
		Ray rightRay = ray(camera, Vector.xy(rightSensor.x(), rightSensor.y()));
		Ray upRay = ray(camera, Vector.xy(upSensor.x(), upSensor.y()));
		Vec3 right = rightRay.d().sub(centerRay.d()).div(rightSensor.x() - center.x());
		Vec3 up = upRay.d().sub(centerRay.d()).div(upSensor.y() - center.y());
		Vec3 forward = centerRay.d().sub(right.mul(center.x())).sub(up.mul(center.y()));
		return new GpuCamera(centerRay.p(), forward, right, up);
	}

	private static int checkedRayFloatElements(int width, int height) {
		if (width < 1 || height < 1) {
			throw new IllegalArgumentException("Ray-grid dimensions must be positive");
		}
		try {
			long rays = Math.multiplyExact((long) width, (long) height);
			return Math.toIntExact(Math.multiplyExact(rays, 6L));
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException(
					"Ray grid exceeds Java array indexing", e);
		}
	}

	private static Vec3 sensor(double x, double y, int width, int height) {
		double aspect = width / (double) height;
		return Vec3.xyz((-1.0 + 2.0 * x / width) * aspect,
				1.0 - 2.0 * y / height, 0.0);
	}

	private static Ray ray(Camera camera, Vector sensor) {
		if (camera instanceof TransformedCamera transformed) {
			return transformed.transformation().at(ray(transformed.source(), sensor));
		}
		return camera.exitingRay(sensor);
	}

	private static int put(float[] data, int offset, Vec3 value) {
		data[offset++] = (float) value.x();
		data[offset++] = (float) value.y();
		data[offset++] = (float) value.z();
		return offset;
	}

}
