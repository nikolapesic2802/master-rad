package xyz.marsavic.gfxlab.playground;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Camera;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.CityOfNightV1;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.GITestPlain;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSupport;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SignalChamber;

public final class SceneCatalog {

	public enum ScenePreset {
		GI_TEST,
		CITY_OF_NIGHT_V1,
		SIGNAL_CHAMBER
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
		};
	}
}
