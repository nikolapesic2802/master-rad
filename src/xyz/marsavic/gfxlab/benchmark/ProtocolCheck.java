package xyz.marsavic.gfxlab.benchmark;

import xyz.marsavic.gfxlab.gpu.BvhBuildConfig;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** CPU-only contract check for the complete benchmark plan. */
public final class ProtocolCheck {
	private ProtocolCheck() { }

	public static void main(String[] arguments) throws Exception {
		if (arguments == null || arguments.length != 0) {
			throw new IllegalArgumentException("benchmark protocol check accepts no arguments");
		}
		verify(Path.of(System.getProperty("gfxlab.projectRoot", ".")));
		System.out.println("benchmark protocol/method/workload/construction CPU checks PASS");
	}

	public static void verify(Path projectRoot) throws Exception {
		BenchmarkProtocol.verify(projectRoot);
		verifyPublicationInventory();
		verifySchedules();
		ConstructionStudy.verifyOrderDesign();
		verifyRepresentativeCpuBuilds();
	}

	private static void verifyPublicationInventory() {
		List<BenchmarkProtocol.PublicationRow> rows = BenchmarkProtocol.publicationRows();
		if (rows.size() != BenchmarkProtocol.PUBLICATION_ROW_COUNT
				|| new HashSet<>(rows.stream().map(
				BenchmarkProtocol.PublicationRow::id).toList()).size() != rows.size()) {
			throw new IllegalStateException("benchmark publication row order differs");
		}
		List<String> edgeCandidates = MethodCatalog.EDGES.stream()
				.map(MethodCatalog.Edge::candidateFamily).toList();
		if (new HashSet<>(MethodCatalog.FAMILIES).size() != MethodCatalog.FAMILIES.size()
				|| MethodCatalog.EDGES.stream().anyMatch(
				edge -> !edge.referenceFamily().equals(MethodCatalog.FAMILIES.get(0)))
				|| !edgeCandidates.equals(MethodCatalog.FAMILIES.subList(
				1, MethodCatalog.FAMILIES.size()))) {
			throw new IllegalStateException("benchmark core method/edge order differs");
		}
	}

	private static void verifySchedules() {
		Set<Long> allSeeds = new HashSet<>();
		List<TimingSchedule.SymmetricBlock> first = null;
		for (int context = 0; context < 2; context++) {
			List<TimingSchedule.SymmetricBlock> blocks = TimingSchedule.blocks(
					2, BenchmarkProtocol.EVALUATION_SCHEDULE_SEED, 0, 0, context, 2);
			if (blocks.size() != 2 || blocks.get(1).order() != blocks.get(0).order().opposite()) {
				throw new IllegalStateException("benchmark within-context order balance differs");
			}
			if (first == null) first = blocks;
			else if (blocks.get(0).order() != first.get(0).order().opposite()) {
				throw new IllegalStateException("benchmark context starting order differs");
			}
			for (TimingSchedule.SymmetricBlock block : blocks) {
				for (long seed : List.of(block.firstMeasurementSeed(),
						block.secondMeasurementSeed(), block.firstConditioningSeed(),
						block.secondConditioningSeed())) {
					if (!allSeeds.add(seed)) throw new IllegalStateException("benchmark seed reuse differs");
				}
			}
		}
		if (allSeeds.size() != 16
				|| Math.abs(PairedMeasurement.ordinaryKernelReductionPercent(
				100, 100, 90, 90) - 10.0) > 1.0e-12
				|| Math.abs(PairedMeasurement.ordinaryWorkReductionPercent(
				400.0, 300.0) - 25.0) > 1.0e-12) {
			throw new IllegalStateException("benchmark reduction contract differs");
		}
	}

	private static void verifyRepresentativeCpuBuilds() {
		BvhBuildConfig base = MethodCatalog.calibratedBase();
		for (int rowOrdinal : List.of(0, 5, 6)) {
			BenchmarkProtocol.PublicationRow row = BenchmarkProtocol.publicationRows().get(rowOrdinal);
			BenchmarkWorkloads.Source source = row.study() == BenchmarkProtocol.StudyKind.RANDOM
					? BenchmarkWorkloads.random(row, BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID)
					: BenchmarkWorkloads.fixed(row);
			List<MethodCatalog.Method> methods = MethodCatalog.buildAll(
					source.scene(), base, BenchmarkProtocol.LEAF_SIZE, BenchmarkProtocol.WEIGHTED_LAMBDA);
			boolean sameGeometry = methods.stream()
					.map(MethodCatalog.Method::packedGeometrySha256)
					.distinct().count() == 1;
			if (methods.size() != 8 || !methods.stream().map(MethodCatalog.Method::family)
					.toList().equals(MethodCatalog.FAMILIES)
					|| !sameGeometry) {
				throw new IllegalStateException("benchmark representative CPU build identity differs for "
						+ row.id() + ": actualGeometry=" + methods.stream()
						.map(MethodCatalog.Method::packedGeometrySha256).distinct().toList());
			}
			if (rowOrdinal == 6 && (source.layoutId() != BenchmarkProtocol.FIRST_RANDOM_LAYOUT_ID
					|| source.layoutSha256() == null
					|| !source.layoutSha256().matches("[0-9a-f]{64}"))) {
				throw new IllegalStateException("benchmark representative random layout identity differs");
			}
		}
	}
}
