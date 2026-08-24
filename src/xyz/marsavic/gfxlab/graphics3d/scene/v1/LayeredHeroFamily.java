package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;

/**
 * Layered Hero scenes made from repeated modules of six spheres and two affine
 * boxes. Module positions are fixed in camera-sensor space, so larger scenes
 * refine the same composition. The box centroids lie behind the spheres while
 * their tilted bounds extend toward the sphere layer, leaving both screen-space
 * and depth partitions as viable BVH candidates.
 */
public final class LayeredHeroFamily {
	public static final int PRIMITIVES_PER_MODULE = 8;
	public static final int AFFINE_BOXES_PER_MODULE = 2;
	private static final Geometry GEOMETRY = new Geometry(0.750, 2.000, 0.150);

	private static final double ASPECT = 16.0 / 9.0;
	private static final double SENSOR_HALF_HEIGHT = 0.92;
	private static final double SENSOR_HALF_WIDTH = ASPECT * SENSOR_HALF_HEIGHT;
	private static final double PERSPECTIVE_Z = 3.0;
	private static final double LAYER_OFFSET = 0.28;
	private static final double MODULE_SCALE = 0.35;
	private static final double DEPTH_STEP = 2.35;

	private static final double OUTER_X = 0.72;
	private static final double OUTER_Y = 0.72;
	private static final double MIDDLE_Y = 0.76;
	private static final double SPHERE_RADIUS = 0.150;
	private static final double BOX_HALF_WIDTH = 0.065;
	private static final double BOX_HALF_DEPTH = 0.110;
	private static final double DIAGONAL_TURNS = 0.125;
	private static final double BOX_NORMAL_OFFSET = 0.140;

	private static final Palette EMISSIVE_PALETTE = new Palette(
			emissive(Color.rgb(0.015, 0.56, 0.36)),
			emissive(Color.rgb(0.015, 0.34, 0.78)),
			emissive(Color.rgb(0.13, 0.48, 0.98)),
			emissive(Color.rgb(1.52, 0.045, 0.008)),
			emissive(Color.rgb(1.28, 0.25, 0.010)));

	private LayeredHeroFamily() {
	}

	/** Three staggered, simultaneously visible depth layers sharing each screen-space cell. */
	public static Scene create(int primitiveCount) {
		validateCount(primitiveCount);
		int moduleCount = primitiveCount / PRIMITIVES_PER_MODULE;
		int cellCount = (moduleCount + 2) / 3;
		Grid grid = Grid.forModules(cellCount);
		List<Solid> solids = new ArrayList<>(primitiveCount + 9);
		int module = 0;
		for (int row = 0; row < grid.rows() && module < moduleCount; row++) {
			int cellsInRow = grid.columnsInRow(row);
			for (int column = 0; column < cellsInRow && module < moduleCount; column++) {
				double cellX = grid.sensorX(column, cellsInRow);
				double cellY = grid.sensorY(row);
				double cellScale = Math.min(grid.sensorCellWidth(), grid.sensorCellHeight());
				for (int layer = 0; layer < 3 && module < moduleCount; layer++, module++) {
					double angle = (layer / 3.0 - 0.25) * 2.0 * Math.PI;
					double sensorX = cellX + LAYER_OFFSET * cellScale * Math.cos(angle);
					double sensorY = cellY + LAYER_OFFSET * cellScale * Math.sin(angle);
					double sensorScale = MODULE_SCALE * cellScale;
					double depth = 4.8 + layer * DEPTH_STEP;
					int orientationSign = ((row + column + layer) & 1) == 0 ? 1 : -1;
					addModule(solids, GEOMETRY, sensorX, sensorY, sensorScale, depth,
							orientationSign, EMISSIVE_PALETTE);
				}
			}
		}
		if (solids.size() != primitiveCount) {
			throw new IllegalStateException("Layered module count mismatch: " + solids.size());
		}
		addRayTracingEnvironment(solids, 12.8);
		return new SimpleScene(Group.of(solids), Color.rgb(0.002, 0.004, 0.014));
	}

