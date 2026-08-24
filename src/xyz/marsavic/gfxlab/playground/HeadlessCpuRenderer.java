package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.geometry.Vector;
import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.benchmark.BenchmarkRecorder;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.raytracers.PathTracer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

final class HeadlessCpuRenderer {
	private static final double DEFAULT_TONEMAP_PRE_FACTOR = 0x1p-4;
	private static final double DEFAULT_TONEMAP_POWER = 1.0;

	private final Scene scene;
	private final Camera camera;
	private final int width;
	private final int height;
	private final int maxDepth;
	private final long seed;

	HeadlessCpuRenderer(Scene scene, Camera camera, int width, int height, int maxDepth, long seed) {
		this.scene = scene;
		this.camera = camera;
		this.width = width;
		this.height = height;
		this.maxDepth = maxDepth;
		this.seed = seed;
	}

	RenderedImage render(int frames, int progressEvery, BenchmarkRecorder recorder) {
		return render(frames, 0, progressEvery, recorder);
	}

	RenderedImage render(int frames, int warmupFrames, int progressEvery, BenchmarkRecorder recorder) {
		PathTracer tracer = new PathTracer(scene, camera, maxDepth);
		double[] sums = new double[width * height * 3];
		double[] warmupSums = warmupFrames > 0 ? new double[width * height * 3] : sums;
		int totalFrames = Math.max(0, warmupFrames) + Math.max(0, frames);

		for (int frame = 0; frame < totalFrames; frame++) {
			int frameIndex = frame;
			boolean measured = frame >= warmupFrames;
			long frameStart = System.nanoTime();
			double[] target = measured ? sums : warmupSums;
			IntStream.range(0, height).parallel().forEach(y -> renderRow(tracer, target, frameIndex, y));
			long totalNanos = System.nanoTime() - frameStart;
			if (measured && recorder != null) {
				recorder.record(1, width, height, totalNanos, null, null);
			}
			int measuredFrame = frame - warmupFrames + 1;
			if (measured && progressEvery > 0 && measuredFrame % progressEvery == 0) {
				System.out.printf("  progress %d/%d%n", measuredFrame, frames);
			}
		}

		return new RenderedImage(width, height, Math.max(frames, 1), sums);
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

	record RenderedImage(int width, int height, int samples, double[] sums) {
		BufferedImage toBufferedImage() {
			return toBufferedImage(DEFAULT_TONEMAP_PRE_FACTOR, DEFAULT_TONEMAP_POWER);
		}

		BufferedImage toBufferedImage(double preFactor, double power) {
			return toBufferedImage(preFactor, power, tonemapPostFactor(preFactor, power));
		}

		BufferedImage toBufferedImage(double preFactor, double power, double postFactor) {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int index = (y * width + x) * 3;
					double r = sums[index] / samples;
					double g = sums[index + 1] / samples;
					double b = sums[index + 2] / samples;
					double scale = luminanceScale(r, g, b, preFactor, power) * postFactor;
					int argb = 0xFF000000
							| (Color.valueToByteClamp(r * scale) << 16)
							| (Color.valueToByteClamp(g * scale) << 8)
							| (Color.valueToByteClamp(b * scale));
					image.setRGB(x, y, argb);
				}
			}
			return image;
		}

		void writePng(Path path) throws IOException {
			Files.createDirectories(path.getParent());
			ImageIO.write(toBufferedImage(), "png", path.toFile());
		}

		void writePng(Path path, double postFactor) throws IOException {
			Files.createDirectories(path.getParent());
			ImageIO.write(toBufferedImage(DEFAULT_TONEMAP_PRE_FACTOR, DEFAULT_TONEMAP_POWER, postFactor), "png", path.toFile());
		}

		double tonemapPostFactor() {
			return tonemapPostFactor(DEFAULT_TONEMAP_PRE_FACTOR, DEFAULT_TONEMAP_POWER);
		}

		double tonemapPostFactor(double preFactor, double power) {
			double maxMapped = 0.0;
			for (int i = 0; i < sums.length; i += 3) {
				double r = sums[i] / samples;
				double g = sums[i + 1] / samples;
				double b = sums[i + 2] / samples;
				double scale = luminanceScale(r, g, b, preFactor, power);
				maxMapped = Math.max(maxMapped, Math.max(r, Math.max(g, b)) * scale);
			}
			return maxMapped > 0.0 ? 1.0 / maxMapped : 1.0;
		}

		private static double luminanceScale(double r, double g, double b, double preFactor, double power) {
			double luminance = 0.212655 * r + 0.715158 * g + 0.072187 * b;
			if (luminance <= 0.0) {
				return 0.0;
			}
			double pre = luminance * preFactor;
			double mapped = 1.0 - 1.0 / (1.0 + Math.pow(pre, power));
			return mapped / luminance;
		}
	}
}
