package xyz.marsavic.gfxlab.graphics3d.bvh;

import xyz.marsavic.gfxlab.Vec3;
import xyz.marsavic.gfxlab.graphics3d.Affine;
import xyz.marsavic.gfxlab.graphics3d.Hit;
import xyz.marsavic.gfxlab.graphics3d.Ray;
import xyz.marsavic.gfxlab.graphics3d.Solid;
import xyz.marsavic.gfxlab.graphics3d.TraceStatsSolid;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneBox;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneHalfSpace;
import xyz.marsavic.gfxlab.graphics3d.scene.v1.SceneSphere;
import xyz.marsavic.gfxlab.graphics3d.solids.Group;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * CPU BVH reference over the current bounded scene primitives.
 *
 * Bounded primitives are stored in a binary BVH with exact SAH splits.
 * Infinite half-spaces stay in a separate side list.
 */
public final class BvhSolid implements TraceStatsSolid {
	private static final int DEFAULT_LEAF_SIZE = 4;
	private static final int APPROX_NODE_BYTES = (6 * Double.BYTES) + (4 * Integer.BYTES);
	private static final int APPROX_SOLID_REF_BYTES = 8;

	private final Node[] nodes;
	private final Solid[] boundedSolids;
	private final int[] boundedOrders;
	private final Solid[] infiniteSolids;
	private final int[] infiniteOrders;
	private final Stats stats;
	private final ThreadLocal<TraceStats> traceStats = ThreadLocal.withInitial(TraceStats::new);
	private final ThreadLocal<int[]> traversalStack;
	private final ThreadLocal<double[]> traversalEntryStack;

	private BvhSolid(Node[] nodes, Solid[] boundedSolids, int[] boundedOrders, Solid[] infiniteSolids, int[] infiniteOrders, Stats stats) {
		this.nodes = nodes;
		this.boundedSolids = boundedSolids;
		this.boundedOrders = boundedOrders;
		this.infiniteSolids = infiniteSolids;
		this.infiniteOrders = infiniteOrders;
		this.stats = stats;
		this.traversalStack = ThreadLocal.withInitial(() -> new int[Math.max(1, nodes.length)]);
		this.traversalEntryStack = ThreadLocal.withInitial(
				() -> new double[Math.max(1, nodes.length)]);
	}

	public record Stats(int boundedPrimitiveCount,
	                   int infinitePrimitiveCount,
	                   int nodeCount,
	                   int leafCount,
	                   int maxDepth,
	                   int leafSize,
	                   double sahCost,
	                   long hostBytes) {
	}

	private record BoundedPrimitive(Solid solid, Aabb bounds, Vec3 centroid, int order) {
	}
	private record OrderedSolid(Solid solid, int order) {
	}

	private record Node(Aabb bounds, int leftChild, int rightChild, int firstPrimitive, int primitiveCount) {
		boolean isLeaf() {
			return primitiveCount > 0;
		}
	}

	private static final class TraceStats {
		long primitiveTests;
		long aabbTests;
		long internalNodeVisits;
		long leafNodeVisits;
	}

	public static BvhSolid build(Solid solid) {
		return build(solid, DEFAULT_LEAF_SIZE);
	}

	public static BvhSolid build(Solid solid, int leafSize) {
		if (leafSize < 1) {
			throw new IllegalArgumentException("BVH leaf size must be positive.");
		}
		List<BoundedPrimitive> bounded = new ArrayList<>();
		List<OrderedSolid> infinite = new ArrayList<>();
		collectPrimitives(solid, bounded, infinite);

		if (bounded.isEmpty()) {
			Stats stats = new Stats(0, infinite.size(), 0, 0, 0, leafSize, 0.0,
					(long) infinite.size() * APPROX_SOLID_REF_BYTES);
			Solid[] infiniteSolids = infinite.stream().map(OrderedSolid::solid).toArray(Solid[]::new);
			int[] infiniteOrders = infinite.stream().mapToInt(OrderedSolid::order).toArray();
			return new BvhSolid(new Node[0], new Solid[0], new int[0], infiniteSolids, infiniteOrders, stats);
		}

		BoundedPrimitive[] primitives = bounded.toArray(BoundedPrimitive[]::new);
		BuildState state = new BuildState(primitives, leafSize);
		int root = state.build(0, primitives.length, 0);
		if (root != 0) {
			throw new IllegalStateException("Unexpected BVH root index " + root);
		}

		Node[] nodes = state.nodes.toArray(Node[]::new);
		Solid[] boundedSolids = new Solid[primitives.length];
		int[] boundedOrders = new int[primitives.length];
		for (int i = 0; i < primitives.length; i++) {
			boundedSolids[i] = primitives[i].solid();
			boundedOrders[i] = primitives[i].order();
		}
		Solid[] infiniteSolids = infinite.stream().map(OrderedSolid::solid).toArray(Solid[]::new);
		int[] infiniteOrders = infinite.stream().mapToInt(OrderedSolid::order).toArray();
		long hostBytes = (long) nodes.length * APPROX_NODE_BYTES
				+ (long) boundedSolids.length * APPROX_SOLID_REF_BYTES
				+ (long) infiniteSolids.length * APPROX_SOLID_REF_BYTES;
		Stats stats = new Stats(primitives.length, infiniteSolids.length, nodes.length, state.leafCount, state.maxDepth,
				leafSize, state.sahCost(0), hostBytes);
		return new BvhSolid(nodes, boundedSolids, boundedOrders, infiniteSolids, infiniteOrders, stats);
	}

