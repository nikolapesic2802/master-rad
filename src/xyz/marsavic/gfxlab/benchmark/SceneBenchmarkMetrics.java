package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.gpu.GpuScene;
import xyz.marsavic.gfxlab.gpu.GpuSceneBuilder;

public record SceneBenchmarkMetrics(
		int sphereCount,
		int boxCount,
		int planeCount,
		int affineSphereCount,
		int affineBoxCount,
		int primitiveCount,
		int materialCount,
		long sceneBytes
) {
	public static SceneBenchmarkMetrics fromScene(Scene scene) {
		return fromGpuScene(GpuSceneBuilder.from(scene));
	}

	public static SceneBenchmarkMetrics fromGpuScene(GpuScene scene) {
		int primitiveCount = scene.spheres().size() + scene.boxes().size() + scene.planes().size()
				+ scene.affineSpheres().size() + scene.affineBoxes().size();
		long sceneBytes = (long) (scene.materialData().length + scene.sphereData().length + scene.boxData().length
				+ scene.planeData().length + scene.affineSphereData().length
				+ scene.affineBoxData().length) * Float.BYTES;
		return new SceneBenchmarkMetrics(
				scene.spheres().size(), scene.boxes().size(), scene.planes().size(),
				scene.affineSpheres().size(), scene.affineBoxes().size(),
				primitiveCount, scene.materialCount(), sceneBytes);
	}
}
