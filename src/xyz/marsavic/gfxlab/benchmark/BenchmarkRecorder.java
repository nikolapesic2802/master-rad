package xyz.marsavic.gfxlab.benchmark;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;

/**
 * Per-frame benchmarking helper.
 * Each row corresponds to one logged frame; the row index is the frame id.
 */
public class BenchmarkRecorder implements AutoCloseable {

	public record FrameSample(
			long count,
			double totalMs,
			Double kernelMs,
			Double copyMs,
			int width,
			int height
	) {
		String toCsv() {
			return String.format(Locale.ROOT, "%d,%.4f,%s,%s,%d,%d%n",
					count,
					totalMs,
					kernelMs == null ? "" : String.format(Locale.ROOT, "%.4f", kernelMs),
					copyMs == null ? "" : String.format(Locale.ROOT, "%.4f", copyMs),
					width,
					height);
		}
	}

	private final Path outputFile;
	private final int flushEvery;
	private final String cpuInfo;
	private final String gpuInfo;
	private final String version;
	private final String sceneName;
	private final String configuration;
	private final List<FrameSample> buffer = new ArrayList<>();
	private final UsageSampler usageSampler;
	private boolean headerWritten;

	public BenchmarkRecorder(Path outputFile, int flushEvery, String cpuInfo, String gpuInfo, String version, String sceneName) {
		this(outputFile, flushEvery, cpuInfo, gpuInfo, version, sceneName, "");
	}

	public BenchmarkRecorder(Path outputFile, int flushEvery, String cpuInfo, String gpuInfo, String version, String sceneName,
	                         String configuration) {
		this.outputFile = outputFile;
		this.flushEvery = Math.max(1, flushEvery);
		this.cpuInfo = cpuInfo == null ? "" : cpuInfo;
		this.gpuInfo = gpuInfo == null ? "" : gpuInfo;
		this.version = version == null ? "" : version;
		this.sceneName = sceneName == null ? "" : sceneName;
		this.configuration = configuration == null ? "" : configuration;
		this.usageSampler = UsageSampler.maybeStart();
	}

	public synchronized void record(long countPerFrame, int width, int height, long totalNanos, Long kernelNanos, Long copyNanos) {
		double totalMs = nanosToMs(totalNanos);
		Double kernelMs = kernelNanos == null ? null : nanosToMs(kernelNanos);
		Double copyMs = copyNanos == null ? null : nanosToMs(copyNanos);

		FrameSample sample = new FrameSample(countPerFrame, totalMs, kernelMs, copyMs, width, height);
		buffer.add(sample);

		printSample(sample);
		if (outputFile != null && buffer.size() >= flushEvery) {
			flush();
		}
	}

	private void printSample(FrameSample sample) {
		String kernelPart = sample.kernelMs == null ? "" : String.format(Locale.ROOT, ", kernel=%.3f ms", sample.kernelMs);
		String copyPart = sample.copyMs == null ? "" : String.format(Locale.ROOT, ", copy=%.3f ms", sample.copyMs);
		System.out.printf(Locale.ROOT,
				"[BENCH]: count=%d total=%.3f ms%s%s (%dx%d)%n",
				sample.count,
				sample.totalMs,
				kernelPart,
				copyPart,
				sample.width,
				sample.height);
	}

	private double nanosToMs(long nanos) {
		return nanos / 1_000_000.0;
	}