	public Stats stats() {
		return stats;
	}

	@Override
	public void resetTraceStats() {
		TraceStats stats = traceStats.get();
		stats.primitiveTests = 0L;
		stats.aabbTests = 0L;
		stats.internalNodeVisits = 0L;
		stats.leafNodeVisits = 0L;
	}

	@Override
	public long lastPrimitiveTests() {
		return traceStats.get().primitiveTests;
	}

	@Override
	public long lastAabbTests() {
		return traceStats.get().aabbTests;
	}

	@Override
	public long lastInternalNodeVisits() {
		return traceStats.get().internalNodeVisits;
	}

	@Override
	public long lastLeafNodeVisits() {
		return traceStats.get().leafNodeVisits;
	}

	@Override
	public Hit firstHit(Ray ray, double afterTime) {
		TraceStats stats = traceStats.get();
		double closestT = Double.POSITIVE_INFINITY;
		Hit closestHit = Hit.AtInfinity.axisAlignedOut(ray.d());

		int bestOrder = Integer.MAX_VALUE;
		for (int i = 0; i < infiniteSolids.length; i++) {
			Solid infiniteSolid = infiniteSolids[i];
			stats.primitiveTests++;
			Hit hit = infiniteSolid.firstHit(ray, afterTime);
			if (betterHit(hit.t(), infiniteOrders[i], closestT, bestOrder)) {
				closestT = hit.t();
				bestOrder = infiniteOrders[i];
				closestHit = hit;
			}
		}

		if (nodes.length == 0) {
			return closestHit;
		}

		int[] stack = traversalStack.get();
		double[] entryStack = traversalEntryStack.get();
		int stackSize = 0;
		stats.aabbTests++;
		double rootEntry = nodes[0].bounds().entryDistance(ray, afterTime, closestT);
		if (rootEntry == Double.POSITIVE_INFINITY) {
			return closestHit;
		}
		stack[stackSize] = 0;
		entryStack[stackSize++] = rootEntry;

		while (stackSize > 0) {
			int nodeIndex = stack[--stackSize];
			double entry = entryStack[stackSize];
			if (entry > closestT) {
				continue;
			}
			Node node = nodes[nodeIndex];

			if (node.isLeaf()) {
				stats.leafNodeVisits++;
				for (int i = 0; i < node.primitiveCount(); i++) {
					Solid solid = boundedSolids[node.firstPrimitive() + i];
					int order = boundedOrders[node.firstPrimitive() + i];
					stats.primitiveTests++;
					Hit hit = solid.firstHit(ray, afterTime);
					if (betterHit(hit.t(), order, closestT, bestOrder)) {
						closestT = hit.t();
						bestOrder = order;
						closestHit = hit;
					}
				}
				continue;
			}

			stats.internalNodeVisits++;
			int leftChild = node.leftChild();
			int rightChild = node.rightChild();
			stats.aabbTests += 2;
			double leftEntry = nodes[leftChild].bounds().entryDistance(ray, afterTime, closestT);
			double rightEntry = nodes[rightChild].bounds().entryDistance(ray, afterTime, closestT);

			if (leftEntry == Double.POSITIVE_INFINITY && rightEntry == Double.POSITIVE_INFINITY) {
				continue;
			}
			if (leftEntry <= rightEntry) {
				if (rightEntry != Double.POSITIVE_INFINITY) {
					stack[stackSize] = rightChild;
					entryStack[stackSize++] = rightEntry;
				}
				if (leftEntry != Double.POSITIVE_INFINITY) {
					stack[stackSize] = leftChild;
					entryStack[stackSize++] = leftEntry;
				}
			} else {
				if (leftEntry != Double.POSITIVE_INFINITY) {
					stack[stackSize] = leftChild;
					entryStack[stackSize++] = leftEntry;
				}
				if (rightEntry != Double.POSITIVE_INFINITY) {
					stack[stackSize] = rightChild;
					entryStack[stackSize++] = rightEntry;
				}
			}
		}

		return closestHit;
	}

