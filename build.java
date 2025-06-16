import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/// Custom build script
interface build {
	final boolean build_debug = get_build_debug_environment_variable();
	final Console cli = System.console();
	final String[] no_params = new String[0];
	final String graalvm_version = "24.0.1";
	// format args: version, version, os, arch, archive format
	final String graalvm_download_url = "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-%s/graalvm-community-jdk-%s_%s-%s_bin.%s";
	final Path graalvm_directory = Path.of(".graalvm");
	final String jresolve_version = "v2025.02.15";
	// format args: version
	final String jresolve_download_url = "https://github.com/bowbahdoe/jresolve-cli/releases/download/%s/jresolve.jar";
	final Path jresolve_directory = Path.of(".jresolve");
	final String[] empty_string_array = new String[0];

	public static
	void main(String... arguments)
	throws NoReturn
	{
		final var start_time = System.nanoTime();
		var command = parse(arguments);
		try {
			var death_code = command.execute();
			final var end_time = System.nanoTime();
			final var duration = Duration.ofNanos(start_time - end_time);
			final var command_name = bold(command.name());
				switch (death_code) {
					case SUCCESS -> {
						dbg(
							"Command %s finished successfully in %s!%n",
							command_name,
							italic(duration.toString())
						);
						throw die(death_code);
				}
				case INVALID_COMMAND -> throw die(
					death_code,
					"Given command %s does not exist.%n",
					command_name
				);
				case MISSING_PARAMETER -> throw die(
					death_code,
					"Given command %s needs at least one parameter.%n",
					command_name
				);
				case UNKNOWN_COMMAND_ARGUMENTS -> throw die(
					death_code,
					"Unknown arguments %s to command %s.%n",
					italic(Arrays.toString(command.parameters())),
					command_name
				);
				case INVALID_CHECKSUM -> throw die(
					death_code,
					"Command %s failed a checksum test.%n",
					command_name
				);
				case BUILD_FAIL ->  throw die(
					death_code ,
					"Command %s failed to execute!%n",
					command_name
				);
				case COMPILE_FAIL -> throw die(
					death_code,
					"A bug in the build script in command %s detected!%n",
					command_name
				);
			}
		}
		catch(RuntimeException failure) {
			if(build_debug) failure.printStackTrace();
			throw die(DeathCode.BUILD_FAIL, "Command %s failed to execute!", command.name());
		}
	}

	static Command parse( String[] input ) {
		final var default_command = new Command.Help(no_params);
		if (input == null || input.length == 0) return default_command;
		final var rest = Arrays.copyOfRange(input,1, input.length);
		return switch (input[0]) {
			case "help" -> new Command.Help(rest);
			case "bootstrap" -> new Command.Bootstrap(rest);
			case "man" -> new Command.Man(rest);
			default -> {
				cli.format("Unknown command %s given.%n%n", bold_italic(input[0]));
				yield default_command;
			}
		};
	}

	/// A subcommand to the build script.
	/// Commands implement the logic to execute, when invoked, and metadata used in
	/// the `help` command.
	static sealed interface Command {
		String name();
		default String synopsis(){ return italic(name()); };
		String description();
		String[] parameters();
		DeathCode execute() throws NoReturn ;

		/// Prints usage information about the build script and all subcommands.
		///
		/// If the name of any other command is given as parameter, only its help
		/// information is printed.
		record Help(String[] parameters) implements Command {
			public String name() { return "help"; }
			public String description() { return "Prints help about all commands or the given subcommand";}
			public DeathCode execute() throws NoReturn {
				cli.format("Usage: java %s [command]%n", bold("build.java"));
				for( var command : all_commands()) {
					final var command_name = command.name();
					if(parameters.length > 0) {
						final var found = linear_search(command_name, parameters);
						if (!found) continue;
					}
					cli.format(
						"%-20s%s%n",
						bold(command_name+':'),
						command.description()
					);
				}
				return DeathCode.SUCCESS;
			}
		};

