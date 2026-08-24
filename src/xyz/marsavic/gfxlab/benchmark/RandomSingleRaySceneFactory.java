package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Material;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneBox;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSphere;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SimpleScene;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Reusable deterministic geometry for the random first-hit experiments.
 *
 * <p>A spatial layout is generated independently of primitive population. The
 * four populations therefore reuse exactly the same centers and conservative
 * envelopes for a given object count and layout identifier.
 * Every envelope is strictly contained in {@code [-1,1]^3}, and distinct
 * envelopes are separated by {@value #GAP_FRACTION} of one envelope radius.</p>
 */
public final class RandomSingleRaySceneFactory {
	public enum Population {
		ALL_SPHERE,
		SPHERE_AFFINE_BOX_50_50,
		ALL_AFFINE_BOX,
		FOUR_TYPES_EQUAL
	}

	private enum PrimitiveKind {
		SPHERE,
		BOX,
		AFFINE_SPHERE,
		AFFINE_BOX
	}

	public static final double BASE_ENVELOPE_SCALE = 0.58;
	public static final double GAP_FRACTION = 0.08;
	private static final int MAX_LAYOUT_ATTEMPTS = 64;
	private static final int MAX_OBJECT_PLACEMENT_ATTEMPTS = 20_000;
	/* Fixed domains keep each (seed, objectCount, layoutId) layout reproducible. */
	private static final long LAYOUT_SEED_DOMAIN = 0x4C41594F55544C00L;
	private static final long POSITION_SEED_DOMAIN = 0x16B00B5L;
	private static final int LAYOUT_HASH_DOMAIN = 0;
	private static final double VALIDATION_EPSILON = 1.0e-12;

	private static final Color[] TYPE_COLORS = {
			Color.rgb(0.10, 0.32, 0.92),
			Color.rgb(0.12, 0.72, 0.30),
			Color.rgb(0.92, 0.12, 0.04),
			Color.rgb(0.35, 0.58, 0.96)
	};

	private RandomSingleRaySceneFactory() {
	}

	/**
	 * Stable layout seed shared by all four populations.
	 */
	public static long layoutSeed(
			long experimentSeed,
			int objectCount,
			int layoutId
	) {
		if (layoutId < 0) {
			throw new IllegalArgumentException("Layout identifier must be nonnegative.");
		}
		return mix(
				experimentSeed,
				((long) objectCount << 32) ^ Integer.toUnsignedLong(layoutId),
				LAYOUT_SEED_DOMAIN);
	}

	public static GeneratedLayout layout(
			int objectCount,
			long seed
	) {
		if (objectCount < 2) {
			throw new IllegalArgumentException("The random study requires at least two objects.");
		}
		double envelopeRadius = BASE_ENVELOPE_SCALE / Math.cbrt(objectCount);
		List<Vec3> centers = positions(
				objectCount,
				envelopeRadius,
				mix(seed, objectCount, POSITION_SEED_DOMAIN));
		GeneratedLayout result = new GeneratedLayout(
				centers,
				envelopeRadius,
				seed);
		validate(result);
		return result;
	}

	public static GeneratedScene generate(
			GeneratedLayout layout,
			long seed,
			Population population
	) {
		int objectCount = layout.centers().size();
		PrimitiveKind[] kinds = populationKinds(objectCount, seed, population);
		List<Solid> solids = new ArrayList<>(objectCount);
		int[] typeCounts = new int[PrimitiveKind.values().length];

		for (int index = 0; index < objectCount; index++) {
			PrimitiveKind kind = kinds[index];
			SplittableRandom shapeRandom = new SplittableRandom(mix(
					seed,
					index,
					0x5A9E5L + 101L * kind.ordinal()));
			solids.add(primitive(
					kind,
					layout.centers().get(index),
					layout.envelopeRadius(),
					shapeRandom,
					Material.matte(TYPE_COLORS[kind.ordinal()])));
			typeCounts[kind.ordinal()]++;
		}

		validateComposition(population, typeCounts, objectCount);
		Scene scene = new SimpleScene(Group.of(solids), Color.BLACK);
		return new GeneratedScene(scene, typeCounts);
	}

	private static PrimitiveKind[] populationKinds(
			int objectCount,
			long seed,
			Population population
	) {
		int[] quotas = switch (population) {
			case ALL_SPHERE -> new int[]{objectCount, 0, 0, 0};
			case ALL_AFFINE_BOX -> new int[]{0, 0, 0, objectCount};
			case SPHERE_AFFINE_BOX_50_50 -> {
				int spheres = objectCount / 2;
				if ((objectCount & 1) != 0 && (mix(seed, objectCount, 0xF1L) & 1L) == 0L) {
					spheres++;
				}
				yield new int[]{spheres, 0, 0, objectCount - spheres};
			}
			case FOUR_TYPES_EQUAL -> equalFourWayQuotas(objectCount, seed);
		};

		PrimitiveKind[] result = new PrimitiveKind[objectCount];
		int write = 0;
		for (PrimitiveKind kind : PrimitiveKind.values()) {
			for (int count = 0; count < quotas[kind.ordinal()]; count++) {
				result[write++] = kind;
			}
		}
		SplittableRandom random = new SplittableRandom(mix(
				seed,
				objectCount,
				0x504F50554C415449L + population.ordinal()));
		for (int i = result.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			PrimitiveKind swap = result[i];
			result[i] = result[j];
			result[j] = swap;
		}
		return result;
	}

	private static int[] equalFourWayQuotas(int objectCount, long seed) {
		int[] result = new int[PrimitiveKind.values().length];
		int base = objectCount / result.length;
		java.util.Arrays.fill(result, base);
		int remainder = objectCount % result.length;
		int offset = (int) Math.floorMod(mix(seed, objectCount, 0xF2L), result.length);
		for (int i = 0; i < remainder; i++) {
			result[(offset + i) % result.length]++;
		}
		return result;
	}

	private static void validateComposition(
			Population population,
			int[] counts,
			int objectCount
	) {
		if (java.util.Arrays.stream(counts).sum() != objectCount) {
			throw new IllegalStateException("Primitive-population count does not match the layout.");
		}
		switch (population) {
			case ALL_SPHERE -> requireCounts(counts, objectCount, 0, 0, 0);
			case ALL_AFFINE_BOX -> requireCounts(counts, 0, 0, 0, objectCount);
			case SPHERE_AFFINE_BOX_50_50 -> {
				if (counts[PrimitiveKind.BOX.ordinal()] != 0
						|| counts[PrimitiveKind.AFFINE_SPHERE.ordinal()] != 0
						|| Math.abs(counts[PrimitiveKind.SPHERE.ordinal()]
						- counts[PrimitiveKind.AFFINE_BOX.ordinal()]) > 1) {
					throw new IllegalStateException("F1 is not an equal sphere/affine-box population.");
				}
			}
			case FOUR_TYPES_EQUAL -> {
				int minimum = java.util.Arrays.stream(counts).min().orElseThrow();
				int maximum = java.util.Arrays.stream(counts).max().orElseThrow();
				if (maximum - minimum > 1) {
					throw new IllegalStateException("F2 primitive counts differ by more than one.");
				}
			}
		}
	}

	private static void requireCounts(
			int[] counts,
			int spheres,
			int boxes,
			int affineSpheres,
			int affineBoxes
	) {
		int[] expected = {spheres, boxes, affineSpheres, affineBoxes};
		if (!java.util.Arrays.equals(counts, expected)) {
			throw new IllegalStateException("Structural-control population is impure.");
		}
	}

	private static List<Vec3> positions(
			int count,
			double envelopeRadius,
			long seed
	) {
		double minimumDistance = envelopeRadius * (2.0 + GAP_FRACTION);
		double limit = 1.0 - envelopeRadius;
		for (int layoutAttempt = 0; layoutAttempt < MAX_LAYOUT_ATTEMPTS; layoutAttempt++) {
			List<Vec3> result = tryPositions(
					count,
					minimumDistance,
					limit,
					mix(seed, layoutAttempt, 0x1A7A77E5L));
			if (result != null) {
				return result;
			}
		}
		throw new IllegalStateException("Could not place " + count
				+ " objects after "
				+ MAX_LAYOUT_ATTEMPTS + " deterministic layout attempts.");
	}

	private static List<Vec3> tryPositions(
			int count,
			double minimumDistance,
			double limit,
			long seed
	) {
		SplittableRandom random = new SplittableRandom(seed);
		Map<Cell, List<Vec3>> grid = new HashMap<>();
		List<Vec3> result = new ArrayList<>(count);

		for (int index = 0; index < count; index++) {
			Vec3 accepted = null;
			for (int attempt = 0; attempt < MAX_OBJECT_PLACEMENT_ATTEMPTS; attempt++) {
				Vec3 candidate = Vec3.xyz(
						random.nextDouble(-limit, limit),
						random.nextDouble(-limit, limit),
						random.nextDouble(-limit, limit));
				if (separated(candidate, minimumDistance, grid)) {
					accepted = candidate;
					break;
				}
			}
			if (accepted == null) {
				return null;
			}
			result.add(accepted);
			grid.computeIfAbsent(cell(accepted, minimumDistance), ignored -> new ArrayList<>())
					.add(accepted);
		}
		return result;
	}

	private static Solid primitive(
			PrimitiveKind kind,
			Vec3 center,
			double bound,
			SplittableRandom random,
			Material material
	) {
		return switch (kind) {
			case SPHERE -> SceneSphere.sphere(center, bound, material);
			case BOX -> SceneBox.axisAligned(
					center,
					Vec3.ONES.mul(bound / Math.sqrt(3.0)),
					material);
			case AFFINE_SPHERE -> {
				Vec3 axes = random.nextBoolean()
						? Vec3.xyz(
								bound,
								bound * random.nextDouble(0.30, 0.44),
								bound * random.nextDouble(0.30, 0.44))
						: Vec3.xyz(
								bound,
								bound * random.nextDouble(0.82, 0.96),
								bound * random.nextDouble(0.26, 0.40));
				yield SceneSphere.affine(transform(center, axes, random), material);
			}
			case AFFINE_BOX -> {
				Vec3 proportions = Vec3.xyz(
						1.0,
						random.nextDouble(0.28, 0.42),
						random.nextDouble(0.28, 0.42));
				Vec3 axes = proportions.mul(bound / proportions.length());
				yield SceneBox.affine(transform(center, axes, random), material);
			}
		};
	}

	private static Affine transform(Vec3 center, Vec3 axes, SplittableRandom random) {
		return Affine.scaling(axes)
				.then(randomRotation(random))
				.then(Affine.translation(center));
	}

	private static Affine randomRotation(SplittableRandom random) {
		double y = random.nextDouble(-1.0, 1.0);
		double phi = random.nextDouble(0.0, 2.0 * Math.PI);
		double radial = Math.sqrt(1.0 - y * y);
		Vec3 ey = Vec3.xyz(radial * Math.cos(phi), y, radial * Math.sin(phi));
		Vec3 helper = Math.abs(ey.y()) < 0.9 ? Vec3.EY : Vec3.EX;
		Vec3 ex = helper.sub(ey.mul(helper.dot(ey))).normalized_();
		Vec3 ez = ex.cross(ey);
		double roll = random.nextDouble(0.0, 2.0 * Math.PI);
		double c = Math.cos(roll);
		double s = Math.sin(roll);
		return Affine.unitVectors(
				ex.mul(c).add(ez.mul(s)),
				ey,
				ex.mul(-s).add(ez.mul(c)));
	}

	private static boolean separated(Vec3 candidate, double distance, Map<Cell, List<Vec3>> grid) {
		Cell center = cell(candidate, distance);
		double distanceSquared = distance * distance;
		for (int x = center.x() - 1; x <= center.x() + 1; x++) {
			for (int y = center.y() - 1; y <= center.y() + 1; y++) {
				for (int z = center.z() - 1; z <= center.z() + 1; z++) {
					for (Vec3 existing : grid.getOrDefault(new Cell(x, y, z), List.of())) {
						if (candidate.sub(existing).lengthSquared() < distanceSquared) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	private static Cell cell(Vec3 point, double cellSize) {
		return new Cell(
				(int) Math.floor(point.x() / cellSize),
				(int) Math.floor(point.y() / cellSize),
				(int) Math.floor(point.z() / cellSize));
	}

	private static void validate(GeneratedLayout layout) {
		List<Vec3> centers = layout.centers();
		double radius = layout.envelopeRadius();
		double minimumDistance = radius * (2.0 + GAP_FRACTION);
		Map<Cell, List<IndexedCenter>> grid = new HashMap<>();
		for (int i = 0; i < centers.size(); i++) {
			Vec3 center = centers.get(i);
			double coordinateMaximum = Math.max(
					Math.abs(center.x()),
					Math.max(Math.abs(center.y()), Math.abs(center.z())));
			if (coordinateMaximum + radius > 1.0 + VALIDATION_EPSILON) {
				throw new IllegalStateException("Unit-box containment failed at object " + i + ".");
			}
			Cell cell = cell(center, minimumDistance);
			for (int x = cell.x() - 1; x <= cell.x() + 1; x++) {
				for (int y = cell.y() - 1; y <= cell.y() + 1; y++) {
					for (int z = cell.z() - 1; z <= cell.z() + 1; z++) {
						for (IndexedCenter existing
								: grid.getOrDefault(new Cell(x, y, z), List.of())) {
							if (center.sub(existing.center()).length()
									+ VALIDATION_EPSILON < minimumDistance) {
								throw new IllegalStateException(
										"Envelope gap failed for objects "
												+ existing.index() + " and " + i + ".");
							}
						}
					}
				}
			}
			grid.computeIfAbsent(cell, ignored -> new ArrayList<>())
					.add(new IndexedCenter(i, center));
		}
	}

	public static String layoutSha256(GeneratedLayout layout) {
		MessageDigest digest = sha256Digest();
		ByteBuffer buffer = ByteBuffer.allocate(4 * Long.BYTES + 2 * Integer.BYTES)
				.order(ByteOrder.BIG_ENDIAN);
		buffer.putInt(layout.centers().size());
		buffer.putInt(LAYOUT_HASH_DOMAIN);
		buffer.putLong(layout.seed());
		buffer.putLong(Double.doubleToLongBits(layout.envelopeRadius()));
		digest.update(buffer.array(), 0, buffer.position());
		buffer.clear();
		for (Vec3 center : layout.centers()) {
			buffer.putLong(Double.doubleToLongBits(center.x()));
			buffer.putLong(Double.doubleToLongBits(center.y()));
			buffer.putLong(Double.doubleToLongBits(center.z()));
			digest.update(buffer.array(), 0, buffer.position());
			buffer.clear();
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static long mix(long seed, long a, long b) {
		long value = seed ^ Long.rotateLeft(a * 0x9E3779B97F4A7C15L, 21) ^ b;
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	public record GeneratedScene(
			Scene scene,
			int[] typeCounts
	) {
		public GeneratedScene {
			typeCounts = typeCounts.clone();
		}

		@Override
		public int[] typeCounts() {
			return typeCounts.clone();
		}

	}

	public record GeneratedLayout(
			List<Vec3> centers,
			double envelopeRadius,
			long seed
	) {
		public GeneratedLayout {
			centers = List.copyOf(centers);
			if (!(envelopeRadius > 0.0) || !Double.isFinite(envelopeRadius)) {
				throw new IllegalArgumentException("Envelope radius must be finite and positive.");
			}
		}
	}

	private record IndexedCenter(int index, Vec3 center) {
	}

	private record Cell(int x, int y, int z) {
	}
}
