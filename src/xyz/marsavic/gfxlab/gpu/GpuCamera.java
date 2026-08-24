package xyz.marsavic.gfxlab.gpu;

import xyz.marsavic.gfxlab.Vec3;

/**
 * Minimal camera description consumed by the CUDA path tracer.
 */
public class GpuCamera {
	private final Vec3 position;
	private final Vec3 forward;
	private final Vec3 right;
	private final Vec3 up;

	public GpuCamera(Vec3 position,
	                 Vec3 forward,
	                 Vec3 right,
	                 Vec3 up) {
		this.position = position;
		this.forward = forward;
		this.right = right;
		this.up = up;
	}

	public Vec3 position() {
		return position;
	}

	public Vec3 forward() {
		return forward;
	}

	public Vec3 right() {
		return right;
	}

	public Vec3 up() {
		return up;
	}
}
