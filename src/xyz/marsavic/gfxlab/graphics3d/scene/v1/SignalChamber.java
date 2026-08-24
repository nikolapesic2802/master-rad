package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class SignalChamber implements Scene {
	private static final long DEFAULT_PIXEL_SEED = 0x5C82D0A4B7319E6FL;

	private final Scene scene;

	public SignalChamber() {
		List<Solid> solids = new ArrayList<>();

		var chamberReflective = SceneSupport.surface(
				Color.BLACK,
				Color.rgb(0.055, 0.060, 0.070),
				Color.rgb(0.44, 0.47, 0.52),
				Color.BLACK,
				1.5
		);
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(0.0, -1.0, 0.0),
				Vec3.EY,
				chamberReflective
		));
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(0.0, 1.96, 0.0),
				Vec3.EY.inverse(),
				chamberReflective
		));
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(-1.26, 0.0, 0.0),
				Vec3.EX,
				SceneSupport.matte(Color.rgb(0.05, 0.055, 0.065))
		));
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(1.26, 0.0, 0.0),
				Vec3.EX.inverse(),
				SceneSupport.matte(Color.rgb(0.05, 0.055, 0.065))
		));
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(0.0, 0.0, 6.50),
				Vec3.EZ.inverse(),
				chamberReflective
		));
		solids.add(SceneSupport.halfSpace(
				Vec3.xyz(0.0, 0.0, -2.60),
				Vec3.EZ,
				chamberReflective
		));

		addPixelWalls(solids);

		scene = new SimpleScene(Group.of(solids), Color.BLACK);
	}

	private void addPixelWalls(List<Solid> solids) {
		// A deliberately higher-scale introductory scene: 30,502 emissive
		// spheres across the two walls, versus 3,033 primitives in City of Night.
		// The occupied wall area is unchanged; the denser sampling increases
		// geometric detail without changing the composition or camera.
		int rows = 101;
		int cols = 151;
		double yStart = -0.80;
		double zStart = -0.10;
		double yStep = 2.60 / (rows - 1);
		double zStep = 6.35 / (cols - 1);
		double radius = 0.014;
		SplittableRandom random = new SplittableRandom(DEFAULT_PIXEL_SEED);

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				Color left = randomPixelColor(random);
				Color right = randomPixelColor(random);
				double y = yStart + row * yStep;
				double z = zStart + col * zStep;
				solids.add(SceneSupport.sphere(Vec3.xyz(-1.268, y, z), radius, SceneSupport.emissive(left)));
				solids.add(SceneSupport.sphere(Vec3.xyz(1.268, y, z), radius, SceneSupport.emissive(right)));
			}
		}
	}

	private Color randomPixelColor(SplittableRandom random) {
		if (random.nextDouble() < 0.08) {
			double hot = random.nextDouble(10.0, 18.0);
			return Color.rgb(hot, hot * random.nextDouble(0.95, 1.05), hot * random.nextDouble(1.00, 1.12));
		}
		Color base = Color.hsb(
				random.nextDouble(),
				random.nextDouble(0.55, 0.92),
				random.nextDouble(0.60, 1.00)
		);
		return base.mul(random.nextDouble(9.0, 24.0));
	}

	@Override
	public Solid solid() {
		return scene.solid();
	}

	@Override
	public Color colorBackground() {
		return scene.colorBackground();
	}
}
