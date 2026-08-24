# GPU operation-cost calibration

Primitive-weighted SAH needs costs measured from the same intersection code as
the production renderer. `PrimitiveCostBenchmark` compiles the production CUDA
source together with specialized calibration kernels for:

- sphere and box intersections;
- affine sphere and affine box intersections;
- plane intersections;
- one BVH-node AABB test;
- one complete binary interior-node expansion.

Runtime dispatch occurs once per CUDA thread, outside the repeated operation.
Each fresh CUDA context evaluates three deterministic ray profiles, with 2,048
iterations per thread, eight operation copies per iteration, four warm-up pairs
and twelve measured pairs. Every observation is split into 32 launches. The
setup-only kernel is subtracted before every value is normalized to the sphere
intersection cost.

The AABB coefficient is used directly for every traversal AABB test in the
reported work metric. The full interior-node operation is measured separately
only for the SAH construction model, because a binary expansion tests both
child bounds and orders the finite results. A box primitive is also different:
it includes hit semantics and normal selection after its slab test.

Five independent JVM/CUDA contexts are used for the final constants. Raw JSON
files and the exact machine configuration are retained with the thesis
evidence.
