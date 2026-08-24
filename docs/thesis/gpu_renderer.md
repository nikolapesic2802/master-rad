# CUDA path tracer

The CUDA renderer reproduces the CPU path tracer with a headless, deterministic
measurement interface. At this stage it supports:

- spheres and affine spheres;
- axis-aligned and affine boxes;
- infinite planes;
- diffuse, reflective, refractive and emissive materials;
- pinhole camera rays and bounded path depth.

Scene conversion and upload are performed on the host. Each measured frame
separates CUDA kernel time, device-to-host copy time and total frame time. The
linear GPU renderer tests every finite primitive for every traced path segment;
it is therefore the reference traversal mode for the subsequent BVH chapter.
