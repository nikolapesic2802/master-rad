package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class CityOfNightV1 implements Scene {
	private static final double FACADE_SURFACE_EPSILON = 0.0025;
	private static final double FACADE_PANEL_HALF_THICKNESS = 0.004;
	private static final long DEFAULT_CITY_SEED = 0x1F234AB9C6D8EE07L;
	private static final double DEFAULT_MOON_PLANE_STRENGTH = 4.0;

	private static final Color[] WINDOW_PALETTE = {
			Color.rgb(30.0, 14.0, 6.0),
			Color.rgb(10.0, 22.0, 40.0),
			Color.rgb(24.0, 12.0, 30.0),
			Color.rgb(16.0, 28.0, 18.0)
	};

	private final Scene scene;

	public CityOfNightV1() {
		List<Solid> solids = new ArrayList<>();
		SplittableRandom random = new SplittableRandom(DEFAULT_CITY_SEED);

		solids.add(SceneSupport.halfSpace(
				Vec3.ZERO,
				Vec3.EY,
				SceneSupport.surface(
						Color.BLACK,
						Color.rgb(0.150, 0.154, 0.160),
						Color.rgb(0.024, 0.026, 0.028),
						Color.BLACK,
						1.5
				)
		));

		for (int gz = 0; gz < 32; gz++) {
			double zBase = -4.45 + gz * 0.60;
			for (int gx = -16; gx <= 16; gx++) {
				double keepChance = gz < 6 ? 0.67 : (gz < 12 ? 0.63 : 0.57);
				if (Math.abs(gx) > 13) {
					keepChance -= 0.08;
				}
				if (random.nextDouble() > keepChance) {
					continue;
				}

				double x = gx * 0.64 + random.nextDouble(-0.10, 0.10);
				double z = zBase + random.nextDouble(-0.10, 0.10);
				double rx = random.nextDouble(0.10, 0.20);
				double rz = random.nextDouble(0.10, 0.20);

				double height = 0.10 + Math.pow(random.nextDouble(), 1.5) * 0.18 + Math.pow(random.nextDouble(), 4.2) * 1.10;
				if (gz > 12 && Math.abs(gx) < 8 && random.nextDouble() < 0.065) {
					height *= random.nextDouble(2.2, 3.5);
					rx *= random.nextDouble(0.76, 0.92);
					rz *= random.nextDouble(0.76, 0.92);
				}

				addTower(solids, random, x, z, rx, rz, height, gz);
			}
		}

		addForegroundMassing(solids, random);
		solids.add(SceneSupport.box(
				Vec3.xyz(0.0, 5.55, 5.1),
				Vec3.xyz(13.5, 0.045, 15.0),
				SceneSupport.emissive(Color.rgb(0.22, 0.24, 0.27).mul(DEFAULT_MOON_PLANE_STRENGTH))
		));

		scene = new SimpleScene(Group.of(solids), Color.rgb(0.021, 0.023, 0.031));
	}

	private void addForegroundMassing(List<Solid> solids, SplittableRandom random) {
		for (int i = 0; i < 26; i++) {
			double x = random.nextDouble(-6.8, 6.8);
			double z = random.nextDouble(-5.4, -0.9);
			double rx = random.nextDouble(0.14, 0.32);
			double rz = random.nextDouble(0.16, 0.34);
			double height = random.nextDouble(0.12, 0.34);
			Color diffuse = Color.hsb(random.nextDouble(), random.nextDouble(0.12, 0.32), random.nextDouble(0.16, 0.28));
			solids.add(SceneSupport.box(
					Vec3.xyz(x, height * 0.5, z),
					Vec3.xyz(rx, height * 0.5, rz),
					SceneSupport.surface(
							Color.BLACK,
							diffuse,
							Color.rgb(0.006, 0.007, 0.008),
							Color.BLACK,
							1.5
					)
			));
		}
	}

	private void addTower(List<Solid> solids,
	                      SplittableRandom random,
	                      double x,
	                      double z,
	                      double rx,
	                      double rz,
	                      double height,
	                      int depthBand) {
		double y = height * 0.5;
		boolean mirrorTower = random.nextDouble() < 0.04;
		boolean colorfulTower = random.nextDouble() < 0.62;

		Color diffuse = colorfulTower
				? Color.hsb(random.nextDouble(), random.nextDouble(0.42, 0.82), random.nextDouble(0.34, 0.60))
				: Color.hsb(random.nextDouble(), random.nextDouble(0.16, 0.32), random.nextDouble(0.22, 0.36));
		Color reflective = mirrorTower
				? Color.rgb(0.44, 0.48, 0.54)
				: Color.rgb(0.026, 0.030, 0.034);

		solids.add(SceneSupport.box(
				Vec3.xyz(x, y, z),
				Vec3.xyz(rx, y, rz),
					SceneSupport.surface(Color.BLACK, diffuse, reflective, Color.BLACK, 1.5)
		));

		if (height > 0.78 && random.nextDouble() < 0.38) {
			solids.add(SceneSupport.box(
					Vec3.xyz(x, 0.08, z),
					Vec3.xyz(rx * 1.08, 0.08, rz * 1.08),
					SceneSupport.surface(
							Color.BLACK,
							diffuse.mul(0.90),
							reflective.mul(0.55),
							Color.BLACK,
							1.5
					)
			));
		}

		Color glow = WINDOW_PALETTE[(random.nextInt(WINDOW_PALETTE.length) + depthBand) % WINDOW_PALETTE.length];
		addFacadeAccents(solids, random, x, z, rx, rz, height, glow);
		double facadePick = random.nextDouble();
		if (facadePick < 0.20) {
			addDenseBands(solids, random, x, z, rx, rz, height, glow);
		} else if (facadePick < 0.32) {
			addDenseGrid(solids, random, x, z, rx, rz, height, glow);
		} else if (facadePick < 0.42) {
			addSparseGrid(solids, random, x, z, rx, rz, height, glow);
		} else if (facadePick < 0.52) {
			addCornerPattern(solids, random, x, z, rx, rz, height, glow);
		}
	}

	private void addFacadeAccents(List<Solid> solids,
	                              SplittableRandom random,
	                              double x,
	                              double z,
	                              double rx,
	                              double rz,
	                              double height,
	                              Color glow) {
		if (height > 0.48 && random.nextDouble() < 0.42) {
			addFrontBackStrip(solids, random, x, z, rx, rz, height, true, glow.mul(random.nextDouble(1.20, 1.66)));
		}
		if (height > 0.54 && random.nextDouble() < 0.18) {
			addFrontBackStrip(solids, random, x, z, rx, rz, height, false, glow.mul(random.nextDouble(1.00, 1.30)));
		}
		if (height > 0.48 && random.nextDouble() < 0.30) {
			addSideStrip(solids, random, x, z, rx, rz, height, true, glow.mul(random.nextDouble(1.12, 1.54)));
		}
		if (height > 0.54 && random.nextDouble() < 0.18) {
			addSideStrip(solids, random, x, z, rx, rz, height, false, glow.mul(random.nextDouble(1.00, 1.34)));
		}
		if (random.nextDouble() < 0.22) {
			addBillboard(solids, random, x, z, rx, rz, height, true, glow.mul(random.nextDouble(1.14, 1.44)));
		}
		if (random.nextDouble() < 0.06) {
			addBillboard(solids, random, x, z, rx, rz, height * random.nextDouble(0.72, 0.96), false, glow.mul(random.nextDouble(1.00, 1.18)));
		}
		if (random.nextDouble() < 0.12) {
			addSideBillboard(solids, random, x, z, rx, rz, height, true, glow.mul(random.nextDouble(1.08, 1.42)));
		}
		if (random.nextDouble() < 0.10) {
			addSideBillboard(solids, random, x, z, rx, rz, height, false, glow.mul(random.nextDouble(1.00, 1.28)));
		}
	}

	private void addFrontBackStrip(List<Solid> solids,
	                               SplittableRandom random,
	                               double x,
	                               double z,
	                               double rx,
	                               double rz,
	                               double height,
	                               boolean front,
	                               Color glow) {
		double halfThickness = FACADE_PANEL_HALF_THICKNESS;
		double halfWidth = Math.min(rx * 0.08, 0.010);
		double halfHeight = Math.min(height * 0.38, Math.max(0.15, height * 0.28));
		double centerY = clamp(height * random.nextDouble(0.42, 0.62), halfHeight + 0.04, height - halfHeight - 0.04);
		addFrontBackPanel(solids, x, centerY, z, halfWidth, halfHeight, rz, halfThickness, glow, front);
	}

	private void addSideStrip(List<Solid> solids,
	                          SplittableRandom random,
	                          double x,
	                          double z,
	                          double rx,
	                          double rz,
	                          double height,
	                          boolean right,
	                          Color glow) {
		double halfThickness = FACADE_PANEL_HALF_THICKNESS;
		double halfDepth = Math.min(rz * 0.08, 0.010);
		double halfHeight = Math.min(height * 0.36, Math.max(0.15, height * 0.26));
		double centerY = clamp(height * random.nextDouble(0.40, 0.60), halfHeight + 0.04, height - halfHeight - 0.04);
		addSidePanel(solids, x, centerY, z, rx, halfThickness, halfHeight, halfDepth, glow, right);
	}

	private void addSideBillboard(List<Solid> solids,
	                              SplittableRandom random,
	                              double x,
	                              double z,
	                              double rx,
	                              double rz,
	                              double height,
	                              boolean right,
	                              Color glow) {
		double halfThickness = FACADE_PANEL_HALF_THICKNESS;
		double halfHeight = Math.min(height * 0.24, 0.14 + random.nextDouble() * 0.08);
		double centerY = clamp(height * random.nextDouble(0.32, 0.72), halfHeight + 0.05, height - halfHeight - 0.05);
		double halfDepth = Math.min(rz * 0.70, 0.24 + random.nextDouble() * 0.10);
		addSidePanel(solids, x, centerY, z, rx, halfThickness, halfHeight, halfDepth, glow, right);
	}

	private void addDenseBands(List<Solid> solids,
	                           SplittableRandom random,
	                           double x,
	                           double z,
	                           double rx,
	                           double rz,
	                           double height,
	                           Color glow) {
		int bands = Math.max(3, (int) Math.floor(height / 0.26));
		for (int i = 0; i < bands; i++) {
			double y = 0.08 + i * (height - 0.16) / bands;
			if (random.nextDouble() < 0.08) {
				continue;
			}
			double widthScale = 0.42 + random.nextDouble() * 0.34;
			addFrontBackWindow(solids, x, y, z, rx * widthScale, 0.016, rz, glow.mul(random.nextDouble(1.16, 1.52)));
		}
	}

	private void addDenseGrid(List<Solid> solids,
	                          SplittableRandom random,
	                          double x,
	                          double z,
	                          double rx,
	                          double rz,
	                          double height,
	                          Color glow) {
		addGrid(solids, random, x, z, rx, rz, height, glow, 0.64);
	}

	private void addSparseGrid(List<Solid> solids,
	                           SplittableRandom random,
	                           double x,
	                           double z,
	                           double rx,
	                           double rz,
	                           double height,
	                           Color glow) {
		addGrid(solids, random, x, z, rx, rz, height, glow.mul(0.98), 0.40);
	}

	private void addGrid(List<Solid> solids,
	                     SplittableRandom random,
	                     double x,
	                     double z,
	                     double rx,
	                     double rz,
	                     double height,
	                     Color glow,
	                     double litChance) {
		int rows = Math.max(4, (int) Math.floor(height / 0.26));
		int cols = Math.max(2, (int) Math.floor((rx * 2.0) / 0.11));
		double halfWidth = Math.min(0.040, rx * 0.24);
		for (int row = 0; row < rows; row++) {
			double y = 0.10 + row * (height - 0.20) / rows;
			for (int col = 0; col < cols; col++) {
				if (random.nextDouble() > litChance) {
					continue;
				}
				double xOffset = cols == 1 ? 0.0 : (-rx * 0.68 + col * (rx * 1.36 / (cols - 1)));
				Color lit = glow.mul(random.nextDouble(1.12, 1.64));
				addFrontBackPanel(solids, x + xOffset, y, z, halfWidth, 0.015, rz, 0.010, lit, true);
				if (random.nextDouble() < 0.64) {
					addFrontBackPanel(solids, x + xOffset, y, z, halfWidth, 0.015, rz, 0.010, lit.mul(0.88), false);
				}
			}
		}
	}

	private void addVerticalStrips(List<Solid> solids,
	                               SplittableRandom random,
	                               double x,
	                               double z,
	                               double rx,
	                               double rz,
	                               double height,
	                               Color glow) {
		int strips = 3 + random.nextInt(2);
		for (int strip = 0; strip < strips; strip++) {
			double t = strips == 1 ? 0.5 : strip / (double) (strips - 1);
			double xOffset = -rx * 0.72 + t * rx * 1.44;
			double y = height * 0.50;
			double h = height * (0.16 + random.nextDouble() * 0.18);
			Color lit = glow.mul(random.nextDouble(1.22, 1.72));
			addFrontBackPanel(solids, x + xOffset, y, z, 0.014, h, rz, 0.010, lit, true);
			if (random.nextDouble() < 0.68) {
				addSidePanel(solids,
						x,
						y,
						z - rz * 0.45 + strip * (rz * 0.90 / Math.max(1, strips - 1)),
						rx,
						0.010,
						h * 0.76,
						0.015,
						lit.mul(0.82),
						true);
			}
		}
	}

	private void addCornerPattern(List<Solid> solids,
	                              SplittableRandom random,
	                              double x,
	                              double z,
	                              double rx,
	                              double rz,
	                              double height,
	                              Color glow) {
		double edgeHeight = height * 0.30;
		double y = height * 0.50;
		Color lit = glow.mul(random.nextDouble(1.12, 1.48));
		addFrontBackPanel(solids, x - rx * 0.72, y, z, 0.014, edgeHeight, rz, 0.010, lit, true);
		addFrontBackPanel(solids, x + rx * 0.72, y, z, 0.014, edgeHeight, rz, 0.010, lit.mul(0.94), true);
		if (random.nextDouble() < 0.54) {
			addSidePanel(solids, x, y, z - rz * 0.52, rx, 0.010, edgeHeight * 0.78, 0.015, lit.mul(0.84), false);
		}
	}

	private void addBillboard(List<Solid> solids,
	                          SplittableRandom random,
	                          double x,
	                          double z,
	                          double rx,
	                          double rz,
	                          double height,
	                          boolean front,
	                          Color glow) {
		double halfThickness = FACADE_PANEL_HALF_THICKNESS;
		double halfHeight = Math.min(height * 0.22, 0.14 + random.nextDouble() * 0.08);
		double centerY = clamp(height * random.nextDouble(0.30, 0.70), halfHeight + 0.05, height - halfHeight - 0.05);
		double halfWidth = Math.min(rx * 0.76, 0.28 + random.nextDouble() * 0.12);
		Color lit = glow.mul(random.nextDouble(1.24, 1.70));
		addFrontBackPanel(solids, x, centerY, z, halfWidth, halfHeight, rz, halfThickness, lit, front);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private void addFrontBackWindow(List<Solid> solids, double x, double y, double z, double halfWidth, double halfHeight, double rz, Color glow) {
		addFrontBackPanel(solids, x, y, z, halfWidth, halfHeight, rz, 0.010, glow, true);
		addFrontBackPanel(solids, x, y, z, halfWidth, halfHeight, rz, 0.010, glow.mul(0.86), false);
	}

	private void addFrontBackPanel(List<Solid> solids,
	                               double x,
	                               double y,
	                               double z,
	                               double halfWidth,
	                               double halfHeight,
	                               double rz,
	                               double halfThickness,
	                               Color glow,
	                               boolean front) {
		double zCenter = front
				? z + rz + halfThickness + FACADE_SURFACE_EPSILON
				: z - rz - halfThickness - FACADE_SURFACE_EPSILON;
		addLightBox(solids, Vec3.xyz(x, y, zCenter), Vec3.xyz(halfWidth, halfHeight, halfThickness), glow);
	}

	private void addSidePanel(List<Solid> solids,
	                          double x,
	                          double y,
	                          double z,
	                          double rx,
	                          double halfThickness,
	                          double halfHeight,
	                          double halfDepth,
	                          Color glow,
	                          boolean right) {
		double xCenter = right
				? x + rx + halfThickness + FACADE_SURFACE_EPSILON
				: x - rx - halfThickness - FACADE_SURFACE_EPSILON;
		addLightBox(solids, Vec3.xyz(xCenter, y, z), Vec3.xyz(halfThickness, halfHeight, halfDepth), glow);
	}

	private void addLightBox(List<Solid> solids, Vec3 center, Vec3 radii, Color glow) {
		solids.add(SceneSupport.box(center, radii, SceneSupport.emissive(glow)));
	}

	@Override
	public Solid solid() {
		return scene.solid();
	}

	@Override
	public java.util.Collection<xyz.marsavic.gfxlab.graphics3d.Light> lights() {
		return scene.lights();
	}

	@Override
	public Color colorBackground() {
		return scene.colorBackground();
	}
}