		/// Opens the local man page from the bootstrapped GraalVM-JDK.
		/// Expects one argument, the name of the JDK command.
		record Man(String[] parameters) implements Command {
			public String name() { return "man"; }
			public String description() { return "Opens up the %s page for given JDK command."
				.formatted(italic("man"));}
			public DeathCode execute() throws NoReturn {
				new Command.Bootstrap(empty_string_array).execute();
				if(parameters.length == 0) {
					return DeathCode.MISSING_PARAMETER;
				}
				final var jdk_command_name = parameters[0];
				final var proc = new ProcessBuilder("man", jdk_command_name).inheritIO();
				final var env = proc.environment();
				env.put("MANPATH", graalvm_directory.resolve("man").toAbsolutePath().toString());
				env.put("MANPAGER", "nvim +Man!");
				try {
					final var code = proc.start().waitFor();
					if (code == 0)
					return DeathCode.SUCCESS;
					else
					return DeathCode.BUILD_FAIL;
				} catch (final IOException | InterruptedException exception) {
					if(build_debug) exception.printStackTrace();
					return DeathCode.COMPILE_FAIL;
				}
			}
		}

		// TODO(bugabinga): adr command
		// TODO(bugabinga): test and build commands

		/// Downloads and caches the latest version of GraalVM Community Editon,
		/// which will be used by all other commands, that require JDK tools.
		/// If the cached GraalVM is already present, nothing happens.
		///
		/// It is expected, that most other commands call `bootstrap` first.
		///
		/// GraalVM will be downloaded to `.graalvm`. In order to force a redownload,
		/// delete that folder.
		record Bootstrap(String[] parameters) implements Command {
			public String name() { return "bootstrap"; }
			public String description() {
				return "Downloads GraalVM Community Edition %s into %s. It will be used as this projects JDK."
							 .formatted(graalvm_version, italic(".graalvm"));
			}
			public DeathCode execute() throws NoReturn {
				if (parameters().length != 0) {
					return DeathCode.UNKNOWN_COMMAND_ARGUMENTS;
				}

				if ( Files.isDirectory(graalvm_directory) ) {
					dbg("%s already exists. Nothing to do...%n", graalvm_directory );
				}
				else {
					final var os = determine_os();
					final var arch = determine_arch();
					final var archive_extension = determine_archive_extension(os);

					try {
						final var graalvm_url = String.format(graalvm_download_url, graalvm_version, graalvm_version, os, arch, archive_extension);
						final var graalvm_temp = Files.createTempFile("graalvm", '.' + archive_extension);
						download(graalvm_url, graalvm_temp);
						final var graalvm_sha256_url = String.format(graalvm_download_url, graalvm_version, graalvm_version, os, arch, archive_extension + ".sha256" );
						final var graalvm_sha256_temp = Files.createTempFile("graalvm_sha256", '.' + archive_extension + ".sha256" );
						download(graalvm_sha256_url, graalvm_sha256_temp);
						if(sha256_valid(graalvm_temp, graalvm_sha256_temp )){
							final var extracted = strip_first_component(extract_archive(graalvm_temp));
							dbg("Moving %s into %s%n", extracted.toString(), graalvm_directory.toString());
							move_directory(extracted, graalvm_directory);
						}
						else {
							return DeathCode.INVALID_CHECKSUM;
						}
					}
					catch (IOException exception) {
						if(build_debug) exception.printStackTrace();
						throw die(DeathCode.COMPILE_FAIL, "File operation error." );
					}
				}

				if ( Files.isDirectory(jresolve_directory) ) {
					dbg("%s already exists. Nothing to do...%n", jresolve_directory );
				} else {
					try {
						final var jresolve_url = String.format(jresolve_download_url, jresolve_version);
						final var jresolve_temp = Files.createTempFile("jresolve", ".jar" );
						download(jresolve_url, jresolve_temp);
						Files.createDirectories(jresolve_directory);
						Files.move(jresolve_temp, jresolve_directory.resolve("jresolve.jar"));
					}
					catch (IOException exception) {
						if(build_debug) exception.printStackTrace();
						throw die(DeathCode.COMPILE_FAIL, "File operation error." );
					}
				}

				return DeathCode.SUCCESS;
			}
		}
	}

