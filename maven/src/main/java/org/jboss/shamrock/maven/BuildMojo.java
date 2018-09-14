package org.jboss.shamrock.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.shamrock.deployment.ArchiveContextBuilder;
import org.jboss.shamrock.deployment.BuildTimeGenerator;
import org.jboss.shamrock.deployment.ClassOutput;
import org.jboss.shamrock.deployment.index.ResolvedArtifact;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

@Mojo(name = "build", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class BuildMojo extends AbstractMojo {

    private static final String DEPENDENCIES_RUNTIME = "dependencies.runtime";
    private static final String PROVIDED = "provided";
    /**
     * The directory for compiled classes.
     */
    @Parameter(readonly = true, required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The directory for classes generated by processing.
     */
    @Parameter(defaultValue = "${project.build.directory}/wiring-classes")
    private File wiringClassesDirectory;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDir;
    /**
     * The directory for library jars
     */
    @Parameter(defaultValue = "${project.build.directory}/lib")
    private File libDir;

    @Parameter(defaultValue = "${project.build.finalName}")
    private String finalName;

    @Parameter(defaultValue = "org.jboss.shamrock.runner.GeneratedMain")
    private String mainClass;

    @Parameter(defaultValue = "true")
    private boolean useStaticInit;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final byte[] buffer = new byte[8000];
        libDir.mkdirs();
        wiringClassesDirectory.mkdirs();
        try {
            StringBuilder classPath = new StringBuilder();
            List<String> problems = new ArrayList<>();
            Set<String> whitelist = new HashSet<>();
            for (Artifact a : project.getArtifacts()) {
                try (ZipFile zip = new ZipFile(a.getFile())) {
                    if (zip.getEntry("META-INF/services/org.jboss.shamrock.deployment.ShamrockSetup") != null) {
                        if (!a.getScope().equals(PROVIDED)) {
                            problems.add("Artifact " + a + " is a deployment artifact, however it does not have scope required. This will result in unnecessary jars being included in the final image");
                        }
                    }
                    ZipEntry deps = zip.getEntry(DEPENDENCIES_RUNTIME);
                    if (deps != null) {
                        whitelist.add(a.getDependencyConflictId());
                        try (InputStream in = zip.getInputStream(deps)) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] parts = line.trim().split(":");
                                if (parts.length < 5) {
                                    continue;
                                }
                                StringBuilder sb = new StringBuilder();
                                //the last two bits are version and scope
                                //which we don't want
                                for (int i = 0; i < parts.length - 2; ++i) {
                                    if (i > 0) {
                                        sb.append(':');
                                    }
                                    sb.append(parts[i]);
                                }
                                whitelist.add(sb.toString());
                            }
                        }
                    }

                }
            }
            if (!problems.isEmpty()) {
                //TODO: add a config option to just log an error instead
                throw new MojoFailureException(problems.toString());
            }

            for (Artifact a : project.getArtifacts()) {
                if (a.getScope().equals(PROVIDED) && !whitelist.contains(a.getDependencyConflictId())) {
                    continue;
                }
                try (FileInputStream in = new FileInputStream(a.getFile())) {
                    File file = new File(libDir, a.getFile().getName());
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        int r;
                        while ((r = in.read(buffer)) > 0) {
                            out.write(buffer, 0, r);
                        }
                    }
                    classPath.append(" lib/" + file.getName());
                }
            }

            List<ResolvedArtifact> artifactList = new ArrayList<>();
            List<URL> classPathUrls = new ArrayList<>();
            for (Artifact artifact : project.getArtifacts()) {
                classPathUrls.add(artifact.getFile().toURL());
                artifactList.add(new ResolvedArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getClassifier(), Paths.get(artifact.getFile().getAbsolutePath())));
            }

            //we need to make sure all the deployment artifacts are on the class path
            //to do this we need to create a new class loader to actually use for the runner
            List<URL> cpCopy = new ArrayList<>();

            cpCopy.add(outputDirectory.toURL());
            cpCopy.addAll(classPathUrls);

            URLClassLoader runnerClassLoader = new URLClassLoader(cpCopy.toArray(new URL[0]), getClass().getClassLoader());
            BuildTimeGenerator buildTimeGenerator = new BuildTimeGenerator(new ClassOutput() {
                @Override
                public void writeClass(boolean applicationClass, String className, byte[] data) throws IOException {
                    String location = className.replace('.', '/');
                    File file = new File(wiringClassesDirectory, location + ".class");
                    file.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(data);
                    }
                }

                @Override
                public void writeResource(String name, byte[] data) throws IOException {
                    File file = new File(wiringClassesDirectory, name);
                    file.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(data);
                    }
                }

            }, runnerClassLoader, useStaticInit, new ArchiveContextBuilder());
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(runnerClassLoader);

                buildTimeGenerator.run(outputDirectory.toPath());
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }

            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(new File(buildDir, finalName + "-runner.jar")))) {
                Path wiringJar = Paths.get(wiringClassesDirectory.getAbsolutePath());
                Files.walk(wiringJar).forEach(new Consumer<Path>() {
                    @Override
                    public void accept(Path path) {
                        try {
                            String pathName = wiringJar.relativize(path).toString();
                            if (Files.isDirectory(path)) {
                                if (!pathName.isEmpty()) {
                                    out.putNextEntry(new ZipEntry(pathName + "/"));
                                }
                            } else {
                                out.putNextEntry(new ZipEntry(pathName));
                                try (FileInputStream in = new FileInputStream(path.toFile())) {
                                    doCopy(out, in);
                                }
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);
                //now copy all the contents to the runner jar
                //I am not 100% sure about this idea, but if we are going to support bytecode transforms it seems
                //like the cleanest way to do it
                //at the end of the PoC phase all this needs review
                Path appJar = Paths.get(outputDirectory.getAbsolutePath());
                Files.walk(appJar).forEach(new Consumer<Path>() {
                    @Override
                    public void accept(Path path) {
                        try {
                            String pathName = appJar.relativize(path).toString();
                            if (Files.isDirectory(path)) {
//                                if (!pathName.isEmpty()) {
//                                    out.putNextEntry(new ZipEntry(pathName + "/"));
//                                }
                            } else if (pathName.endsWith(".class") && !buildTimeGenerator.getBytecodeTransformers().isEmpty()) {
                                String className = pathName.substring(0, pathName.length() - 6).replace("/", ".");
                                List<Function<ClassVisitor, ClassVisitor>> visitors = new ArrayList<>();
                                for (Function<String, Function<ClassVisitor, ClassVisitor>> t : buildTimeGenerator.getBytecodeTransformers()) {
                                    Function<ClassVisitor, ClassVisitor> visitor = t.apply(className);
                                    if (visitor != null) {
                                        visitors.add(visitor);
                                    }
                                  }
                                out.putNextEntry(new ZipEntry(pathName));
                                if (visitors.isEmpty()) {
                                    try (FileInputStream in = new FileInputStream(path.toFile())) {
                                        doCopy(out, in);
                                    }
                                } else {
                                    try (InputStream in = new FileInputStream(path.toFile())) {
                                        ClassReader cr = new ClassReader(in);
                                        ClassWriter writer = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                                        ClassVisitor visitor = writer;
                                        for (Function<ClassVisitor, ClassVisitor> i : visitors) {
                                            visitor = i.apply(visitor);
                                        }
                                        cr.accept(visitor, 0);
                                        out.write(writer.toByteArray());
                                    }
                                }
                            } else {
                                out.putNextEntry(new ZipEntry(pathName));
                                try (FileInputStream in = new FileInputStream(path.toFile())) {
                                    doCopy(out, in);
                                }
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }


        } catch (Exception e) {
            throw new MojoFailureException("Failed to run", e);
        }
    }

    private static void doCopy(OutputStream out, InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        int r;
        while ((r = in.read(buffer)) > 0) {
            out.write(buffer, 0, r);
        }
    }
}
