package xyz.marsavic.gfxlab.graphics3d;

public interface TraceStatsSolid extends Solid {
	void resetTraceStats();
	long lastPrimitiveTests();
	long lastAabbTests();
	long lastInternalNodeVisits();
	long lastLeafNodeVisits();
}
