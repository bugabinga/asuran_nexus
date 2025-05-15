import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;


/// Custom build script
interface build {
	final boolean build_debug = get_build_debug_environment_variable();
	final Console cli = System.console();
	final String[] no_params = new String[0];

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
							"Command %s finished successfully in %s!\n",
							command_name,
							italic(duration.toString())
						);
						throw die( death_code);
				}
				case INVALID_COMMAND -> throw die(
					"Given command %s does not exist.\n",
					death_code,
					command_name
				);
				case UNKNOWN_COMMAND_ARGUMENTS -> throw die(
					"Unknown arguments %s to command %s.\n",
					death_code,
					italic(Arrays.toString(command.parameters())),
					command_name
				);
				case INVALID_CHECKSUM -> throw die(
					"Command %s failed a checksum test.\n",
					death_code,
					command_name
				);
				case BUILD_FAIL ->  throw die(
					"Command %s failed to execute!\n",
					death_code ,
					command_name
				);
				case COMPILE_FAIL -> throw die(
					"A bug in the build script in command %s detected!\n",
					death_code,
					command_name
				);
			}
		}
		catch(RuntimeException failure) {
			if(build_debug) failure.printStackTrace();
			throw die("Command %s failed to execute!", DeathCode.BUILD_FAIL, command.name());
		}
	}

	static Command parse( String[] input ) {
		final var default_command = new Command.Help(no_params);
		if (input == null || input.length == 0) return default_command;
		final var rest = Arrays.copyOfRange(input,1, input.length);
		return switch (input[0]) {
			case "help" -> new Command.Help(rest);
			case "bootstrap" -> new Command.Bootstrap(rest);
			default -> {
				cli.format("Unknown command %s given.\n\n", bold_italic(input[0]));
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
			public String synopsis() { return "%s [command]".formatted(italic(name())); }
			public String description() { return "Prints help about all commands or the given subcommand";}
			public DeathCode execute() throws NoReturn {
				cli.format("Usage: java %s [command]\n", bold("build.java"));
				for( var command : all_commands()) {
					final var command_name = command.name();
					if(parameters.length > 0) {
						final var found = linear_search(command_name, parameters);
						if (!found) continue;
					}
					cli.format(
						"%-20s%s\n",
						bold(command_name+':'),
						command.description()
					);
				}
				return DeathCode.SUCCESS;
			}
		};

		/// Downloads and caches the latest version of GraalVM Community Editon,
		/// which will be used by all other commands, that require JDK tools.
		/// If the cached GraalVM is already present, nothing happens.
		///
		/// It is expected, that most other commands call `bootstrap` first.
		///
		/// GraalVM will be downloaded to `.graalvm`. In order to force a redownload,
		/// delete that folder.
		record Bootstrap(String[] parameters) implements Command {

			private final static String graalvm_version = "24.0.1";
			// format args: version, os, arch, archive format
			private final static String graalvm_download_url = "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-24.0.1/graalvm-community-jdk-%s_%s-%s_bin.%s";
			private final static Path graalvm_directory = Path.of(".graalvm");

			public String name() { return "bootstrap"; }
			public String synopsis() { return "%s".formatted(italic(name())); }
			public String description() {
				return "Downloads GraalVM Community Edition %s into %s. It will be used as this projects JDK."
							 .formatted(graalvm_version, italic(".graalvm"));
			}
			public DeathCode execute() throws NoReturn {
				if (parameters().length != 0) {
					return DeathCode.UNKNOWN_COMMAND_ARGUMENTS;
				}

				if ( Files.isDirectory(graalvm_directory) ) {
					dbg("%s already exists. Nothing to do...", graalvm_directory);
					return DeathCode.SUCCESS;
				}

				final var os = determine_os();
				final var arch = determine_arch();
				final var archive_extension = determine_archive_extension(os);

				try {
					final var temp_graalvm = download(graalvm_download_url, graalvm_version, os, arch, archive_extension);
					final var temp_checksum_file = download(graalvm_download_url, graalvm_version, os, arch, archive_extension + ".sha256" );
					if(sha256_valid(temp_graalvm, temp_checksum_file)){
						final var extracted = strip_first_component(extract_archive(temp_graalvm));
						Files.move(extracted, graalvm_directory);
					}
					else {
						return DeathCode.INVALID_CHECKSUM;
					}
				}
				catch (IOException exception) {
					if(build_debug) exception.printStackTrace();
					throw die("File operation error." , DeathCode.COMPILE_FAIL);
				}

				return DeathCode.SUCCESS;
			}
		}
	}

	final static String github_linux = "linux";
	final static String github_windows = "windows";
	final static String github_macos = "macos";
	final static String github_aarch64 = "aarch64";
	final static String github_x64 = "x64";
	final static String github_unix_archive_extension = "tar.gz";
	final static String github_windows_archive_extension = "zip";

	static Path download(String base_url, String version, String os, String arch, String extension)
	throws IOException, NoReturn {
		final var url = String.format(base_url, version, os, arch ,extension);
		final var temp = Files.createTempFile("graalvm", '.' + extension);
		try {
			try(final var stream = new URI(url).toURL().openStream()){
				Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			// TODO progress bar
			return temp;
		}
		catch( URISyntaxException exception) {
			if(build_debug) exception.printStackTrace();
			throw die("Invalid url %s.", DeathCode.COMPILE_FAIL, url);
		}
	}

	static boolean sha256_valid( Path file, Path checksum_file)
	throws IOException, NoReturn {
		try {
			String expectedChecksum = Files.readString(checksum_file).trim().toLowerCase();

			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream is = Files.newInputStream(file)) {
					byte[] buffer = new byte[8192];
					int bytes_read;
					while ((bytes_read = is.read(buffer)) != -1) {
							digest.update(buffer, 0, bytes_read);
					}
			}
			byte[] hash = digest.digest();
			String computedChecksum = bytes_to_hex(hash).toLowerCase();

			return expectedChecksum.equals(computedChecksum);
    } catch (NoSuchAlgorithmException | IOException e) {
    	if (build_debug) e.printStackTrace();
			throw die("Your JDK has no SHA256?!", DeathCode.COMPILE_FAIL);
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
			throw die("Path %s is not a directory!", DeathCode.COMPILE_FAIL, directory.toString());
		}

		return Files.list(directory)
								.findFirst()
								.orElseThrow(()-> die("Directory %s is empty!", DeathCode.COMPILE_FAIL, directory));
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
                    throw die("Invalid ZIP archive: path traversal detected in %s.", DeathCode.COMPILE_FAIL, entry.getName());
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
            throw die("Invalid ZIP archive.", DeathCode.COMPILE_FAIL);
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
                    throw die("Invalid tar.gz archive: incomplete header block", DeathCode.COMPILE_FAIL);
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
                    throw die("Invalid tar.gz archive: empty file name", DeathCode.COMPILE_FAIL);
                }
                final var size_str = new String(buffer, 124, 12, StandardCharsets.US_ASCII).trim();
                long size;
                try {
                    size = Long.parseLong(size_str, 8); // Octal
                } catch (NumberFormatException e) {
										if(build_debug) e.printStackTrace();
                    throw die("Invalid tar.gz archive: invalid file size in header", DeathCode.COMPILE_FAIL);
                }
                final char type_flag = (char) buffer[156]; // Typeflag at offset 156

                // Resolve entry path and prevent path traversal
                final var entry_path = extract_dir.resolve(name).normalize();
                if (!entry_path.startsWith(extract_dir)) {
                    throw die("Invalid tar.gz archive: path traversal detected in %s.", DeathCode.COMPILE_FAIL, name);
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
                                throw die("Invalid tar.gz archive: unexpected end of file data", DeathCode.COMPILE_FAIL);
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
            throw die("Invalid tar.gz archive: ", DeathCode.COMPILE_FAIL);
        }
    } else {
        throw die("Unsupported archive format: %s.", DeathCode.COMPILE_FAIL, file_name);
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
			default -> throw die("Unknown OS %s. Cannot determine archive extension.", DeathCode.COMPILE_FAIL, github_os);
		};
	}
	static String determine_arch() throws NoReturn {
		final var arch = System.getProperty("os.arch").toLowerCase();
		if (arch.contains("amd64") || arch.contains("x86_64")) return github_x64;
		if (arch.contains("aarch64")) return github_aarch64;
		throw die("Architecture %s is not supported by GrtaalVM.", DeathCode.COMPILE_FAIL, arch);

	}
	static String determine_os() throws NoReturn {
		final var os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win")) return github_windows;
		if (os.contains("mac")) return github_macos;
		if (os.contains("linux")) return github_linux;
		throw die("Operating system %s is not supported by GraalVM.", DeathCode.COMPILE_FAIL, os);
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
			System.err.printf(message, arguments);
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
	static NoReturn die(String reason, DeathCode code, Object... arguments ) {
		dbg(reason+'\n', arguments);
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
						throw die("Failed to instantiate %s. %s" ,DeathCode.COMPILE_FAIL, clazz.getName(), e.getMessage());
				}
			}
		return commands;
	}
}
