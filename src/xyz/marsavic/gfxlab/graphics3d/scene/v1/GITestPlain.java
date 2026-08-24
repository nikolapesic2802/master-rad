package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.List;

public final class GITestPlain implements Scene {
	private final Scene scene;

	public GITestPlain() {
		Color leftWall = Color.hsb(0.0 / 3.0, 0.5, 0.7);
		Color rightWall = Color.hsb(1.0 / 3.0, 0.5, 0.7);
		Color neutralWall = Color.gray(0.7);

		var glass = SceneSupport.surface(
				Color.BLACK,
				Color.BLACK,
				Color.gray(0.05),
				Color.gray(0.95),
				1.4
		);

		List<Solid> solids = new ArrayList<>();
		solids.add(SceneSupport.halfSpace(Vec3.xyz(-1.0, 0.0, 0.0), Vec3.xyz(1.0, 0.0, 0.0), SceneSupport.matte(leftWall)));
		solids.add(SceneSupport.halfSpace(Vec3.xyz(1.0, 0.0, 0.0), Vec3.xyz(-1.0, 0.0, 0.0), SceneSupport.matte(rightWall)));
		solids.add(SceneSupport.halfSpace(Vec3.xyz(0.0, -1.0, 0.0), Vec3.xyz(0.0, 1.0, 0.0), SceneSupport.matte(neutralWall)));
		solids.add(SceneSupport.halfSpace(Vec3.xyz(0.0, 1.0, 0.0), Vec3.xyz(0.0, -1.0, 0.0), SceneSupport.emissive(Color.WHITE)));
		solids.add(SceneSupport.halfSpace(Vec3.xyz(0.0, 0.0, 1.0), Vec3.xyz(0.0, 0.0, -1.0), SceneSupport.matte(neutralWall)));

		solids.add(SceneSupport.sphere(Vec3.xyz(-0.2, -0.5, 0.0), 0.3, glass));
		solids.add(SceneSupport.sphere(
				Vec3.xyz(0.5, -0.5, -0.3),
				0.3,
				SceneSupport.surface(Color.BLACK, Color.BLACK, Color.WHITE, Color.BLACK, 1.5)
		));
		solids.add(SceneSupport.sphere(Vec3.xyz(0.0, 0.2, 0.0), 0.2, SceneSupport.matte(Color.gray(0.7))));
		solids.add(SceneSupport.sphere(
				Vec3.xyz(-0.4, 0.5, 0.1),
				0.2,
				SceneSupport.surface(Color.BLACK, Color.BLACK, Color.gray(0.9), Color.BLACK, 1.5)
		));

		scene = new SimpleScene(Group.of(solids), Color.BLACK);
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
