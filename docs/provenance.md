# Source provenance

## Upstream boundary

This repository preserves the complete history of
`marsavic/GfxLab-2022-2023`. Commit
`8f526393e6c862c41327495dab8b8afde0ef3f30` is the final unmodified course
commit and the parent of the thesis history.

Upstream repository:
https://github.com/marsavic/GfxLab-2022-2023

## CPU reference preparation

The first thesis commit retains the analytic CPU path tracer, camera, material,
ray, hit, affine transformation and primitive-intersection code used by the
research implementation. GUI code, course demonstrations, unused rendering
utilities and their third-party dependencies are removed from the current
tree; they remain available in the preserved upstream history.

The retained CPU code has four intentional preparation changes:

1. expose RGB components for deterministic headless image output;
2. expose group children for scene conversion;
3. add explicit material construction helpers used by packed GPU scenes;
4. account for component-selection probability in BSDF mixtures.

No thesis primitive, BVH implementation, GPU renderer or measurement result is
present at this boundary.

## Evidence policy

Generated timing data, operation counters and figures identify the producing
Git commit and runtime environment. Scratch runs are ignored. Evidence used by
the thesis is committed under `benchmarks/results` and `docs/thesis/figures`
only after the corresponding implementation and protocol have been frozen.
