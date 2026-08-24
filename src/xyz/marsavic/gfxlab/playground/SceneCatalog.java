package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.CityOfNightV1;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.GalleryOverlapScene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.GITestPlain;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.LayeredHeroFamily;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSupport;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SignalChamber;

public final class SceneCatalog {

	public enum ScenePreset {
		GI_TEST,
		CITY_OF_NIGHT_V1,
		SIGNAL_CHAMBER,
		LAYERED_HERO_96,
		LAYERED_HERO_1K,
		LAYERED_HERO_10K,
		LAYERED_HERO_100K,
		LAYERED_HERO_1M,
		OVERLAP_GALLERY_10K_FRONT,
		OVERLAP_GALLERY_10K_SIDE
	}

	public record SceneSetup(Scene scene, Camera camera) { }

	private SceneCatalog() {
	}

	public static SceneSetup create(ScenePreset preset) {
		return switch (preset) {
			case GI_TEST -> new SceneSetup(
					new GITestPlain(),
					SceneSupport.camera(Vec3.xyz(0.0, 0.0, -4.0), 0.0, 0.0)
			);
			case CITY_OF_NIGHT_V1 -> new SceneSetup(
					new CityOfNightV1(),
					SceneSupport.camera(Vec3.xyz(0.0, 0.34, -6.6), 0.08, -0.01)
			);
			case SIGNAL_CHAMBER -> new SceneSetup(
					new SignalChamber(),
					SceneSupport.camera(Vec3.xyz(0.0, 0.20, -1.40), 0.016, 0.0)
			);
			case LAYERED_HERO_96 -> layered(96);
			case LAYERED_HERO_1K -> layered(1_000);
			case LAYERED_HERO_10K -> layered(10_000);
			case LAYERED_HERO_100K -> layered(100_000);
			case LAYERED_HERO_1M -> layered(1_000_000);
			case OVERLAP_GALLERY_10K_FRONT -> galleryOverlap(false);
			case OVERLAP_GALLERY_10K_SIDE -> galleryOverlap(true);
		};
	}

	public static ScenePreset[] introductoryPresets() {
		return new ScenePreset[]{
				ScenePreset.GI_TEST,
				ScenePreset.CITY_OF_NIGHT_V1,
				ScenePreset.SIGNAL_CHAMBER
		};
	}

	private static SceneSetup layered(int count) {
		return new SceneSetup(
				LayeredHeroFamily.create(count),
				LayeredHeroFamily.camera()
		);
	}

	private static SceneSetup galleryOverlap(boolean sideView) {
		GalleryOverlapScene.Setup setup = GalleryOverlapScene.create();
		return new SceneSetup(
				setup.scene(),
				sideView ? setup.sideCamera() : setup.frontCamera()
		);
	}

}
