# Primitive-cost-aware BVH construction

This repository contains the implementation, validation and measurements for a
master thesis on BVH construction for heterogeneous analytic primitives.

The Git history begins with the complete
[GfxLab-2022-2023](https://github.com/marsavic/GfxLab-2022-2023)
course history. The last unmodified upstream commit is
[`8f52639`](https://github.com/marsavic/GfxLab-2022-2023/commit/8f526393e6c862c41327495dab8b8afde0ef3f30).
Thesis commits then introduce the headless reference renderer, evaluation
scenes, CUDA path tracing, conventional SAH BVHs, primitive-cost-aware
construction and the final measurement protocol in manuscript order.

## Build

The project requires JDK 19 or newer:

```powershell
./scripts/compile.ps1
```

GPU rendering additionally requires an NVIDIA driver compatible with CUDA 12.
The JCuda native libraries are extracted into the ignored
`lib/jcuda-native` directory on first use.

See [docs/provenance.md](docs/provenance.md) for the exact upstream boundary
and intentional baseline changes.

## Verification and measurements

The study runner always recompiles the project before a stage. A CPU-only
protocol check is available with:

```powershell
./scripts/run_thesis_studies.ps1 -Stage SelfTest
```

GPU correctness and measurement stages require a clean commit and write to a
new directory below `benchmarks/runs/`. For example:

```powershell
./scripts/run_thesis_studies.ps1 -Stage Correctness `
    -OutputRoot benchmarks/runs/correctness-final
```

The complete stage order and output contracts are described in
[docs/thesis/measurement_protocol.md](docs/thesis/measurement_protocol.md).

The exact published summary tables are retained under `benchmarks/results/`.
Their measured source identities and integrity digests are listed in
[benchmarks/RESULTS_PROVENANCE.md](benchmarks/RESULTS_PROVENANCE.md).

Final analysis requires the four completed study roots, the correctness root
and the applied calibration record:

```powershell
python -B benchmarks/analyze_results.py `
    --construction-root benchmarks/runs/construction-final `
    --evaluation-root benchmarks/runs/evaluation-final `
    --leaf-root benchmarks/runs/leaf-final `
    --depth-root benchmarks/runs/depth-final `
    --correctness-root benchmarks/runs/correctness-final `
    --calibration-file benchmarks/results/primitive-cost-calibration/applied-weights.json `
    --output-dir benchmarks/runs/analysis-final
```
