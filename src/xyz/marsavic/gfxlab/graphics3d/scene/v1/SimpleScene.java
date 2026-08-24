package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.graphics3d.Light;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;

import java.util.Collection;
import java.util.List;

public record SimpleScene(
		Solid solid,
		Color background
) implements Scene {
	@Override
	public Collection<Light> lights() {
		return List.of();
	}

	@Override
	public Color colorBackground() {
		return background;
	}
}
