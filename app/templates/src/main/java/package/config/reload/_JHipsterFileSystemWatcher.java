package <%=packageName%>.config.reload;

import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springsource.loaded.ReloadableType;
import org.springsource.loaded.TypeRegistry;
import org.springsource.loaded.Utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * A watcher for the target class folder.
 * 
 * The watcher will monitor all folders and sub-folders to check if a new class
 * is created. If so, the new class will be loaded and managed by Spring-Loaded.
 */
public class JHipsterFileSystemWatcher implements Runnable {

    private static Logger log = LoggerFactory.getLogger(JHipsterFileSystemWatcher.class);

    private static boolean isStarted;
    private final WatchService watcher;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private ClassLoader parentClassLoader;
    private Map<String, URLClassLoader> urlClassLoaderMap = new HashMap<>();

    public JHipsterFileSystemWatcher(List<String> watchFolders, ClassLoader classLoader) throws Exception {
        this.parentClassLoader = classLoader;

        watcher = FileSystems.getDefault().newWatchService();

        // Register all folders
        for (String watchFolder : watchFolders) {
            URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {new File(watchFolder).toURI().toURL()}, classLoader);
            urlClassLoaderMap.put(watchFolder, urlClassLoader);

            final Path classesFolderPath = FileSystems.getDefault().getPath(watchFolder);
            registerAll(classesFolderPath);
        }

        isStarted = true;
    }

    /**
     * Register the classLoader and start a thread that will be used to monitor folders where classes can be created.
     *
     * @param classLoader the classLoader of the application
     * @param watchFolders the list of folders to watch
     */
    public static void register(ClassLoader classLoader, List<String> watchFolders) {
        try {
            if (watchFolders.size() == 0) {
                log.warn("SpringLoaded - No watched folders have been defined in the application-{profile}.yml. " +
                        "We will use the default target/classes");
                watchFolders.add("target/classes");
            }

            final Thread thread = new Thread(new JHipsterFileSystemWatcher(watchFolders, classLoader));
            thread.setDaemon(true);
            thread.start();

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    JHipsterFileSystemWatcher.isStarted = false;
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        log.error("Failed during the JVM shutdown", e);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to start the watcher. New class will not be loaded.", e);
        }
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Register the given directory with the WatchService.
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE);
        Path prev = keys.get(key);
        if (prev == null) {
            log.debug("Directory : '{}' will be monitored for changes", dir);
        }
        keys.put(key, dir);
    }

    /**
     * Process all events for keys queued to the watcher.
     * 
     * When the event is a ENTRY_CREATE, the folders will be added to the watcher,
     * the classes will be loaded by SpringLoaded
     */
    public void run() {
        while (isStarted) {
            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                // Context for directory entry event is the file name of entry
                // noinspection unchecked
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (kind == ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                            // load the classes that have been copied
                            final File[] classes = child.toFile().listFiles((FileFilter) new SuffixFileFilter(".class"));
                            for (File aFile : classes) {
                                final String parentFolder = aFile.getParent();
                                loadClassFromPath(parentFolder, aFile.getName(), aFile);
                            }
                        } else {
                            loadClassFromPath(dir.toString(), name.toString(), child.toFile());
                        }
                    } catch (IOException e) {
                        log.error("Failed to load the class named: " + name.toString(), e);
                    }
                }
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

    private void loadClassFromPath(String dir, String fileName, File theFile) {
        // A class has been added, so it needs to be added to the classloader
        try {
            Map.Entry<String, URLClassLoader> urlLoaderEntry = selectUrlLoaderEntry(dir);

            if (urlLoaderEntry == null) {
                log.error("Failed to find a watched folder for the directory: " + dir);
                return;
            }

            final String classesFolder = urlLoaderEntry.getKey();
            final URLClassLoader urlClassLoader = urlLoaderEntry.getValue();


            // Try to load the new class
            // First we need to remove the global classesFolder from the child path
            String slashedClassPath = StringUtils.substringAfter(dir, classesFolder);
            if (slashedClassPath.startsWith("/")) {
                slashedClassPath = slashedClassPath.substring(1);
            }

            // Replace / by . to create the dottedClassName
            String dottedClassPath = slashedClassPath.replace("/", ".");

            String slashedClassName = slashedClassPath + "/" + StringUtils.substringBefore(fileName, ".");
            String dottedClassName = dottedClassPath + "." + StringUtils.substringBefore(fileName, ".");

            // Retrieve the Spring Loaded registry.
            // We will use to validate the class has not been already loaded
            TypeRegistry typeRegistry = TypeRegistry.getTypeRegistryFor(parentClassLoader);

            // Load the class
            urlClassLoader.loadClass(dottedClassName);

            // Force SpringLoaded to instrument the class
            if (typeRegistry != null) {
                String versionstamp = Utils.encode(theFile.lastModified());
                ReloadableType  rtype = typeRegistry.getReloadableType(slashedClassName);
                typeRegistry.fireReloadEvent(rtype, versionstamp);
            }
            log.debug("New class : '{}' has been loaded", dottedClassName);
        } catch (Exception e) {
            log.error("Failed to load the class named: " + fileName, e);
        }
    }

    private Map.Entry<String, URLClassLoader> selectUrlLoaderEntry(String dir) {

        for (Map.Entry<String, URLClassLoader> urlClassLoaderEntry : urlClassLoaderMap.entrySet()) {
            final String key = urlClassLoaderEntry.getKey();

            if (StringUtils.contains(dir, key)) {
                return urlClassLoaderEntry;
            }
        }

        return null;
    }
}
