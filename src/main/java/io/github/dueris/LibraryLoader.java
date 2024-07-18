package io.github.dueris;

import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class LibraryLoader {

	protected void start(Bundler.Provider<String> input) throws IOException {
		start(input.get());
	}

	protected void start(String mcVersion) {
		Path out = Paths.get("libraries");
		if (!out.toFile().exists()) {
			out.toFile().mkdirs();
		}

		String jarFilePath = "cache/vanilla-bundler-" + mcVersion + ".jar";
		String destDirectory = "libraries";
		String targetFolder = "META-INF/libraries/";

		try {
			extractJar(jarFilePath, destDirectory, targetFolder);
			System.out.println("Library extraction completed, linking classpath...");
			try (Stream<Path> stream = Files.walk(out)) {
				stream.filter(p -> !Files.isDirectory(p))
					.filter(p -> p.toString().endsWith(".jar"))
					.forEach(jF -> {
						try {
							Bundler.linkedClassPathUrls.add(jF.toUri().toURL());
						} catch (MalformedURLException e) {
							throw new RuntimeException("Unable to link library jar to classpath!", e);
						}
					});
			}
		} catch (IOException e) {
			System.err.println("An error occurred during library extraction: " + e.getMessage());
		}
	}

	public static void extractJar(String jarFilePath, String destDirectory, String targetFolder) throws IOException {
		File destDir = new File(destDirectory);
		if (!destDir.exists()) {
			destDir.mkdirs();
		}

		try (JarFile jarFile = new JarFile(jarFilePath)) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.getName().startsWith(targetFolder)) {
					File entryDest = new File(destDir, entry.getName().substring(targetFolder.length()));

					if (entry.isDirectory()) {
						entryDest.mkdirs();
					} else {
						File parent = entryDest.getParentFile();
						if (!parent.exists()) {
							parent.mkdirs();
						}

						try (InputStream in = jarFile.getInputStream(entry);
							 OutputStream out = new FileOutputStream(entryDest)) {
							copy(in, out);
						}
					}
				}
			}
		}
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int len;
		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
	}
}
