# BVH construction families

The renderer exposes the four construction families evaluated in the thesis.
Each family has an ordinary and a primitive-cost-weighted variant:

- object SAH evaluates every legal centroid boundary on all three axes;
- per-type SAH builds one root for each finite primitive type present in the scene;
- SBVH competes object splits with bounded spatial splits and caps duplicated references;
- local rotations improve an already built object-SAH tree without changing the node layout.

All eight variants use the same packed binary nodes, near-first GPU traversal,
intersection code, random samples and render settings. `BvhBuildConfig` holds
the SAH objective and calibrated costs. `BvhBuildOptions` contains only the
SBVH and rotation parameters that are used by these four families.

RDH and OSAH remain part of the literature review, but they are not implemented
in this repository. Their representative-ray and visibility-training inputs are
therefore absent from the public builder API.
