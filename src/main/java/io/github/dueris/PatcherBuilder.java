package io.github.dueris;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PatcherBuilder {

	protected void start(Getter<String> input) throws IOException {
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

			Path path1 = Paths.get("cache/vanilla-" + mcVersion + ".jar");
			Path directoryPath = path1.getParent();
			if (!Files.exists(directoryPath)) {
				Files.createDirectories(directoryPath);
			}

			if (!path1.toFile().exists()) {
				System.out.println("Downloading vanilla jar...");

				try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
					 FileOutputStream fileOutputStream = new FileOutputStream("cache/vanilla-" + mcVersion + ".jar")) {

					byte[] dataBuffer = new byte[1024];
					int bytesRead;
					while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
						fileOutputStream.write(dataBuffer, 0, bytesRead);
					}

				} finally {
					connection.disconnect();
				}
			}

		} catch (IOException e) {
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

	public interface Getter<T> {
		T get() throws IOException;
	}
}
