package xyz.marsavic.gfxlab.gpu;

/** Launch limits and timing semantics shared by GPU renderers and measurements. */
public final class GpuLaunchProvenance {
	public static final int MAX_RENDER_PIXELS_PER_LAUNCH = 262_144;
	public static final int MAX_REPLAY_RAYS_PER_LAUNCH = 262_144;
	public static final String RENDER_PIXELS_PER_LAUNCH_PROPERTY =
			"gfxlab.gpu.renderPixelsPerLaunch";
	public static final String REPLAY_RAYS_PER_LAUNCH_PROPERTY =
			"gfxlab.gpu.replayRaysPerLaunch";
	public static final int SAMPLES_PER_PHYSICAL_KERNEL = 1;
	public static final String KERNEL_NANOS_SEMANTICS =
			"one CUDA-event interval around the complete physical-launch sequence of a logical frame";

	private GpuLaunchProvenance() { }

	record RenderTileLayout(
			int tileWidth,
			int tileHeight,
			int tileColumns,
			int tileRows,
			long tileCount
	) { }

	public static int renderPixelsPerLaunch() {
		return boundedPositiveProperty(
				RENDER_PIXELS_PER_LAUNCH_PROPERTY, MAX_RENDER_PIXELS_PER_LAUNCH);
	}

	public static int replayRaysPerLaunch() {
		return boundedPositiveProperty(
				REPLAY_RAYS_PER_LAUNCH_PROPERTY, MAX_REPLAY_RAYS_PER_LAUNCH);
	}

	public static long physicalRenderLaunches(int width, int height, int logicalSamples) {
		if (width < 1 || height < 1 || logicalSamples < 1) {
			throw new IllegalArgumentException("Render dimensions and logical samples must be positive");
		}
		RenderTileLayout layout = renderTileLayout(
				width, height, renderPixelsPerLaunch());
		return physicalRenderLaunches(layout.tileCount(), logicalSamples);
	}

	static RenderTileLayout renderTileLayout(
			int width,
			int height,
			int pixelsPerLaunch
	) {
		if (width < 1 || height < 1 || pixelsPerLaunch < 1
				|| pixelsPerLaunch > MAX_RENDER_PIXELS_PER_LAUNCH) {
			throw new IllegalArgumentException("Invalid render tile-plan dimensions");
		}
		int tileWidth = Math.min(width, pixelsPerLaunch);
		int tileHeight = Math.min(height, Math.max(1, pixelsPerLaunch / tileWidth));
		int tileColumns = 1 + (width - 1) / tileWidth;
		int tileRows = 1 + (height - 1) / tileHeight;
		long tileCount = Math.multiplyExact((long) tileColumns, (long) tileRows);
		return new RenderTileLayout(
				tileWidth, tileHeight, tileColumns, tileRows, tileCount);
	}

	static long physicalRenderLaunches(long tileCount, int logicalSamples) {
		if (tileCount < 1L || logicalSamples < 1) {
			throw new IllegalArgumentException("Render tile count and logical samples must be positive");
		}
		return Math.multiplyExact(tileCount, logicalSamples);
	}

	private static int boundedPositiveProperty(String name, int maximum) {
		String raw = System.getProperty(name);
		if (raw == null) {
			return maximum;
		}
		final int value;
		try {
			value = Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("GPU launch configuration requires an integer -D"
					+ name, e);
		}
		if (value < 1 || value > maximum) {
			throw new IllegalStateException(
					"GPU launch configuration requires 1 <= -D" + name + " <= "
							+ maximum + ", got " + value);
		}
		return value;
	}
}
