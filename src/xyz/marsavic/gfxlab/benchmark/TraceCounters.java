package xyz.marsavic.gfxlab.benchmark;

import java.util.concurrent.atomic.LongAdder;

public final class TraceCounters {

	public record Snapshot(long primaryRays, long rays, long primitiveTests, long aabbTests,
	                       long internalNodeVisits, long leafNodeVisits) { }

	private final LongAdder primaryRays = new LongAdder();
	private final LongAdder rays = new LongAdder();
	private final LongAdder primitiveTests = new LongAdder();
	private final LongAdder aabbTests = new LongAdder();
	private final LongAdder internalNodeVisits = new LongAdder();
	private final LongAdder leafNodeVisits = new LongAdder();

	public void recordPrimaryRay() {
		primaryRays.increment();
	}

	public void recordRay(long primitiveTestsForRay, long aabbTestsForRay) {
		recordRay(primitiveTestsForRay, aabbTestsForRay, 0L, 0L);
	}

	public void recordRay(long primitiveTestsForRay, long aabbTestsForRay,
	                      long internalNodeVisitsForRay, long leafNodeVisitsForRay) {
		rays.increment();
		primitiveTests.add(Math.max(0L, primitiveTestsForRay));
		aabbTests.add(Math.max(0L, aabbTestsForRay));
		internalNodeVisits.add(Math.max(0L, internalNodeVisitsForRay));
		leafNodeVisits.add(Math.max(0L, leafNodeVisitsForRay));
	}

	public Snapshot snapshot() {
		return new Snapshot(
				primaryRays.sum(),
				rays.sum(),
				primitiveTests.sum(),
				aabbTests.sum(),
				internalNodeVisits.sum(),
				leafNodeVisits.sum()
		);
	}
}
