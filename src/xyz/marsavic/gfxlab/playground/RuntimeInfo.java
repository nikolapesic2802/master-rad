package xyz.marsavic.gfxlab.playground;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class RuntimeInfo {
	private RuntimeInfo() {
	}

	static String cpuInfo() {
		String os = System.getProperty("os.name", "unknown");
		String arch = System.getProperty("os.arch", "unknown");
		String osv = System.getProperty("os.version", "unknown");
		String cpuName = detectCpuName().orElseGet(() ->
				Stream.of(
								System.getenv("PROCESSOR_IDENTIFIER"),
								System.getenv("PROCESSOR_NAME"),
								System.getProperty("sun.cpu.isalist"))
						.filter(s -> s != null && !s.isBlank())
						.findFirst()
						.orElse("cpu-unknown"));
		int cores = Runtime.getRuntime().availableProcessors();
		return cpuName + " | " + os + " " + arch + " osv=" + osv + " cores=" + cores;
	}

	static String gpuInfo() {
		String raw = execFirstLine("nvidia-smi", "--query-gpu=name,driver_version,memory.total", "--format=csv,noheader");
		if (raw == null || raw.isBlank()) {
			return "gpu-unavailable";
		}
		return raw.trim();
	}

	private static Optional<String> detectCpuName() {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			String name = execFirstLine("powershell", "-Command", "Get-CimInstance Win32_Processor | Select-Object -ExpandProperty Name | Select-Object -First 1");
			if (name != null && !name.isBlank()) return Optional.of(name.trim());
			name = execFirstLine("wmic", "cpu", "get", "Name");
			if (name != null && !name.isBlank() && !name.toLowerCase(Locale.ROOT).startsWith("name")) {
				return Optional.of(name.trim());
			}
		} else {
			String name = execFirstLine("bash", "-lc", "cat /proc/cpuinfo | grep -m1 'model name' | cut -d: -f2");
			if (name != null && !name.isBlank()) {
				return Optional.of(name.trim());
			}
			name = execFirstLine("sysctl", "-n", "machdep.cpu.brand_string");
			if (name != null && !name.isBlank()) {
				return Optional.of(name.trim());
			}
		}
		return Optional.empty();
	}

	private static String execFirstLine(String... command) {
		try {
			Process p = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			p.waitFor(2, TimeUnit.SECONDS);
			try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = br.readLine()) != null) {
					if (!line.isBlank()) {
						return line;
					}
				}
			}
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		} catch (IOException ignored) {
		}
		return null;
	}
}
