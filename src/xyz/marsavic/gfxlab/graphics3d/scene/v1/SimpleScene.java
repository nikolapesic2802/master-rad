package xyz.marsavic.gfxlab.graphics3d.scene.v1;

import xyz.marsavic.gfxlab.Color;
import xyz.marsavic.gfxlab.graphics3d.Scene;
import xyz.marsavic.gfxlab.graphics3d.Solid;

public record SimpleScene(
		Solid solid,
		Color background
) implements Scene {
	@Override
	public Color colorBackground() {
		return background;
	}
}
