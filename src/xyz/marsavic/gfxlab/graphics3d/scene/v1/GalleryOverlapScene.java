package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;

/**
 * Gallery scene with overlapping projected primitives. Every long ribbon is
 * an ultra-thin orthogonal affine box whose endpoints lie at different depths
 * while retaining the intended front-view projection.
 */
public final class GalleryOverlapScene {
	public static final int OBJECT_COUNT = GalleryOverlapSource.EVALUATION_OBJECTS;
	public static final int SPHERE_COUNT = 3_000;
	public static final int RIBBON_COUNT = 7_000;
	public static final double PERSPECTIVE_Z = 3.0;
	public static final double RIBBON_HALF_THICKNESS = 0.008;
	private static final double MINIMUM_NEAR_DEPTH = 5.04;
	private static final double NEAR_DEPTH_RANGE = 0.14;
	private static final double MINIMUM_FAR_DEPTH = 12.82;
	private static final double FAR_DEPTH_RANGE = 0.14;

	private GalleryOverlapScene() { }

	public record Setup(
			Scene scene,
			Camera frontCamera,
			Camera sideCamera,
			int sphereCount,
			int affineBoxCount
	) {
		public Setup {
			if (scene == null || frontCamera == null || sideCamera == null
					|| sphereCount + affineBoxCount != OBJECT_COUNT) {
				throw new IllegalArgumentException("Gallery overlap setup must be complete.");
			}
		}
	}

	public static Setup create() {
		Scene source = GalleryOverlapSource.overlapGallery();
		if (!(source.solid() instanceof Group sourceGroup)) {
			throw new IllegalStateException("The overlap source must remain a group.");
		}
		Solid[] sourceSolids = sourceGroup.solids();
		if (sourceSolids.length != OBJECT_COUNT) {
			throw new IllegalStateException("The overlap source population changed.");
		}

		List<Solid> converted = new ArrayList<>(sourceSolids.length);
		int spheres = 0;
		int ribbons = 0;

		for (int index = 0; index < sourceSolids.length; index++) {
			Solid solid = sourceSolids[index];
			if (solid instanceof SceneSphere) {
				converted.add(solid);
				spheres++;
				continue;
			}
			if (!(solid instanceof SceneBox sourceBox) || sourceBox.isAffine()) {
				throw new IllegalStateException(
						"Unexpected overlap primitive at source index " + index + '.');
			}

			Vec3 center = sourceBox.p().add(sourceBox.q()).mul(0.5);
			Vec3 radii = sourceBox.q().sub(sourceBox.p()).mul(0.5);
			boolean horizontal = radii.x() > radii.y();
			double oldHalfLength = horizontal ? radii.x() : radii.y();
			double oldHalfWidth = horizontal ? radii.y() : radii.x();
			double sensorCenterX = PERSPECTIVE_Z * center.x() / center.z();
			double sensorCenterY = PERSPECTIVE_Z * center.y() / center.z();
			double sensorHalfLength = PERSPECTIVE_Z * oldHalfLength / center.z();
			double sensorHalfWidth = PERSPECTIVE_Z * oldHalfWidth / center.z();
			double nearDepth = MINIMUM_NEAR_DEPTH
					+ NEAR_DEPTH_RANGE * unit(index, 0x754A32D192ED03L);
			double farDepth = MINIMUM_FAR_DEPTH
					+ FAR_DEPTH_RANGE * unit(index, 0x16B4CE29890A1FL);
			boolean reverseDepth = unit(index, 0x6D2B79F5AA010DL) < 0.5;
			double minusDepth = reverseDepth ? farDepth : nearDepth;
			double plusDepth = reverseDepth ? nearDepth : farDepth;

			Vec3 minusSensor = horizontal
					? Vec3.xyz(sensorCenterX - sensorHalfLength, sensorCenterY, 0.0)
					: Vec3.xyz(sensorCenterX, sensorCenterY - sensorHalfLength, 0.0);
			Vec3 plusSensor = horizontal
					? Vec3.xyz(sensorCenterX + sensorHalfLength, sensorCenterY, 0.0)
					: Vec3.xyz(sensorCenterX, sensorCenterY + sensorHalfLength, 0.0);
			Vec3 minusEndpoint = worldPoint(minusSensor.x(), minusSensor.y(), minusDepth);
			Vec3 plusEndpoint = worldPoint(plusSensor.x(), plusSensor.y(), plusDepth);
			Vec3 ribbonCenter = minusEndpoint.add(plusEndpoint).mul(0.5);
			Vec3 longHalfAxis = plusEndpoint.sub(minusEndpoint).mul(0.5);
			double meanDepth = 0.5 * (nearDepth + farDepth);
			double newHalfWidth = sensorHalfWidth * meanDepth / PERSPECTIVE_Z;
			Vec3 desiredWidthDirection = horizontal
					? Vec3.EY : Vec3.EX.inverse();
			Vec3 widthHalfAxis = desiredWidthDirection
					.rejection(longHalfAxis).normalizedTo(newHalfWidth);
			Vec3 thicknessHalfAxis = longHalfAxis.cross(widthHalfAxis)
					.normalizedTo(RIBBON_HALF_THICKNESS);
			Affine transform = Affine.unitVectors(
					longHalfAxis, widthHalfAxis, thicknessHalfAxis)
					.then(Affine.translation(ribbonCenter));
			converted.add(SceneBox.affine(transform, sourceBox.material()));
			ribbons++;
		}

		if (converted.size() != OBJECT_COUNT
				|| spheres != SPHERE_COUNT
				|| ribbons != RIBBON_COUNT) {
			throw new IllegalStateException("Invalid gallery overlap population.");
		}
		Scene scene = new SimpleScene(Group.of(converted), source.colorBackground());
		return new Setup(
				scene,
				SceneSupport.camera(Vec3.ZERO, 0.0, 0.0, 0.125),
				SceneSupport.cameraLookAt(
						Vec3.xyz(-22.0, 6.0, 9.0),
						Vec3.xyz(0.0, 0.0, 9.0),
						1.0 / 3.0),
				spheres,
				ribbons);
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
}
