package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic front-projection source used by {@link GalleryOverlapScene}.
 */
final class GalleryOverlapSource {
	static final int EVALUATION_OBJECTS = 10_000;
	private static final int OVERLAP_SPHERES = 3_000;
	private static final int OVERLAP_LARGE_SPHERES = 1_000;
	private static final int OVERLAP_FOREGROUND_LARGE_SPHERES = 128;
	private static final double PERSPECTIVE_Z = 3.0;
	private static final double GOLDEN_ANGLE_TURNS = 0.3819660112501051;

	private static final Material[] OVERLAP_MATERIALS = {
		SceneSupport.emissive(Color.rgb(0.46, 0.018, 0.006)),
		SceneSupport.emissive(Color.rgb(0.006, 0.17, 0.62)),
		SceneSupport.emissive(Color.rgb(0.014, 0.42, 0.22)),
		SceneSupport.emissive(Color.rgb(0.70, 0.16, 0.010))
	};
	private static final Material[] OVERLAP_SPHERE_MATERIALS = {
		SceneSupport.emissive(Color.rgb(0.10, 0.56, 0.88)),
		SceneSupport.emissive(Color.rgb(0.92, 0.23, 0.035)),
		SceneSupport.emissive(Color.rgb(0.055, 0.72, 0.34)),
		SceneSupport.emissive(Color.rgb(0.90, 0.50, 0.040))
	};
	private static final Material OVERLAP_MIRROR = SceneSupport.surface(
			Color.BLACK,
			Color.BLACK,
			Color.gray(0.92),
			Color.BLACK,
			1.5);
	private static final Material OVERLAP_GLASS = SceneSupport.surface(
			Color.BLACK,
			Color.BLACK,
			Color.gray(0.05),
			Color.gray(0.95),
			1.45);

	private GalleryOverlapSource() {
	}

