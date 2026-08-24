# Result provenance

The measurements used by the frozen thesis remain attached to the exact source
that produced them. The final submission branch reorganizes and simplifies the
implementation without relabelling archived data as output of the refactored
code.

## Main BVH study

The construction, traversal, leaf-size and path-depth studies were measured
from commit `2ffc71f97cbd0085fe1296ed7e3f88c076b3f136`, source tree
`656782480ffebbe6cefe2688df8e0cbc95ac5782` and compiled-class digest
`a8a09df0c8f4ef3e183bdf041ddb9a1e15a1b1042d3cfc1cc8e6d75da6485146`.

The four raw evidence packages are bound by these SHA-256 ledgers:

- construction: `bbd2228bf0bb1c0bea717af729fb26b2c3a31c62cefa529ee2764ec9a7025c1e`;
- traversal evaluation: `6226789e4c60448accf89c7c2ee6e23fa30366697e6c864f3e6ed355870ce759`;
- leaf-size study: `5405ddb3443bdcb2242dac7f23fcc50b5af452a6beae393171edd634c84a58f8`;
- path-depth study: `eb875e586423eb655e2c15ddc5fe93a292ea4c46d63aa838d90a2cffb4ef276d`.

The exact eleven CSV summaries used for the thesis figures, their manifest and
their SHA-256 ledger are committed under `benchmarks/results/main-bvh-study/`.
The analysis ledger has digest
`a2743c43bcb2881273b1aceafc4f536745ea6c25b3b47c8513026215cc6083b2`.

## Path-depth image similarity

The image-similarity curve and representative depth renders form a separate
presentation study. They were rendered from commit
`8cc8a45436ef83e1ea291b95576d170c74dea3fd`, tree
`37dd7ec862356d2e55214011d8e8c58495f58bd5`, and the same compiled-class
digest as the main study. This package uses weighted object SAH with leaf size
8 and lambda 1 at 3840x2160 and 512 samples per pixel. Its CSV and complete
render provenance are retained under `benchmarks/results/depth-similarity/`.
The CSV digest is
`a1aeeb1cd7716fe63cb5d09d9b093e179542daf0b2c7576e2048a77080b906cd`.

These renders provide image-quality evidence only. The timing curve comes from
the main path-depth study, which uses ordinary object SAH with leaf size 8 and
lambda 0 at 1920x1080 and one sample per pixel. The two packages keep their
original identities and are not presented as one measurement run.

## CPU and GPU context figures

The CPU-versus-GPU and linear-GPU-versus-BVH figures use the separately
archived matched-platform study at 1280x720, maximum path depth 12 and leaf
size 8. Its source is commit
`50395ee6a42526e0e96ba936ff6064bda3554947`, tree
`d7b8dba857d616b90de565b3b97004dfc07d97ce`, and compiled-class digest
`04134ba26d0913954e80f14962b285eec32ca3e77e1c8462e23207ca7f5741c8`.
The unchanged summary is committed as
`benchmarks/results/platform-comparison.csv` with SHA-256 digest
`22a6981359da2fad2db983f3039b15f6ba323e430ab9aebf19b93cd5fd8e5b80`.

## Primitive-cost calibration

The applied sphere-normalized weights were produced by commit
`cf9080687ac3706f1cbc7f094750aea197713299` and tree
`ebaa96a5bb504946f94ad9f07f899bcb6a64956a`. Their calibration identity is
`631fed4c77a8ca2de8966a084677a16e3787319319cef3a6a97948f37841ad72` and
the full evidence-manifest digest is
`bd9783dfb0071ac359ce6e02b36f3056145b63f61da37e89b61db88af50e7bab`.
The applied values and context summaries are committed under
`benchmarks/results/primitive-cost-calibration/`.

## Final refactor

The final submission commits preserve the active algorithms, scene definitions,
measurement schedules and output schemas while removing unused APIs and
historical implementation paths. They are not recorded as measurement sources.
Any future campaign must use a new evidence root and retain its own source,
binary, protocol, PTX, device and driver identities.