	private static void addRayTracingEnvironment(List<Solid> solids, double rearPlaneZ) {
		Material rearMirror = SceneSupport.surface(
				Color.rgb(0.002, 0.003, 0.006), Color.BLACK,
				Color.rgb(0.82, 0.88, 0.98), Color.BLACK, 1.5);
		Material glassLens = SceneSupport.surface(
				Color.rgb(0.004, 0.010, 0.018), Color.BLACK,
				Color.rgb(0.06, 0.08, 0.10), Color.rgb(0.88, 0.96, 1.00), 1.33);
		Material mirrorFrame = SceneSupport.surface(
				Color.rgb(0.025, 0.22, 0.72), Color.rgb(0.010, 0.035, 0.10),
				Color.rgb(0.22, 0.30, 0.46), Color.BLACK, 1.5);

		// The reflective surface is finite and contained by the blue frame.
		double frameZ = rearPlaneZ - 0.10;
		double frameScale = rearPlaneZ / 8.8;
		solids.add(SceneSupport.box(Vec3.xyz(0.0, 0.0, rearPlaneZ + 0.045),
				Vec3.xyz(2.66 * frameScale, 1.57 * frameScale, 0.045), rearMirror));
		solids.add(SceneSupport.box(Vec3.xyz(-2.72 * frameScale, 0.0, frameZ),
				Vec3.xyz(0.055 * frameScale, 1.68 * frameScale, 0.045), mirrorFrame));
		solids.add(SceneSupport.box(Vec3.xyz(2.72 * frameScale, 0.0, frameZ),
				Vec3.xyz(0.055 * frameScale, 1.68 * frameScale, 0.045), mirrorFrame));
		solids.add(SceneSupport.box(Vec3.xyz(0.0, 1.68 * frameScale, frameZ),
				Vec3.xyz(2.78 * frameScale, 0.055 * frameScale, 0.045), mirrorFrame));
		solids.add(SceneSupport.box(Vec3.xyz(0.0, -1.68 * frameScale, frameZ),
				Vec3.xyz(2.78 * frameScale, 0.055 * frameScale, 0.045), mirrorFrame));
		// The four corner lenses are fixed across scene sizes.
		for (double x : new double[] {-2.15, 2.15}) {
			for (double y : new double[] {-1.18, 1.18}) {
				solids.add(SceneSupport.sphere(Vec3.xyz(x, y, 4.20), 0.22, glassLens));
			}
		}
	}

	public static Camera camera() {
		return SceneSupport.camera(Vec3.ZERO, 0.0, 0.0);
	}

	private static void addModule(List<Solid> solids,
	                              Geometry geometry,
	                              double sensorX,
	                              double sensorY,
	                              double sensorScale,
	                              double depth,
	                              int orientationSign,
	                              Palette palette) {
		double baseWorldUnit = sensorScale * depth / PERSPECTIVE_Z;
		double halfDepthSeparation = 0.5 * geometry.depthSeparation() * baseWorldUnit;
		double cheapDepth = depth - halfDepthSeparation;
		double boxDepth = depth + halfDepthSeparation;

		addSphere(solids, sensorX - OUTER_X * sensorScale,
				sensorY + OUTER_Y * sensorScale, sensorScale, cheapDepth, palette.green());
		addSphere(solids, sensorX, sensorY + MIDDLE_Y * sensorScale,
				sensorScale, cheapDepth, palette.blue());
		addSphere(solids, sensorX + OUTER_X * sensorScale,
				sensorY + OUTER_Y * sensorScale, sensorScale, cheapDepth, palette.cyan());
		addSphere(solids, sensorX - OUTER_X * sensorScale,
				sensorY - OUTER_Y * sensorScale, sensorScale, cheapDepth, palette.cyan());
		addSphere(solids, sensorX, sensorY - MIDDLE_Y * sensorScale,
				sensorScale, cheapDepth, palette.green());
		addSphere(solids, sensorX + OUTER_X * sensorScale,
				sensorY - OUTER_Y * sensorScale, sensorScale, cheapDepth, palette.blue());

		addBoxPair(solids, geometry, sensorX, sensorY, sensorScale,
				boxDepth, orientationSign, palette);
	}

