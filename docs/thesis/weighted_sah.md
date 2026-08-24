# Primitive-cost-weighted SAH

Conventional object SAH assigns the same intersection cost to every primitive.
That assumption is inaccurate for the renderer's analytic primitives: an
affine box executes substantially more work than a sphere. The generalized
builder replaces the unit primitive weight with

`q_i(lambda) = 1 + lambda (c_i - 1)`,

where `c_i` is the measured cost of primitive `i`, normalized to a sphere.

- `lambda = 0` is exactly conventional uniform SAH.
- `lambda = 1` uses the measured primitive costs directly.
- values between zero and one reduce the influence of measured cost;
- values above one are sensitivity cases that amplify cost differences.

The binary split objective is

`C = c_traversal A_parent + A_left sum(q_i in left) + A_right sum(q_i in right)`.

The same deterministic full-sweep object-SAH implementation evaluates all
centroid-order split positions on all three axes. Only the primitive weights
change. Leaf capacity is a stopping preference rather than a forced maximum:
the builder may split a smaller node when its SAH objective is lower.

The renderer exposes separate `GPU_BVH` and `GPU_WEIGHTED_BVH` modes. The
conventional mode always uses unit primitive weights; setting lambda to zero in
the weighted mode is an exact structural control.
