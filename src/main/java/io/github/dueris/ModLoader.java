package io.github.dueris;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.HttpResponseException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ModLoader {
	public static Map<JarFile, JsonObject> META_DATA = new HashMap<>();

	public void start(Bundler.Provider<String> input) throws IOException {
		start(input.get());
	}

	public void start(String mcVersion) {
		Path modsDir = Paths.get("mods");
		if (!modsDir.toFile().exists()) {
			modsDir.toFile().mkdirs();
		}

		try {
			Files.walk(modsDir)
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".jar"))
				.forEach(this::processJarFile);
		} catch (IOException e) {
			throw new RuntimeException("Unable to parse mods directory!", e);
		}
	}

	private void processJarFile(Path jarPath) {
		try (JarFile jarFile = new JarFile(jarPath.toFile())) {
			JarEntry entry = jarFile.getJarEntry("plugin.json");

			if (entry != null) {
				try (InputStream inputStream = jarFile.getInputStream(entry)) {
					Gson gson = new Gson();
					JsonObject jsonObject = gson.fromJson(new InputStreamReader(inputStream), JsonObject.class);
					META_DATA.put(jarFile, jsonObject);

					if (jsonObject.has("libraries")) {
						jsonObject.getAsJsonArray("libraries").forEach(element -> {
							if (element.isJsonObject()) {
								JsonObject library = element.getAsJsonObject();
								String repository = library.get("repository").getAsString();
								String dependency = library.get("dependency").getAsString();
								try {
									String[] dependencyParts = dependency.split(":");
									if (dependencyParts.length != 3) {
										throw new IllegalArgumentException("Invalid dependency format. Expected format: groupId:artifactId:version");
									}

									String groupId = dependencyParts[0];
									String artifactId = dependencyParts[1];
									String version = dependencyParts[2];

									File lib = downloadJar(repository, groupId, artifactId, version, "libraries/mods");
									if (lib != null)
										Bundler.linkedClassPathUrls.add(lib.toPath().toUri().toURL());
								} catch (Exception e) {
									throw new RuntimeException(e);
								}
							}
						});
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static File downloadJar(String repoUrl, String groupId, String artifactId, String version, String outputDir) throws Exception {
		String jarUrl = String.format("%s/%s/%s/%s/%s-%s.jar",
			repoUrl,
			groupId.replace('.', '/'),
			artifactId,
			version,
			artifactId,
			version);

		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpGet request = new HttpGet(URI.create(jarUrl));
			try (CloseableHttpResponse response = httpClient.execute(request)) {
				if (response.getCode() != 200) {
					throw new HttpResponseException(response.getCode(), "Failed to download library: " + jarUrl);
				}
				HttpEntity entity = response.getEntity();
				if (entity != null) {
					File outputFile = new File(outputDir, artifactId + "-" + version + ".jar");
					if (!outputFile.exists()) {
						outputFile.getParentFile().mkdirs();
						outputFile.createNewFile();
					}
					try (InputStream inputStream = entity.getContent();
						 FileOutputStream outputStream = new FileOutputStream(outputFile)) {
						byte[] buffer = new byte[1024];
						int bytesRead;
						while ((bytesRead = inputStream.read(buffer)) != -1) {
							outputStream.write(buffer, 0, bytesRead);
						}
					}
					return outputFile;
				}
			}
		}
		return null;
	}

}
