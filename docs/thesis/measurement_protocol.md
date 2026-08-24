# Measurement protocol

A measurement campaign starts from a clean commit after the CPU protocol check
and the renderer correctness comparison pass. The correctness comparison uses
the introductory scenes, Layered Hero, Overlap and one layout from each random
family. It requires CPU, linear GPU and all eight BVH variants to select the
same closest hits. Stack-overflow counts must remain zero.

Both checks are stages of `scripts/run_thesis_studies.ps1`. `SelfTest` performs
the CPU protocol check. `Correctness` uses the same fixed stack capacity and
exclusive GPU lock as the timed stages. Its create-new evidence root contains
the comparison CSV, a manifest that binds the source, compiled classes, GPU,
PTX and applied primitive costs, and a SHA-256 ledger.

Timed launches do not collect operation counters. A separate instrumented CUDA
context records the counters used by the modeled-work calculation. Each study
root records the source commit and tree, compiled-class hash, protocol hash,
PTX hash, GPU and driver. Study artifacts are first written to temporary
directories and published with SHA-256 ledgers only after they are complete.

Raw evidence is written below `benchmarks/runs/`. Failed or incomplete roots
are not reused for final analysis.

`benchmarks/analyze_results.py` requires the completed construction,
evaluation, leaf-size and depth roots together with `--correctness-root` and
`--calibration-file`. It accepts the applied-weights JSON independently of its
schema version, verifies its calibration identity and weights against the
correctness package and the modeled-work constants, and records the two input
digests in the analysis manifest.

The path-depth study uses GI Test and one ordinary object-SAH tree with leaf
size 8 and lambda 0 for both parts of the study. Kernel timing is measured at
1920x1080 with paired deterministic frames. The presentation stage renders the
same scene, camera, tree and experiment seed at 3840x2160 and 512 samples per
pixel. Every tested depth uses the same frame sequence and the exposure derived
from depth 32. Image similarity is 100 times one minus the mean absolute
8-bit RGB channel difference from depth 32 divided by 255. The package contains
all source PNG files, the similarity CSV, the render-strip selection, a manifest
and a SHA-256 ledger.
