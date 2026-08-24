package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.LayeredHeroFamily;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSupport;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SimpleScene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.GalleryOverlapScene;

import java.util.Arrays;

/** Deterministic scene factories shared by benchmark evaluation and construction. */
public final class BenchmarkWorkloads {
	private static final Color RANDOM_ENVIRONMENT = Color.rgb(0.18, 0.22, 0.30);
	private static final Camera RANDOM_CAMERA = SceneSupport.cameraLookAt(
			Vec3.xyz(-3.0, 0.0, 0.0), Vec3.ZERO, 0.30);

	public record Source(
			BenchmarkProtocol.PublicationRow publicationRow, int layoutId, long geometrySeed,
			String layoutSha256, Scene scene, Camera camera,
			int expectedPrimitiveCount, int expectedActivePrimitiveTypeCount,
			int[] primitiveTypeCounts
	) {
		public Source {
			primitiveTypeCounts = primitiveTypeCounts.clone();
			if (publicationRow == null || scene == null || camera == null
					|| expectedPrimitiveCount < 1 || expectedActivePrimitiveTypeCount < 1
					|| expectedActivePrimitiveTypeCount > 4 || primitiveTypeCounts.length != 4
					|| Arrays.stream(primitiveTypeCounts).sum() != expectedPrimitiveCount
					|| Arrays.stream(primitiveTypeCounts).filter(value -> value > 0).count()
					!= expectedActivePrimitiveTypeCount) {
				throw new IllegalArgumentException("Incomplete benchmark workload source");
			}
			if (publicationRow.study() == BenchmarkProtocol.StudyKind.RANDOM) {
				if (layoutId < 0 || layoutSha256 == null
						|| !layoutSha256.matches("[0-9a-f]{64}")) {
					throw new IllegalArgumentException("Invalid benchmark random source identity");
				}
			} else if (layoutId != -1 || geometrySeed != 0L || layoutSha256 != null) {
				throw new IllegalArgumentException("Fixed benchmark workload has random identity");
			}
		}
		@Override public int[] primitiveTypeCounts() { return primitiveTypeCounts.clone(); }
	}

	private BenchmarkWorkloads() { }

	public static Source fixed(BenchmarkProtocol.PublicationRow row) {
		if (row == null || row.study() == BenchmarkProtocol.StudyKind.RANDOM) {
			throw new IllegalArgumentException("benchmark fixed workload row is required");
		}
		if (row.study() == BenchmarkProtocol.StudyKind.HERO) {
			Scene scene = LayeredHeroFamily.create(row.heroScale());
			// Six spheres and two affine boxes per module, plus four frame spheres
			// and five finite frame boxes. Planes are intentionally not bounded BVH input.
			int[] counts = {
					row.heroScale() * 3 / 4 + 4, 5, 0, row.heroScale() / 4
			};
			return new Source(row, -1, 0L, null, scene, LayeredHeroFamily.camera(),
					Arrays.stream(counts).sum(), activeCount(counts), counts);
		}
		GalleryOverlapScene.Setup setup = GalleryOverlapScene.create();
		int[] counts = {
				setup.sphereCount(), 0, 0, setup.affineBoxCount()
		};
		return new Source(row, -1, 0L, null, setup.scene(), setup.frontCamera(),
				GalleryOverlapScene.OBJECT_COUNT, activeCount(counts), counts);
	}

	public static Source random(BenchmarkProtocol.PublicationRow row, int layoutId) {
		boolean measuredLayout = layoutId >= BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID
				&& layoutId <= BenchmarkProtocol.LAST_RANDOM_LAYOUT_ID;
		if (row == null || row.study() != BenchmarkProtocol.StudyKind.RANDOM
				|| (!measuredLayout && layoutId != BenchmarkProtocol.RANDOM_WARMUP_LAYOUT_ID)) {
			throw new IllegalArgumentException("Unknown benchmark random workload");
		}
		long seed = RandomSingleRaySceneFactory.layoutSeed(
				BenchmarkProtocol.RANDOM_LAYOUT_EXPERIMENT_SEED, row.objectCount(), layoutId);
		RandomSingleRaySceneFactory.GeneratedLayout layout =
				RandomSingleRaySceneFactory.layout(row.objectCount(), seed);
		RandomSingleRaySceneFactory.GeneratedScene generated =
				RandomSingleRaySceneFactory.generate(layout, seed, population(row.randomFamily()));
		Scene source = new SimpleScene(generated.scene().solid(), RANDOM_ENVIRONMENT);
		int[] counts = generated.typeCounts();
		return new Source(row, layoutId, seed,
				RandomSingleRaySceneFactory.layoutSha256(layout), source, RANDOM_CAMERA,
				row.objectCount(), activeCount(counts), counts);
	}

	private static RandomSingleRaySceneFactory.Population population(String family) {
		return switch (family) {
			case "F1" -> RandomSingleRaySceneFactory.Population.SPHERE_AFFINE_BOX_50_50;
			case "F2" -> RandomSingleRaySceneFactory.Population.FOUR_TYPES_EQUAL;
			case "F3" -> RandomSingleRaySceneFactory.Population.ALL_SPHERE;
			case "F4" -> RandomSingleRaySceneFactory.Population.ALL_AFFINE_BOX;
			default -> throw new IllegalArgumentException("Unknown benchmark random family");
		};
	}

	private static int activeCount(int[] counts) {
		return (int) Arrays.stream(counts).filter(value -> value > 0).count();
	}

}