	static void move_directory(Path sourceDir, Path targetDir)
	throws IOException, NoReturn {
    if (!Files.isDirectory(sourceDir)) {
        throw die(DeathCode.COMPILE_FAIL, "Source %s is not a directory.", sourceDir.toString());
    }
    if (Files.exists(targetDir) && !Files.isDirectory(targetDir)) {
        throw die(DeathCode.COMPILE_FAIL, "Target %s exists and is not a directory.", targetDir.toString());
    }

    try {
        Files.createDirectories(targetDir);
        dbg("Created target directory %s%n", targetDir.toString());

        try (final var paths = Files.walk(sourceDir)) {
					final var path_list = paths.toList();
						for(var source: path_list) {
                try {
                    final var target = targetDir.resolve(sourceDir.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                        dbg("Created directory %s%n", target.toString());
                    } else {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                        dbg("Moved %s to %s%n", source.toString(), target.toString());
                    }
                } catch (IOException exception) {
                    if (build_debug) exception.printStackTrace();
                    throw die(DeathCode.BUILD_FAIL, "Failed to move %s to %s: %s", source.toString(), targetDir.toString(), exception.getMessage());
                }
            }
        }

        try (final var paths = Files.walk(sourceDir)) {
            final var sorted = paths.sorted((a, b) -> b.toString().length() - a.toString().length()).toList();
								 for(var path: sorted) {
                     try {
                         Files.deleteIfExists(path);
                         dbg("Deleted %s%n", path.toString());
                     } catch (IOException exception) {
                         if (build_debug) exception.printStackTrace();
                         throw die(DeathCode.BUILD_FAIL, "Failed to delete %s: %s", path.toString(), exception.getMessage());
                     }
                 }
        }
        dbg("Moved directory %s to %s%n", bold(sourceDir.toString()), bold(targetDir.toString()));
    } catch (IOException exception) {
        if (build_debug) exception.printStackTrace();
        throw die(DeathCode.BUILD_FAIL, "Directory move operation failed: %s", exception.getMessage());
    }
}

	final static String github_linux = "linux";
	final static String github_windows = "windows";
	final static String github_macos = "macos";
	final static String github_aarch64 = "aarch64";
	final static String github_x64 = "x64";
	final static String github_unix_archive_extension = "tar.gz";
	final static String github_windows_archive_extension = "zip";

	static Path download(final String url, final Path temp)
	throws IOException, NoReturn {
    try {
        final URL source = new URI(url).toURL();
        final HttpURLConnection conn = (HttpURLConnection) source.openConnection();
        final long totalSize = conn.getContentLengthLong();
				final var file = source.getFile();
				final var last_slash_index = source.getFile().lastIndexOf("/");
				final var name = file.substring(last_slash_index + 1);
				cli.format("Downloading: %s%n", name);
        try (var stream = conn.getInputStream()) {
            final char BAR_CHAR = '█';
            final int BAR_WIDTH = 50;
						final char BAR_LEFT_ENCLOSING = '❰';
						final char BAR_RIGHT_ENCLOSING = '❱';

            final byte[] buffer = new byte[8192];
            long downloaded = 0;
            int bytesRead;
            try (var out = Files.newOutputStream(temp, StandardOpenOption.WRITE)) {
                while ((bytesRead = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    final double progress = totalSize > 0 ? (double) downloaded / totalSize : 0;
                    final int barProgress = (int) (progress * BAR_WIDTH);
                    final StringBuilder bar = new StringBuilder(ANSI.CYAN.escape() + ANSI.BOLD.escape() + BAR_LEFT_ENCLOSING );
                    for (int i = 0; i < BAR_WIDTH; i++) {
                        bar.append(i < barProgress ? ANSI.MAGENTA.escape() + BAR_CHAR : " ");
                    }
                    bar.append(ANSI.CYAN.escape() + BAR_RIGHT_ENCLOSING + ANSI.RESET.escape());
                    final String percentage = String.format("%.1f", progress * 100);
                    final String sizeInfo = totalSize > 0 
                        ? String.format("%s/%s", format_size(downloaded), format_size(totalSize))
                        : String.format("%s", format_size(downloaded));

                    cli.format(
                    	"\r" +
											ANSI.BOLD.escape() + "Downloading: " +
											bar + " " +
											percentage + "%%" + " " +
											sizeInfo
                    );
                }
            }
						cli.format("%n");
        }
        return temp;
    } catch (URISyntaxException exception) {
        if (build_debug) exception.printStackTrace();
        throw die(DeathCode.COMPILE_FAIL, "Invalid url %s.", url);
    }
}

static String format_size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    final int exp = (int) (Math.log(bytes) / Math.log(1024));
    final String unit = "KMGTPE".charAt(exp - 1) + "B";
    return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
}

