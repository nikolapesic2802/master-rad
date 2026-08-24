package xyz.marsavic.gfxlab.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class EvidenceFiles {
	private static final String SHA256_LEDGER = "SHA256SUMS.txt";

	private EvidenceFiles() {
	}

	static Path requireCreateNewOutput(Path projectRoot, Path output) throws IOException {
		Path root = projectRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
		Path normalized = output.toAbsolutePath().normalize();
		if (normalized.equals(root) || !normalized.startsWith(root)
				|| normalized.getParent() == null
				|| !Files.isDirectory(normalized.getParent(), LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(normalized.getParent())
				|| Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("evidence output must be a create-new repository child");
		}
		return normalized;
	}

	static void requireUnattempted(Path root, Path target) throws IOException {
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("evidence output already exists");
		}
		String prefix = target.getFileName() + ".attempt-";
		try (var stream = Files.list(root)) {
			if (stream.anyMatch(path -> path.getFileName().toString().startsWith(prefix))) {
				throw new IllegalStateException("a prior failed attempt closes this evidence root");
			}
		}
	}

	static void writeNew(Path path, byte[] bytes) throws IOException {
		Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	static void writeNew(Path path, String text) throws IOException {
		Files.writeString(path, text, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	static void writeAtomicNew(Path path, byte[] bytes) throws IOException {
		Path partial = path.resolveSibling(path.getFileName() + ".partial-" + UUID.randomUUID());
		writeNew(partial, bytes);
		try {
			moveAtomic(partial, path);
		} catch (IOException | RuntimeException failure) {
			Files.deleteIfExists(partial);
			throw failure;
		}
	}

	static void moveAtomic(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw new IllegalStateException("atomic evidence publication unavailable", unsupported);
		}
	}

	static void writeSha256Ledger(Path directory) throws Exception {
		List<Path> files;
		try (var stream = Files.list(directory)) {
			files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> !path.getFileName().toString().equals(SHA256_LEDGER))
					.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
		}
		StringBuilder sums = new StringBuilder();
		for (Path file : files) {
			sums.append(sha256(Files.readAllBytes(file))).append("  ")
					.append(file.getFileName()).append('\n');
		}
		writeNew(directory.resolve(SHA256_LEDGER), sums.toString().getBytes(StandardCharsets.UTF_8));
	}

	static Set<String> verifySha256Ledger(Path directory) throws Exception {
		Map<String, String> entries = readSha256Ledger(directory.resolve(SHA256_LEDGER));
		verifyHashes(directory, entries);
		return Set.copyOf(entries.keySet());
	}

	static void verifySha256Ledger(Path directory, Set<String> requiredNames) throws Exception {
		Path ledger = directory.resolve(SHA256_LEDGER);
		if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(ledger)) {
			throw new IllegalStateException("missing regular evidence SHA256SUMS");
		}
		Map<String, String> entries = readSha256Ledger(ledger);
		if (!entries.keySet().equals(requiredNames)) {
			throw new IllegalStateException("evidence SHA256SUMS inventory differs");
		}
		verifyHashes(directory, entries);
	}

	static void verifyDirectory(Path directory, Set<String> expected) throws IOException {
		verifyDirectory(directory, expected, path -> false, false);
	}

	static void verifyDirectory(
			Path directory, Set<String> expected, Predicate<Path> ignored
	) throws IOException {
		verifyDirectory(directory, expected, ignored, false);
	}

	static void verifyRegularFileDirectory(Path directory, Set<String> expected)
			throws IOException {
		verifyDirectory(directory, expected, path -> false, true);
	}

	static void verifyRegularFileDirectory(
			Path directory, Set<String> expected, Predicate<Path> ignored
	) throws IOException {
		verifyDirectory(directory, expected, ignored, true);
	}

	private static void verifyDirectory(
			Path directory, Set<String> expected, Predicate<Path> ignored,
			boolean requireRegularFiles
	) throws IOException {
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(directory)) {
			throw new IllegalStateException("missing or noncanonical evidence directory");
		}
		Set<String> names = new HashSet<>();
		try (var stream = Files.list(directory)) {
			for (Path path : stream.toList()) {
				if (ignored.test(path)) continue;
				String name = path.getFileName().toString();
				if (requireRegularFiles && expected.contains(name)
						&& (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(path))) {
					throw new IllegalStateException("evidence artifact is not a regular file");
				}
				names.add(name);
			}
		}
		if (!names.equals(expected)) {
			throw new IllegalStateException("evidence artifact inventory differs");
		}
	}

	static String json(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	static boolean isSha256(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	static String sha256(byte[] bytes) {
		return BenchmarkProtocol.sha256(bytes);
	}

	private static Map<String, String> readSha256Ledger(Path ledger) throws IOException {
		Map<String, String> entries = new HashMap<>();
		for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
			String[] fields = line.split("  ", -1);
			if (fields.length != 2 || !isSha256(fields[0])
					|| entries.put(fields[1], fields[0]) != null) {
				throw new IllegalStateException("malformed evidence SHA256SUMS");
			}
		}
		return entries;
	}

	private static void verifyHashes(Path directory, Map<String, String> entries)
			throws IOException {
		for (Map.Entry<String, String> entry : entries.entrySet()) {
			Path file = directory.resolve(entry.getKey()).normalize();
			if (!file.startsWith(directory)
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					|| !sha256(Files.readAllBytes(file)).equals(entry.getValue())) {
				throw new IllegalStateException("evidence artifact hash differs: " + entry.getKey());
			}
		}
	}
}
