package io.github.dueris;

import io.sigpipe.jbsdiff.InvalidHeaderException;
import io.sigpipe.jbsdiff.Patch;
import org.apache.commons.compress.compressors.CompressorException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PatcherBuilder {

	protected void start(Bundler.Provider<String> input) throws IOException {
		start(input.get());
	}

	protected void start(String mcVersion) {
		File versionsDir = Paths.get("versions").toFile();
		if (!versionsDir.exists()) {
			versionsDir.mkdirs();
		}

		if (mcVersion == null) throw new RuntimeException("Provided Minecraft version was null!");
		System.out.println("Loading Minecraft version: " + mcVersion);

		try {
			String resultUrl = null;

			try (InputStream inputStream = PatcherBuilder.class.getResourceAsStream("/data/versions.ver");
				 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

				String line;
				while ((line = reader.readLine()) != null) {
					if (line.contains(mcVersion)) {
						String[] parts = line.split("\\|\\|");
						if (parts.length == 2) {
							resultUrl = parts[1].trim();
							break;
						}
					}
				}

			} catch (Exception e) {
				e.printStackTrace();
			}

			if (resultUrl == null) throw new FileNotFoundException("Unable to locate vanilla server download url! Corrupted download?");

			URL url = new URL(resultUrl);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");

			Path path1 = Paths.get("cache/vanilla-bundler-" + mcVersion + ".jar");
			Path directoryPath = path1.getParent();
			if (!Files.exists(directoryPath)) {
				Files.createDirectories(directoryPath);
			}

			if (!path1.toFile().exists()) {
				System.out.println("Downloading vanilla jar...");

				try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
					 FileOutputStream fileOutputStream = new FileOutputStream("cache/vanilla-bundler-" + mcVersion + ".jar")) {

					byte[] dataBuffer = new byte[1024];
					int bytesRead;
					while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
						fileOutputStream.write(dataBuffer, 0, bytesRead);
					}

				} finally {
					connection.disconnect();
				}
			}

			try (JarFile jarFile = new JarFile(path1.toFile())) {
				JarEntry entry = jarFile.getJarEntry("META-INF/versions/" + mcVersion + "/server-" + mcVersion + ".jar");

				if (entry == null) {
					System.out.println("Entry not found in the JAR file.");
					return;
				}

				try (InputStream inputStream = jarFile.getInputStream(entry)) {
					Files.copy(inputStream, Paths.get("cache/vanilla-" + mcVersion + ".jar"), StandardCopyOption.REPLACE_EXISTING);
					System.out.println("Entry extracted successfully.");
				}
			}

			File patch = extractResource("/versions/patch.patch", "cache");
			File out = Paths.get("versions/" + mcVersion + "/pencil-" + mcVersion + ".jar").toFile();
			if (!out.getParentFile().exists()) {
				out.getParentFile().mkdirs();
			}

			Patch.patch(
				new FileInputStream("cache/vanilla-" + mcVersion + ".jar").readAllBytes(),
				new FileInputStream(patch).readAllBytes(),
				new FileOutputStream(out)
			);

			if (!out.exists()) {
				throw new RuntimeException("Version file was not found after patching!");
			}

			Bundler.linkedClassPathUrls.add(out.toPath().toUri().toURL());
		} catch (IOException | CompressorException | InvalidHeaderException e) {
			throw new RuntimeException("Unable to build patched jar!", e);
		}
	}

	private File extractResource(String resourcePath, String outputDir) throws IOException {
		try (InputStream resourceStream = PatcherBuilder.class.getResourceAsStream(resourcePath)) {
			if (resourceStream == null) {
				throw new IOException("Resource not found: " + resourcePath);
			}

			Path outputDirectory = Path.of(outputDir);
			if (Files.notExists(outputDirectory)) {
				Files.createDirectories(outputDirectory);
			}

			Path outputPath = outputDirectory.resolve(Path.of(resourcePath).getFileName());
			File outputFile = outputPath.toFile();

			try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
				resourceStream.transferTo(outputStream);
			}

			return outputFile;
		}
	}
}