	private static boolean betterHit(double candidateT, int candidateOrder, double bestT, int bestOrder) {
		return candidateT < bestT || (candidateT == bestT && candidateOrder < bestOrder);
	}

	private static void collectPrimitives(Solid solid, List<BoundedPrimitive> bounded, List<OrderedSolid> infinite) {
		if (solid instanceof Group group) {
			for (Solid child : group.solids()) {
				collectPrimitives(child, bounded, infinite);
			}
			return;
		}
		int order = bounded.size() + infinite.size();
		if (solid instanceof SceneHalfSpace) {
			infinite.add(new OrderedSolid(solid, order));
			return;
		}
		if (solid instanceof SceneSphere sphere) {
			Aabb bounds = boundsOf(sphere);
			bounded.add(new BoundedPrimitive(sphere, bounds, centroidOf(bounds), order));
			return;
		}
		if (solid instanceof SceneBox box) {
			Aabb bounds = boundsOf(box);
			bounded.add(new BoundedPrimitive(box, bounds, centroidOf(bounds), order));
			return;
		}
		throw new UnsupportedOperationException("BVH builder does not support solid type " + solid.getClass().getName());
	}

	private static Aabb boundsOf(SceneSphere sphere) {
		if (!sphere.isAffine()) {
			Vec3 radius = Vec3.xyz(sphere.radius(), sphere.radius(), sphere.radius());
			return outwardBounds(sphere.center().sub(radius), sphere.center().add(radius));
		}
		Affine transform = sphere.transform();
		Vec3 center = transform.at(Vec3.ZERO);
		Vec3 extents = Vec3.xyz(
				rowLength(transform.m00(), transform.m01(), transform.m02()),
				rowLength(transform.m10(), transform.m11(), transform.m12()),
				rowLength(transform.m20(), transform.m21(), transform.m22())
		);
		return outwardBounds(center.sub(extents), center.add(extents));
	}

	private static Aabb boundsOf(SceneBox box) {
		if (!box.isAffine()) {
			return outwardBounds(Vec3.min(box.p(), box.q()), Vec3.max(box.p(), box.q()));
		}
		Affine transform = box.transform();
		Vec3 center = transform.at(Vec3.ZERO);
		Vec3 extents = Vec3.xyz(
				rowAbsSum(transform.m00(), transform.m01(), transform.m02()),
				rowAbsSum(transform.m10(), transform.m11(), transform.m12()),
				rowAbsSum(transform.m20(), transform.m21(), transform.m22())
		);
		return outwardBounds(center.sub(extents), center.add(extents));
	}

	private static Aabb outwardBounds(Vec3 min, Vec3 max) {
		return new Aabb(
				Vec3.xyz(nextDown2(min.x()), nextDown2(min.y()), nextDown2(min.z())),
				Vec3.xyz(nextUp2(max.x()), nextUp2(max.y()), nextUp2(max.z())));
	}

	private static double nextDown2(double value) {
		return Math.nextDown(Math.nextDown(value));
	}

	private static double nextUp2(double value) {
		return Math.nextUp(Math.nextUp(value));
	}

	private static Vec3 centroidOf(Aabb bounds) {
		return bounds.min().add(bounds.max()).mul(0.5);
	}

	private static double rowLength(double a, double b, double c) {
		return Math.sqrt(a * a + b * b + c * c);
	}

	private static double rowAbsSum(double a, double b, double c) {
		return Math.abs(a) + Math.abs(b) + Math.abs(c);
	}

	private static double centroidComponent(BoundedPrimitive primitive, int axis) {
		return primitive.centroid().get(axis);
	}

	private static final class BuildState {
		private final BoundedPrimitive[] primitives;
		private final int leafSize;
		private final List<Node> nodes = new ArrayList<>();
		private int leafCount;
		private int maxDepth;

		private BuildState(BoundedPrimitive[] primitives, int leafSize) {
			this.primitives = primitives;
			this.leafSize = leafSize;
		}

		private int build(int start, int end, int depth) {
			int count = end - start;
			Aabb bounds = bounds(start, end);
			int nodeIndex = nodes.size();
			nodes.add(null);
			maxDepth = Math.max(maxDepth, depth);

			if (count <= leafSize) {
				leafCount++;
				nodes.set(nodeIndex, new Node(bounds, -1, -1, start, count));
				return nodeIndex;
			}

			Split split = bestSplit(start, end, bounds);
			if (split == null) {
				leafCount++;
				nodes.set(nodeIndex, new Node(bounds, -1, -1, start, count));
				return nodeIndex;
			}

			Arrays.sort(primitives, start, end, Comparator.comparingDouble(p -> centroidComponent(p, split.axis)));
			int mid = start + split.leftCount;
			if (mid <= start || mid >= end) {
				leafCount++;
				nodes.set(nodeIndex, new Node(bounds, -1, -1, start, count));
				return nodeIndex;
			}

			int leftChild = build(start, mid, depth + 1);
			int rightChild = build(mid, end, depth + 1);
			nodes.set(nodeIndex, new Node(bounds, leftChild, rightChild, -1, 0));
			return nodeIndex;
		}

