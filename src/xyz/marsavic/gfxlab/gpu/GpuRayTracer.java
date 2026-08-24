package xyz.marsavic.gfxlab.gpu;

import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdevice_attribute;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUevent;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import jcuda.driver.JCudaDriver;
import jcuda.nvrtc.JNvrtc;
import jcuda.nvrtc.nvrtcProgram;
import xyz.marsavic.gfxlab.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static jcuda.driver.JCudaDriver.cuCtxCreate;
import static jcuda.driver.JCudaDriver.cuCtxDestroy;
import static jcuda.driver.JCudaDriver.cuCtxSetCurrent;
import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuDeviceGet;
import static jcuda.driver.JCudaDriver.cuEventCreate;
import static jcuda.driver.JCudaDriver.cuEventDestroy;
import static jcuda.driver.JCudaDriver.cuEventElapsedTime;
import static jcuda.driver.JCudaDriver.cuEventRecord;
import static jcuda.driver.JCudaDriver.cuEventSynchronize;
import static jcuda.driver.JCudaDriver.cuInit;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemAlloc;
import static jcuda.driver.JCudaDriver.cuMemFree;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuMemcpyHtoD;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;
import static jcuda.driver.JCudaDriver.cuModuleLoadData;
import static jcuda.driver.JCudaDriver.cuModuleUnload;

/**
 * JCuda bridge for the headless thesis renderer.
 */
