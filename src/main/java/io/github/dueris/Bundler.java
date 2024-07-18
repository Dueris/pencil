package io.github.dueris;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class Bundler {
	protected static List<URL> linkedClassPathUrls = new ArrayList<>();

	public static void main(String[] argv) {
		(new Bundler()).run(argv);
	}

	private void run(String[] argv) {
		try {
			String defaultMainClassName = this.readResource("main-class", BufferedReader::readLine);
			String mainClassName = System.getProperty("bundlerMainClass", defaultMainClassName);
			String repoDir = System.getProperty("bundlerRepoDir", "");
			Path outputDir = Paths.get(repoDir);
			Files.createDirectories(outputDir);
			Provider<String> versionProvider = () -> {
				InputStream inputStream = Bundler.class.getResourceAsStream("/version.json");

				if (inputStream == null) {
					throw new IOException("Unable to locate version resource!");
				}

				String jsonContent;
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						sb.append(line);
					}
					jsonContent = sb.toString();
				}

				return jsonContent.split("\"id\": \"")[1].split("\"")[0];
			};
			new PatcherBuilder().start(versionProvider);
			new LibraryLoader().start(versionProvider);
			if (mainClassName == null || mainClassName.isEmpty()) {
				System.out.println("Empty main class specified, exiting");
				System.exit(0);
			}

			ClassLoader maybePlatformClassLoader = this.getClass().getClassLoader().getParent();
			URLClassLoader classLoader = new URLClassLoader(linkedClassPathUrls.toArray(new URL[0]), maybePlatformClassLoader);
			System.out.println("Starting " + mainClassName);
			Thread runThread = new Thread(() -> {
				try {
					Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
					MethodHandle mainHandle = MethodHandles.lookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class)).asFixedArity();
					mainHandle.invoke((Object)argv);
				} catch (Throwable var5x) {
					Bundler.Thrower.INSTANCE.sneakyThrow(var5x);
				}
			}, "ServerMain");
			runThread.setContextClassLoader(classLoader);
			runThread.start();
		} catch (Exception var10) {
			var10.printStackTrace(System.out);
			System.out.println("Failed to extract server libraries, exiting");
		}
	}

	private <T> T readResource(String resource, Bundler.ResourceParser<T> parser) throws Exception {
		String fullPath = "/META-INF/" + resource;

		Object var5;
		try (InputStream is = this.getClass().getResourceAsStream(fullPath)) {
			if (is == null) {
				throw new IllegalStateException("Resource " + fullPath + " not found");
			}

			var5 = parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
		}

		return (T)var5;
	}

	@FunctionalInterface
	private interface ResourceParser<T> {
		T parse(BufferedReader var1) throws Exception;
	}

	private static class Thrower<T extends Throwable> {
		private static final Bundler.Thrower<RuntimeException> INSTANCE = new Bundler.Thrower<>();

		public void sneakyThrow(Throwable exception) throws T {
			throw new RuntimeException(exception);
		}
	}

	public interface Provider<T> {
		T get() throws IOException;
	}
}