		private Split bestSplit(int start, int end, Aabb nodeBounds) {
			int count = end - start;
			double nodeArea = nodeBounds.surfaceArea();
			double leafCost = nodeArea * count;
			Split best = null;
			double bestCost = Double.POSITIVE_INFINITY;

			for (int axis = 0; axis < 3; axis++) {
				int splitAxis = axis;
				Arrays.sort(primitives, start, end, Comparator.comparingDouble(p -> centroidComponent(p, splitAxis)));

				Aabb[] prefix = new Aabb[count];
				Aabb[] suffix = new Aabb[count];
				Aabb running = Aabb.EMPTY;
				for (int i = 0; i < count; i++) {
					running = running.union(primitives[start + i].bounds());
					prefix[i] = running;
				}
				running = Aabb.EMPTY;
				for (int i = count - 1; i >= 0; i--) {
					running = running.union(primitives[start + i].bounds());
					suffix[i] = running;
				}

				for (int splitIndex = 1; splitIndex < count; splitIndex++) {
					double leftMax = centroidComponent(primitives[start + splitIndex - 1], axis);
					double rightMin = centroidComponent(primitives[start + splitIndex], axis);
					if (leftMax == rightMin) {
						continue;
					}
					double cost = nodeArea
							+ prefix[splitIndex - 1].surfaceArea() * splitIndex
							+ suffix[splitIndex].surfaceArea() * (count - splitIndex);
					if (cost < bestCost) {
						bestCost = cost;
						best = new Split(axis, splitIndex);
					}
				}
			}

			if (best == null || bestCost >= leafCost) {
				return null;
			}
			return best;
		}

		private Aabb bounds(int start, int end) {
			Aabb bounds = Aabb.EMPTY;
			for (int i = start; i < end; i++) {
				bounds = bounds.union(primitives[i].bounds());
			}
			return bounds;
		}

		private double sahCost(int nodeIndex) {
			Node node = nodes.get(nodeIndex);
			if (node.isLeaf()) {
				return node.primitiveCount();
			}
			double parentArea = node.bounds().surfaceArea();
			if (!(parentArea > 0.0)) {
				return sahCost(node.leftChild()) + sahCost(node.rightChild());
			}
			Node left = nodes.get(node.leftChild());
			Node right = nodes.get(node.rightChild());
			return 1.0
					+ left.bounds().surfaceArea() / parentArea * sahCost(node.leftChild())
					+ right.bounds().surfaceArea() / parentArea * sahCost(node.rightChild());
		}
	}

	private record Split(int axis, int leftCount) {
	}

	private record Aabb(Vec3 min, Vec3 max) {
		private static final Aabb EMPTY = new Aabb(
				Vec3.xyz(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
				Vec3.xyz(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
		);

		Aabb union(Aabb other) {
			return new Aabb(Vec3.min(min, other.min), Vec3.max(max, other.max));
		}

		double surfaceArea() {
			Vec3 d = max.sub(min);
			return 2.0 * (d.x() * d.y() + d.x() * d.z() + d.y() * d.z());
		}

		boolean hits(Ray ray, double afterTime, double beforeTime) {
			return entryDistance(ray, afterTime, beforeTime) != Double.POSITIVE_INFINITY;
		}

		double entryDistance(Ray ray, double afterTime, double beforeTime) {
			double tMin = afterTime;
			double tMax = beforeTime;

			for (int axis = 0; axis < 3; axis++) {
				double origin = ray.p().get(axis);
				double direction = ray.d().get(axis);
				double minValue = min.get(axis);
				double maxValue = max.get(axis);

				if (direction == 0.0) {
					if (origin < minValue || origin > maxValue) {
						return Double.POSITIVE_INFINITY;
					}
					continue;
				}

				double t1 = (minValue - origin) / direction;
				double t2 = (maxValue - origin) / direction;
				if (t1 > t2) {
					double tmp = t1;
					t1 = t2;
					t2 = tmp;
				}
				if (t2 > 0.0 && Double.isFinite(t2)) {
					t2 = nextUp2(t2);
				}

				tMin = Math.max(tMin, t1);
				tMax = Math.min(tMax, t2);
				if (tMax < tMin) {
					return Double.POSITIVE_INFINITY;
				}
			}

			return tMin <= beforeTime ? tMin : Double.POSITIVE_INFINITY;
		}
	}
}
