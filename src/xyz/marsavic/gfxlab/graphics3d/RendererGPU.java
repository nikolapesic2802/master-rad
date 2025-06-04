package xyz.marsavic.gfxlab.graphics3d;

/**
 * Bridge to the native GPU ray tracer implementation. The implementation is
 * expected to be provided in a native library named {@code raytracer_gpu} as
 * described in the README.
 */
public class RendererGPU {

	static {
	System.loadLibrary("raytracer_gpu");
	}

	/**
	* Renders a single frame using the GPU.
	*
	* @param width        width of the output image in pixels
	* @param height       height of the output image in pixels
	* @param spheresData  packed sphere parameters
	* @param numSpheres   number of spheres in the scene
	* @param outColors    output buffer that will receive RGB triples
	*/
	public static native void renderImage(int width,
	int height,
	float[] spheresData,
	int numSpheres,
	float[] outColors);
}
