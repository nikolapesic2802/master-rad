package xyz.marsavic.gfxlab.graphics3d;

import xyz.marsavic.gfxlab.Color;


public interface Scene {
	Solid solid();

	default Color colorBackground() {
		return Color.BLACK;
	}


	class Base implements Scene {

		protected Solid solid;
		protected final Color colorBackground = Color.BLACK;

		@Override
		public Solid solid() {
			return solid;
		}

		@Override
		public Color colorBackground() {
			return colorBackground;
		}

	}


}
