# Layered Hero family

The Layered Hero is a deterministic scale family, not one isolated favorable
scene. The scalable core contains repeated eight-primitive modules:

- six inexpensive emissive spheres;
- two expensive red/orange affine boxes.

Every module is projected into the same organized three-layer composition.
Increasing the primitive count refines the fixed image instead of expanding the
world or moving the camera. The supported core counts are 96, 1,000, 10,000,
100,000 and 1,000,000.

The construction challenge is intentional but not a disjoint-plane special
case. Uniform SAH can prefer compact screen-space groups containing both cheap
and expensive primitives. The two affine boxes share a farther centroid layer,
while their tilted bounds overlap toward the sphere layer. A
primitive-cost-aware builder can therefore trade a modest spatial penalty for
separating expensive work.

The presentation variant adds nine fixed ray-tracing objects:

- one finite rear mirror;
- four blue frame elements;
- four glass corner spheres.

These objects make reflection and refraction visible without scaling with the
module count. Core variants omit them for a predeclared presentation ablation.
A 90-degree side view illustrates the three depth layers but is not timed.

All scalable objects remain visible in the primary composition. The research
geometry contains spheres and affine boxes; the fixed mirror frame uses
ordinary boxes.
