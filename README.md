# Primitive-cost-aware BVH construction

This repository contains the implementation, validation and measurements for a
master thesis on BVH construction for heterogeneous analytic primitives.

The Git history begins with the complete
[GfxLab-2022-2023](https://github.com/marsavic/GfxLab-2022-2023)
course history. The last unmodified upstream commit is
[`8f52639`](https://github.com/marsavic/GfxLab-2022-2023/commit/8f526393e6c862c41327495dab8b8afde0ef3f30).
Thesis commits then introduce the headless reference renderer, evaluation
scenes, CUDA path tracing, conventional and primitive-cost-aware BVH
construction, and the measurement protocol in manuscript order.

## Build

The CPU reference renderer requires JDK 19 or newer:

```powershell
./scripts/compile.ps1
```

Later CUDA commits add the JCuda runtime and document the corresponding NVIDIA
driver requirements.

See [docs/provenance.md](docs/provenance.md) for the exact upstream boundary
and intentional baseline changes.