	private static void addBoxPair(List<Solid> solids,
	                               Geometry geometry,
	                               double sensorX,
	                               double sensorY,
	                               double sensorScale,
	                               double depth,
	                               int orientationSign,
	                               Palette palette) {
		double baseRotation = orientationSign * DIAGONAL_TURNS;
		double radians = baseRotation * 2.0 * Math.PI;
		// Unit local Y after the screen rotation, plus its screen-space normal.
		double axisX = -Math.sin(radians);
		double axisY = Math.cos(radians);
		double normalX = axisY;
		double normalY = -axisX;
		double normalOffset = BOX_NORMAL_OFFSET;

		for (int box = 0; box < AFFINE_BOXES_PER_MODULE; box++) {
			double sign = box == 0 ? -1.0 : 1.0;
			double boxX = sensorX + sign * sensorScale
					* normalOffset * normalX;
			double boxY = sensorY + sign * sensorScale
					* normalOffset * normalY;
			double screenRotation = baseRotation + (box == 0 ? 0.0 : 0.5);
			double depthTilt = sign * geometry.boxDepthTiltTurns();
			double unit = sensorScale * depth / PERSPECTIVE_Z;
			Affine transform = Affine.IDENTITY
					.then(Affine.scaling(Vec3.xyz(
							BOX_HALF_WIDTH * unit,
							geometry.boxHalfLength() * unit,
							BOX_HALF_DEPTH * unit)))
					.then(Affine.rotationAboutX(depthTilt))
					.then(Affine.rotationAboutZ(screenRotation))
					.then(Affine.translation(worldPoint(boxX, boxY, depth)));
			solids.add(SceneSupport.box(transform,
					box == 0 ? palette.red() : palette.orange()));
		}
	}

	private static void addSphere(List<Solid> solids,
	                              double sensorX,
	                              double sensorY,
	                              double sensorScale,
	                              double depth,
	                              Material material) {
		double worldPerSensor = depth / PERSPECTIVE_Z;
		solids.add(SceneSupport.sphere(worldPoint(sensorX, sensorY, depth),
				SPHERE_RADIUS * sensorScale * worldPerSensor, material));
	}

	private static Vec3 worldPoint(double sensorX, double sensorY, double depth) {
		return Vec3.xyz(sensorX * depth / PERSPECTIVE_Z,
				sensorY * depth / PERSPECTIVE_Z, depth);
	}

	private static Material emissive(Color emission) {
		return SceneSupport.surface(emission, Color.BLACK, Color.BLACK, Color.BLACK, 1.5);
	}

	private static void validateCount(int primitiveCount) {
		if (primitiveCount <= 0 || primitiveCount % PRIMITIVES_PER_MODULE != 0) {
			throw new IllegalArgumentException("primitiveCount must be a positive multiple of "
					+ PRIMITIVES_PER_MODULE + ": " + primitiveCount);
		}
	}

	private record Geometry(double depthSeparation,
	                        double boxHalfLength,
	                        double boxDepthTiltTurns) {
	}

	private record Palette(Material green,
	                       Material cyan,
	                       Material blue,
	                       Material red,
	                       Material orange) {
	}

	private record Grid(int rows,
	                    int baseColumns,
	                    int extraRows,
	                    double sensorCellWidth,
	                    double sensorCellHeight) {
		static Grid forModules(int moduleCount) {
			int nominalColumns = Math.max(1, (int) Math.ceil(Math.sqrt(moduleCount * ASPECT)));
			int rows = Math.max(1, (moduleCount + nominalColumns - 1) / nominalColumns);
			int baseColumns = moduleCount / rows;
			int extraRows = moduleCount % rows;
			return new Grid(rows, baseColumns, extraRows,
					2.0 * SENSOR_HALF_WIDTH / nominalColumns,
					2.0 * SENSOR_HALF_HEIGHT / rows);
		}

		int columnsInRow(int row) {
			return baseColumns + (hasCentredExtra(row) ? 1 : 0);
		}

		private boolean hasCentredExtra(int row) {
			if (extraRows <= 0) return false;
			if ((rows & 1) != 0) {
				int centre = rows / 2;
				int distance = Math.abs(row - centre);
				return (extraRows & 1) != 0
						? distance <= extraRows / 2
						: distance > 0 && distance <= extraRows / 2;
			}
			int pairCount = extraRows / 2;
			int lower = rows / 2 - pairCount;
			int upperExclusive = rows / 2 + pairCount;
			return (extraRows & 1) == 0
					? row >= lower && row < upperExclusive
					: row >= lower && row <= upperExclusive;
		}

		double sensorX(int column, int modulesInRow) {
			return (column - (modulesInRow - 1) * 0.5) * sensorCellWidth;
		}

		double sensorY(int row) {
			return SENSOR_HALF_HEIGHT - (row + 0.5) * sensorCellHeight;
		}
	}
}