public final class GpuRayTracer implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(GpuRayTracer.class.getName());
	public static final int MIN_FULL_PATH_DEPTH = 0;
	public static final int MAX_FULL_PATH_DEPTH = 32;
	private static final float MS_TO_NS = 1_000_000.0f;
	private static final long MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE = 250_000_000L;
	private static final int FRAME_STATS_COUNT = 14;
	static final int MAX_REPLAY_RAYS_PER_LAUNCH =
			GpuLaunchProvenance.MAX_REPLAY_RAYS_PER_LAUNCH;
	static final int MAX_RENDER_LAUNCHES_PER_CALL = 8_192;
	static final long MAX_LINEAR_REPLAY_PRIMITIVE_WORK = 1L << 20;
	static final int MAX_LINEAR_REPLAY_CHUNKS = 8_192;
	// Watchdog planning deliberately does not depend on measured performance
	// weights. These conservative integral work units remain safe before and
	// after the active primitive-cost calibration is applied.
	private static final long SPHERE_REPLAY_WORK = 8L;
	private static final long BOX_REPLAY_WORK = 8L;
	private static final long PLANE_REPLAY_WORK = 8L;
	private static final long AFFINE_SPHERE_REPLAY_WORK = 64L;
	private static final long AFFINE_BOX_REPLAY_WORK = 64L;
	private static volatile String lastCompiledPtxSha256 = "unavailable";

	public record FrameStats(long totalNanos, long kernelNanos,
	                         long maximumPhysicalKernelNanos,
	                         long copyNanos, long uploadNanos,
	                         long rays, long primitiveTests, long aabbTests,
	                         long sphereTests, long boxTests, long planeTests,
	                         long affineSphereTests, long affineBoxTests,
	                         long stackOverflows, long maxStackSize, long internalNodeVisits,
	                         long leafNodeVisits, long homogeneousLeafNodeVisits,
	                         long mixedLeafNodeVisits) {
		public FrameStats {
			if (kernelNanos < 0L || maximumPhysicalKernelNanos < 0L
					|| maximumPhysicalKernelNanos > kernelNanos) {
				throw new IllegalArgumentException(
						"Physical-kernel timing must be nonnegative and bounded by total kernel time");
			}
			if (leafNodeVisits != homogeneousLeafNodeVisits + mixedLeafNodeVisits) {
				throw new IllegalArgumentException(
						"Leaf-kind visits must partition total leaf visits");
			}
		}
	}

	public record ConditioningStats(
			int physicalLaunches,
			long uploadNanos,
			long aggregatePhysicalKernelNanos,
			long maximumPhysicalKernelNanos,
			long totalNanos
	) {
		public ConditioningStats {
			if (physicalLaunches < 1 || uploadNanos < 0L
					|| aggregatePhysicalKernelNanos <= 0L
					|| maximumPhysicalKernelNanos <= 0L
					|| maximumPhysicalKernelNanos > aggregatePhysicalKernelNanos
					|| totalNanos <= 0L) {
				throw new IllegalArgumentException("Invalid conditioning diagnostics");
			}
		}
	}

	public record TraceReplayResult(
			FrameStats stats,
			int[] hitFlags,
			float[] hitDistances,
			int[] hitPrimitiveOrders,
			int[] hitMaterialIndices,
			float[] hitNormals
	) { }

	public record DeviceInfo(String name, String computeCapability) {
		@Override public String toString() {
			return name + " (" + computeCapability + ")";
		}
	}

	record RenderTile(int x, int y, int width, int height) {
		int pixelCount() {
			return Math.multiplyExact(width, height);
		}
	}

	record RenderTilePlan(
			int imageWidth,
			int imageHeight,
			int tileWidth,
			int tileHeight,
			int tileColumns,
			int tileRows,
			int tileCount
	) {
		RenderTile tile(int index) {
			if (index < 0 || index >= tileCount) {
				throw new IllegalArgumentException("Render tile index is outside the plan");
			}
			int column = index % tileColumns;
			int row = index / tileColumns;
			int x = Math.multiplyExact(column, tileWidth);
			int y = Math.multiplyExact(row, tileHeight);
			return new RenderTile(
					x,
					y,
					Math.min(tileWidth, imageWidth - x),
					Math.min(tileHeight, imageHeight - y));
		}
	}

	/** SHA-256 of the most recently NVRTC-compiled PTX in this JVM. */
	public static String lastCompiledPtxSha256() {
		return lastCompiledPtxSha256;
	}

	private final int width;
	private final int height;
	private final int outputElements;
	private final long outputBytes;
	private final int samplesPerFrame;
	private final boolean bvhTraversal;
	private final boolean collectMetrics;
	private final int blockX;
	private final int blockY;

	private boolean initialized;
	private boolean available = true;

	private CUcontext context;
	private CUmodule module;
	private CUfunction kernel;
	private CUfunction traceReplayKernel;

	private CUdeviceptr dOutput;
	private CUdeviceptr dSpheres;
	private CUdeviceptr dBoxes;
	private CUdeviceptr dPlanes;
	private CUdeviceptr dAffineSpheres;
	private CUdeviceptr dAffineBoxes;
	private CUdeviceptr dBvhNodeBounds;
	private CUdeviceptr dBvhNodeData;
	private CUdeviceptr dBvhPrimitiveRefs;
	private CUdeviceptr dBvhRootIndices;
	private CUdeviceptr dMaterials;
	private CUdeviceptr dFrameStats;
	private CUdeviceptr dReplayRays;
	private CUdeviceptr dReplayHitFlags;
	private CUdeviceptr dReplayHitDistances;
	private CUdeviceptr dReplayHitPrimitiveOrders;
	private CUdeviceptr dReplayHitMaterialIndices;
	private CUdeviceptr dReplayHitNormals;

	private CUevent kernelStartEvent;
	private CUevent kernelEndEvent;
	private CUevent[] physicalBoundaryEvents = new CUevent[0];

	private int sphereCapacity;
	private int boxCapacity;
	private int planeCapacity;
	private int affineSphereCapacity;
	private int affineBoxCapacity;
	private int bvhNodeBoundsCapacity;
	private int bvhNodeDataCapacity;
	private int bvhPrimitiveRefCapacity;
	private int bvhRootIndexCapacity;
	private int materialCapacity;
	private int replayRayCapacity;
	private int replayHitCapacity;
	private int preparedReplayRayCount;
	private int preparedReplayRaysPerLaunch;

	private float[] hostBuffer;
	private final float[] kernelMsBuffer = new float[1];
	private final long[] hostFrameStats = new long[FRAME_STATS_COUNT];
	private long lastKernelNanos;
	private long lastMaximumPhysicalKernelNanos;
	private long lastCopyNanos;
	private long lastUploadNanos;
	private long lastTotalNanos;
	private long lastRays;
	private long lastPrimitiveTests;
	private long lastAabbTests;
	private long lastSphereTests;
	private long lastBoxTests;
	private long lastPlaneTests;
	private long lastAffineSphereTests;
	private long lastAffineBoxTests;
	private long lastStackOverflows;
	private long lastMaxStackSize;
	private long lastInternalNodeVisits;
	private long lastLeafNodeVisits;
	private long lastHomogeneousLeafNodeVisits;
	private long lastMixedLeafNodeVisits;
	private int lastPhysicalKernelLaunchCount;
	private String deviceName = "unknown";
	private String deviceCC = "compute_unknown";
	private String compiledPtxSha256 = "unavailable";
	private GpuScene residentScene;
	private long residentSceneRevision = Long.MIN_VALUE;
	private float[] conditioningBuffer;

	public GpuRayTracer(int width, int height, int samplesPerFrame, boolean bvhTraversal) {
		this(width, height, samplesPerFrame, bvhTraversal,
				Boolean.getBoolean("gfxlab.gpu.collectMetrics"));
	}

	public GpuRayTracer(
			int width,
			int height,
			int samplesPerFrame,
			boolean bvhTraversal,
			boolean collectMetrics
	) {
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		long pixelCount = (long) this.width * (long) this.height;
		if (pixelCount > Integer.MAX_VALUE / 3L) {
			throw new IllegalArgumentException("GPU image dimensions exceed Java array indexing");
		}
		long elementCount = pixelCount * 3L;
		this.outputElements = (int) elementCount;
		this.outputBytes = Math.multiplyExact((long) outputElements, (long) Sizeof.FLOAT);
		this.samplesPerFrame = Math.max(1, samplesPerFrame);
		this.bvhTraversal = bvhTraversal;
		this.collectMetrics = collectMetrics;
		this.blockX = 16;
		this.blockY = 16;
	}

	public boolean isAvailable() {
		initializeIfNeeded();
		return available;
	}

	public boolean collectsMetrics() {
		return collectMetrics;
	}

	/** Number of physical full-path kernels in the last completed logical frame. */
	public int lastPhysicalKernelLaunchCount() {
		return lastPhysicalKernelLaunchCount;
	}

	public FrameStats lastFrameStats() {
		return new FrameStats(lastTotalNanos, lastKernelNanos,
				lastMaximumPhysicalKernelNanos, lastCopyNanos, lastUploadNanos,
				lastRays, lastPrimitiveTests, lastAabbTests,
				lastSphereTests, lastBoxTests, lastPlaneTests,
				lastAffineSphereTests, lastAffineBoxTests,
				lastStackOverflows, lastMaxStackSize, lastInternalNodeVisits, lastLeafNodeVisits,
				lastHomogeneousLeafNodeVisits, lastMixedLeafNodeVisits);
	}

	public DeviceInfo deviceInfo() {
		return new DeviceInfo(deviceName, deviceCC);
	}

	/** SHA-256 of the PTX module loaded by this tracer instance. */
	public String compiledPtxSha256() {
		return compiledPtxSha256;
	}

	public void renderSample(float[] rgbBuffer,
	                         GpuScene scene,
	                         GpuCamera camera,
	                         int maxDepth,
	                         int frameIndex,
	                         long frameSeed) {
		validateFullPathDepth(maxDepth);
		if (rgbBuffer == null || rgbBuffer.length < outputElements) {
			throw new IllegalArgumentException(
					"RGB output must contain at least " + outputElements + " elements");
		}
		if (scene == null || camera == null) {
			throw new IllegalArgumentException("GPU scene and camera must not be null");
		}
		RenderTilePlan tilePlan = renderTilePlan(
				width, height, renderPixelsPerLaunch());
		int physicalLaunchCount = renderLaunchCount(tilePlan, samplesPerFrame);
		initializeIfNeeded();
		if (!available) {
			throw new IllegalStateException("CUDA path tracer is unavailable");
		}

		synchronized (this) {
			cuCtxSetCurrent(context);
			long frameStart = System.nanoTime();
			ensureTimingEvents();
			ensurePhysicalBoundaryEvents(physicalLaunchCount - 1);
			ensureOutputBuffer();
			ensureSceneResident(scene);
			ensureFrameStatsBuffer();
			if (collectMetrics) {
				JCudaDriver.cuMemsetD8(dFrameStats, (byte) 0, (long) FRAME_STATS_COUNT * Long.BYTES);
			}

			// Sample zero overwrites every covered pixel. Later samples read the raw
			// partial sum, and the last sample normalizes it, so no output memset or
			// intermediate device-to-host copy is required.
			cuEventRecord(kernelStartEvent, null);
			int physicalLaunch = 0;
			for (int sampleOffset = 0; sampleOffset < samplesPerFrame; sampleOffset++) {
				for (int tileIndex = 0; tileIndex < tilePlan.tileCount(); tileIndex++) {
					RenderTile tile = tilePlan.tile(tileIndex);
					Pointer kernelParams = renderKernelParameters(
							scene, camera, maxDepth, frameIndex, frameSeed,
							tile, sampleOffset);
					int launchGridX = ceilDiv(tile.width(), blockX);
					int launchGridY = ceilDiv(tile.height(), blockY);
					cuLaunchKernel(
							kernel,
							launchGridX, launchGridY, 1,
							blockX, blockY, 1,
							0, null,
							kernelParams, null
					);
					physicalLaunch++;
					if (physicalLaunch < physicalLaunchCount) {
						cuEventRecord(physicalBoundaryEvents[physicalLaunch - 1], null);
					}
				}
			}
			cuEventRecord(kernelEndEvent, null);
			cuEventSynchronize(kernelEndEvent);
			cuEventElapsedTime(kernelMsBuffer, kernelStartEvent, kernelEndEvent);
			long kernelNanos = (long) (kernelMsBuffer[0] * MS_TO_NS);
			long maximumPhysicalKernelNanos = 0L;
			CUevent physicalStart = kernelStartEvent;
			for (int index = 0; index < physicalLaunchCount; index++) {
				CUevent physicalEnd = index + 1 == physicalLaunchCount
						? kernelEndEvent : physicalBoundaryEvents[index];
				cuEventElapsedTime(kernelMsBuffer, physicalStart, physicalEnd);
				long physicalKernelNanos = (long) (kernelMsBuffer[0] * MS_TO_NS);
				requireSafePhysicalKernelNanos(physicalKernelNanos);
				maximumPhysicalKernelNanos = Math.max(
						maximumPhysicalKernelNanos, physicalKernelNanos);
				physicalStart = physicalEnd;
			}
			if (maximumPhysicalKernelNanos > kernelNanos) {
				throw new IllegalStateException(
						"A physical CUDA interval exceeds its logical frame");
			}
			long copyStart = System.nanoTime();
			cuMemcpyDtoH(Pointer.to(hostBuffer), dOutput, outputBytes);
			lastCopyNanos = System.nanoTime() - copyStart;
			lastKernelNanos = kernelNanos;
			lastMaximumPhysicalKernelNanos = maximumPhysicalKernelNanos;
			if (collectMetrics) {
				cuMemcpyDtoH(Pointer.to(hostFrameStats), dFrameStats, (long) FRAME_STATS_COUNT * Long.BYTES);
				lastRays = hostFrameStats[0];
				lastAabbTests = hostFrameStats[1];
				lastPrimitiveTests = hostFrameStats[2];
				lastSphereTests = hostFrameStats[3];
				lastBoxTests = hostFrameStats[4];
				lastPlaneTests = hostFrameStats[5];
				lastAffineSphereTests = hostFrameStats[6];
				lastAffineBoxTests = hostFrameStats[7];
				lastStackOverflows = hostFrameStats[8];
				lastMaxStackSize = hostFrameStats[9];
				lastInternalNodeVisits = hostFrameStats[10];
				lastLeafNodeVisits = hostFrameStats[11];
				lastHomogeneousLeafNodeVisits = hostFrameStats[12];
				lastMixedLeafNodeVisits = hostFrameStats[13];
			} else {
				lastRays = 0L;
				lastAabbTests = 0L;
				lastPrimitiveTests = 0L;
				lastSphereTests = lastBoxTests = lastPlaneTests = 0L;
				lastAffineSphereTests = lastAffineBoxTests = 0L;
				lastStackOverflows = lastMaxStackSize = 0L;
				lastInternalNodeVisits = lastLeafNodeVisits = 0L;
				lastHomogeneousLeafNodeVisits = lastMixedLeafNodeVisits = 0L;
			}
			System.arraycopy(hostBuffer, 0, rgbBuffer, 0, hostBuffer.length);
			lastPhysicalKernelLaunchCount = physicalLaunchCount;
			lastTotalNanos = System.nanoTime() - frameStart;
		}
	}

	public ConditioningStats conditionSteadyStateSample(
			GpuScene scene,
			GpuCamera camera,
			int maxDepth,
			int frameIndex,
			long frameSeed
	) {
		if (conditioningBuffer == null) {
			conditioningBuffer = new float[outputElements];
		}
		renderSample(conditioningBuffer, scene, camera, maxDepth, frameIndex, frameSeed);
		synchronized (this) {
			return new ConditioningStats(
					lastPhysicalKernelLaunchCount,
					lastUploadNanos,
					lastKernelNanos,
					lastMaximumPhysicalKernelNanos,
					lastTotalNanos);
		}
	}

	public void renderSteadyStateSample(
			float[] rgbBuffer,
			GpuScene scene,
			GpuCamera camera,
			int maxDepth,
			int frameIndex,
			long frameSeed
	) {
		renderSample(rgbBuffer, scene, camera, maxDepth, frameIndex, frameSeed);
		if (lastUploadNanos != 0L) {
			throw new IllegalStateException("A steady-state sample uploaded scene data");
		}
	}

	/** Validates the supported full-path recursion-depth envelope. */
	public static void validateFullPathDepth(int maxDepth) {
		if (maxDepth < MIN_FULL_PATH_DEPTH || maxDepth > MAX_FULL_PATH_DEPTH) {
			throw new IllegalArgumentException(
					"GPU full-path depth must be in [" + MIN_FULL_PATH_DEPTH + ", "
							+ MAX_FULL_PATH_DEPTH + "] but was " + maxDepth);
		}
	}

	private Pointer renderKernelParameters(
			GpuScene scene,
			GpuCamera camera,
			int maxDepth,
			int frameIndex,
			long frameSeed,
			RenderTile tile,
			int sampleOffset
	) {
		return Pointer.to(
				Pointer.to(dOutput),
				Pointer.to(new int[]{width}),
				Pointer.to(new int[]{height}),
				Pointer.to(new int[]{tile.x()}),
				Pointer.to(new int[]{tile.y()}),
				Pointer.to(new int[]{tile.width()}),
				Pointer.to(new int[]{tile.height()}),
				Pointer.to(new float[]{(float) camera.position().x()}),
				Pointer.to(new float[]{(float) camera.position().y()}),
				Pointer.to(new float[]{(float) camera.position().z()}),
				Pointer.to(new float[]{(float) camera.forward().x()}),
				Pointer.to(new float[]{(float) camera.forward().y()}),
				Pointer.to(new float[]{(float) camera.forward().z()}),
				Pointer.to(new float[]{(float) camera.right().x()}),
				Pointer.to(new float[]{(float) camera.right().y()}),
				Pointer.to(new float[]{(float) camera.right().z()}),
				Pointer.to(new float[]{(float) camera.up().x()}),
				Pointer.to(new float[]{(float) camera.up().y()}),
				Pointer.to(new float[]{(float) camera.up().z()}),
				Pointer.to(dSpheres),
				Pointer.to(new int[]{scene.spheres().size()}),
				Pointer.to(dBoxes),
				Pointer.to(new int[]{scene.boxes().size()}),
				Pointer.to(dPlanes),
				Pointer.to(new int[]{scene.planes().size()}),
				Pointer.to(dAffineSpheres),
				Pointer.to(new int[]{scene.affineSpheres().size()}),
				Pointer.to(dAffineBoxes),
				Pointer.to(new int[]{scene.affineBoxes().size()}),
				Pointer.to(dBvhNodeBounds),
				Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().nodeCount() : 0}),
				Pointer.to(dBvhNodeData),
				Pointer.to(dBvhPrimitiveRefs),
				Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().primitiveRefCount() : 0}),
				Pointer.to(dBvhRootIndices),
				Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().rootCount() : 0}),
				Pointer.to(new int[]{bvhTraversal ? 1 : 0}),
				Pointer.to(dMaterials),
				Pointer.to(new int[]{scene.materialCount()}),
				Pointer.to(new float[]{(float) scene.background().x()}),
				Pointer.to(new float[]{(float) scene.background().y()}),
				Pointer.to(new float[]{(float) scene.background().z()}),
				Pointer.to(new int[]{maxDepth}),
				Pointer.to(new int[]{frameIndex}),
				Pointer.to(new long[]{frameSeed}),
				Pointer.to(new int[]{sampleOffset}),
				Pointer.to(new int[]{samplesPerFrame}),
				Pointer.to(dFrameStats),
				Pointer.to(new int[]{collectMetrics ? 1 : 0})
		);
	}

	static int renderPixelsPerLaunch() {
		return GpuLaunchProvenance.renderPixelsPerLaunch();
	}

	static RenderTilePlan renderTilePlan(int width, int height, int pixelsPerLaunch) {
		GpuLaunchProvenance.RenderTileLayout layout =
				GpuLaunchProvenance.renderTileLayout(width, height, pixelsPerLaunch);
		long tileCount = layout.tileCount();
		if (tileCount > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Render tile count exceeds Java indexing");
		}
		return new RenderTilePlan(
				width, height, layout.tileWidth(), layout.tileHeight(),
				layout.tileColumns(), layout.tileRows(), (int) tileCount);
	}

	static int renderLaunchCount(RenderTilePlan plan, int samples) {
		if (plan == null || samples < 1) {
			throw new IllegalArgumentException("Invalid render launch count");
		}
		long launchCount = GpuLaunchProvenance.physicalRenderLaunches(
				plan.tileCount(), samples);
		if (launchCount > MAX_RENDER_LAUNCHES_PER_CALL) {
			throw new IllegalArgumentException(
					"Render would require " + launchCount
							+ " watchdog-safe pixel/sample launches, exceeding the limit of "
							+ MAX_RENDER_LAUNCHES_PER_CALL
							+ ". Increase the bounded pixel cap or reduce samples per frame.");
		}
		return (int) launchCount;
	}

	private static void requireSafePhysicalKernelNanos(long physicalKernelNanos) {
		if (physicalKernelNanos <= 0L
				|| physicalKernelNanos >= MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE) {
			throw new IllegalStateException(
					"Physical CUDA kernel duration must be greater than zero and less than "
							+ MAXIMUM_PHYSICAL_KERNEL_NANOS_EXCLUSIVE
							+ " ns; observed " + physicalKernelNanos + " ns");
		}
	}

	private static int ceilDiv(int value, int divisor) {
		if (value < 1 || divisor < 1) {
			throw new IllegalArgumentException("Ceiling division requires positive operands");
		}
		return 1 + (value - 1) / divisor;
	}

	/**
	 * Traces caller-provided rays through the closest-hit routine used by the
	 * path tracer. Rays are packed as origin.xyz followed by direction.xyz.
	 */
	public TraceReplayResult traceReplay(GpuScene scene, float[] rayData, int rayCount, boolean collectReplayMetrics) {
		requireReplayRays(rayData, rayCount);
		requireReplayScene(scene);
		initializeIfNeeded();
		if (!available) {
			throw new IllegalStateException("CUDA path tracer is unavailable");
		}

		synchronized (this) {
			cuCtxSetCurrent(context);
			prepareReplayRaysLocked(rayData, rayCount);
			return launchTraceReplayLocked(scene, collectReplayMetrics);
		}
	}

	private void prepareReplayRaysLocked(float[] rayData, int rayCount) {
		ensureReplayBuffers(rayCount);
		cuMemcpyHtoD(dReplayRays, Pointer.to(rayData), (long) rayCount * 6L * Sizeof.FLOAT);
		preparedReplayRayCount = rayCount;
		preparedReplayRaysPerLaunch = replayRaysPerLaunch();
	}

	private TraceReplayResult launchTraceReplayLocked(
			GpuScene scene,
			boolean collectReplayMetrics
	) {
		long frameStart = System.nanoTime();
		ensureFrameStatsBuffer();
		ensureSceneResident(scene);
		JCudaDriver.cuMemsetD8(
				dFrameStats, (byte) 0, (long) FRAME_STATS_COUNT * Long.BYTES);

		int raysPerLaunch = preparedReplayRaysPerLaunch;
		if (!bvhTraversal) {
			raysPerLaunch = linearReplayRaysPerLaunch(
					raysPerLaunch, linearPrimitiveWorkPerRay(scene), preparedReplayRayCount);
		}

		int blockSize = 256;
		int physicalLaunches = 0;
		for (int rayOffset = 0; rayOffset < preparedReplayRayCount; ) {
			int chunkRayCount = replayChunkRayCount(
					rayOffset, preparedReplayRayCount, raysPerLaunch);
			CUdeviceptr chunkRays = dReplayRays.withByteOffset(
					(long) rayOffset * 6L * Sizeof.FLOAT);
			CUdeviceptr chunkHitFlags = dReplayHitFlags.withByteOffset(
					(long) rayOffset * Sizeof.INT);
			CUdeviceptr chunkHitDistances = dReplayHitDistances.withByteOffset(
					(long) rayOffset * Sizeof.FLOAT);
			CUdeviceptr chunkHitPrimitiveOrders = dReplayHitPrimitiveOrders.withByteOffset(
					(long) rayOffset * Sizeof.INT);
			CUdeviceptr chunkHitMaterialIndices = dReplayHitMaterialIndices.withByteOffset(
					(long) rayOffset * Sizeof.INT);
			CUdeviceptr chunkHitNormals = dReplayHitNormals.withByteOffset(
					(long) rayOffset * 3L * Sizeof.FLOAT);

			Pointer kernelParams = Pointer.to(
					Pointer.to(chunkRays), Pointer.to(new int[]{chunkRayCount}),
					Pointer.to(dSpheres), Pointer.to(new int[]{scene.spheres().size()}),
					Pointer.to(dBoxes), Pointer.to(new int[]{scene.boxes().size()}),
					Pointer.to(dPlanes), Pointer.to(new int[]{scene.planes().size()}),
					Pointer.to(dAffineSpheres), Pointer.to(new int[]{scene.affineSpheres().size()}),
					Pointer.to(dAffineBoxes), Pointer.to(new int[]{scene.affineBoxes().size()}),
					Pointer.to(dBvhNodeBounds),
					Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().nodeCount() : 0}),
					Pointer.to(dBvhNodeData), Pointer.to(dBvhPrimitiveRefs),
					Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().primitiveRefCount() : 0}),
					Pointer.to(dBvhRootIndices),
					Pointer.to(new int[]{bvhTraversal ? scene.bvhStats().rootCount() : 0}),
					Pointer.to(new int[]{bvhTraversal ? 1 : 0}), Pointer.to(dMaterials),
					Pointer.to(chunkHitFlags), Pointer.to(chunkHitDistances),
					Pointer.to(chunkHitPrimitiveOrders), Pointer.to(chunkHitMaterialIndices),
					Pointer.to(chunkHitNormals),
					Pointer.to(new int[]{collectReplayMetrics ? 1 : 0}), Pointer.to(dFrameStats)
			);

			int gridSize = (chunkRayCount + blockSize - 1) / blockSize;
			cuLaunchKernel(traceReplayKernel, gridSize, 1, 1, blockSize, 1, 1,
					0, null, kernelParams, null);
			physicalLaunches++;
			rayOffset += chunkRayCount;
		}
		cuCtxSynchronize();

		long copyStart = System.nanoTime();
		int[] hitFlags = new int[preparedReplayRayCount];
		float[] hitDistances = new float[preparedReplayRayCount];
		int[] hitPrimitiveOrders = new int[preparedReplayRayCount];
		int[] hitMaterialIndices = new int[preparedReplayRayCount];
		float[] hitNormals = new float[Math.multiplyExact(preparedReplayRayCount, 3)];
		cuMemcpyDtoH(Pointer.to(hostFrameStats), dFrameStats, (long) FRAME_STATS_COUNT * Long.BYTES);
		cuMemcpyDtoH(Pointer.to(hitFlags), dReplayHitFlags,
				(long) preparedReplayRayCount * Sizeof.INT);
		cuMemcpyDtoH(Pointer.to(hitDistances), dReplayHitDistances,
				(long) preparedReplayRayCount * Sizeof.FLOAT);
		cuMemcpyDtoH(Pointer.to(hitPrimitiveOrders), dReplayHitPrimitiveOrders,
				(long) preparedReplayRayCount * Sizeof.INT);
		cuMemcpyDtoH(Pointer.to(hitMaterialIndices), dReplayHitMaterialIndices,
				(long) preparedReplayRayCount * Sizeof.INT);
		cuMemcpyDtoH(Pointer.to(hitNormals), dReplayHitNormals,
				(long) preparedReplayRayCount * 3L * Sizeof.FLOAT);
		lastCopyNanos = System.nanoTime() - copyStart;
		lastKernelNanos = 0L;
		lastMaximumPhysicalKernelNanos = 0L;
		lastRays = hostFrameStats[0];
		lastAabbTests = hostFrameStats[1];
		lastPrimitiveTests = hostFrameStats[2];
		lastSphereTests = hostFrameStats[3];
		lastBoxTests = hostFrameStats[4];
		lastPlaneTests = hostFrameStats[5];
		lastAffineSphereTests = hostFrameStats[6];
		lastAffineBoxTests = hostFrameStats[7];
		lastStackOverflows = hostFrameStats[8];
		lastMaxStackSize = hostFrameStats[9];
		lastInternalNodeVisits = hostFrameStats[10];
		lastLeafNodeVisits = hostFrameStats[11];
		lastHomogeneousLeafNodeVisits = hostFrameStats[12];
		lastMixedLeafNodeVisits = hostFrameStats[13];
		lastPhysicalKernelLaunchCount = physicalLaunches;
		lastTotalNanos = System.nanoTime() - frameStart;
		return new TraceReplayResult(
				lastFrameStats(), hitFlags, hitDistances, hitPrimitiveOrders,
				hitMaterialIndices, hitNormals);
	}

	private static void requireReplayRays(float[] rayData, int rayCount) {
		if (rayData == null || rayCount <= 0 || (long) rayData.length < (long) rayCount * 6L) {
			throw new IllegalArgumentException(
					"Replay rays must contain origin.xyz and direction.xyz.");
		}
	}

	private static void requireReplayScene(GpuScene scene) {
		if (scene == null) {
			throw new IllegalArgumentException("GPU scene must not be null");
		}
	}

	static int replayRaysPerLaunch() {
		return GpuLaunchProvenance.replayRaysPerLaunch();
	}

	static int replayChunkRayCount(int rayOffset, int rayCount, int raysPerLaunch) {
		if (rayOffset < 0 || rayOffset >= rayCount || raysPerLaunch < 1
				|| raysPerLaunch > MAX_REPLAY_RAYS_PER_LAUNCH) {
			throw new IllegalArgumentException("Invalid replay chunk bounds");
		}
		return Math.min(rayCount - rayOffset, raysPerLaunch);
	}

	static int linearReplayRaysPerLaunch(
			int configuredRayLimit,
			long primitiveWorkPerRay,
			int rayCount
	) {
		if (configuredRayLimit < 1 || configuredRayLimit > MAX_REPLAY_RAYS_PER_LAUNCH
				|| primitiveWorkPerRay < 0L || rayCount < 1) {
			throw new IllegalArgumentException("Invalid linear replay launch plan");
		}
		if (primitiveWorkPerRay > MAX_LINEAR_REPLAY_PRIMITIVE_WORK) {
			throw new IllegalArgumentException(
					"Linear replay requires " + primitiveWorkPerRay
							+ " primitive-work units per ray, exceeding the watchdog-safe budget of "
							+ MAX_LINEAR_REPLAY_PRIMITIVE_WORK + ". Use BVH traversal or a smaller scene.");
		}
		int workLimitedRays = primitiveWorkPerRay == 0L
				? configuredRayLimit
				: (int) (MAX_LINEAR_REPLAY_PRIMITIVE_WORK / primitiveWorkPerRay);
		int effectiveRayLimit = Math.min(configuredRayLimit, workLimitedRays);
		long chunkCount = ((long) rayCount + effectiveRayLimit - 1L) / effectiveRayLimit;
		if (chunkCount > MAX_LINEAR_REPLAY_CHUNKS) {
			throw new IllegalArgumentException(
					"Linear replay would require " + chunkCount + " watchdog-safe chunks, exceeding the limit of "
							+ MAX_LINEAR_REPLAY_CHUNKS + ". Reduce the ray count or use BVH traversal.");
		}
		return effectiveRayLimit;
	}

	static long linearPrimitiveWorkPerRay(GpuScene scene) {
		long work = SPHERE_REPLAY_WORK * scene.spheres().size();
		work += BOX_REPLAY_WORK * scene.boxes().size();
		work += PLANE_REPLAY_WORK * scene.planes().size();
		work += AFFINE_SPHERE_REPLAY_WORK * scene.affineSpheres().size();
		work += AFFINE_BOX_REPLAY_WORK * scene.affineBoxes().size();
		return work;
	}

	private void initializeIfNeeded() {
		if (initialized || !available) {
			return;
		}
		synchronized (this) {
			if (initialized || !available) {
				return;
			}
			try {
				ensureNativeLibrariesPresent();

				JCudaDriver.setExceptionsEnabled(true);
				JNvrtc.setExceptionsEnabled(true);

				cuInit(0);
				CUdevice device = new CUdevice();
				cuDeviceGet(device, 0);

				int[] major = new int[1];
				int[] minor = new int[1];
				byte[] nameBytes = new byte[256];
				JCudaDriver.cuDeviceGetName(nameBytes, nameBytes.length, device);
				int nameLength = 0;
				while (nameLength < nameBytes.length && nameBytes[nameLength] != 0) {
					nameLength++;
				}
				deviceName = new String(nameBytes, 0, nameLength, StandardCharsets.UTF_8).trim();
				JCudaDriver.cuDeviceGetAttribute(major, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device);
				JCudaDriver.cuDeviceGetAttribute(minor, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device);
				deviceCC = String.format(Locale.ROOT, "compute_%d%d", major[0], minor[0]);

				context = new CUcontext();
				cuCtxCreate(context, 0, device);
				cuCtxSetCurrent(context);
				String ptx = compileToPtx(
						GpuKernelSources.PATH_TRACER, deviceCC, collectMetrics);
				compiledPtxSha256 = sha256(ptx.getBytes(StandardCharsets.UTF_8));
				lastCompiledPtxSha256 = compiledPtxSha256;
				byte[] ptxBytes = (ptx + "\0").getBytes(StandardCharsets.UTF_8);
				module = new CUmodule();
				cuModuleLoadData(module, ptxBytes);

				kernel = new CUfunction();
				cuModuleGetFunction(kernel, module, "renderKernel");
				traceReplayKernel = new CUfunction();
				cuModuleGetFunction(traceReplayKernel, module, "traceReplayKernel");

				hostBuffer = new float[outputElements];
				initialized = true;
			} catch (Throwable t) {
				available = false;
				LOG.log(Level.WARNING, "Failed to initialize CUDA path tracer.", t);
				close();
			}
		}
	}

	private void ensureOutputBuffer() {
		if (dOutput == null) {
			dOutput = new CUdeviceptr();
			cuMemAlloc(dOutput, outputBytes);
		}
	}

	private void ensureTimingEvents() {
		if (kernelStartEvent == null) {
			kernelStartEvent = new CUevent();
			cuEventCreate(kernelStartEvent, 0);
		}
		if (kernelEndEvent == null) {
			kernelEndEvent = new CUevent();
			cuEventCreate(kernelEndEvent, 0);
		}
	}

	private void ensurePhysicalBoundaryEvents(int requiredEvents) {
		if (requiredEvents < 0 || requiredEvents >= MAX_RENDER_LAUNCHES_PER_CALL) {
			throw new IllegalArgumentException(
					"Invalid physical-boundary event count: " + requiredEvents);
		}
		if (physicalBoundaryEvents.length >= requiredEvents) {
			return;
		}
		CUevent[] expanded = new CUevent[requiredEvents];
		System.arraycopy(
				physicalBoundaryEvents, 0, expanded, 0, physicalBoundaryEvents.length);
		for (int index = physicalBoundaryEvents.length; index < expanded.length; index++) {
			expanded[index] = new CUevent();
			cuEventCreate(expanded[index], 0);
		}
		physicalBoundaryEvents = expanded;
	}

	private void uploadScene(GpuScene scene) {
		float[] spheres = scene.sphereData();
		float[] boxes = scene.boxData();
		float[] planes = scene.planeData();
		float[] affineSpheres = scene.affineSphereData();
		float[] affineBoxes = scene.affineBoxData();
		float[] bvhNodeBounds = bvhTraversal ? scene.bvhNodeBoundsData() : new float[0];
		int[] bvhNodeData = bvhTraversal ? scene.bvhNodeData() : new int[0];
		int[] bvhPrimitiveRefs = bvhTraversal ? scene.bvhPrimitiveRefs() : new int[0];
		int[] bvhRootIndices = bvhTraversal ? scene.bvhRootIndices() : new int[0];
		float[] materials = scene.materialData();

		ensureSphereBuffer(spheres.length);
		if (spheres.length > 0) {
			cuMemcpyHtoD(dSpheres, Pointer.to(spheres), (long) spheres.length * Sizeof.FLOAT);
		}

		ensureBoxBuffer(boxes.length);
		if (boxes.length > 0) {
			cuMemcpyHtoD(dBoxes, Pointer.to(boxes), (long) boxes.length * Sizeof.FLOAT);
		}

		ensurePlaneBuffer(planes.length);
		if (planes.length > 0) {
			cuMemcpyHtoD(dPlanes, Pointer.to(planes), (long) planes.length * Sizeof.FLOAT);
		}

		ensureAffineSphereBuffer(affineSpheres.length);
		if (affineSpheres.length > 0) {
			cuMemcpyHtoD(dAffineSpheres, Pointer.to(affineSpheres), (long) affineSpheres.length * Sizeof.FLOAT);
		}

		ensureAffineBoxBuffer(affineBoxes.length);
		if (affineBoxes.length > 0) {
			cuMemcpyHtoD(dAffineBoxes, Pointer.to(affineBoxes), (long) affineBoxes.length * Sizeof.FLOAT);
		}

		ensureBvhNodeBoundsBuffer(bvhNodeBounds.length);
		if (bvhNodeBounds.length > 0) {
			cuMemcpyHtoD(dBvhNodeBounds, Pointer.to(bvhNodeBounds), (long) bvhNodeBounds.length * Sizeof.FLOAT);
		}
		ensureBvhNodeDataBuffer(bvhNodeData.length);
		if (bvhNodeData.length > 0) {
			cuMemcpyHtoD(dBvhNodeData, Pointer.to(bvhNodeData), (long) bvhNodeData.length * Sizeof.INT);
		}
		ensureBvhPrimitiveRefBuffer(bvhPrimitiveRefs.length);
		if (bvhPrimitiveRefs.length > 0) {
			cuMemcpyHtoD(dBvhPrimitiveRefs, Pointer.to(bvhPrimitiveRefs), (long) bvhPrimitiveRefs.length * Sizeof.INT);
		}
		ensureBvhRootIndexBuffer(bvhRootIndices.length);
		if (bvhRootIndices.length > 0) {
			cuMemcpyHtoD(dBvhRootIndices, Pointer.to(bvhRootIndices),
					(long) bvhRootIndices.length * Sizeof.INT);
		}

		ensureMaterialBuffer(materials.length);
		if (materials.length > 0) {
			cuMemcpyHtoD(dMaterials, Pointer.to(materials), (long) materials.length * Sizeof.FLOAT);
		}
	}

	private void ensureSceneResident(GpuScene scene) {
		if (scene == null) {
			throw new IllegalArgumentException("GPU scene must not be null");
		}
		long revision = scene.revision();
		if (residentScene == scene && residentSceneRevision == revision) {
			lastUploadNanos = 0L;
			return;
		}
		long uploadStart = System.nanoTime();
		uploadScene(scene);
		lastUploadNanos = System.nanoTime() - uploadStart;
		residentScene = scene;
		residentSceneRevision = revision;
	}

	private void ensureSphereBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dSpheres == null || sphereCapacity < required) {
			free(dSpheres);
			dSpheres = new CUdeviceptr();
			cuMemAlloc(dSpheres, (long) required * Sizeof.FLOAT);
			sphereCapacity = required;
		}
	}

	private void ensureBoxBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dBoxes == null || boxCapacity < required) {
			free(dBoxes);
			dBoxes = new CUdeviceptr();
			cuMemAlloc(dBoxes, (long) required * Sizeof.FLOAT);
			boxCapacity = required;
		}
	}

	private void ensureFrameStatsBuffer() {
		if (dFrameStats == null) {
			dFrameStats = new CUdeviceptr();
			cuMemAlloc(dFrameStats, (long) FRAME_STATS_COUNT * Long.BYTES);
		}
	}

	private void ensureReplayBuffers(int rayCount) {
		int rayFloats = Math.max(1, rayCount * 6);
		if (dReplayRays == null || replayRayCapacity < rayFloats) {
			free(dReplayRays);
			dReplayRays = new CUdeviceptr();
			cuMemAlloc(dReplayRays, (long) rayFloats * Sizeof.FLOAT);
			replayRayCapacity = rayFloats;
		}
		int hits = Math.max(1, rayCount);
		if (dReplayHitFlags == null || replayHitCapacity < hits) {
			free(dReplayHitFlags);
			free(dReplayHitDistances);
			free(dReplayHitPrimitiveOrders);
			free(dReplayHitMaterialIndices);
			free(dReplayHitNormals);
			dReplayHitFlags = new CUdeviceptr();
			dReplayHitDistances = new CUdeviceptr();
			dReplayHitPrimitiveOrders = new CUdeviceptr();
			dReplayHitMaterialIndices = new CUdeviceptr();
			dReplayHitNormals = new CUdeviceptr();
			cuMemAlloc(dReplayHitFlags, (long) hits * Sizeof.INT);
			cuMemAlloc(dReplayHitDistances, (long) hits * Sizeof.FLOAT);
			cuMemAlloc(dReplayHitPrimitiveOrders, (long) hits * Sizeof.INT);
			cuMemAlloc(dReplayHitMaterialIndices, (long) hits * Sizeof.INT);
			cuMemAlloc(dReplayHitNormals, (long) hits * 3L * Sizeof.FLOAT);
			replayHitCapacity = hits;
		}
	}

	private void ensurePlaneBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dPlanes == null || planeCapacity < required) {
			free(dPlanes);
			dPlanes = new CUdeviceptr();
			cuMemAlloc(dPlanes, (long) required * Sizeof.FLOAT);
			planeCapacity = required;
		}
	}

	private void ensureAffineSphereBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dAffineSpheres == null || affineSphereCapacity < required) {
			free(dAffineSpheres);
			dAffineSpheres = new CUdeviceptr();
			cuMemAlloc(dAffineSpheres, (long) required * Sizeof.FLOAT);
			affineSphereCapacity = required;
		}
	}

	private void ensureAffineBoxBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dAffineBoxes == null || affineBoxCapacity < required) {
			free(dAffineBoxes);
			dAffineBoxes = new CUdeviceptr();
			cuMemAlloc(dAffineBoxes, (long) required * Sizeof.FLOAT);
			affineBoxCapacity = required;
		}
	}

	private void ensureBvhNodeBoundsBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dBvhNodeBounds == null || bvhNodeBoundsCapacity < required) {
			free(dBvhNodeBounds);
			dBvhNodeBounds = new CUdeviceptr();
			cuMemAlloc(dBvhNodeBounds, (long) required * Sizeof.FLOAT);
			bvhNodeBoundsCapacity = required;
		}
	}

	private void ensureBvhNodeDataBuffer(int requiredInts) {
		int required = Math.max(1, requiredInts);
		if (dBvhNodeData == null || bvhNodeDataCapacity < required) {
			free(dBvhNodeData);
			dBvhNodeData = new CUdeviceptr();
			cuMemAlloc(dBvhNodeData, (long) required * Sizeof.INT);
			bvhNodeDataCapacity = required;
		}
	}

	private void ensureBvhPrimitiveRefBuffer(int requiredInts) {
		int required = Math.max(1, requiredInts);
		if (dBvhPrimitiveRefs == null || bvhPrimitiveRefCapacity < required) {
			free(dBvhPrimitiveRefs);
			dBvhPrimitiveRefs = new CUdeviceptr();
			cuMemAlloc(dBvhPrimitiveRefs, (long) required * Sizeof.INT);
			bvhPrimitiveRefCapacity = required;
		}
	}

	private void ensureBvhRootIndexBuffer(int requiredInts) {
		int required = Math.max(1, requiredInts);
		if (dBvhRootIndices == null || bvhRootIndexCapacity < required) {
			free(dBvhRootIndices);
			dBvhRootIndices = new CUdeviceptr();
			cuMemAlloc(dBvhRootIndices, (long) required * Sizeof.INT);
			bvhRootIndexCapacity = required;
		}
	}

	private void ensureMaterialBuffer(int requiredFloats) {
		int required = Math.max(1, requiredFloats);
		if (dMaterials == null || materialCapacity < required) {
			free(dMaterials);
			dMaterials = new CUdeviceptr();
			cuMemAlloc(dMaterials, (long) required * Sizeof.FLOAT);
			materialCapacity = required;
		}
	}

	static String compileToPtx(String source, String computeCapability) throws IOException {
		return compileToPtx(source, computeCapability, false);
	}

	static String compileToPtx(
			String source,
			String computeCapability,
			boolean renderMetrics
	) throws IOException {
		nvrtcProgram program = new nvrtcProgram();
		JNvrtc.nvrtcCreateProgram(program, source, null, 0, null, null);
		try {
			List<String> options = new ArrayList<>();
			options.add("--gpu-architecture=" + (computeCapability == null || computeCapability.isBlank() ? "compute_61" : computeCapability));
			int stackSize = Integer.getInteger("gfxlab.gpu.bvhStackSize", 32);
			if (stackSize < 8 || stackSize > 256) {
				throw new IllegalArgumentException("gfxlab.gpu.bvhStackSize must be between 8 and 256");
			}
			options.add("-DBVH_STACK_SIZE=" + stackSize);
			options.add("-DGFXLAB_RENDER_METRICS=" + (renderMetrics ? "1" : "0"));
			String includePath = detectCudaIncludePath();
			if (includePath != null) {
				options.add("--include-path=" + includePath);
			}
			JNvrtc.nvrtcCompileProgram(program, options.size(), options.toArray(String[]::new));
			String[] ptx = new String[1];
			JNvrtc.nvrtcGetPTX(program, ptx);
			lastCompiledPtxSha256 = sha256(ptx[0].getBytes(StandardCharsets.UTF_8));
			return ptx[0];
		} catch (Exception ex) {
			String[] log = new String[1];
			JNvrtc.nvrtcGetProgramLog(program, log);
			throw new IOException("NVRTC compilation failed:\n" + (log[0] == null ? "" : log[0]), ex);
		} finally {
			JNvrtc.nvrtcDestroyProgram(program);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String detectCudaIncludePath() {
		String[] candidates = {
				"CUDA_PATH",
				"CUDA_PATH_V13_0",
				"CUDA_PATH_V12_9",
				"CUDA_PATH_V12_8",
				"CUDA_PATH_V12_7",
				"CUDA_PATH_V12_6",
				"CUDA_PATH_V12_5",
				"CUDA_PATH_V12_4",
				"CUDA_PATH_V12_3",
				"CUDA_PATH_V12_2",
				"CUDA_PATH_V12_1",
				"CUDA_PATH_V12_0",
				"CUDA_PATH_V11_8"
		};

		for (String env : candidates) {
			String value = System.getenv(env);
			if (value != null && !value.isBlank()) {
				Path include = Path.of(value, "include");
				if (Files.isRegularFile(include.resolve("cuda_runtime.h"))) {
					return include.toString();
				}
			}
		}

		Path base = Path.of("C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA");
		if (Files.isDirectory(base)) {
			try (var versions = Files.list(base)) {
				return versions
						.filter(Files::isDirectory)
						.map(p -> p.resolve("include"))
						.filter(p -> Files.isRegularFile(p.resolve("cuda_runtime.h")))
						.sorted()
						.reduce((first, second) -> second)
						.map(Path::toString)
						.orElse(null);
			} catch (IOException ignored) {
			}
		}
		return null;
	}

	static void ensureNativeLibrariesPresent() throws IOException {
		if (System.getProperty("jcuda.libdir") != null) {
			preloadNativeLibraries(Path.of(System.getProperty("jcuda.libdir")));
			return;
		}

		Path targetDir = Path.of("lib", "jcuda-native");
		Files.createDirectories(targetDir);
		Path nativesJar = Path.of("lib", "jcuda-natives-12.0.0-windows-x86_64.jar");
		if (!Files.isRegularFile(nativesJar)) {
			throw new IOException("JCuda native archive not found: " + nativesJar.toAbsolutePath());
		}

		try (FileSystem fs = FileSystems.newFileSystem(nativesJar, (ClassLoader) null)) {
			for (String name : new String[]{
					"JCudaDriver-12.0.0-windows-x86_64.dll",
					"JCudaRuntime-12.0.0-windows-x86_64.dll",
					"JNvPTXCompiler-12.0.0-windows-x86_64.dll",
					"JNvrtc-12.0.0-windows-x86_64.dll"
			}) {
				Path source = fs.getPath("lib", name);
				Path target = targetDir.resolve(name);
				if (!Files.isRegularFile(target)) {
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}

		System.setProperty("jcuda.libdir", targetDir.toAbsolutePath().toString());
		preloadNativeLibraries(targetDir);
	}

	private static void preloadNativeLibraries(Path directory) throws IOException {
		for (String name : new String[]{
				"JCudaDriver-12.0.0-windows-x86_64.dll",
				"JCudaRuntime-12.0.0-windows-x86_64.dll",
				"JNvPTXCompiler-12.0.0-windows-x86_64.dll",
				"JNvrtc-12.0.0-windows-x86_64.dll"
		}) {
			Path file = directory.resolve(name);
			if (!Files.isRegularFile(file)) {
				throw new IOException("Missing JCuda native library: " + file.toAbsolutePath());
			}
			System.load(file.toAbsolutePath().toString());
		}
	}

	@Override
	public void close() {
		synchronized (this) {
			if (context != null) {
				try {
					cuCtxSetCurrent(context);
				} catch (Throwable ignored) {
				}
			}

			free(dOutput);
			free(dSpheres);
			free(dBoxes);
			free(dPlanes);
			free(dAffineSpheres);
			free(dAffineBoxes);
			free(dBvhNodeBounds);
			free(dBvhNodeData);
			free(dBvhPrimitiveRefs);
			free(dBvhRootIndices);
			free(dMaterials);
			free(dFrameStats);
			free(dReplayRays);
			free(dReplayHitFlags);
			free(dReplayHitDistances);
			free(dReplayHitPrimitiveOrders);
			free(dReplayHitMaterialIndices);
			free(dReplayHitNormals);
			dOutput = null;
			dSpheres = null;
			dBoxes = null;
			dPlanes = null;
			dAffineSpheres = null;
			dAffineBoxes = null;
			dBvhNodeBounds = null;
			dBvhNodeData = null;
			dBvhPrimitiveRefs = null;
			dBvhRootIndices = null;
			dMaterials = null;
			dFrameStats = null;
			dReplayRays = null;
			dReplayHitFlags = null;
			dReplayHitDistances = null;
			dReplayHitPrimitiveOrders = null;
			dReplayHitMaterialIndices = null;
			dReplayHitNormals = null;
			sphereCapacity = 0;
			boxCapacity = 0;
			planeCapacity = 0;
			affineSphereCapacity = 0;
			affineBoxCapacity = 0;
			bvhNodeBoundsCapacity = 0;
			bvhNodeDataCapacity = 0;
			bvhPrimitiveRefCapacity = 0;
			bvhRootIndexCapacity = 0;
			materialCapacity = 0;
			replayRayCapacity = 0;
			replayHitCapacity = 0;
			preparedReplayRayCount = 0;
			preparedReplayRaysPerLaunch = 0;
			residentScene = null;
			residentSceneRevision = Long.MIN_VALUE;

			if (kernelStartEvent != null) {
				cuEventDestroy(kernelStartEvent);
				kernelStartEvent = null;
			}
			if (kernelEndEvent != null) {
				cuEventDestroy(kernelEndEvent);
				kernelEndEvent = null;
			}
			for (CUevent boundaryEvent : physicalBoundaryEvents) {
				if (boundaryEvent != null) {
					cuEventDestroy(boundaryEvent);
				}
			}
			physicalBoundaryEvents = new CUevent[0];
			if (module != null) {
				cuModuleUnload(module);
				module = null;
			}
			if (context != null) {
				cuCtxDestroy(context);
				context = null;
			}
			initialized = false;
		}
	}

	private static void free(CUdeviceptr ptr) {
		if (ptr != null) {
			cuMemFree(ptr);
		}
	}
}
