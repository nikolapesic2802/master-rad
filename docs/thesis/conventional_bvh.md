# Conventional SAH BVH

Finite analytic primitives are organized in a binary bounding volume hierarchy.
Infinite planes remain outside the hierarchy and are tested once per traced
path segment.

For a node with surface area `A_P`, the full-sweep object SAH evaluates every
centroid split on all three axes:

`C_split = C_T + A_L / A_P N_L C_I + A_R / A_P N_R C_I`.

At this stage `C_I = 1` for every primitive. A leaf has cost `N C_I`. Splitting
is required above the configured leaf preference whenever a valid centroid
split exists. At or below the preference, splitting continues only when a
candidate strictly improves the leaf cost. The parameter is therefore a
preferred upper occupancy: SAH may create smaller leaves, while unsplittable
centroid degeneracies remain in one leaf.

The host builder stores conservative primitive bounds and flattens the tree
into contiguous node-bound, node-data and primitive-reference arrays. Traversal
tests the root, evaluates both child bounds at an interior node, visits the near
child first and prunes entries beyond the current closest hit.

The implementation records construction time, node and leaf counts, maximum
depth, flattened memory, primitive references and SAH objective. Leaf
capacities 1, 2, 4, 8, 16, 32 and 64 are measured rather than assuming one
literature-prescribed optimum.
