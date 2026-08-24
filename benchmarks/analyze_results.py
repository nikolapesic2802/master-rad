#!/usr/bin/env python3
"""Reduce the final construction, evaluation, leaf-size, and depth studies."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import tempfile
from collections import Counter, defaultdict
from decimal import Decimal, localcontext
from pathlib import Path
from typing import Iterable, Mapping, Sequence


SCHEMA_VERSION = 1
ANALYZER_VERSION = "results-analysis-v1"

METHODS = (
    "uniform",
    "weighted",
    "per_type",
    "weighted_per_type",
    "sbvh",
    "weighted_sbvh",
    "sah_rotations",
    "weighted_sah_rotations",
)
PUBLICATION_ROWS = (
    "hero-96",
    "hero-1k",
    "hero-10k",
    "hero-100k",
    "hero-1m",
    "overlap-10k",
    "F1-100",
    "F1-1k",
    "F1-10k",
    "F2-100",
    "F2-1k",
    "F2-10k",
    "F3-100",
    "F3-1k",
    "F3-10k",
    "F4-100",
    "F4-1k",
    "F4-10k",
)

MATRIX_HEADER = (
    "schemaVersion",
    "evidenceState",
    "rowOrdinal",
    "rowId",
    *METHODS,
)
SIGN_HEADER = (
    "schemaVersion",
    "evidenceState",
    "family",
    "objectCount",
    "comparisonId",
    "layoutCount",
    "timeBetter",
    "timeWorse",
    "timeTie",
    "workBetter",
    "workWorse",
    "workTie",
)
LEAF_HEADER = (
    "schemaVersion",
    "evidenceState",
    "sceneOrdinal",
    "sceneId",
    "leafSize",
    "context1ReductionPercent",
    "context2ReductionPercent",
    "context3ReductionPercent",
    "context4ReductionPercent",
    "context5ReductionPercent",
    "medianBlockReductionPercent",
    "relativeSlowdownFromBestPercent",
    "topologySha256",
    "nodeCount",
    "maxDepth",
    "identicalTopologyLeafSizes",
)
DEPTH_HEADER = (
    "schemaVersion",
    "evidenceState",
    "depthOrdinal",
    "maximumPathDepth",
    "isReference",
    "comparisonId",
    "blockCount",
    "medianRelativeKernelPercent",
    "topologySha256",
)
CORE_DETAIL_HEADER = (
    "schemaVersion",
    "evidenceState",
    "rowOrdinal",
    "rowId",
    "methodOrdinal",
    "methodId",
    "layoutCount",
    "timeReductionPercent",
    "workReductionPercent",
    "aabbReductionPercent",
    "primitiveReductionPercent",
    "medianRays",
    "medianAabbTests",
    "medianPrimitiveTests",
    "medianSphereTests",
    "medianBoxTests",
    "medianPlaneTests",
    "medianAffineSphereTests",
    "medianAffineBoxTests",
    "medianInternalNodeVisits",
    "medianLeafNodeVisits",
    "medianModeledWork",
    "topologySha256",
    "topologyDistinctCount",
)
HERO_SENSITIVITY_HEADER = (
    "schemaVersion",
    "evidenceState",
    "rowOrdinal",
    "rowId",
    "scale",
    "cellOrdinal",
    "leafSize",
    "lambda",
    "comparisonId",
    "anchorSynthetic",
    "blockCount",
    "timeReductionPercent",
    "workReductionPercent",
    "aabbReductionPercent",
    "primitiveReductionPercent",
    "referenceModeledWork",
    "candidateModeledWork",
    "topologySha256",
)

CONSTRUCTION_RAW_HEADER = tuple(
    "schemaVersion,protocolVersion,buildOrdinal,publicationRowOrdinal,rowId,study,"
    "family,objectCount,layoutId,layoutSha256,phase,round,position,methodOrdinal,"
    "methodId,mode,leafSize,lambda,optionKey,primitiveCount,activePackedPrimitiveTypeCount,"
    "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,duplicateReferenceCount,"
    "spatialSplitCount,rotationCount,bytes,buildNanos,wallNanos,"
    "packedGeometrySha256,topologySha256".split(",")
)
EVALUATION_BUILD_HEADER = tuple(
    "schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
    "objectCount,layoutId,endpointId,methodId,mode,leafSize,lambda,optionKey,"
    "primitiveCount,nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,"
    "duplicateReferenceCount,spatialSplitCount,rotationCount,bytes,buildNanos,"
    "wallNanos,packedGeometrySha256,topologySha256".split(",")
)
EVALUATION_TIMING_HEADER = tuple(
    "schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
    "objectCount,layoutId,comparisonOrdinal,comparisonId,referenceId,candidateId,"
    "context,blockIndex,position,order,variant,endpointId,topologySha256,"
    "maximumPathDepth,measurementSeed,conditioningSeed,conditioningFrameIndex,"
    "conditioningUploadNanos,conditioningMaximumPhysicalKernelNanos,"
    "conditioningAggregatePhysicalKernelNanos,conditioningTotalNanos,"
    "measurementFrameIndex,kernelNanos,maximumPhysicalKernelNanos,uploadNanos,"
    "copyNanos,totalNanos".split(",")
)
EVALUATION_METRICS_HEADER = tuple(
    "schemaVersion,protocolVersion,study,publicationRowOrdinal,rowId,family,"
    "objectCount,layoutId,methodId,metricsContext,frameSeed,kernelNanos,"
    "maximumPhysicalKernelNanos,rays,aabbTests,primitiveTests,sphereTests,"
    "boxTests,planeTests,affineSphereTests,affineBoxTests,internalNodeVisits,"
    "leafNodeVisits,stackOverflows,maxStackSize,modeledWork,topologySha256".split(",")
)
LEAF_BUILD_HEADER = tuple(
    "schemaVersion,protocolVersion,sceneOrdinal,sceneId,leafSize,primitiveCount,"
    "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,bytes,buildNanos,"
    "wallNanos,packedGeometrySha256,topologySha256".split(",")
)
LEAF_TIMING_HEADER = tuple(
    "schemaVersion,protocolVersion,sceneOrdinal,sceneId,comparisonOrdinal,comparisonId,"
    "referenceLeafSize,candidateLeafSize,context,blockIndex,position,order,variant,"
    "endpointId,topologySha256,maximumPathDepth,measurementSeed,conditioningSeed,"
    "conditioningFrameIndex,conditioningUploadNanos,"
    "conditioningMaximumPhysicalKernelNanos,conditioningAggregatePhysicalKernelNanos,"
    "conditioningTotalNanos,measurementFrameIndex,kernelNanos,"
    "maximumPhysicalKernelNanos,uploadNanos,copyNanos,totalNanos".split(",")
)
DEPTH_BUILD_HEADER = tuple(
    "schemaVersion,protocolVersion,sceneId,methodId,leafSize,lambda,primitiveCount,"
    "nodeCount,rootCount,leafCount,maxDepth,primitiveRefCount,bytes,buildNanos,"
    "wallNanos,packedGeometrySha256,topologySha256".split(",")
)
DEPTH_TIMING_HEADER = tuple(
    "schemaVersion,protocolVersion,sceneId,comparisonOrdinal,comparisonId,"
    "referenceDepth,candidateDepth,context,blockIndex,position,order,variant,"
    "endpointId,topologySha256,maximumPathDepth,measurementSeed,conditioningSeed,"
    "conditioningFrameIndex,conditioningUploadNanos,"
    "conditioningMaximumPhysicalKernelNanos,conditioningAggregatePhysicalKernelNanos,"
    "conditioningTotalNanos,measurementFrameIndex,kernelNanos,"
    "maximumPhysicalKernelNanos,uploadNanos,copyNanos,totalNanos".split(",")
)
CORRECTNESS_HEADER = tuple(
    "scene,implementation,formula,leafSize,lambda,rays,primitiveCount,mismatches,"
    "maxAbsoluteError,maxRelativeError,maxNormalAbsoluteError,aabbTests,primitiveTests,"
    "stackOverflows,leafNodeVisits,homogeneousLeafNodeVisits,mixedLeafNodeVisits,"
    "maxStackSize,nodeCount,leafCount,maxDepth,bvhBytes".split(",")
)

COSTS = {
    "aabbTests": Decimal("0.507189194"),
    "sphereTests": Decimal("1.0"),
    "boxTests": Decimal("1.288951704"),
    "planeTests": Decimal("2.860319327"),
    "affineSphereTests": Decimal("2.552743938"),
    "affineBoxTests": Decimal("2.728115282"),
}
CALIBRATION_WEIGHT_NAMES = (
    "sphere",
    "box",
    "affineSphere",
    "affineBox",
    "plane",
    "nodeAabb",
    "interiorTraversal",
)
COST_CALIBRATION_NAMES = {
    "aabbTests": "nodeAabb",
    "sphereTests": "sphere",
    "boxTests": "box",
    "planeTests": "plane",
    "affineSphereTests": "affineSphere",
    "affineBoxTests": "affineBox",
}
SOURCE_FIELDS = ("compiledClassesSha256", "sourceCommit", "sourceTree")
GPU_FIELDS = ("deviceName", "computeCapability", "driverVersion", "timingPtxSha256")


class AnalysisError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def strict_int(row: Mapping[str, object], field: str) -> int:
    value = row.get(field)
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if not isinstance(value, str) or not value or value.strip() != value:
        raise AnalysisError(f"missing integer field: {field}")
    try:
        parsed = int(value)
    except ValueError as error:
        raise AnalysisError(f"invalid integer field: {field}") from error
    if str(parsed) != value:
        raise AnalysisError(f"noncanonical integer field: {field}")
    return parsed


def strict_decimal(row: Mapping[str, object], field: str) -> Decimal:
    value = row.get(field)
    if not isinstance(value, str) or not value or value.strip() != value:
        raise AnalysisError(f"missing decimal field: {field}")
    try:
        result = Decimal(value)
    except Exception as error:
        raise AnalysisError(f"invalid decimal field: {field}") from error
    if not result.is_finite():
        raise AnalysisError(f"nonfinite decimal field: {field}")
    return result


def strict_hex(value: object, length: int, label: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != length
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise AnalysisError(f"invalid {label}")
    return value


def load_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AnalysisError(f"invalid JSON: {path}") from error
    if not isinstance(value, dict):
        raise AnalysisError(f"JSON object required: {path}")
    return value


def load_decimal_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AnalysisError(f"invalid JSON: {path}") from error
    if not isinstance(value, dict):
        raise AnalysisError(f"JSON object required: {path}")
    return value


def strict_json_decimal(value: object, label: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        raise AnalysisError(f"invalid {label}")
    result = Decimal(value)
    if not result.is_finite() or result <= 0:
        raise AnalysisError(f"invalid {label}")
    return result


def read_csv(path: Path, expected_header: Sequence[str]) -> list[dict[str, str]]:
    if not path.is_file():
        raise AnalysisError(f"missing CSV: {path}")
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if tuple(reader.fieldnames or ()) != tuple(expected_header):
            raise AnalysisError(f"CSV schema differs: {path}")
        rows = list(reader)
    if not rows:
        raise AnalysisError(f"empty CSV: {path}")
    return rows


def verify_ledger(
    root: Path,
    ledger_name: str = "SHA256SUMS.txt",
    expected_names: set[str] | None = None,
) -> str:
    root = root.resolve()
    if not root.is_dir() or root.is_symlink():
        raise AnalysisError(f"invalid evidence directory: {root}")
    ledger = root / ledger_name
    if not ledger.is_file():
        raise AnalysisError(f"missing ledger: {ledger}")
    names: set[str] = set()
    for line in ledger.read_text(encoding="utf-8").splitlines():
        fields = line.split("  ")
        if len(fields) != 2 or fields[1] in names:
            raise AnalysisError(f"malformed ledger: {ledger}")
        digest = strict_hex(fields[0], 64, "ledger digest")
        target = (root / fields[1]).resolve()
        try:
            target.relative_to(root)
        except ValueError as error:
            raise AnalysisError(f"ledger path escapes evidence root: {fields[1]}") from error
        if not target.is_file() or target.is_symlink() or sha256(target) != digest:
            raise AnalysisError(f"artifact hash differs: {target}")
        names.add(fields[1])
    if not names:
        raise AnalysisError(f"empty ledger: {ledger}")
    if expected_names is not None and names != expected_names:
        raise AnalysisError(f"ledger inventory differs: {ledger}")
    return sha256(ledger)


def source_identity(manifest: Mapping[str, object], label: str) -> dict[str, str]:
    return {
        "compiledClassesSha256": strict_hex(
            manifest.get("compiledClassesSha256"), 64, f"{label} compiled classes hash"
        ),
        "sourceCommit": strict_hex(manifest.get("sourceCommit"), 40, f"{label} source commit"),
        "sourceTree": strict_hex(manifest.get("sourceTree"), 40, f"{label} source tree"),
    }


def gpu_identity(manifest: Mapping[str, object], label: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for field in GPU_FIELDS:
        value = manifest.get(field)
        if field == "timingPtxSha256":
            result[field] = strict_hex(value, 64, f"{label} timing PTX hash")
        elif not isinstance(value, str) or not value:
            raise AnalysisError(f"missing {label} GPU field: {field}")
        else:
            result[field] = value
    return result


def campaign_identity(
    root: Path,
    ledger_name: str,
    manifest: Mapping[str, object],
    label: str,
    *,
    gpu: bool,
    metrics_ptx: bool = False,
) -> dict[str, object]:
    protocol_version = manifest.get("protocolVersion")
    if not isinstance(protocol_version, str) or not protocol_version:
        raise AnalysisError(f"missing {label} protocol version")
    record: dict[str, object] = {
        "sourceIdentity": source_identity(manifest, label),
        "protocolVersion": protocol_version,
        "protocolSha256": strict_hex(
            manifest.get("protocolSha256"), 64, f"{label} protocol hash"
        ),
        "ledgerFile": ledger_name,
        "ledgerSha256": sha256(root / ledger_name),
    }
    if gpu:
        record["gpuRuntimeIdentity"] = gpu_identity(manifest, label)
    if metrics_ptx:
        record["metricsPtxSha256"] = strict_hex(
            manifest.get("metricsPtxSha256"), 64, f"{label} metrics PTX hash"
        )
    return record


def calibration_weights(record: Mapping[str, object], label: str) -> dict[str, Decimal]:
    value = record.get("calibrationWeights")
    if not isinstance(value, Mapping) or set(value) != set(CALIBRATION_WEIGHT_NAMES):
        raise AnalysisError(f"{label} calibration-weight inventory differs")
    return {
        name: strict_json_decimal(value.get(name), f"{label} calibration weight {name}")
        for name in CALIBRATION_WEIGHT_NAMES
    }


def analyze_correctness(root: Path) -> dict[str, object]:
    candidate = root.absolute()
    if candidate.is_symlink():
        raise AnalysisError(f"invalid correctness evidence directory: {candidate}")
    root = candidate.resolve()
    ledger_digest = verify_ledger(
        root,
        expected_names={"correctness.csv", "manifest.json"},
    )
    if {path.name for path in root.iterdir()} != {
        "correctness.csv",
        "manifest.json",
        "SHA256SUMS.txt",
    }:
        raise AnalysisError("correctness evidence inventory differs")
    manifest = load_decimal_json(root / "manifest.json")
    expected_manifest_fields = {
        "schemaVersion",
        "study",
        "measurementState",
        "scenarioCount",
        "methodCount",
        "rowCount",
        "mismatches",
        "bvhStackSize",
        "deviceName",
        "computeCapability",
        "driverVersion",
        "linearPtxSha256",
        "timingPtxSha256",
        "compiledClassesSha256",
        "sourceCommit",
        "sourceTree",
        "calibrationAppliedIdentitySha256",
        "calibrationWeights",
    }
    if set(manifest) != expected_manifest_fields:
        raise AnalysisError("correctness manifest fields differ")
    if (
        manifest.get("schemaVersion") != 1
        or manifest.get("study") != "gpu-bvh-correctness"
        or manifest.get("measurementState") != "GPU_CORRECTNESS_PASS"
        or manifest.get("methodCount") != len(METHODS)
        or manifest.get("mismatches") != 0
    ):
        raise AnalysisError("correctness manifest state differs")
    stack_size = strict_int(manifest, "bvhStackSize")
    if stack_size != 32:
        raise AnalysisError("correctness stack capacity differs")
    source = source_identity(manifest, "correctness")
    gpu = gpu_identity(manifest, "correctness")
    linear_ptx = strict_hex(
        manifest.get("linearPtxSha256"), 64, "correctness linear PTX hash"
    )
    calibration_identity = strict_hex(
        manifest.get("calibrationAppliedIdentitySha256"),
        64,
        "correctness calibration identity",
    )
    weights = calibration_weights(manifest, "correctness")

    rows = read_csv(root / "correctness.csv", CORRECTNESS_HEADER)
    expected_scenes = {
        "GI_TEST",
        "CITY_OF_NIGHT_V1",
        "SIGNAL_CHAMBER",
        "LAYERED_HERO_96",
        "GALLERY_OVERLAP",
        "F1-100",
        "F2-100",
        "F3-100",
        "F4-100",
    }
    weighted_methods = {
        "weighted",
        "weighted_per_type",
        "weighted_sbvh",
        "weighted_sah_rotations",
    }
    methods_by_scene: dict[str, set[str]] = defaultdict(set)
    linear_by_scene: Counter[str] = Counter()
    for row in rows:
        scene = row.get("scene", "")
        if scene not in expected_scenes or strict_int(row, "mismatches") != 0:
            raise AnalysisError("correctness row contains an unknown scene or mismatch")
        if strict_int(row, "rays") != 32 * 18 or strict_int(row, "primitiveCount") <= 0:
            raise AnalysisError(f"correctness workload differs: {scene}")
        for field in ("maxAbsoluteError", "maxRelativeError", "maxNormalAbsoluteError"):
            if strict_decimal(row, field) < 0:
                raise AnalysisError(f"negative correctness error: {scene}/{field}")
        implementation = row.get("implementation")
        formula = row.get("formula", "")
        if implementation == "GPU_LINEAR":
            linear_by_scene[scene] += 1
            if (
                formula != "none"
                or strict_int(row, "leafSize") != 0
                or strict_decimal(row, "lambda") != 0
                or strict_int(row, "stackOverflows") != 0
            ):
                raise AnalysisError(f"invalid linear correctness row: {scene}")
        elif implementation == "GPU_BVH":
            if formula not in METHODS or formula in methods_by_scene[scene]:
                raise AnalysisError(f"duplicate or unknown correctness method: {scene}/{formula}")
            expected_lambda = Decimal(1) if formula in weighted_methods else Decimal(0)
            if (
                strict_int(row, "leafSize") != 8
                or strict_decimal(row, "lambda") != expected_lambda
                or strict_int(row, "stackOverflows") != 0
                or strict_int(row, "maxStackSize") >= stack_size
            ):
                raise AnalysisError(f"invalid BVH correctness row: {scene}/{formula}")
            methods_by_scene[scene].add(formula)
        else:
            raise AnalysisError(f"unknown correctness implementation: {implementation}")
    if (
        set(methods_by_scene) != expected_scenes
        or any(methods_by_scene[scene] != set(METHODS) for scene in expected_scenes)
        or set(linear_by_scene) != expected_scenes
        or any(linear_by_scene[scene] != 1 for scene in expected_scenes)
        or manifest.get("scenarioCount") != len(expected_scenes)
        or manifest.get("rowCount") != len(rows)
        or len(rows) != len(expected_scenes) * (len(METHODS) + 1)
    ):
        raise AnalysisError("correctness evidence coverage differs")
    return {
        "sourceIdentity": source,
        "gpuRuntimeIdentity": gpu,
        "ledgerFile": "SHA256SUMS.txt",
        "ledgerSha256": ledger_digest,
        "measurementState": "GPU_CORRECTNESS_PASS",
        "linearPtxSha256": linear_ptx,
        "calibrationAppliedIdentitySha256": calibration_identity,
        "calibrationWeights": weights,
    }


def analyze_calibration_file(
    path: Path, correctness: Mapping[str, object]
) -> dict[str, object]:
    candidate = path.absolute()
    if candidate.is_symlink() or not candidate.is_file():
        raise AnalysisError(f"invalid applied-calibration file: {candidate}")
    resolved = candidate.resolve()
    record = load_decimal_json(resolved)
    calibration_identity = strict_hex(
        record.get("calibrationIdentitySha256"), 64, "applied calibration identity"
    )
    if calibration_identity != correctness.get("calibrationAppliedIdentitySha256"):
        raise AnalysisError("applied calibration identity differs from correctness evidence")
    value = record.get("weights")
    if not isinstance(value, Mapping) or set(value) != set(CALIBRATION_WEIGHT_NAMES):
        raise AnalysisError("applied calibration-weight inventory differs")
    weights = {
        name: strict_json_decimal(value.get(name), f"applied calibration weight {name}")
        for name in CALIBRATION_WEIGHT_NAMES
    }
    if weights != correctness.get("calibrationWeights"):
        raise AnalysisError("applied calibration weights differ from correctness evidence")
    for counter, weight_name in COST_CALIBRATION_NAMES.items():
        if COSTS[counter] != weights[weight_name]:
            raise AnalysisError(f"analysis cost differs from applied calibration: {counter}")
    device = record.get("device")
    correctness_gpu = correctness.get("gpuRuntimeIdentity")
    if not isinstance(device, Mapping) or not isinstance(correctness_gpu, Mapping):
        raise AnalysisError("applied calibration device is missing")
    for calibration_field, correctness_field in (
        ("name", "deviceName"),
        ("computeCapability", "computeCapability"),
        ("driverVersion", "driverVersion"),
    ):
        if device.get(calibration_field) != correctness_gpu.get(correctness_field):
            raise AnalysisError("applied calibration device differs from correctness evidence")
    evidence_manifest = strict_hex(
        record.get("evidenceManifestSha256"), 64, "calibration evidence-manifest hash"
    )
    return {
        "fileName": resolved.name,
        "fileSha256": sha256(resolved),
        "calibrationIdentitySha256": calibration_identity,
        "evidenceManifestSha256": evidence_manifest,
    }


def structure_identity(row: Mapping[str, str], label: str) -> tuple[object, ...]:
    mode = row.get("mode", "")
    option = row.get("optionKey", "")
    if not mode or not option:
        raise AnalysisError(f"missing {label} structure configuration")
    positive_fields = (
        "primitiveCount",
        "nodeCount",
        "rootCount",
        "leafCount",
        "maxDepth",
        "primitiveRefCount",
        "bytes",
    )
    nonnegative_fields = (
        "duplicateReferenceCount",
        "spatialSplitCount",
        "rotationCount",
    )
    positive = tuple(strict_int(row, field) for field in positive_fields)
    nonnegative = tuple(strict_int(row, field) for field in nonnegative_fields)
    if any(value <= 0 for value in positive) or any(value < 0 for value in nonnegative):
        raise AnalysisError(f"invalid {label} structure")
    return (
        mode,
        strict_int(row, "leafSize"),
        strict_decimal(row, "lambda"),
        option,
        *positive,
        *nonnegative,
        strict_hex(row.get("packedGeometrySha256"), 64, f"{label} geometry hash"),
        strict_hex(row.get("topologySha256"), 64, f"{label} topology hash"),
    )


def median_decimal(values: Sequence[Decimal]) -> Decimal:
    if not values or any(not value.is_finite() for value in values):
        raise AnalysisError("invalid median input")
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / Decimal(2)


def format_decimal(value: Decimal, places: int | None = None) -> str:
    if places is not None:
        return f"{value:.{places}f}"
    if value == value.to_integral_value():
        return str(int(value))
    return format(value.normalize(), "f")


def block_reduction_percent(rows: Sequence[Mapping[str, str]]) -> Decimal:
    if len(rows) != 4:
        raise AnalysisError("a timing block must contain four rows")
    ordered = sorted(rows, key=lambda row: strict_int(row, "position"))
    if [strict_int(row, "position") for row in ordered] != [0, 1, 2, 3]:
        raise AnalysisError("timing block positions differ")
    order = ordered[0].get("order")
    expected = (
        ("A", "B", "B", "A")
        if order == "ABBA"
        else (("B", "A", "A", "B") if order == "BAAB" else None)
    )
    if expected is None or any(row.get("order") != order for row in ordered):
        raise AnalysisError("timing block order differs")
    if tuple(row.get("variant") for row in ordered) != expected:
        raise AnalysisError("timing block variants differ")
    if strict_int(ordered[0], "measurementSeed") != strict_int(ordered[1], "measurementSeed"):
        raise AnalysisError("first timing pair uses different seeds")
    if strict_int(ordered[2], "measurementSeed") != strict_int(ordered[3], "measurementSeed"):
        raise AnalysisError("second timing pair uses different seeds")
    references = [strict_int(row, "kernelNanos") for row in ordered if row["variant"] == "A"]
    candidates = [strict_int(row, "kernelNanos") for row in ordered if row["variant"] == "B"]
    if min(*references, *candidates) <= 0:
        raise AnalysisError("kernel time must be positive")
    with localcontext() as context:
        context.prec = 60
        ratio = (
            Decimal(candidates[0])
            * Decimal(candidates[1])
            / (Decimal(references[0]) * Decimal(references[1]))
        ).sqrt()
        return Decimal(100) * (Decimal(1) - ratio)


def direct_reduction(reference: Decimal, candidate: Decimal) -> Decimal:
    if reference <= 0 or candidate < 0:
        raise AnalysisError("invalid reduction input")
    return Decimal(100) * (Decimal(1) - candidate / reference)


def reconstructed_work(row: Mapping[str, str]) -> Decimal:
    primitive = strict_int(row, "primitiveTests")
    component_sum = sum(
        strict_int(row, field)
        for field in (
            "sphereTests",
            "boxTests",
            "planeTests",
            "affineSphereTests",
            "affineBoxTests",
        )
    )
    if primitive != component_sum:
        raise AnalysisError("primitive counters do not sum to primitiveTests")
    return sum(Decimal(strict_int(row, field)) * weight for field, weight in COSTS.items())


def metric_record(row: Mapping[str, str]) -> dict[str, Decimal]:
    if strict_int(row, "stackOverflows") != 0:
        raise AnalysisError("instrumented traversal overflowed its stack")
    fields = (
        "rays",
        "aabbTests",
        "primitiveTests",
        "sphereTests",
        "boxTests",
        "planeTests",
        "affineSphereTests",
        "affineBoxTests",
        "internalNodeVisits",
        "leafNodeVisits",
    )
    values = {field: Decimal(strict_int(row, field)) for field in fields}
    if values["rays"] <= 0:
        raise AnalysisError("instrumented frame contains no rays")
    values["modeledWork"] = reconstructed_work(row)
    return values


def analyze_construction(
    root: Path,
) -> tuple[
    dict[tuple[str, str], dict[str, Decimal]],
    dict[tuple[str, int, str], tuple[object, ...]],
    dict[str, object],
]:
    verify_ledger(root)
    manifest = load_json(root / "manifest.json")
    if manifest.get("measurementState") != "CPU_MEASURED":
        raise AnalysisError("construction evidence is not complete")
    protocol_version = manifest.get("protocolVersion")
    rows = read_csv(root / "raw-builds.csv", CONSTRUCTION_RAW_HEADER)
    if manifest.get("totalRows") != len(rows):
        raise AnalysisError("construction manifest row count differs")

    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    structures: dict[tuple[str, int, str], tuple[object, ...]] = {}
    for row in rows:
        if row.get("phase") != "measured":
            continue
        row_id = row.get("rowId", "")
        method = row.get("methodId", "")
        if (
            row.get("protocolVersion") != protocol_version
            or row_id not in PUBLICATION_ROWS
            or method not in METHODS
            or strict_int(row, "publicationRowOrdinal") != PUBLICATION_ROWS.index(row_id)
            or strict_int(row, "methodOrdinal") != METHODS.index(method)
        ):
            raise AnalysisError("invalid construction row identity")
        build = strict_int(row, "buildNanos")
        wall = strict_int(row, "wallNanos")
        if build <= 0 or wall < build:
            raise AnalysisError("invalid construction timing")
        grouped[(row_id, method)].append(row)
        key = (row_id, strict_int(row, "layoutId"), method)
        identity = structure_identity(row, "construction")
        previous = structures.setdefault(key, identity)
        if previous != identity:
            raise AnalysisError("construction topology changed inside one workload")

    if manifest.get("measuredRows") != sum(len(value) for value in grouped.values()):
        raise AnalysisError("construction measured-row count differs")

    cells: dict[tuple[str, str], dict[str, Decimal]] = {}
    for row_id in PUBLICATION_ROWS:
        random_row = row_id.startswith(("F1-", "F2-", "F3-", "F4-"))
        for method in METHODS:
            cell_rows = grouped.get((row_id, method), [])
            expected_count = 100 if random_row else 8
            if len(cell_rows) != expected_count:
                raise AnalysisError(f"construction cell count differs: {row_id}/{method}")
            layouts = {strict_int(row, "layoutId") for row in cell_rows}
            expected_layouts = set(range(100)) if random_row else {-1}
            if layouts != expected_layouts:
                raise AnalysisError(f"construction layouts differ: {row_id}/{method}")
            if not random_row and len({row["topologySha256"] for row in cell_rows}) != 1:
                raise AnalysisError(f"fixed construction topology differs: {row_id}/{method}")
            cells[(row_id, method)] = {
                "build_ms": median_decimal(
                    [Decimal(strict_int(row, "buildNanos")) / Decimal(1_000_000) for row in cell_rows]
                ),
                "nodes": median_decimal(
                    [Decimal(strict_int(row, "nodeCount")) for row in cell_rows]
                ),
                "bytes": median_decimal(
                    [Decimal(strict_int(row, "bytes")) for row in cell_rows]
                ),
                "depth": median_decimal(
                    [Decimal(strict_int(row, "maxDepth")) for row in cell_rows]
                ),
            }
    return cells, structures, campaign_identity(
        root, "SHA256SUMS.txt", manifest, "construction", gpu=False
    )


def matrix_rows(
    cells: Mapping[tuple[str, str], Mapping[str, Decimal]],
    field: str,
    evidence_state: str,
    places: int | None = None,
) -> list[dict[str, object]]:
    output: list[dict[str, object]] = []
    for ordinal, row_id in enumerate(PUBLICATION_ROWS):
        row: dict[str, object] = {
            "schemaVersion": SCHEMA_VERSION,
            "evidenceState": evidence_state,
            "rowOrdinal": ordinal,
            "rowId": row_id,
        }
        for method in METHODS:
            try:
                value = cells[(row_id, method)][field]
            except KeyError as error:
                raise AnalysisError(f"missing matrix cell: {row_id}/{method}/{field}") from error
            row[method] = format_decimal(value, places)
        output.append(row)
    return output


def _consistent_row_info(
    row_info: dict[str, tuple[int, str, str, int]], row: Mapping[str, str]
) -> None:
    row_id = row.get("rowId", "")
    if row_id not in PUBLICATION_ROWS:
        raise AnalysisError(f"unknown publication row: {row_id}")
    value = (
        strict_int(row, "publicationRowOrdinal"),
        row.get("study", ""),
        row.get("family", ""),
        strict_int(row, "objectCount"),
    )
    if value[0] != PUBLICATION_ROWS.index(row_id) or not value[1]:
        raise AnalysisError(f"invalid publication row metadata: {row_id}")
    previous = row_info.setdefault(row_id, value)
    if previous != value:
        raise AnalysisError(f"inconsistent publication row metadata: {row_id}")


def _sensitivity_cells(
    structures: Mapping[tuple[str, int, str], tuple[object, ...]],
) -> list[dict[str, object]]:
    expected: dict[str, tuple[int, Decimal]] | None = None
    for row_id in PUBLICATION_ROWS[:5]:
        endpoints: dict[str, tuple[int, Decimal]] = {}
        for (candidate_row, layout, endpoint), identity in structures.items():
            if candidate_row != row_id or layout != -1:
                continue
            if endpoint in {"uniform", "weighted"} or endpoint.startswith("sensitivity_"):
                endpoints[endpoint] = (int(identity[1]), Decimal(identity[2]))
        if expected is None:
            expected = endpoints
        elif endpoints != expected:
            raise AnalysisError("Hero sensitivity configurations differ by scale")
    if expected is None or set(("uniform", "weighted")) - set(expected):
        raise AnalysisError("missing Hero sensitivity anchors")
    ordered = sorted(expected.items(), key=lambda item: (item[1][0], item[1][1]))
    if len(ordered) != 77:
        raise AnalysisError("Hero sensitivity grid differs")
    output: list[dict[str, object]] = []
    for ordinal, (endpoint, (leaf_size, lambda_value)) in enumerate(ordered):
        anchor = endpoint == "uniform"
        comparison = "anchor" if anchor else f"uniform__{endpoint}"
        output.append(
            {
                "cellOrdinal": ordinal,
                "leafSize": leaf_size,
                "lambda": lambda_value,
                "anchor": anchor,
                "endpoint": endpoint,
                "comparisonId": comparison,
            }
        )
    return output


def _sign(value: Decimal) -> str:
    if value > 0:
        return "better"
    if value < 0:
        return "worse"
    return "tie"


def analyze_evaluation(
    root: Path,
) -> tuple[
    dict[str, list[dict[str, object]]],
    dict[tuple[str, int, str], tuple[object, ...]],
    dict[str, object],
]:
    verify_ledger(root)
    verify_ledger(root, "FINAL_SHA256SUMS.txt")
    final_manifest = load_json(root / "final-manifest.json")
    if final_manifest.get("measurementState") != "GPU_MEASURED":
        raise AnalysisError("evaluation evidence is not complete")
    protocol_version = final_manifest.get("protocolVersion")
    protocol_hash = strict_hex(
        final_manifest.get("protocolSha256"), 64, "evaluation protocol hash"
    )
    if sha256(root / "protocol.json") != protocol_hash:
        raise AnalysisError("evaluation protocol hash differs")
    archived_analyzer_hash = strict_hex(
        final_manifest.get("analyzerSha256"), 64, "archived analyzer hash"
    )
    if sha256(root / "analyze.py") != archived_analyzer_hash:
        raise AnalysisError("archived analyzer hash differs")
    identity = campaign_identity(
        root,
        "FINAL_SHA256SUMS.txt",
        final_manifest,
        "evaluation",
        gpu=True,
        metrics_ptx=True,
    )
    source = identity["sourceIdentity"]
    gpu = identity["gpuRuntimeIdentity"]
    if not isinstance(source, Mapping) or not isinstance(gpu, Mapping):
        raise AnalysisError("invalid evaluation identity")

    method_rows = read_csv(
        root / "methods.csv", ("schemaVersion", "methodOrdinal", "methodId", "mode", "weighted")
    )
    methods = tuple(row.get("methodId", "") for row in method_rows)
    if methods != METHODS or [strict_int(row, "methodOrdinal") for row in method_rows] != list(
        range(len(METHODS))
    ):
        raise AnalysisError("evaluation method order differs")
    comparison_rows = read_csv(
        root / "comparisons.csv",
        ("schemaVersion", "edgeOrdinal", "comparisonId", "referenceId", "candidateId"),
    )
    core_comparison_by_method: dict[str, str] = {}
    for ordinal, row in enumerate(comparison_rows):
        candidate = row.get("candidateId", "")
        if (
            strict_int(row, "edgeOrdinal") != ordinal
            or row.get("referenceId") != "uniform"
            or candidate not in METHODS[1:]
            or row.get("comparisonId") != f"uniform__{candidate}"
        ):
            raise AnalysisError("evaluation comparison plan differs")
        core_comparison_by_method[candidate] = row["comparisonId"]
    if set(core_comparison_by_method) != set(METHODS[1:]):
        raise AnalysisError("evaluation comparison plan is incomplete")

    chunk_header = (
        "schemaVersion",
        "chunkIndex",
        "study",
        "rowId",
        "firstLayoutId",
        "lastLayoutId",
        "firstHeroCandidate",
        "lastHeroCandidate",
        "includesCore",
        "directComparisons",
        "buildRows",
        "timingRows",
        "blockRows",
        "metricRows",
        "hardTimeoutSeconds",
    )
    chunk_plan = read_csv(root / "chunks.csv", chunk_header)
    if [strict_int(row, "chunkIndex") for row in chunk_plan] != list(range(len(chunk_plan))):
        raise AnalysisError("evaluation chunk indexes differ")
    if final_manifest.get("chunkCount") != len(chunk_plan):
        raise AnalysisError("evaluation chunk count differs")

    protocol = load_json(root / "protocol.json")
    renderer = protocol.get("renderer")
    metrics_protocol = protocol.get("metrics")
    method_settings = protocol.get("methodSettings")
    if (
        not isinstance(renderer, Mapping)
        or not isinstance(metrics_protocol, Mapping)
        or not isinstance(method_settings, Mapping)
    ):
        raise AnalysisError("evaluation protocol is incomplete")
    identity["calibrationAppliedIdentitySha256"] = strict_hex(
        method_settings.get("calibrationAppliedIdentitySha256"),
        64,
        "evaluation applied-calibration identity",
    )
    maximum_path_depth = renderer.get("maximumPathDepth")
    metrics_context = metrics_protocol.get("freshInstrumentedContextOrdinal")
    if type(maximum_path_depth) is not int or type(metrics_context) is not int:
        raise AnalysisError("evaluation protocol has invalid renderer settings")

    build_rows: list[dict[str, str]] = []
    timing_rows: list[dict[str, str]] = []
    metric_rows: list[dict[str, str]] = []
    structures: dict[tuple[str, int, str], tuple[object, ...]] = {}
    row_info: dict[str, tuple[int, str, str, int]] = {}

    for plan in chunk_plan:
        index = strict_int(plan, "chunkIndex")
        chunk = root / f"chunk-{index:02d}"
        verify_ledger(chunk)
        manifest = load_json(chunk / "manifest.json")
        for field, value in {
            "protocolVersion": protocol_version,
            "measurementState": "GPU_MEASURED",
            "chunkIndex": index,
            **source,
            **gpu,
            "metricsPtxSha256": identity["metricsPtxSha256"],
            "protocolSha256": protocol_hash,
        }.items():
            if manifest.get(field) != value:
                raise AnalysisError(f"evaluation chunk {index} identity differs: {field}")
        builds = read_csv(chunk / "builds.csv", EVALUATION_BUILD_HEADER)
        timings = read_csv(chunk / "timing.csv", EVALUATION_TIMING_HEADER)
        metrics = read_csv(chunk / "metrics.csv", EVALUATION_METRICS_HEADER)
        expected_counts = {
            "buildRows": len(builds),
            "timingRows": len(timings),
            "metricRows": len(metrics),
        }
        for field, value in expected_counts.items():
            if manifest.get(field) != value or (
                field in plan and strict_int(plan, field) != value
            ):
                raise AnalysisError(f"evaluation chunk {index} row count differs: {field}")
        chunk_structures: dict[tuple[str, int, str], tuple[object, ...]] = {}
        for row in builds:
            if row.get("protocolVersion") != protocol_version:
                raise AnalysisError("evaluation build protocol differs")
            _consistent_row_info(row_info, row)
            key = (row.get("rowId", ""), strict_int(row, "layoutId"), row.get("endpointId", ""))
            if not key[2] or key in chunk_structures:
                raise AnalysisError("duplicate evaluation build")
            value = structure_identity(row, "evaluation")
            chunk_structures[key] = value
            previous = structures.setdefault(key, value)
            if previous != value:
                raise AnalysisError("evaluation endpoint was rebuilt differently")
        for row in timings:
            if row.get("protocolVersion") != protocol_version:
                raise AnalysisError("evaluation timing protocol differs")
            _consistent_row_info(row_info, row)
            key = (row.get("rowId", ""), strict_int(row, "layoutId"), row.get("endpointId", ""))
            if key not in chunk_structures or row.get("topologySha256") != chunk_structures[key][-1]:
                raise AnalysisError("evaluation timing topology differs from its build")
        for row in metrics:
            if row.get("protocolVersion") != protocol_version:
                raise AnalysisError("evaluation metrics protocol differs")
            _consistent_row_info(row_info, row)
            key = (row.get("rowId", ""), strict_int(row, "layoutId"), row.get("methodId", ""))
            if key not in chunk_structures or row.get("topologySha256") != chunk_structures[key][-1]:
                raise AnalysisError("evaluation metric topology differs from its build")
        build_rows.extend(builds)
        timing_rows.extend(timings)
        metric_rows.extend(metrics)

    totals = {
        "timingRows": len(timing_rows),
        "metricRows": len(metric_rows),
    }
    for field, value in totals.items():
        if final_manifest.get(field) != value:
            raise AnalysisError(f"evaluation total differs: {field}")
    if set(row_info) != set(PUBLICATION_ROWS):
        raise AnalysisError("evaluation publication rows differ")

    sensitivity = _sensitivity_cells(structures)
    raw_blocks: dict[
        tuple[str, int, int, str, int, int], list[dict[str, str]]
    ] = defaultdict(list)
    comparison_endpoints: dict[tuple[str, int, str], tuple[str, str]] = {}
    for row in timing_rows:
        row_id = row.get("rowId", "")
        layout = strict_int(row, "layoutId")
        comparison_ordinal = strict_int(row, "comparisonOrdinal")
        comparison_id = row.get("comparisonId", "")
        reference = row.get("referenceId", "")
        candidate = row.get("candidateId", "")
        if reference != "uniform" or not candidate or comparison_id != f"uniform__{candidate}":
            raise AnalysisError("evaluation comparison identity differs")
        endpoint_pair = (reference, candidate)
        endpoint_key = (row_id, comparison_ordinal, comparison_id)
        if comparison_endpoints.setdefault(endpoint_key, endpoint_pair) != endpoint_pair:
            raise AnalysisError("evaluation comparison endpoints differ")
        context = strict_int(row, "context")
        block = strict_int(row, "blockIndex")
        if context not in (1, 2) or block not in (0, 1):
            raise AnalysisError("evaluation timing context or block differs")
        variant = row.get("variant")
        endpoint = reference if variant == "A" else candidate if variant == "B" else ""
        structure_key = (row_id, layout, endpoint)
        if (
            not endpoint
            or row.get("endpointId") != endpoint
            or structure_key not in structures
            or row.get("topologySha256") != structures[structure_key][-1]
            or strict_int(row, "maximumPathDepth") != maximum_path_depth
        ):
            raise AnalysisError("evaluation timing endpoint differs")
        raw_blocks[(row_id, layout, comparison_ordinal, comparison_id, context, block)].append(row)

    cell_blocks: dict[tuple[str, int, int, str], list[Decimal]] = defaultdict(list)
    for key, rows in raw_blocks.items():
        cell_blocks[key[:4]].append(block_reduction_percent(rows))
    time_by_comparison: dict[tuple[str, int, str], Decimal] = {}
    for key, values in cell_blocks.items():
        if len(values) != 4:
            raise AnalysisError("evaluation comparison does not contain four blocks")
        time_key = (key[0], key[1], key[3])
        if time_key in time_by_comparison:
            raise AnalysisError("duplicate evaluation comparison")
        time_by_comparison[time_key] = median_decimal(values)
    if final_manifest.get("directComparisons") != len(time_by_comparison):
        raise AnalysisError("evaluation comparison count differs")

    metrics: dict[tuple[str, int, str], dict[str, Decimal]] = {}
    metric_topology: dict[tuple[str, int, str], str] = {}
    seeds_by_workload: dict[tuple[str, int], set[int]] = defaultdict(set)
    for row in metric_rows:
        row_id = row.get("rowId", "")
        layout = strict_int(row, "layoutId")
        endpoint = row.get("methodId", "")
        key = (row_id, layout, endpoint)
        if (
            strict_int(row, "metricsContext") != metrics_context
            or key in metrics
            or key not in structures
            or row.get("topologySha256") != structures[key][-1]
        ):
            raise AnalysisError("evaluation metric coordinate differs")
        metrics[key] = metric_record(row)
        metric_topology[key] = row["topologySha256"]
        seeds_by_workload[(row_id, layout)].add(strict_int(row, "frameSeed"))
    if any(len(seeds) != 1 for seeds in seeds_by_workload.values()):
        raise AnalysisError("methods of one workload use different metric seeds")

    layouts_by_row: dict[str, tuple[int, ...]] = {}
    for row_id in PUBLICATION_ROWS:
        layouts = tuple(sorted(layout for candidate_row, layout, endpoint in metrics if candidate_row == row_id and endpoint == "uniform"))
        if not layouts:
            raise AnalysisError(f"missing evaluation layouts: {row_id}")
        layouts_by_row[row_id] = layouts
        for layout in layouts:
            missing = [method for method in METHODS if (row_id, layout, method) not in metrics]
            if missing:
                raise AnalysisError(f"missing core metrics: {row_id}/{layout}/{missing[0]}")

    detail: list[dict[str, object]] = []
    time_cells: dict[tuple[str, str], dict[str, Decimal]] = {}
    for row_ordinal, row_id in enumerate(PUBLICATION_ROWS):
        layouts = layouts_by_row[row_id]
        for method_ordinal, method in enumerate(METHODS):
            times: list[Decimal] = []
            work_reductions: list[Decimal] = []
            aabb_reductions: list[Decimal] = []
            primitive_reductions: list[Decimal] = []
            absolutes: dict[str, list[Decimal]] = defaultdict(list)
            topologies: set[str] = set()
            for layout in layouts:
                reference = metrics[(row_id, layout, "uniform")]
                candidate = metrics[(row_id, layout, method)]
                if method == "uniform":
                    times.append(Decimal(0))
                else:
                    comparison_id = core_comparison_by_method[method]
                    try:
                        times.append(time_by_comparison[(row_id, layout, comparison_id)])
                    except KeyError as error:
                        raise AnalysisError(
                            f"missing core timing: {row_id}/{layout}/{method}"
                        ) from error
                work_reductions.append(
                    direct_reduction(reference["modeledWork"], candidate["modeledWork"])
                )
                aabb_reductions.append(
                    direct_reduction(reference["aabbTests"], candidate["aabbTests"])
                )
                primitive_reductions.append(
                    direct_reduction(reference["primitiveTests"], candidate["primitiveTests"])
                )
                for field, value in candidate.items():
                    absolutes[field].append(value)
                topologies.add(metric_topology[(row_id, layout, method)])
            time_value = median_decimal(times)
            work_value = median_decimal(work_reductions)
            time_cells[(row_id, method)] = {"time": time_value, "work": work_value}
            detail.append(
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "evidenceState": "GPU_MEASURED",
                    "rowOrdinal": row_ordinal,
                    "rowId": row_id,
                    "methodOrdinal": method_ordinal,
                    "methodId": method,
                    "layoutCount": len(layouts),
                    "timeReductionPercent": format_decimal(time_value, 9),
                    "workReductionPercent": format_decimal(work_value, 9),
                    "aabbReductionPercent": format_decimal(
                        median_decimal(aabb_reductions), 9
                    ),
                    "primitiveReductionPercent": format_decimal(
                        median_decimal(primitive_reductions), 9
                    ),
                    **{
                        "median" + field[0].upper() + field[1:]: format_decimal(
                            median_decimal(values), 9 if field == "modeledWork" else None
                        )
                        for field, values in absolutes.items()
                    },
                    "topologySha256": next(iter(topologies)) if len(layouts) == 1 else "",
                    "topologyDistinctCount": len(topologies),
                }
            )

    time_matrix: list[dict[str, object]] = []
    work_matrix: list[dict[str, object]] = []
    for row_ordinal, row_id in enumerate(PUBLICATION_ROWS):
        common = {
            "schemaVersion": SCHEMA_VERSION,
            "evidenceState": "GPU_MEASURED",
            "rowOrdinal": row_ordinal,
            "rowId": row_id,
        }
        time_matrix.append(
            {
                **common,
                **{
                    method: format_decimal(time_cells[(row_id, method)]["time"], 9)
                    for method in METHODS
                },
            }
        )
        work_matrix.append(
            {
                **common,
                **{
                    method: format_decimal(time_cells[(row_id, method)]["work"], 9)
                    for method in METHODS
                },
            }
        )

    hero: list[dict[str, object]] = []
    for row_ordinal, row_id in enumerate(PUBLICATION_ROWS[:5]):
        reference = metrics[(row_id, -1, "uniform")]
        scale = row_info[row_id][3]
        for cell in sensitivity:
            endpoint = str(cell["endpoint"])
            candidate = metrics[(row_id, -1, endpoint)]
            anchor = bool(cell["anchor"])
            comparison_id = str(cell["comparisonId"])
            hero.append(
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "evidenceState": "GPU_MEASURED",
                    "rowOrdinal": row_ordinal,
                    "rowId": row_id,
                    "scale": scale,
                    "cellOrdinal": cell["cellOrdinal"],
                    "leafSize": cell["leafSize"],
                    "lambda": format_decimal(Decimal(cell["lambda"])),
                    "comparisonId": comparison_id,
                    "anchorSynthetic": str(anchor).lower(),
                    "blockCount": 0 if anchor else 4,
                    "timeReductionPercent": format_decimal(
                        Decimal(0)
                        if anchor
                        else time_by_comparison[(row_id, -1, comparison_id)],
                        9,
                    ),
                    "workReductionPercent": format_decimal(
                        direct_reduction(reference["modeledWork"], candidate["modeledWork"]), 9
                    ),
                    "aabbReductionPercent": format_decimal(
                        direct_reduction(reference["aabbTests"], candidate["aabbTests"]), 9
                    ),
                    "primitiveReductionPercent": format_decimal(
                        direct_reduction(reference["primitiveTests"], candidate["primitiveTests"]),
                        9,
                    ),
                    "referenceModeledWork": format_decimal(reference["modeledWork"], 9),
                    "candidateModeledWork": format_decimal(candidate["modeledWork"], 9),
                    "topologySha256": metric_topology[(row_id, -1, endpoint)],
                }
            )

    signs: list[dict[str, object]] = []
    weighted_comparison = core_comparison_by_method["weighted"]
    for row_id in PUBLICATION_ROWS[6:]:
        family = row_info[row_id][2]
        object_count = row_info[row_id][3]
        time_counts: Counter[str] = Counter()
        work_counts: Counter[str] = Counter()
        layouts = layouts_by_row[row_id]
        for layout in layouts:
            time_counts[_sign(time_by_comparison[(row_id, layout, weighted_comparison)])] += 1
            reference = metrics[(row_id, layout, "uniform")]["modeledWork"]
            candidate = metrics[(row_id, layout, "weighted")]["modeledWork"]
            work_counts[_sign(direct_reduction(reference, candidate))] += 1
        signs.append(
            {
                "schemaVersion": SCHEMA_VERSION,
                "evidenceState": "GPU_MEASURED",
                "family": family,
                "objectCount": object_count,
                "comparisonId": weighted_comparison,
                "layoutCount": len(layouts),
                "timeBetter": time_counts["better"],
                "timeWorse": time_counts["worse"],
                "timeTie": time_counts["tie"],
                "workBetter": work_counts["better"],
                "workWorse": work_counts["worse"],
                "workTie": work_counts["tie"],
            }
        )

    return (
        {
            "hero-sensitivity.csv": hero,
            "core8-time-matrix.csv": time_matrix,
            "core8-work-matrix.csv": work_matrix,
            "core8-detail.csv": detail,
            "layout-sign-counts.csv": signs,
        },
        structures,
        identity,
    )


def analyze_leaf(root: Path) -> tuple[list[dict[str, object]], dict[str, object]]:
    verify_ledger(root)
    verify_ledger(root, "FINAL_SHA256SUMS.txt")
    final_manifest = load_json(root / "final-manifest.json")
    if final_manifest.get("measurementState") != "GPU_MEASURED":
        raise AnalysisError("leaf-size evidence is not complete")
    protocol_version = final_manifest.get("protocolVersion")
    protocol_hash = strict_hex(final_manifest.get("protocolSha256"), 64, "leaf protocol hash")
    if sha256(root / "protocol.json") != protocol_hash:
        raise AnalysisError("leaf-size protocol hash differs")
    identity = campaign_identity(
        root, "FINAL_SHA256SUMS.txt", final_manifest, "leaf", gpu=True
    )
    source = identity["sourceIdentity"]
    gpu = identity["gpuRuntimeIdentity"]
    if not isinstance(source, Mapping) or not isinstance(gpu, Mapping):
        raise AnalysisError("invalid leaf-size identity")

    scene_rows = read_csv(
        root / "scenes.csv",
        (
            "schemaVersion",
            "sceneOrdinal",
            "sceneId",
            "referenceLeafSize",
            "candidateCount",
            "contexts",
            "blocksPerContext",
        ),
    )
    if [strict_int(row, "sceneOrdinal") for row in scene_rows] != list(range(len(scene_rows))):
        raise AnalysisError("leaf-size scene order differs")
    if final_manifest.get("sceneCount") != len(scene_rows):
        raise AnalysisError("leaf-size scene count differs")

    structures: dict[tuple[int, int], dict[str, object]] = {}
    reductions: dict[tuple[int, int, int], list[Decimal]] = defaultdict(list)
    timing_total = 0
    block_total = 0
    for scene in scene_rows:
        scene_ordinal = strict_int(scene, "sceneOrdinal")
        scene_id = scene.get("sceneId", "")
        reference_leaf = strict_int(scene, "referenceLeafSize")
        context_count = strict_int(scene, "contexts")
        blocks_per_context = strict_int(scene, "blocksPerContext")
        candidate_count = strict_int(scene, "candidateCount")
        chunk = root / f"chunk-{scene_ordinal:02d}"
        verify_ledger(chunk)
        manifest = load_json(chunk / "manifest.json")
        for field, value in {
            "protocolVersion": protocol_version,
            "measurementState": "GPU_MEASURED",
            "sceneOrdinal": scene_ordinal,
            "sceneId": scene_id,
            **source,
            **gpu,
            "protocolSha256": protocol_hash,
        }.items():
            if manifest.get(field) != value:
                raise AnalysisError(f"leaf-size chunk identity differs: {scene_id}/{field}")
        builds = read_csv(chunk / "builds.csv", LEAF_BUILD_HEADER)
        timings = read_csv(chunk / "timing.csv", LEAF_TIMING_HEADER)
        if manifest.get("buildRows") != len(builds) or manifest.get("timingRows") != len(timings):
            raise AnalysisError(f"leaf-size chunk row count differs: {scene_id}")
        timing_total += len(timings)
        block_total += strict_int(manifest, "blockRows")

        geometry: set[str] = set()
        leaves: set[int] = set()
        for row in builds:
            leaf = strict_int(row, "leafSize")
            if (
                row.get("protocolVersion") != protocol_version
                or strict_int(row, "sceneOrdinal") != scene_ordinal
                or row.get("sceneId") != scene_id
                or leaf in leaves
            ):
                raise AnalysisError(f"invalid leaf-size build: {scene_id}/{leaf}")
            topology = strict_hex(row.get("topologySha256"), 64, "leaf topology hash")
            packed = strict_hex(row.get("packedGeometrySha256"), 64, "leaf geometry hash")
            leaves.add(leaf)
            geometry.add(packed)
            structures[(scene_ordinal, leaf)] = {
                "topology": topology,
                "nodes": strict_int(row, "nodeCount"),
                "depth": strict_int(row, "maxDepth"),
            }
        if reference_leaf not in leaves or len(leaves) != candidate_count + 1 or len(geometry) != 1:
            raise AnalysisError(f"leaf-size build coverage differs: {scene_id}")

        timing_groups: dict[tuple[int, int, int], list[dict[str, str]]] = defaultdict(list)
        comparison_candidates: dict[int, int] = {}
        for row in timings:
            comparison = strict_int(row, "comparisonOrdinal")
            candidate_leaf = strict_int(row, "candidateLeafSize")
            previous = comparison_candidates.setdefault(comparison, candidate_leaf)
            if previous != candidate_leaf:
                raise AnalysisError(f"leaf-size comparison differs: {scene_id}/{comparison}")
            context = strict_int(row, "context")
            block = strict_int(row, "blockIndex")
            variant = row.get("variant")
            endpoint_leaf = reference_leaf if variant == "A" else candidate_leaf if variant == "B" else -1
            if (
                row.get("protocolVersion") != protocol_version
                or strict_int(row, "sceneOrdinal") != scene_ordinal
                or row.get("sceneId") != scene_id
                or strict_int(row, "referenceLeafSize") != reference_leaf
                or candidate_leaf not in leaves - {reference_leaf}
                or context not in range(1, context_count + 1)
                or block not in range(blocks_per_context)
                or endpoint_leaf < 0
                or row.get("endpointId") != f"leaf_{endpoint_leaf}"
                or row.get("topologySha256")
                != structures[(scene_ordinal, endpoint_leaf)]["topology"]
            ):
                raise AnalysisError(f"invalid leaf-size timing row: {scene_id}")
            timing_groups[(candidate_leaf, context, block)].append(row)
        if len(comparison_candidates) != candidate_count:
            raise AnalysisError(f"leaf-size comparison coverage differs: {scene_id}")
        expected_keys = {
            (leaf, context, block)
            for leaf in leaves - {reference_leaf}
            for context in range(1, context_count + 1)
            for block in range(blocks_per_context)
        }
        if set(timing_groups) != expected_keys:
            raise AnalysisError(f"leaf-size timing coverage differs: {scene_id}")
        for key, rows in timing_groups.items():
            reductions[(scene_ordinal, key[0], key[1])].append(
                block_reduction_percent(rows)
            )

    if final_manifest.get("timingRows") != timing_total or final_manifest.get("blockRows") != block_total:
        raise AnalysisError("leaf-size totals differ")

    output: list[dict[str, object]] = []
    for scene in scene_rows:
        scene_ordinal = strict_int(scene, "sceneOrdinal")
        scene_id = scene.get("sceneId", "")
        reference_leaf = strict_int(scene, "referenceLeafSize")
        context_count = strict_int(scene, "contexts")
        blocks_per_context = strict_int(scene, "blocksPerContext")
        leaves = sorted(leaf for ordinal, leaf in structures if ordinal == scene_ordinal)
        aggregate_ratios: dict[int, Decimal] = {reference_leaf: Decimal(1)}
        context_values: dict[int, list[Decimal]] = {
            reference_leaf: [Decimal(0)] * context_count
        }
        final_values: dict[int, Decimal] = {reference_leaf: Decimal(0)}
        for leaf in leaves:
            if leaf == reference_leaf:
                continue
            contexts: list[Decimal] = []
            all_blocks: list[Decimal] = []
            for context in range(1, context_count + 1):
                values = reductions.get((scene_ordinal, leaf, context), [])
                if len(values) != blocks_per_context:
                    raise AnalysisError(f"leaf-size block coverage differs: {scene_id}/{leaf}")
                contexts.append(median_decimal(values))
                all_blocks.extend(values)
            final_value = median_decimal(all_blocks)
            context_values[leaf] = contexts
            final_values[leaf] = final_value
            aggregate_ratios[leaf] = Decimal(1) - final_value / Decimal(100)

        topology_groups: dict[str, list[int]] = defaultdict(list)
        for leaf in leaves:
            topology_groups[str(structures[(scene_ordinal, leaf)]["topology"])].append(leaf)
        topology_ratios = {
            topology: median_decimal([aggregate_ratios[leaf] for leaf in group_leaves])
            for topology, group_leaves in topology_groups.items()
        }
        best_ratio = min(topology_ratios.values())
        if best_ratio <= 0:
            raise AnalysisError(f"leaf-size ratio is nonpositive: {scene_id}")
        for leaf in leaves:
            structure = structures[(scene_ordinal, leaf)]
            topology = str(structure["topology"])
            contexts = context_values[leaf]
            if len(contexts) != 5:
                raise AnalysisError("leaf-size output contract requires five contexts")
            output.append(
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "evidenceState": "GPU_MEASURED",
                    "sceneOrdinal": scene_ordinal,
                    "sceneId": scene_id,
                    "leafSize": leaf,
                    **{
                        f"context{index + 1}ReductionPercent": format_decimal(value, 9)
                        for index, value in enumerate(contexts)
                    },
                    "medianBlockReductionPercent": format_decimal(final_values[leaf], 9),
                    "relativeSlowdownFromBestPercent": format_decimal(
                        Decimal(100) * (topology_ratios[topology] / best_ratio - Decimal(1)), 9
                    ),
                    "topologySha256": topology,
                    "nodeCount": structure["nodes"],
                    "maxDepth": structure["depth"],
                    "identicalTopologyLeafSizes": "|".join(
                        str(value) for value in topology_groups[topology]
                    ),
                }
            )
    return output, identity


def analyze_depth(root: Path) -> tuple[list[dict[str, object]], dict[str, object]]:
    verify_ledger(root)
    verify_ledger(root, "FINAL_SHA256SUMS.txt")
    final_manifest = load_json(root / "final-manifest.json")
    if final_manifest.get("measurementState") != "GPU_MEASURED":
        raise AnalysisError("depth evidence is not complete")
    protocol_version = final_manifest.get("protocolVersion")
    protocol_hash = strict_hex(final_manifest.get("protocolSha256"), 64, "depth protocol hash")
    if sha256(root / "protocol.json") != protocol_hash:
        raise AnalysisError("depth protocol hash differs")
    identity = campaign_identity(
        root, "FINAL_SHA256SUMS.txt", final_manifest, "depth", gpu=True
    )
    source = identity["sourceIdentity"]
    gpu = identity["gpuRuntimeIdentity"]
    if not isinstance(source, Mapping) or not isinstance(gpu, Mapping):
        raise AnalysisError("invalid depth identity")

    plan_rows = read_csv(
        root / "depths.csv",
        (
            "schemaVersion",
            "depthOrdinal",
            "maximumPathDepth",
            "isReference",
            "comparisonOrdinal",
        ),
    )
    if [strict_int(row, "depthOrdinal") for row in plan_rows] != list(range(len(plan_rows))):
        raise AnalysisError("depth plan order differs")
    reference_rows = [row for row in plan_rows if row.get("isReference") == "true"]
    if len(reference_rows) != 1:
        raise AnalysisError("depth plan needs one reference")
    reference_depth = strict_int(reference_rows[0], "maximumPathDepth")
    if strict_int(reference_rows[0], "comparisonOrdinal") != -1:
        raise AnalysisError("depth reference comparison ordinal differs")
    candidate_by_comparison: dict[int, int] = {}
    for row in plan_rows:
        if row.get("isReference") == "true":
            continue
        comparison = strict_int(row, "comparisonOrdinal")
        depth = strict_int(row, "maximumPathDepth")
        if comparison in candidate_by_comparison:
            raise AnalysisError("duplicate depth comparison")
        candidate_by_comparison[comparison] = depth
    if sorted(candidate_by_comparison) != list(range(len(candidate_by_comparison))):
        raise AnalysisError("depth comparison order differs")

    chunk = root / "chunk-00"
    verify_ledger(chunk)
    manifest = load_json(chunk / "manifest.json")
    for field, value in {
        "protocolVersion": protocol_version,
        "measurementState": "GPU_MEASURED",
        **source,
        **gpu,
        "protocolSha256": protocol_hash,
    }.items():
        if manifest.get(field) != value:
            raise AnalysisError(f"depth chunk identity differs: {field}")
    builds = read_csv(chunk / "build.csv", DEPTH_BUILD_HEADER)
    timings = read_csv(chunk / "timing.csv", DEPTH_TIMING_HEADER)
    if len(builds) != 1 or manifest.get("timingRows") != len(timings):
        raise AnalysisError("depth chunk row count differs")
    if final_manifest.get("timingRows") != len(timings):
        raise AnalysisError("depth total timing rows differ")
    build = builds[0]
    topology = strict_hex(build.get("topologySha256"), 64, "depth topology hash")
    if build.get("protocolVersion") != protocol_version:
        raise AnalysisError("depth build protocol differs")

    raw_blocks: dict[tuple[int, int, int], list[dict[str, str]]] = defaultdict(list)
    comparison_ids: dict[int, str] = {}
    for row in timings:
        comparison = strict_int(row, "comparisonOrdinal")
        if comparison not in candidate_by_comparison:
            raise AnalysisError("unknown depth comparison")
        candidate_depth = candidate_by_comparison[comparison]
        comparison_id = row.get("comparisonId", "")
        if comparison_ids.setdefault(comparison, comparison_id) != comparison_id:
            raise AnalysisError("depth comparison identifier differs")
        context = strict_int(row, "context")
        block = strict_int(row, "blockIndex")
        variant = row.get("variant")
        endpoint_depth = reference_depth if variant == "A" else candidate_depth if variant == "B" else -1
        if (
            row.get("protocolVersion") != protocol_version
            or row.get("sceneId") != final_manifest.get("sceneId")
            or strict_int(row, "referenceDepth") != reference_depth
            or strict_int(row, "candidateDepth") != candidate_depth
            or endpoint_depth < 0
            or row.get("endpointId") != f"depth_{endpoint_depth}"
            or strict_int(row, "maximumPathDepth") != endpoint_depth
            or row.get("topologySha256") != topology
        ):
            raise AnalysisError("invalid depth timing row")
        raw_blocks[(comparison, context, block)].append(row)

    block_reductions: dict[tuple[int, int, int], Decimal] = {}
    for key, rows in raw_blocks.items():
        ordered = sorted(rows, key=lambda row: strict_int(row, "position"))
        if len(ordered) == 4:
            first_conditioning = strict_int(ordered[0], "conditioningSeed")
            second_conditioning = strict_int(ordered[2], "conditioningSeed")
            if (
                first_conditioning != strict_int(ordered[1], "conditioningSeed")
                or second_conditioning != strict_int(ordered[3], "conditioningSeed")
                or first_conditioning == second_conditioning
            ):
                raise AnalysisError("depth conditioning seeds are not paired")
        block_reductions[key] = block_reduction_percent(rows)

    output: list[dict[str, object]] = []
    for row in plan_rows:
        ordinal = strict_int(row, "depthOrdinal")
        depth = strict_int(row, "maximumPathDepth")
        reference = row.get("isReference") == "true"
        if reference:
            comparison_id = "anchor"
            values: list[Decimal] = []
            ratio = Decimal(100)
        else:
            comparison = strict_int(row, "comparisonOrdinal")
            comparison_id = comparison_ids[comparison]
            values = [
                value
                for (candidate_comparison, _context, _block), value in block_reductions.items()
                if candidate_comparison == comparison
            ]
            if len(values) != 4:
                raise AnalysisError(f"depth comparison does not contain four blocks: {depth}")
            ratio = Decimal(100) - median_decimal(values)
            if ratio <= 0:
                raise AnalysisError(f"depth ratio is nonpositive: {depth}")
        output.append(
            {
                "schemaVersion": SCHEMA_VERSION,
                "evidenceState": "GPU_MEASURED",
                "depthOrdinal": ordinal,
                "maximumPathDepth": depth,
                "isReference": str(reference).lower(),
                "comparisonId": comparison_id,
                "blockCount": len(values),
                "medianRelativeKernelPercent": format_decimal(ratio, 9),
                "topologySha256": topology,
            }
        )
    return output, identity


def verify_shared_identity(
    campaigns: Mapping[str, Mapping[str, object]],
    correctness: Mapping[str, object],
    calibration: Mapping[str, object],
) -> tuple[dict[str, str], dict[str, str], dict[str, dict[str, str]]]:
    expected_campaigns = ("construction", "evaluation", "leaf", "depth")
    if set(campaigns) != set(expected_campaigns):
        raise AnalysisError("all four evidence roots are required")
    shared_source: dict[str, str] | None = None
    shared_gpu: dict[str, str] | None = None
    inputs: dict[str, dict[str, str]] = {}
    for name in expected_campaigns:
        record = campaigns[name]
        source = record.get("sourceIdentity")
        if not isinstance(source, Mapping):
            raise AnalysisError(f"missing source identity: {name}")
        canonical_source = {field: str(source[field]) for field in SOURCE_FIELDS}
        if shared_source is None:
            shared_source = canonical_source
        elif shared_source != canonical_source:
            raise AnalysisError("campaigns use different source identities")
        bound_input = {
            "ledgerFile": str(record["ledgerFile"]),
            "ledgerSha256": strict_hex(record.get("ledgerSha256"), 64, f"{name} ledger hash"),
            "protocolVersion": str(record["protocolVersion"]),
            "protocolSha256": strict_hex(
                record.get("protocolSha256"), 64, f"{name} protocol hash"
            ),
        }
        if name == "evaluation":
            bound_input["metricsPtxSha256"] = strict_hex(
                record.get("metricsPtxSha256"), 64, "evaluation metrics PTX hash"
            )
            bound_input["calibrationAppliedIdentitySha256"] = strict_hex(
                record.get("calibrationAppliedIdentitySha256"),
                64,
                "evaluation applied-calibration identity",
            )
        inputs[name] = bound_input
        if name == "construction":
            continue
        gpu = record.get("gpuRuntimeIdentity")
        if not isinstance(gpu, Mapping):
            raise AnalysisError(f"missing GPU identity: {name}")
        canonical_gpu = {field: str(gpu[field]) for field in GPU_FIELDS}
        if shared_gpu is None:
            shared_gpu = canonical_gpu
        elif shared_gpu != canonical_gpu:
            raise AnalysisError("GPU campaigns use different runtime identities")
    if shared_source is None or shared_gpu is None:
        raise AnalysisError("incomplete shared evidence identity")
    correctness_source = correctness.get("sourceIdentity")
    correctness_gpu = correctness.get("gpuRuntimeIdentity")
    if not isinstance(correctness_source, Mapping) or not isinstance(correctness_gpu, Mapping):
        raise AnalysisError("correctness evidence identity is incomplete")
    if {field: str(correctness_source[field]) for field in SOURCE_FIELDS} != shared_source:
        raise AnalysisError("correctness and study campaigns use different source identities")
    if {field: str(correctness_gpu[field]) for field in GPU_FIELDS} != shared_gpu:
        raise AnalysisError("correctness and study campaigns use different GPU/PTX identities")
    if (
        campaigns["evaluation"].get("calibrationAppliedIdentitySha256")
        != correctness.get("calibrationAppliedIdentitySha256")
    ):
        raise AnalysisError("evaluation protocol and correctness use different calibrations")
    inputs["correctness"] = {
        "ledgerFile": str(correctness["ledgerFile"]),
        "ledgerSha256": strict_hex(
            correctness.get("ledgerSha256"), 64, "correctness ledger hash"
        ),
        "measurementState": str(correctness["measurementState"]),
        "linearPtxSha256": strict_hex(
            correctness.get("linearPtxSha256"), 64, "correctness linear PTX hash"
        ),
        "calibrationAppliedIdentitySha256": strict_hex(
            correctness.get("calibrationAppliedIdentitySha256"),
            64,
            "correctness calibration identity",
        ),
    }
    inputs["calibration"] = {
        "fileName": str(calibration["fileName"]),
        "fileSha256": strict_hex(
            calibration.get("fileSha256"), 64, "applied-calibration file hash"
        ),
        "calibrationIdentitySha256": strict_hex(
            calibration.get("calibrationIdentitySha256"),
            64,
            "applied calibration identity",
        ),
        "evidenceManifestSha256": strict_hex(
            calibration.get("evidenceManifestSha256"),
            64,
            "calibration evidence-manifest hash",
        ),
    }
    return shared_source, shared_gpu, inputs


def verify_core_topologies(
    construction: Mapping[tuple[str, int, str], tuple[object, ...]],
    evaluation: Mapping[tuple[str, int, str], tuple[object, ...]],
) -> None:
    evaluation_core = {
        key: value for key, value in evaluation.items() if key[2] in METHODS
    }
    if set(construction) != set(evaluation_core):
        raise AnalysisError("construction and evaluation core inventories differ")
    for key, value in construction.items():
        if evaluation_core[key] != value:
            raise AnalysisError(
                f"construction and evaluation topology differ: {key[0]}/{key[1]}/{key[2]}"
            )


def write_csv(
    path: Path, fieldnames: Sequence[str], rows: Iterable[Mapping[str, object]]
) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def publish(
    output: Path,
    files: Mapping[str, tuple[Sequence[str], list[dict[str, object]]]],
    campaigns: Mapping[str, Mapping[str, object]],
    correctness: Mapping[str, object],
    calibration: Mapping[str, object],
) -> None:
    expected_names = {
        "hero-sensitivity.csv",
        "core8-time-matrix.csv",
        "core8-work-matrix.csv",
        "core8-detail.csv",
        "layout-sign-counts.csv",
        "bvh-node-count-matrix.csv",
        "bvh-bytes-matrix.csv",
        "bvh-depth-matrix.csv",
        "bvh-build-ms-matrix.csv",
        "leaf-size-time.csv",
        "depth-time.csv",
    }
    if set(files) != expected_names:
        raise AnalysisError("analysis output inventory differs")
    shared_source, shared_gpu, inputs = verify_shared_identity(
        campaigns, correctness, calibration
    )
    output = output.resolve()
    if output.exists() or not output.parent.is_dir():
        raise AnalysisError("output directory must be new and its parent must exist")
    partial = Path(tempfile.mkdtemp(prefix=f"{output.name}.partial-", dir=output.parent))
    try:
        for name, (header, rows) in files.items():
            write_csv(partial / name, header, rows)
        contracts: dict[str, object] = {}
        for name, (header, rows) in files.items():
            contracts[name] = {
                "fileName": name,
                "sha256": sha256(partial / name),
                "columns": list(header),
                "dataRows": len(rows),
                "evidenceStates": sorted({str(row["evidenceState"]) for row in rows}),
            }
        manifest = {
            "schemaVersion": "ANALYSIS_V1",
            "analyzerVersion": ANALYZER_VERSION,
            "analyzerSha256": sha256(Path(__file__).resolve()),
            "evidenceState": "COMPLETE",
            "sharedSourceIdentity": shared_source,
            "sharedGpuRuntimeIdentity": shared_gpu,
            "inputEvidence": inputs,
            "outputs": contracts,
        }
        (partial / "analysis-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        names = sorted(path.name for path in partial.iterdir() if path.is_file())
        (partial / "SHA256SUMS.txt").write_text(
            "".join(f"{sha256(partial / name)}  {name}\n" for name in names),
            encoding="utf-8",
        )
        partial.replace(output)
    except Exception:
        shutil.rmtree(partial, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--construction-root", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--leaf-root", type=Path, required=True)
    parser.add_argument("--depth-root", type=Path, required=True)
    parser.add_argument("--correctness-root", type=Path, required=True)
    parser.add_argument("--calibration-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    arguments = parse_args()
    correctness_identity = analyze_correctness(arguments.correctness_root)
    calibration_identity = analyze_calibration_file(
        arguments.calibration_file, correctness_identity
    )
    construction_cells, construction_structures, construction_identity = analyze_construction(
        arguments.construction_root
    )
    evaluation_files, evaluation_structures, evaluation_identity = analyze_evaluation(
        arguments.evaluation_root
    )
    leaf_rows, leaf_identity = analyze_leaf(arguments.leaf_root)
    depth_rows, depth_identity = analyze_depth(arguments.depth_root)
    verify_core_topologies(construction_structures, evaluation_structures)

    files: dict[str, tuple[Sequence[str], list[dict[str, object]]]] = {
        "bvh-node-count-matrix.csv": (
            MATRIX_HEADER,
            matrix_rows(construction_cells, "nodes", "CPU_MEASURED"),
        ),
        "bvh-bytes-matrix.csv": (
            MATRIX_HEADER,
            matrix_rows(construction_cells, "bytes", "CPU_MEASURED"),
        ),
        "bvh-depth-matrix.csv": (
            MATRIX_HEADER,
            matrix_rows(construction_cells, "depth", "CPU_MEASURED"),
        ),
        "bvh-build-ms-matrix.csv": (
            MATRIX_HEADER,
            matrix_rows(construction_cells, "build_ms", "CPU_MEASURED", 6),
        ),
        "hero-sensitivity.csv": (
            HERO_SENSITIVITY_HEADER,
            evaluation_files["hero-sensitivity.csv"],
        ),
        "core8-time-matrix.csv": (
            MATRIX_HEADER,
            evaluation_files["core8-time-matrix.csv"],
        ),
        "core8-work-matrix.csv": (
            MATRIX_HEADER,
            evaluation_files["core8-work-matrix.csv"],
        ),
        "core8-detail.csv": (
            CORE_DETAIL_HEADER,
            evaluation_files["core8-detail.csv"],
        ),
        "layout-sign-counts.csv": (
            SIGN_HEADER,
            evaluation_files["layout-sign-counts.csv"],
        ),
        "leaf-size-time.csv": (LEAF_HEADER, leaf_rows),
        "depth-time.csv": (DEPTH_HEADER, depth_rows),
    }
    publish(
        arguments.output_dir,
        files,
        {
            "construction": construction_identity,
            "evaluation": evaluation_identity,
            "leaf": leaf_identity,
            "depth": depth_identity,
        },
        correctness_identity,
        calibration_identity,
    )
    print(arguments.output_dir.resolve())


if __name__ == "__main__":
    main()
