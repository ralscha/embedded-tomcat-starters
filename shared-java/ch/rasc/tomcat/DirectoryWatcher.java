package ch.rasc.tomcat;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Watches a source directory tree for file changes and copies modified/created
 * files to a target directory, preserving the relative path structure.
 * <p>
 * Primarily intended for JSF development workflows where .xhtml facelet files,
 * CSS, JavaScript, and other web resources in {@code src/main/webapp} (or a
 * resources directory) need to be mirrored into the target build output so that
 * changes are immediately reflected without a full rebuild.
 * <p>
 * Usage example:
 * <pre>{@code
 * DirectoryWatcher watcher = new DirectoryWatcher(
 *     Path.of("src/main/webapp"),
 *     Path.of("target/classes/META-INF/resources"),
 *     Set.of(".xhtml", ".css", ".js", ".properties"),
 *     DirectoryWatcher::logChange
 * );
 * Thread watcherThread = new Thread(watcher, "directory-watcher");
 * watcherThread.setDaemon(true);
 * watcherThread.start();
 * }</pre>
 */
public final class DirectoryWatcher implements Runnable {

    private final Path sourceDir;
    private final Path targetDir;
    private final Set<String> extensions;
    private final Consumer<String> onChange;
    private volatile boolean running = true;

    /**
     * Creates a new directory watcher.
     *
     * @param sourceDir  the directory to watch recursively
     * @param targetDir  the directory to copy changed files into
     * @param extensions file extensions to watch (e.g. {@code ".xhtml"}, {@code ".css"}).
     *                   If empty, all files are watched.
     * @param onChange   callback invoked with a description of each change (e.g. for logging)
     */
    public DirectoryWatcher(Path sourceDir, Path targetDir, Set<String> extensions, Consumer<String> onChange) {
        this.sourceDir = sourceDir.toAbsolutePath().normalize();
        this.targetDir = targetDir.toAbsolutePath().normalize();
        this.extensions = extensions.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        this.onChange = onChange;
    }

    /**
     * Creates a watcher that watches all file types.
     */
    public DirectoryWatcher(Path sourceDir, Path targetDir, Consumer<String> onChange) {
        this(sourceDir, targetDir, Set.of(), onChange);
    }

    /**
     * Stops the watcher. The running thread will exit after the current
     * watch cycle completes.
     */
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        if (!Files.isDirectory(sourceDir)) {
            onChange.accept("WARNING: Source directory does not exist, watcher not started: " + sourceDir);
            return;
        }

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            onChange.accept("WARNING: Could not create target directory, watcher not started: " + targetDir);
            return;
        }

        onChange.accept("Directory watcher started");
        onChange.accept("  Watching: " + sourceDir);
        onChange.accept("  Target:   " + targetDir);
        onChange.accept("  Filter:   " + (extensions.isEmpty() ? "ALL files" : String.join(", ", extensions)));

        Map<WatchKey, Path> keyToDir = new HashMap<>();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerAll(sourceDir, watchService, keyToDir);
            onChange.accept("  Registered " + keyToDir.size() + " directories for watching");

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) {
                    continue;
                }

                Path watchedDir = keyToDir.get(key);
                if (watchedDir == null) {
                    key.cancel();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        onChange.accept("WARNING: File system events may have been lost");
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path sourcePath = watchedDir.resolve(fileName);

                    if (!matchesFilter(sourcePath)) {
                        continue;
                    }

                    Path relativePath = sourceDir.relativize(sourcePath);
                    Path targetPath = targetDir.resolve(relativePath);

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        if (Files.isDirectory(sourcePath)) {
                            registerAll(sourcePath, watchService, keyToDir);
                            onChange.accept("  + [DIR]  " + relativePath);
                        } else {
                            handleFileChange(sourcePath, targetPath, relativePath, kind);
                        }
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        try {
                            Files.deleteIfExists(targetPath);
                            onChange.accept("  - [DEL]  " + relativePath);
                        } catch (IOException e) {
                            onChange.accept("  ! [ERR]  Failed to delete " + relativePath + ": " + e.getMessage());
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    keyToDir.remove(key);
                    onChange.accept("  Directory no longer accessible, unregistered: " + watchedDir);
                }
            }
        } catch (ClosedWatchServiceException e) {
            onChange.accept("Watcher service closed");
        } catch (IOException e) {
            onChange.accept("ERROR: Watcher failed: " + e.getMessage());
        }

        onChange.accept("Directory watcher stopped");
    }

    private void handleFileChange(Path sourcePath, Path targetPath, Path relativePath, WatchEvent.Kind<?> kind) {
        // Delay slightly to let the file system settle (debounce partial writes)
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!Files.isRegularFile(sourcePath)) {
            return;
        }

        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            String action = kind == StandardWatchEventKinds.ENTRY_CREATE ? "NEW" : "MOD";
            onChange.accept("  > [" + action + "] " + relativePath);
        } catch (IOException e) {
            onChange.accept("  ! [ERR]  Failed to copy " + relativePath + ": " + e.getMessage());
        }
    }

    private boolean matchesFilter(Path path) {
        if (extensions.isEmpty()) {
            return true;
        }
        String fileName = path.getFileName().toString().toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return false;
        }
        return extensions.contains(fileName.substring(dotIndex));
    }

    private static void registerAll(Path root, WatchService watchService, Map<WatchKey, Path> keyToDir) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                keyToDir.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Default change callback that prints to {@code System.out}.
     */
    public static void logChange(String message) {
        System.out.println("[watcher] " + message);
    }

    /**
     * Parses a comma-separated list of file extensions from a command-line
     * argument value. Each extension should start with a dot.
     * Use {@code "*"} to watch all file types.
     * <p>
     * Example: {@code ".xhtml,.css,.js"} → {@code Set.of(".xhtml", ".css", ".js")}
     * <p>
     * Example: {@code "*"} → empty set (matches everything)
     *
     * @param value comma-separated extensions, {@code "*"}, or {@code null}/blank
     * @return the set of extensions, or an empty set to match all files
     */
    public static Set<String> parseExtensions(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        if ("*".equals(value.trim())) {
            return Set.of();
        }
        return Stream.of(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }
}
