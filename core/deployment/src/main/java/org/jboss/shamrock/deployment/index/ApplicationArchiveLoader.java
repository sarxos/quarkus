package org.jboss.shamrock.deployment.index;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.shamrock.deployment.ApplicationArchive;
import org.jboss.shamrock.deployment.ApplicationArchiveImpl;
import org.jboss.shamrock.deployment.buildconfig.BuildConfig;

/**
 * Class that is responsible for loading application archives from outside the deployment
 */
public class ApplicationArchiveLoader {

    private static final String INDEX_DEPENDENCIES = "index-dependencies";
    private static final String INDEX_JAR = "index-jar";

    public static List<ApplicationArchive> scanForOtherIndexes(ClassLoader classLoader, BuildConfig config, Set<String> applicationArchiveFiles, Path appRoot, List<Path> additionalApplicationArchives) throws IOException {

        Set<Path> dependenciesToIndex = new HashSet<>();

        //get paths that have index-jar: true
        dependenciesToIndex.addAll(getIndexJarPaths(config));
        //get paths that are included via index-dependencies
        dependenciesToIndex.addAll(getIndexDependencyPaths(config, classLoader));
        //get paths that are included via marker files
        dependenciesToIndex.addAll(getMarkerFilePaths(classLoader, applicationArchiveFiles));

        //we don't index the application root, this is handled elsewhere
        dependenciesToIndex.remove(appRoot);

        dependenciesToIndex.addAll(additionalApplicationArchives);

        return indexPaths(dependenciesToIndex, classLoader);
    }

    private static List<ApplicationArchive> indexPaths(Set<Path> dependenciesToIndex, ClassLoader classLoader) throws IOException {
        List<ApplicationArchive> ret = new ArrayList<>();

        for (final Path dep : dependenciesToIndex) {
            if (Files.isDirectory(dep)) {
                Indexer indexer = new Indexer();
                handleFilePath(dep, indexer);
                IndexView indexView = indexer.complete();
                ret.add(new ApplicationArchiveImpl(indexView, dep, null));
            } else {
                Indexer indexer = new Indexer();
                handleJarPath(dep, indexer);
                IndexView index = indexer.complete();
                FileSystem fs = FileSystems.newFileSystem(dep, classLoader);
                ret.add(new ApplicationArchiveImpl(index, fs.getRootDirectories().iterator().next(), fs));
            }
        }

        return ret;
    }

    private static Collection<? extends Path> getMarkerFilePaths(ClassLoader classLoader, Set<String> applicationArchiveFiles) throws IOException {
        List<Path> ret = new ArrayList<>();
        for (String file : applicationArchiveFiles) {
            Enumeration<URL> e = classLoader.getResources(file);
            while (e.hasMoreElements()) {
                URL url = e.nextElement();
                ret.add(urlToPath(url));
            }
        }

        return ret;
    }

    public static List<Path> getIndexJarPaths(BuildConfig config) {
        try {
            List<Path> ret = new ArrayList<>();
            for (Map.Entry<URL, BuildConfig.ConfigNode> dep : config.getDependencyConfig().entrySet()) {
                Boolean node = dep.getValue().get(INDEX_JAR).asBoolean();
                if (node != null && node) {
                    URL url = dep.getKey();
                    ret.add(urlToPath(url));
                }
            }
            return ret;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path urlToPath(URL url) {
        if (url.getProtocol().equals("jar")) {
            String jarPath = url.getPath().substring(5, url.getPath().lastIndexOf('!'));
            return Paths.get(jarPath);
        } else if (url.getProtocol().equals("file")) {
            int index = url.getPath().lastIndexOf("/META-INF");
            String pathString = url.getPath().substring(0, index);
            Path path = Paths.get(pathString);
            return path;
        }
        throw new RuntimeException("Unkown URL type " + url.getProtocol());
    }

    public static List<Path> getIndexDependencyPaths(BuildConfig config, ClassLoader classLoader) {
        ArtifactIndex artifactIndex = new ArtifactIndex(new ClassPathArtifactResolver(classLoader));
        try {
            List<Path> ret = new ArrayList<>();
            List<BuildConfig.ConfigNode> depList = config.getAll(INDEX_DEPENDENCIES);
            Set<String> depsToIndex = new HashSet<>();
            for (BuildConfig.ConfigNode i : depList) {
                depsToIndex.addAll(i.asStringList());
            }
            for (String line : depsToIndex) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(":");
                Path path;
                if (parts.length == 2) {
                    path = artifactIndex.getPath(parts[0], parts[1], null);
                } else if (parts.length == 3) {
                    path = artifactIndex.getPath(parts[0], parts[1], parts[2]);
                } else {
                    throw new RuntimeException("Invalid dependencies to index " + line);
                }
                ret.add(path);
            }
            return ret;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void handleFilePath(Path path, Indexer indexer) throws IOException {
        Files.walk(path).forEach(new Consumer<Path>() {
            @Override
            public void accept(Path path) {
                if (path.toString().endsWith(".class")) {
                    try (FileInputStream in = new FileInputStream(path.toFile())) {
                        indexer.index(in);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    private static void handleJarPath(Path path, Indexer indexer) throws IOException {
        try (JarFile file = new JarFile(path.toFile())) {
            Enumeration<JarEntry> e = file.entries();
            while (e.hasMoreElements()) {
                JarEntry entry = e.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream inputStream = file.getInputStream(entry)) {
                        indexer.index(inputStream);
                    }
                }
            }
        }
    }
}