	static boolean sha256_valid( Path file, Path checksum_file)
	throws IOException, NoReturn {
		try {
			final String expectedChecksum = Files.readString(checksum_file).trim().toLowerCase();

			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream is = Files.newInputStream(file)) {
					final byte[] buffer = new byte[8192];
					int bytes_read;
					while ((bytes_read = is.read(buffer)) != -1) {
							digest.update(buffer, 0, bytes_read);
					}
			}
			final byte[] hash = digest.digest();
			final String computedChecksum = bytes_to_hex(hash).toLowerCase();

			dbg("Expected checksum: %s, copmuted cecksum: %s%n", expectedChecksum, computedChecksum);
			return expectedChecksum.equals(computedChecksum);
    } catch (NoSuchAlgorithmException | IOException e) {
    	if (build_debug) e.printStackTrace();
			throw die(DeathCode.COMPILE_FAIL, "Your JDK has no SHA256?!");
    }
	}

	static String bytes_to_hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder();
    for (byte b : bytes) {
        hex.append(String.format("%02x", b));
    }
    return hex.toString();
	}

	static Path strip_first_component( Path directory )
		throws IOException, NoReturn {
		if (!Files.isDirectory(directory)){
			throw die(DeathCode.COMPILE_FAIL, "Path %s is not a directory!", directory.toString());
		}

		return Files.list(directory)
								.findFirst()
								.orElseThrow(()-> die(DeathCode.COMPILE_FAIL, "Directory %s is empty!", directory));
	}

	static Path extract_archive( Path archive ) 
		throws IOException, NoReturn {

    final var file_name = archive.getFileName().toString().toLowerCase();
    final var extract_dir = Files.createTempDirectory("graalvm");
    Files.createDirectories(extract_dir);

    if (file_name.endsWith(".zip")) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final var entry_path = extract_dir.resolve(entry.getName()).normalize();
                if (!entry_path.startsWith(extract_dir)) {
                    throw die(DeathCode.COMPILE_FAIL, "Invalid ZIP archive: path traversal detected in %s.", entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entry_path);
                } else {
                    Files.createDirectories(entry_path.getParent());
                    Files.copy(zis, entry_path, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } catch (ZipException e) {
						if(build_debug) e.printStackTrace();
            throw die(DeathCode.COMPILE_FAIL, "Invalid ZIP archive.");
        }
    } else if (file_name.endsWith(".tar.gz")) {
        try (final var fis = Files.newInputStream(archive);
             final var bis = new BufferedInputStream(fis);
             final var gzis = new GZIPInputStream(bis)) {
            byte[] buffer = new byte[512]; // Tar block size
            while (true) {
                // Read header block (512 bytes)
                int bytes_read = read_fully(gzis, buffer, 512);
                if (bytes_read == 0) break; // End of archive
                if (bytes_read != 512) {
                    throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: incomplete header block");
                }

                // Check if header is all zeros (end of archive marker)
                boolean all_zeroes = true;
                for (byte b : buffer) {
                    if (b != 0) {
                        all_zeroes = false;
                        break;
                    }
                }
                if (all_zeroes) break; // Two consecutive zero blocks mark end

                // Parse header
                final var name = new String(buffer, 0, 100, StandardCharsets.US_ASCII).trim();
                if (name.isEmpty()) {
                    throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: empty file name");
                }
                final var size_str = new String(buffer, 124, 12, StandardCharsets.US_ASCII).trim();
                long size;
                try {
                    size = Long.parseLong(size_str, 8); // Octal
                } catch (NumberFormatException e) {
										if(build_debug) e.printStackTrace();
                    throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: invalid file size in header");
                }
                final char type_flag = (char) buffer[156]; // Typeflag at offset 156

                // Resolve entry path and prevent path traversal
                final var entry_path = extract_dir.resolve(name).normalize();
                if (!entry_path.startsWith(extract_dir)) {
                    throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: path traversal detected in %s.", name);
                }

                // Handle entry based on type
                if (type_flag == '0' || type_flag == 0) { // Regular file
                    Files.createDirectories(entry_path.getParent());
                    try (final var fos = Files.newOutputStream(entry_path)) {
                        long bytesToRead = size;
                        byte[] dataBuffer = new byte[8192];
                        while (bytesToRead > 0) {
                            int toRead = (int) Math.min(dataBuffer.length, bytesToRead);
                            int read = read_fully(gzis, dataBuffer, toRead);
                            if (read == 0) {
                                throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: unexpected end of file data");
                            }
                            fos.write(dataBuffer, 0, read);
                            bytesToRead -= read;
                        }
                    }
                    // Skip padding to next 512-byte block
                    long padding = (512 - (size % 512)) % 512;
                    if (padding > 0) {
                        read_fully(gzis, new byte[(int) padding], (int) padding);
                    }
                } else if (type_flag == '5') { // Directory
                    Files.createDirectories(entry_path);
                } else {
                    // Skip unsupported types (e.g., links)
                    long bytes_to_skip = size;
                    while (bytes_to_skip > 0) {
                        int toSkip = (int) Math.min(8192, bytes_to_skip);
                        read_fully(gzis, new byte[toSkip], toSkip);
                        bytes_to_skip -= toSkip;
                    }
                    // Skip padding
                    long padding = (512 - (size % 512)) % 512;
                    if (padding > 0) {
                        read_fully(gzis, new byte[(int) padding], (int) padding);
                    }
                }
            }
        } catch (IOException e) {
						if(build_debug) e.printStackTrace();
            throw die(DeathCode.COMPILE_FAIL, "Invalid tar.gz archive: ");
        }
    } else {
        throw die(DeathCode.COMPILE_FAIL, "Unsupported archive format: %s.", file_name);
    }

    return extract_dir;
	}

	static int read_fully(InputStream in, byte[] buffer, int n) throws IOException {
    int totalRead = 0;
    while (totalRead < n) {
        int read = in.read(buffer, totalRead, n - totalRead);
        if (read == -1) {
            return totalRead; // EOF
        }
        totalRead += read;
    }
    return totalRead;
	}

	static String determine_archive_extension( String github_os ) throws NoReturn {
		return switch (github_os) {
			case github_windows -> github_windows_archive_extension;
			case github_linux, github_macos -> github_unix_archive_extension;
			default -> throw die(DeathCode.COMPILE_FAIL, "Unknown OS %s. Cannot determine archive extension.", github_os);
		};
	}
	static String determine_arch() throws NoReturn {
		final var arch = System.getProperty("os.arch").toLowerCase();
		if (arch.contains("amd64") || arch.contains("x86_64")) return github_x64;
		if (arch.contains("aarch64")) return github_aarch64;
		throw die(DeathCode.COMPILE_FAIL, "Architecture %s is not supported by GrtaalVM.", arch);

	}
	static String determine_os() throws NoReturn {
		final var os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win")) return github_windows;
		if (os.contains("mac")) return github_macos;
		if (os.contains("linux")) return github_linux;
		throw die(DeathCode.COMPILE_FAIL, "Operating system %s is not supported by GraalVM.", os);
	}

	static boolean linear_search( Object needle, Object[] haystack ) {
		for (var hay:haystack) {
			if (Objects.equals(needle, hay)) return true;
		}
		return false;
	}

	/// Debug logger for the build script.
	/// Logs output to stderr.
	/// @param message message format
	/// @param arguments input arguments to the message format
	/// @see String#format(java.lang.String, java.lang.Object...)
	static void dbg(String message, Object... arguments) {
		if (build_debug)
			System.err.printf("⦗dbg⦘ " + message, arguments);
	}

	/// Returns the `BUILD_DEBUG` property from the environment.
	/// When this environment variable is set to "true", then this build script emits
	/// verbose debug loggers.
	/// ```sh
	/// BUILD_DEBUG=true java build.java
	/// ```
	/// @see Boolean#parseBoolean(String)
	static boolean get_build_debug_environment_variable() {
		var build_debug = System.getenv("BUILD_DEBUG");
		return build_debug != null && Boolean.parseBoolean(build_debug);
	}

	/// Abuses Java's exception mechanism in order to encode non-terminating control flow.
	/// Normally, when calling non-returning methods, like `System.exit`, there is
	/// no way to encode that information in the method signature.
	/// To that end, we abuse checked exceptions, to force methods, that might never
	/// return normally, to declare so.
	/// @{snippet: Terminating java method
	/// void mightQuit() throws Terminating {
	///   throw die();
	/// }
	/// }
	static class NoReturn extends Exception {
		NoReturn() {
			super("method did not return normally");
		}
	}

	/// Failure modes of this build script.
	static enum DeathCode {
		/// Everything went OK.
		SUCCESS(0),
		/// Checksum of downloaded thing was not valid.
		INVALID_CHECKSUM(444),
		/// Subcommand received unknown arguments.
		UNKNOWN_COMMAND_ARGUMENTS(555),
		/// Build script received invalid input command.
		INVALID_COMMAND(666),
		/// Some parameter for some command is missing.
		MISSING_PARAMETER(667),
		/// Some build step failed during execution.
		BUILD_FAIL(777),
		/// An error in the build script, that should have been a compile time error, if Java could.
		COMPILE_FAIL(999),
		;

		private int code;

		DeathCode(int code) {
			this.code = code;
		}

		int asInt() {
			return code;
		}
	}

	/// Terminates the build script with given `reason` and [code](DeathCode).
	static NoReturn die(DeathCode code, String reason, Object... arguments ) {
		dbg(reason+"%n", arguments);
		return die(code);
	}

	/// Terminates the build scripts without a reason.
	static NoReturn die(DeathCode code) {
		System.exit(code.asInt());
		return new NoReturn();
	}

	/// ANSI escape code constants
	static enum ANSI {
		RESET("\u001B[0m"),
		BOLD("\u001B[1m"),
		ITALIC("\u001B[3m"),
		BOLD_ITALIC("\u001B[1;3m"),
		BLACK( "\u001B[30m" ),
		RED( "\u001B[31m" ),
		GREEN( "\u001B[32m" ),
		YELLOW( "\u001B[33m" ),
		BLUE( "\u001B[34m" ),
		MAGENTA( "\u001B[35m" ),
		CYAN( "\u001B[36m" ),
		WHITE( "\u001B[37m" ),
		;

		private String escape;

		ANSI(String escape) {
			this.escape = escape;
		}

		String escape() {
			return escape;
		}
	}

	/// Formats the input string as bold.
	///
	/// @param input The string to format.
	/// @return The formatted string with VT100 bold styling.
	static String bold(String input) {
		return ANSI.BOLD.escape() + input + ANSI.RESET.escape();
	}

	/// Formats the input string as italic.
	///
	/// @param input The string to format.
	/// @return The formatted string with VT100 italic styling.
	static String italic(String input) {
		return ANSI.ITALIC.escape() + input + ANSI.RESET.escape();
	}

	/// Formats the input string as both bold and italic.
	///
	/// @param input The string to format.
	/// @return The formatted string with VT100 bold and italic styling.
	static String bold_italic(String input) {
		return ANSI.BOLD_ITALIC.escape() + input + ANSI.RESET.escape();
	}

	/// Instantiates all permitted subclasses of the [Command] interface.
	static Command[] all_commands()
	throws NoReturn
	{
			final var commandClasses =  Command.class.getPermittedSubclasses();
			final var commands = new Command[commandClasses.length];
			for (int index = 0; index < commandClasses.length; ++index) {
				final var clazz = commandClasses[index];
				try {
						final var ctor = clazz.getConstructors()[0];
						// this ctor just happens to work, because all command constructors
						// have the same shape.
						final var instance = (Command) ctor.newInstance(new Object[]{no_params});
						commands[index] = instance;
				} catch (Exception e) {
						if (build_debug) e.printStackTrace();
						throw die(DeathCode.COMPILE_FAIL, "Failed to instantiate %s. %s" ,clazz.getName(), e.getMessage());
				}
			}
		return commands;
	}
}
