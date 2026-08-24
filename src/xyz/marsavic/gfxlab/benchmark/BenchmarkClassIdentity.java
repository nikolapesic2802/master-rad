package xyz.marsavic.gfxlab.benchmark;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Recomputes the exact compiled-class directory identity used by benchmark artifacts. */
public final class BenchmarkClassIdentity {
	private BenchmarkClassIdentity() { }

	public static void main(String[] args) throws IOException {
		if (args.length != 0) {
			throw new IllegalArgumentException("This command accepts no arguments");
		}
		System.out.println(recompute(BenchmarkClassIdentity.class));
	}

	public static String requireLiveIdentity(Class<?> anchor, String claimed) throws IOException {
		if (claimed == null || !claimed.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Malformed benchmark compiled-classes assertion");
		}
		String actual = recompute(anchor);
		if (!actual.equals(claimed)) {
			throw new IllegalStateException("benchmark compiled classes differ from their assertion");
		}
		return actual;
	}

	public static String recompute(Class<?> anchor) throws IOException {
		if (anchor == null || anchor.getProtectionDomain() == null
				|| anchor.getProtectionDomain().getCodeSource() == null) {
			throw new IllegalArgumentException("benchmark class identity needs a code-source anchor");
		}
		Path root;
		try {
			root = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI())
					.toAbsolutePath().normalize();
		} catch (URISyntaxException failure) {
			throw new IllegalArgumentException("Invalid benchmark class code-source URI", failure);
		}
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("benchmark execution requires a class directory");
		}
		List<Path> files;
		try (var walk = Files.walk(root)) {
			files = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> path.getFileName().toString().endsWith(".class"))
					.sorted(Comparator.comparing(path -> relative(root, path))).toList();
		}
		if (files.isEmpty()) throw new IllegalStateException("benchmark class directory is empty");
		MessageDigest digest = sha256();
		for (Path file : files) {
			String name = relative(root, file);
			digest.update(name.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(Files.readAllBytes(file));
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String relative(Path root, Path file) {
		String value = root.relativize(file).toString().replace('\\', '/');
		if (value.isEmpty() || value.startsWith("/") || value.contains("../")) {
			throw new IllegalStateException("Invalid benchmark class relative path");
		}
		return value;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}
}
