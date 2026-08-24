# Introductory test scenes

The introductory scene set separates renderer behavior by geometric scale while
retaining visible ray-tracing effects.

| Scene | Role | Analytic primitives |
| --- | --- | ---: |
| GI Test | Low-count path-tracing reference with diffuse transport, reflection and refraction | 9 |
| City of Night | Medium-count structured exterior scene | 3,033 |
| Signal Chamber | High-count structured interior scene with dense emissive wall signals | 30,508 |

All three scenes are deterministic. Their geometry and camera are independent
of renderer mode, BVH leaf capacity and SAH construction parameters.

The scene set is used for:

- CPU versus GPU implementation context;
- linear GPU versus conventional BVH traversal;
- leaf-capacity and resolution scaling;
- full-image renderer validation.

Presentation renders are generated only after the final GPU implementation has
been frozen. They are illustrations and are not timing inputs.