	/**
	 * Most primitives are extremely thin direct boxes whose long axis spans a
	 * large fraction of the image. Their projected area remains small while
	 * their axis-aligned bounds overlap broad regions of the scene.
	 */
	static Scene overlapGallery() {
		List<Solid> solids = new ArrayList<>(EVALUATION_OBJECTS);
		int sphereOrdinal = 0;
		int largeSphereOrdinal = 0;
		int foregroundLargeOrdinal = 0;
		int interleavedLargeOrdinal = 0;
		for (int index = 0; index < EVALUATION_OBJECTS; index++) {
			OverlapPlacement placement = overlapPlacement(index);
			boolean sphere = evenlySelected(
					index, EVALUATION_OBJECTS, OVERLAP_SPHERES);
			boolean largeSphere = sphere && evenlySelected(
					sphereOrdinal, OVERLAP_SPHERES, OVERLAP_LARGE_SPHERES);
			boolean foregroundLargeSphere = largeSphere && evenlySelected(
					largeSphereOrdinal,
					OVERLAP_LARGE_SPHERES,
					OVERLAP_FOREGROUND_LARGE_SPHERES);
			double depth = foregroundLargeSphere
					? 5.03 + 0.07 * unit(index, 0x4B1D5A77L)
					: 5.2 + 7.8 * unit(index, 0x4B1D5A77L);
			Material material = OVERLAP_MATERIALS[index & 3];
			if (sphere) {
				double radiusSensor = largeSphere
						? foregroundLargeSphere
								? 0.018 + 0.006 * unit(index, 0x23A4D159L)
								: 0.013 + 0.005 * unit(index, 0x23A4D159L)
						: 0.0040 + 0.0020 * unit(index, 0x23A4D159L);
				Material sphereMaterial = material;
				if (largeSphere) {
					int opticalClass;
					if (foregroundLargeSphere) {
						opticalClass = Math.floorMod(
								foregroundLargeOrdinal * 53, 128);
						foregroundLargeOrdinal++;
						sphereMaterial = opticalClass < 48
								? OVERLAP_GLASS
								: opticalClass < 96
										? OVERLAP_MIRROR
										: OVERLAP_SPHERE_MATERIALS[largeSphereOrdinal & 3];
					} else {
						opticalClass = Math.floorMod(
								interleavedLargeOrdinal * 361, 872);
						interleavedLargeOrdinal++;
						sphereMaterial = opticalClass < 80
								? OVERLAP_GLASS
								: opticalClass < 160
										? OVERLAP_MIRROR
										: OVERLAP_SPHERE_MATERIALS[largeSphereOrdinal & 3];
					}
					largeSphereOrdinal++;
				}
				solids.add(SceneSupport.sphere(
						worldPoint(placement.sensorX(), placement.sensorY(), depth),
						radiusSensor * depth / PERSPECTIVE_Z,
						sphereMaterial));
				sphereOrdinal++;
				continue;
			}

			double halfLengthSensor = 1.03 + 0.34 * unit(index, 0x6F23E991L);
			double halfWidthSensor = 0.000045 + 0.000025 * unit(index, 0x19C5B7D3L);
			double radians = placement.rotationTurns() * 2.0 * Math.PI;
			boolean horizontal = Math.abs(Math.sin(radians)) >= Math.abs(Math.cos(radians));
			double halfLength = halfLengthSensor * depth / PERSPECTIVE_Z;
			double halfWidth = halfWidthSensor * depth / PERSPECTIVE_Z;
			Vec3 halfExtents = horizontal
					? Vec3.xyz(halfLength, halfWidth, 0.008)
					: Vec3.xyz(halfWidth, halfLength, 0.008);
			solids.add(SceneSupport.box(
					worldPoint(placement.sensorX(), placement.sensorY(), depth),
					halfExtents,
					material));
		}
		if (sphereOrdinal != OVERLAP_SPHERES) {
			throw new IllegalStateException(
					"Overlap scene contains " + sphereOrdinal
							+ " spheres; expected " + OVERLAP_SPHERES + '.');
		}
		if (largeSphereOrdinal != OVERLAP_LARGE_SPHERES) {
			throw new IllegalStateException(
					"Overlap scene contains " + largeSphereOrdinal
							+ " large spheres; expected " + OVERLAP_LARGE_SPHERES + '.');
		}
		if (foregroundLargeOrdinal != OVERLAP_FOREGROUND_LARGE_SPHERES) {
			throw new IllegalStateException(
					"Overlap scene contains " + foregroundLargeOrdinal
							+ " foreground large spheres; expected "
							+ OVERLAP_FOREGROUND_LARGE_SPHERES + '.');
		}
		requireTotal(solids, EVALUATION_OBJECTS);
		return new SimpleScene(Group.of(solids), Color.rgb(0.003, 0.006, 0.014));
	}

	private static OverlapPlacement overlapPlacement(int index) {
		double turn = fractional(index * GOLDEN_ANGLE_TURNS);
		double angle = turn * 2.0 * Math.PI;
		double progress = (index + 0.5) / EVALUATION_OBJECTS;
		double radius = 0.12 + 1.05 * Math.sqrt(progress);
		double phase = angle + 5.0 * Math.PI * progress;
		return new OverlapPlacement(
				1.34 * radius * Math.cos(phase),
				0.72 * radius * Math.sin(phase),
				turn + 0.25 + 0.020 * Math.sin(phase * 3.0));
	}

	private static Vec3 worldPoint(double sensorX, double sensorY, double depth) {
		return Vec3.xyz(
				sensorX * depth / PERSPECTIVE_Z,
				sensorY * depth / PERSPECTIVE_Z,
				depth);
	}

	private static double unit(int index, long salt) {
		long value = index * 0x9E3779B97F4A7C15L + salt;
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return (value >>> 11) * 0x1.0p-53;
	}

	private static double fractional(double value) {
		return value - Math.floor(value);
	}

	private static boolean evenlySelected(int index, int population, int selected) {
		return (long) (index + 1) * selected / population
				!= (long) index * selected / population;
	}

	private static void requireTotal(List<Solid> solids, int expected) {
		if (solids.size() != expected) {
			throw new IllegalStateException(
					"Gallery source contains " + solids.size()
							+ " primitives; expected " + expected + '.');
		}
	}

	private record OverlapPlacement(
			double sensorX,
			double sensorY,
			double rotationTurns
	) {
	}
}
