# Traversal-work metric

Primitive-test counts alone do not describe BVH work. A tree can reduce costly
affine-box intersections while increasing bound tests. The reported scalar therefore
prices every measured AABB and primitive test:

`C_work = c_AABB N_AABB + sum_t c_t N_t`

`N_AABB` is the number of production BVH slab tests, `N_t` is the number of
intersection tests for primitive type `t`, and each coefficient is normalized
to the measured sphere cost. The AABB coefficient is not the box-primitive
coefficient: a box hit also performs hit selection and normal construction.

The CUDA kernel additionally records path segments, total primitive tests,
root AABB entries, interior-node visits, leaf visits, stack overflows and
maximum live stack occupancy. Those raw counters explain tree behaviour, but
they are not separate terms in `C_work`; their AABB operations are already
included in `N_AABB`.

Timing and counter collection use separate launches. Atomic counter updates
would otherwise perturb the kernel time being measured. The instrumented pass
uses one deterministic frame seed for every method compared on the same
workload.

This is an interpretable operation-count model rather than a cycle-accurate GPU
simulator. Memory traffic, cache behaviour, warp divergence and shading remain
possible sources of residual disagreement with execution time.