	public synchronized void flush() {
		if (outputFile == null || buffer.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(outputFile.getParent());
			if (!headerWritten) {
				String header = "# cpu=" + cpuInfo + "; gpu=" + gpuInfo + "; version=" + version + "; scene=" + sceneName
						+ "; config=" + configuration + "\n"
						+ "count,totalMs,kernelMs,copyMs,width,height\n";
				Files.writeString(outputFile, header, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				headerWritten = true;
			}
			StringBuilder sb = new StringBuilder();
			for (FrameSample sample : buffer) {
				sb.append(sample.toCsv());
			}
			Files.writeString(outputFile, sb.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			buffer.clear();
		} catch (IOException ex) {
			System.err.println("Failed to write benchmark data: " + ex.getMessage());
		}
	}

	@Override
	public void close() {
		UsageSummary summary = usageSampler == null ? null : usageSampler.stopAndGetSummary();
		flush();
		appendUsageSummary(summary);
	}

	private void appendUsageSummary(UsageSummary summary) {
		if (summary == null || outputFile == null) {
			return;
		}
		String cpuPart = summary.avgProcessCpuPercent().isPresent()
				? String.format(Locale.ROOT, "avgProcessCpu=%.1f%%", summary.avgProcessCpuPercent().getAsDouble())
				: "avgProcessCpu=";
		String gpuPart = summary.avgGpuPercent().isPresent()
				? String.format(Locale.ROOT, "avgGpu=%.1f%%", summary.avgGpuPercent().getAsDouble())
				: "avgGpu=";
		String ramPart = summary.avgProcessRamMb().isPresent()
				? String.format(Locale.ROOT, "avgProcessRamMb=%.1f", summary.avgProcessRamMb().getAsDouble())
				: "avgProcessRamMb=";
		String gpuMemPart = summary.avgGpuMemMb().isPresent()
				? String.format(Locale.ROOT, "avgGpuMemMb=%.1f", summary.avgGpuMemMb().getAsDouble())
				: "avgGpuMemMb=";
		String line = String.format(Locale.ROOT,
				"# usage %s; %s; %s; %s; samples=%d; intervalMs=%d%n",
				cpuPart,
				gpuPart,
				ramPart,
				gpuMemPart,
				summary.sampleCount(),
				summary.intervalMs());
		try {
			Files.writeString(outputFile, line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ex) {
			System.err.println("Failed to write benchmark usage summary: " + ex.getMessage());
		}
	}

	private record UsageSummary(OptionalDouble avgProcessCpuPercent,
	                            OptionalDouble avgGpuPercent,
	                            OptionalDouble avgProcessRamMb,
	                            OptionalDouble avgGpuMemMb,
	                            int sampleCount,
	                            int intervalMs) { }

	private static final class UsageSampler implements Runnable {
		private static final int DEFAULT_INTERVAL_MS = 1000;
		private static final double BYTES_TO_MB = 1.0 / (1024.0 * 1024.0);
		private final int intervalMs;
		private final OperatingSystemMXBean osBean;
		private final Thread thread;
		private volatile boolean running = true;
		private double cpuSum;
		private double gpuSum;
		private double processRamSumMb;
		private double gpuMemSumMb;
		private int cpuSamples;
		private int gpuSamples;
		private int processRamSamples;
		private int gpuMemSamples;
		private final long pid;
		private final boolean isWindows;

		private UsageSampler(int intervalMs, OperatingSystemMXBean osBean) {
			this.intervalMs = Math.max(200, intervalMs);
			this.osBean = osBean;
			this.thread = new Thread(this, "benchmark-usage-sampler");
			this.thread.setDaemon(true);
			this.pid = ProcessHandle.current().pid();
			String osName = System.getProperty("os.name", "");
			this.isWindows = osName.toLowerCase(Locale.ROOT).contains("win");
		}

		static UsageSampler maybeStart() {
			boolean enabled = Boolean.parseBoolean(System.getProperty("gfxlab.trackUsage", "true"));
			if (!enabled) {
				return null;
			}
			OperatingSystemMXBean osBean = null;
			try {
				var bean = ManagementFactory.getOperatingSystemMXBean();
				if (bean instanceof OperatingSystemMXBean casted) {
					osBean = casted;
				}
			} catch (Exception ignored) {
			}
			int intervalMs = parseInterval(System.getProperty("gfxlab.usageSampleMs"));
			UsageSampler sampler = new UsageSampler(intervalMs, osBean);
			sampler.thread.start();
			return sampler;
		}

		UsageSummary stopAndGetSummary() {
			running = false;
			thread.interrupt();
			try {
				thread.join(TimeUnit.SECONDS.toMillis(2));
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			OptionalDouble avgCpu = cpuSamples > 0 ? OptionalDouble.of(cpuSum / cpuSamples) : OptionalDouble.empty();
			OptionalDouble avgGpu = gpuSamples > 0 ? OptionalDouble.of(gpuSum / gpuSamples) : OptionalDouble.empty();
			OptionalDouble avgProcessRam = processRamSamples > 0 ? OptionalDouble.of(processRamSumMb / processRamSamples) : OptionalDouble.empty();
			OptionalDouble avgGpuMem = gpuMemSamples > 0 ? OptionalDouble.of(gpuMemSumMb / gpuMemSamples) : OptionalDouble.empty();
			int sampleCount = Math.max(Math.max(cpuSamples, gpuSamples), Math.max(processRamSamples, gpuMemSamples));
			return new UsageSummary(avgCpu, avgGpu, avgProcessRam, avgGpuMem, sampleCount, intervalMs);
		}

		@Override
		public void run() {
			while (running) {
				sampleOnce();
				try {
					Thread.sleep(intervalMs);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}

		private void sampleOnce() {
			if (osBean != null) {
				double cpuLoad = osBean.getProcessCpuLoad();
				if (cpuLoad >= 0.0) {
					cpuSum += cpuLoad * 100.0;
					cpuSamples++;
				}
			}
			Double processRam = readProcessRamMb();
			if (processRam != null && processRam >= 0.0) {
				processRamSumMb += processRam;
				processRamSamples++;
			}

			GpuStats gpuStats = readGpuStats();
			if (gpuStats != null) {
				if (gpuStats.utilization() != null && gpuStats.utilization() >= 0.0) {
					gpuSum += gpuStats.utilization();
					gpuSamples++;
				}
				if (gpuStats.memoryUsedMb() != null && gpuStats.memoryUsedMb() >= 0.0) {
					gpuMemSumMb += gpuStats.memoryUsedMb();
					gpuMemSamples++;
				}
			}
		}

		private Double readProcessRamMb() {
			Long rssBytes = readProcessWorkingSetBytes();
			if (rssBytes != null && rssBytes > 0) {
				return rssBytes * BYTES_TO_MB;
			}
			if (osBean != null) {
				long committed = osBean.getCommittedVirtualMemorySize();
				if (committed > 0) {
					return committed * BYTES_TO_MB;
				}
			}
			return null;
		}

		private Long readProcessWorkingSetBytes() {
			if (!isWindows) {
				return null;
			}
			ProcessBuilder builder = new ProcessBuilder(
					"powershell",
					"-NoProfile",
					"-Command",
					"(Get-Process -Id " + pid + ").WorkingSet64");
			builder.redirectErrorStream(true);
			try {
				Process process = builder.start();
				String line = null;
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					line = reader.readLine();
				}
				process.waitFor(2, TimeUnit.SECONDS);
				if (line == null) {
					return null;
				}
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					return null;
				}
				return Long.parseLong(trimmed);
			} catch (Exception ignored) {
				return null;
			}
		}

		private static GpuStats readGpuStats() {
			ProcessBuilder builder = new ProcessBuilder(
					"nvidia-smi",
					"--query-gpu=utilization.gpu,memory.used",
					"--format=csv,noheader,nounits");
			builder.redirectErrorStream(true);
			try {
				Process process = builder.start();
				double utilSum = 0.0;
				double memSum = 0.0;
				int utilCount = 0;
				int memCount = 0;
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						String trimmed = line.trim();
						if (trimmed.isEmpty()) continue;
						String[] parts = trimmed.split(",");
						if (parts.length >= 1) {
							Double util = parseDouble(parts[0]);
							if (util != null) {
								utilSum += util;
								utilCount++;
							}
						}
						if (parts.length >= 2) {
							Double mem = parseDouble(parts[1]);
							if (mem != null) {
								memSum += mem;
								memCount++;
							}
						}
					}
				}
				process.waitFor(2, TimeUnit.SECONDS);
				if (!process.isAlive() && process.exitValue() != 0 || (utilCount == 0 && memCount == 0)) {
					return null;
				}
				Double utilAvg = utilCount > 0 ? utilSum / utilCount : null;
				Double memAvg = memCount > 0 ? memSum / memCount : null;
				return new GpuStats(utilAvg, memAvg);
			} catch (Exception ignored) {
				return null;
			}
		}

		private static Double parseDouble(String raw) {
			if (raw == null) {
				return null;
			}
			String trimmed = raw.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			try {
				return Double.parseDouble(trimmed);
			} catch (NumberFormatException ex) {
				return null;
			}
		}

		private static int parseInterval(String raw) {
			if (raw == null || raw.isBlank()) {
				return DEFAULT_INTERVAL_MS;
			}
			try {
				return Integer.parseInt(raw.trim());
			} catch (NumberFormatException ex) {
				return DEFAULT_INTERVAL_MS;
			}
		}
	}

	private record GpuStats(Double utilization, Double memoryUsedMb) { }
}
