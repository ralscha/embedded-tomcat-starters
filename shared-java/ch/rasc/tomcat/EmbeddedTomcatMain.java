package ch.rasc.tomcat;

import ch.rasc.tomcat.ContextXmlParser.ContextConfiguration;
import ch.rasc.tomcat.ContextXmlParser.EnvironmentConfiguration;
import ch.rasc.tomcat.ContextXmlParser.ParameterConfiguration;
import ch.rasc.tomcat.ContextXmlParser.ResourceConfiguration;
import ch.rasc.tomcat.ContextXmlParser.ResourceSetConfiguration;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.FileResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.tomcat.util.descriptor.web.ContextEnvironment;
import org.apache.tomcat.util.descriptor.web.ContextResource;

public final class EmbeddedTomcatMain {

    private static final String DATASOURCE_TYPE = "javax.sql.DataSource";

    private static final String DBCP_FACTORY = "org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory";

    private EmbeddedTomcatMain() {
    }

    public static void main(String[] args) throws Exception {
        LauncherArguments launcherArguments = LauncherArguments.parse(args);
        ContextConfiguration contextConfiguration = ContextXmlParser.parse(launcherArguments.contextXml());

        Path appProject = launcherArguments.appProject();
        Path webappDirectory = launcherArguments.webappDirectory();
        Path classesDirectory = launcherArguments.classesDirectory();
        Path catalinaBase = launcherArguments.catalinaBase();
        boolean explodedWebapp = launcherArguments.explodedWebapp();

        requireDirectory(appProject, "App project");
        requireDirectory(webappDirectory, "Webapp directory");
        Files.createDirectories(catalinaBase);
        requireDirectory(catalinaBase, "Catalina base");

        List<Path> runtimeJars = explodedWebapp ? Collections.emptyList() : findRuntimeJars(appProject);
        if (explodedWebapp) {
            System.out.println("Using WEB-INF/classes and WEB-INF/lib from exploded webapp: " + webappDirectory);
        } else {
            System.out.println("=== Runtime jars (" + runtimeJars.size() + " found) ===");
            runtimeJars.forEach(jar -> System.out.println("  " + jar));
            System.out.println();
        }
        List<Path> sharedJars = findSharedJars(launcherArguments.sharedLibDirectories());
        URLClassLoader sharedClassLoader = buildSharedClassLoader(sharedJars);

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(catalinaBase.toString());
        tomcat.setHostname(launcherArguments.host());
        tomcat.setPort(launcherArguments.port());
        tomcat.enableNaming();
        tomcat.getEngine().setBackgroundProcessorDelay(1);
        tomcat.getHost().setAutoDeploy(false);

        StandardContext context = (StandardContext) tomcat.addWebapp(launcherArguments.contextPath(), webappDirectory.toString());
        context.setCrossContext(contextConfiguration.crossContext());
        context.setReloadable(launcherArguments.reloadable() || contextConfiguration.reloadable());
        context.setParentClassLoader(sharedClassLoader);
        context.setBackgroundProcessorDelay(1);

        StandardRoot resources = new StandardRoot(context);
        context.setResources(resources);

        mountApplicationResources(resources, classesDirectory, runtimeJars, explodedWebapp);
        mountResourceSets(resources, contextConfiguration.preResources(), resourceSet -> resources.addPreResources(resourceSet));
        mountResourceSets(resources, contextConfiguration.jarResources(), resourceSet -> resources.addJarResources(resourceSet));
        mountResourceSets(resources, contextConfiguration.postResources(), resourceSet -> resources.addPostResources(resourceSet));
        bindNamingResources(context, contextConfiguration);

        if (launcherArguments.watchSource() != null && launcherArguments.watchTarget() != null) {
            DirectoryWatcher watcher = new DirectoryWatcher(
                launcherArguments.watchSource(),
                launcherArguments.watchTarget(),
                launcherArguments.watchExtensions(),
                DirectoryWatcher::logChange
            );
            Thread watcherThread = new Thread(watcher, "directory-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
        }

        tomcat.getConnector();
        tomcat.start();

        System.out.println("Embedded Tomcat started");
        System.out.println("  URL: http://" + launcherArguments.host() + ':' + launcherArguments.port() + launcherArguments.contextPath());
        System.out.println("  Webapp: " + webappDirectory);
        System.out.println("  Webapp mode: " + (explodedWebapp ? "exploded" : "source"));
        if (!explodedWebapp) {
            System.out.println("  Classes: " + classesDirectory);
            System.out.println("  Runtime jars: " + runtimeJars.size());
        }
        System.out.println("  Shared jars: " + sharedJars.size());

        tomcat.getServer().await();
    }

    private static void requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(label + " directory does not exist: " + path);
        }
    }

    private static void mountClasses(StandardRoot resources, Path classesDirectory) {
        if (!Files.isDirectory(classesDirectory)) {
            System.out.println("Classes directory not found, skipping /WEB-INF/classes mount: " + classesDirectory);
            return;
        }

        resources.addPostResources(new DirResourceSet(resources, "/WEB-INF/classes", classesDirectory.toString(), "/"));
    }

    private static void mountApplicationResources(StandardRoot resources, Path classesDirectory,
            List<Path> runtimeJars, boolean explodedWebapp) {
        if (explodedWebapp) {
            return;
        }

        mountClasses(resources, classesDirectory);
        mountLibraries(resources, runtimeJars);
    }

    private static void mountLibraries(StandardRoot resources, List<Path> runtimeJars) {
        for (Path runtimeJar : runtimeJars) {
            resources.addPostResources(new JarResourceSet(
                resources,
                "/WEB-INF/lib/" + runtimeJar.getFileName(),
                runtimeJar.toString(),
                "/"
            ));
        }
    }

    private static List<Path> findRuntimeJars(Path appProject) throws IOException {
        Set<Path> runtimeJars = new LinkedHashSet<>();
        Path targetDirectory = appProject.resolve("target");
        if (!Files.isDirectory(targetDirectory)) {
            return Collections.emptyList();
        }

        collectJars(targetDirectory.resolve("dependency"), runtimeJars);
        collectJars(targetDirectory.resolve("lib"), runtimeJars);

        try (Stream<Path> targetEntries = Files.list(targetDirectory)) {
            List<Path> explodedWebInfLibDirectories = targetEntries
                .filter(Files::isDirectory)
                .map(directory -> directory.resolve("WEB-INF").resolve("lib"))
                .filter(Files::isDirectory)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());

            for (Path explodedWebInfLibDirectory : explodedWebInfLibDirectories) {
                collectJars(explodedWebInfLibDirectory, runtimeJars);
            }
        }

        return new ArrayList<>(runtimeJars);
    }

    private static List<Path> findSharedJars(List<Path> sharedLibDirectories) throws IOException {
        Set<Path> sharedJars = new LinkedHashSet<>();
        for (Path sharedLibDirectory : sharedLibDirectories) {
            collectJars(sharedLibDirectory, sharedJars);
        }
        return new ArrayList<>(sharedJars);
    }

    private static void collectJars(Path directory, Set<Path> jars) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(jars::add);
        }
    }

    private static URLClassLoader buildSharedClassLoader(List<Path> sharedJars) {
        List<URL> urls = sharedJars.stream()
            .map(EmbeddedTomcatMain::toUrl)
            .collect(Collectors.toList());
        return new URLClassLoader(urls.toArray(new URL[urls.size()]), EmbeddedTomcatMain.class.getClassLoader());
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot convert path to URL: " + path, exception);
        }
    }

    private static void mountResourceSets(StandardRoot resources,
            List<ResourceSetConfiguration> resourceSets,
            ResourceSetConsumer consumer) {
        for (ResourceSetConfiguration resourceSet : resourceSets) {
            if (!resourceSet.isSupported()) {
                throw new IllegalArgumentException(
                    "Unsupported " + resourceSet.elementName() + " className: " + resourceSet.attributes().get("className")
                );
            }

            consumer.accept(createResourceSet(resources, resourceSet));
        }
    }

    private static WebResourceSet createResourceSet(StandardRoot resources, ResourceSetConfiguration resourceSet) {
        Map<String, String> attributes = resourceSet.attributes();
        String className = required(attributes, "className");
        String webAppMount = required(attributes, "webAppMount");
        String base = required(attributes, "base");
        String internalPath = attributes.getOrDefault("internalPath", "/");

        if (Objects.equals(className, DirResourceSet.class.getName())) {
            return new DirResourceSet(resources, webAppMount, base, internalPath);
        }
        if (Objects.equals(className, JarResourceSet.class.getName())) {
            return new JarResourceSet(resources, webAppMount, base, internalPath);
        }
        if (Objects.equals(className, FileResourceSet.class.getName())) {
            return new FileResourceSet(resources, webAppMount, base, internalPath);
        }

        throw new IllegalArgumentException("Unsupported resource set className: " + className);
    }

    private static void bindNamingResources(StandardContext context, ContextConfiguration configuration) {
        Set<String> environmentNames = configuration.environments().stream()
            .map(environment -> environment.attributes().get("name"))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        for (ResourceConfiguration resourceConfiguration : configuration.resources()) {
            ContextResource contextResource = new ContextResource();
            Map<String, String> normalizedAttributes = normalizeResourceAttributes(resourceConfiguration.attributes());
            for (Map.Entry<String, String> entry : normalizedAttributes.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                switch (key) {
                    case "name":
                        contextResource.setName(value);
                        break;
                    case "type":
                        contextResource.setType(value);
                        break;
                    case "auth":
                        contextResource.setAuth(value);
                        break;
                    default:
                        contextResource.setProperty(key, value);
                        break;
                }
            }
            context.getNamingResources().addResource(contextResource);
        }

        for (EnvironmentConfiguration environmentConfiguration : configuration.environments()) {
            context.getNamingResources().addEnvironment(toEnvironment(environmentConfiguration.attributes()));
        }

        for (ParameterConfiguration parameterConfiguration : configuration.parameters()) {
            String name = required(parameterConfiguration.attributes(), "name");
            String value = parameterConfiguration.attributes().getOrDefault("value", "");
            context.addParameter(name, value);

            if (!environmentNames.contains(name)) {
                ContextEnvironment parameterEnvironment = new ContextEnvironment();
                parameterEnvironment.setName(name);
                parameterEnvironment.setType("java.lang.String");
                parameterEnvironment.setValue(value);
                parameterEnvironment.setOverride(Boolean.parseBoolean(parameterConfiguration.attributes().getOrDefault("override", "false")));
                context.getNamingResources().addEnvironment(parameterEnvironment);
            }
        }
    }

    private static Map<String, String> normalizeResourceAttributes(Map<String, String> attributes) {
        Map<String, String> normalizedAttributes = new LinkedHashMap<>(attributes);

        if (!isDataSourceResource(normalizedAttributes)) {
            return normalizedAttributes;
        }

        String factory = normalizedAttributes.get("factory");
        if (factory != null && !factory.trim().isEmpty() && !DBCP_FACTORY.equals(factory)) {
            return normalizedAttributes;
        }

        renameAttribute(normalizedAttributes, "maxActive", "maxTotal");
        renameAttribute(normalizedAttributes, "maxWait", "maxWaitMillis");

        String removeAbandoned = normalizedAttributes.remove("removeAbandoned");
        if (Boolean.parseBoolean(removeAbandoned)) {
            normalizedAttributes.putIfAbsent("removeAbandonedOnBorrow", "true");
            normalizedAttributes.putIfAbsent("removeAbandonedOnMaintenance", "true");
        }
        normalizedAttributes.putIfAbsent("factory", DBCP_FACTORY);

        return normalizedAttributes;
    }

    private static boolean isDataSourceResource(Map<String, String> attributes) {
        String type = attributes.get("type");
        String factory = attributes.get("factory");
        return DATASOURCE_TYPE.equals(type) || DBCP_FACTORY.equals(factory);
    }

    private static void renameAttribute(Map<String, String> attributes, String oldName, String newName) {
        String value = attributes.remove(oldName);
        if (value != null && !attributes.containsKey(newName)) {
            attributes.put(newName, value);
        }
    }

    private static ContextEnvironment toEnvironment(Map<String, String> attributes) {
        ContextEnvironment contextEnvironment = new ContextEnvironment();
        contextEnvironment.setName(required(attributes, "name"));
        contextEnvironment.setType(attributes.getOrDefault("type", String.class.getName()));
        contextEnvironment.setValue(attributes.getOrDefault("value", ""));
        contextEnvironment.setOverride(Boolean.parseBoolean(attributes.getOrDefault("override", "false")));
        return contextEnvironment;
    }

    private static String required(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required attribute '" + key + "' in " + attributes);
        }
        return value;
    }

    private static final class LauncherArguments {

        private final Path appProject;
        private final Path contextXml;
        private final Path webappDirectory;
        private final Path classesDirectory;
        private final boolean explodedWebapp;
        private final Path catalinaBase;
        private final List<Path> sharedLibDirectories;
        private final String contextPath;
        private final String host;
        private final int port;
        private final boolean reloadable;
        private final Path watchSource;
        private final Path watchTarget;
        private final Set<String> watchExtensions;

        private LauncherArguments(Path appProject, Path contextXml, Path webappDirectory,
                Path classesDirectory, boolean explodedWebapp, Path catalinaBase, List<Path> sharedLibDirectories,
                String contextPath, String host, int port, boolean reloadable,
                Path watchSource, Path watchTarget, Set<String> watchExtensions) {
            this.appProject = appProject;
            this.contextXml = contextXml;
            this.webappDirectory = webappDirectory;
            this.classesDirectory = classesDirectory;
            this.explodedWebapp = explodedWebapp;
            this.catalinaBase = catalinaBase;
            this.sharedLibDirectories = sharedLibDirectories;
            this.contextPath = contextPath;
            this.host = host;
            this.port = port;
            this.reloadable = reloadable;
            this.watchSource = watchSource;
            this.watchTarget = watchTarget;
            this.watchExtensions = watchExtensions;
        }

        private Path appProject() {
            return this.appProject;
        }

        private Path contextXml() {
            return this.contextXml;
        }

        private Path webappDirectory() {
            return this.webappDirectory;
        }

        private Path classesDirectory() {
            return this.classesDirectory;
        }

        private boolean explodedWebapp() {
            return this.explodedWebapp;
        }

        private Path catalinaBase() {
            return this.catalinaBase;
        }

        private List<Path> sharedLibDirectories() {
            return this.sharedLibDirectories;
        }

        private String contextPath() {
            return this.contextPath;
        }

        private String host() {
            return this.host;
        }

        private int port() {
            return this.port;
        }

        private boolean reloadable() {
            return this.reloadable;
        }

        private Path watchSource() {
            return this.watchSource;
        }

        private Path watchTarget() {
            return this.watchTarget;
        }

        private Set<String> watchExtensions() {
            return this.watchExtensions;
        }

        private static LauncherArguments parse(String[] args) {
            Map<String, String> values = Stream.of(args)
                .map(EmbeddedTomcatMain::splitArgument)
                .collect(Collectors.toMap(Argument::name, Argument::value, (left, right) -> right));

            Path appProject = EmbeddedTomcatMain.requirePathArgument(values, "appProject");
            Path contextXml = EmbeddedTomcatMain.requirePathArgument(values, "contextXml");
            boolean explodedWebapp = Boolean.parseBoolean(values.getOrDefault("explodedWebapp", "false"));
            if (explodedWebapp && !values.containsKey("webappDir")) {
                throw new IllegalArgumentException("--webappDir=<path> is required when --explodedWebapp=true");
            }
            Path webappDirectory = resolvePath(values.getOrDefault("webappDir", appProject.resolve(Paths.get("src", "main", "webapp")).toString()));
            Path classesDirectory = resolvePath(values.getOrDefault("classesDir", appProject.resolve(Paths.get("target", "classes")).toString()));
            Path catalinaBase = resolvePath(values.getOrDefault("catalinaBase", Paths.get("target", "catalina-base").toString()));
            List<Path> sharedLibDirectories = parseSharedLibDirectories(values.get("sharedLibDir"), contextXml);

            String watchSourceValue = values.get("watchSource");
            Path watchSource = watchSourceValue != null && !watchSourceValue.trim().isEmpty()
                ? resolvePath(watchSourceValue) : null;

            String watchTargetValue = values.get("watchTarget");
            Path watchTarget = watchTargetValue != null && !watchTargetValue.trim().isEmpty()
                ? resolvePath(watchTargetValue) : null;

            Set<String> watchExtensions = DirectoryWatcher.parseExtensions(values.get("watchExtensions"));

            return new LauncherArguments(
                appProject,
                contextXml,
                webappDirectory,
                classesDirectory,
                explodedWebapp,
                catalinaBase,
                sharedLibDirectories,
                normalizeContextPath(values.getOrDefault("contextPath", EmbeddedTomcatMain.inferContextPath(contextXml))),
                values.getOrDefault("host", "localhost"),
                Integer.parseInt(values.getOrDefault("port", "8080")),
                Boolean.parseBoolean(values.getOrDefault("reloadable", "true")),
                watchSource,
                watchTarget,
                watchExtensions
            );
        }
    }

    private static List<Path> parseSharedLibDirectories(String sharedLibDirValue, Path contextXml) {
        if (sharedLibDirValue != null && !sharedLibDirValue.trim().isEmpty()) {
            return Stream.of(sharedLibDirValue.split(Pattern.quote(File.pathSeparator)))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(EmbeddedTomcatMain::resolvePath)
                .collect(Collectors.toList());
        }

        Path inferredDirectory = inferSharedLibDirectory(contextXml);
        if (inferredDirectory != null) {
            return Collections.singletonList(inferredDirectory);
        }

        return Collections.emptyList();
    }

    private static Path inferSharedLibDirectory(Path contextXml) {
        Path current = contextXml.toAbsolutePath().normalize();
        for (int index = 0; index < 4 && current != null; index++) {
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }

        Path inferredDirectory = current.resolve("lib");
        return Files.isDirectory(inferredDirectory) ? inferredDirectory : null;
    }

    private static final class Argument {

        private final String name;
        private final String value;

        private Argument(String name, String value) {
            this.name = name;
            this.value = value;
        }

        private String name() {
            return this.name;
        }

        private String value() {
            return this.value;
        }
    }

    private static Argument splitArgument(String argument) {
        if (!argument.startsWith("--") || !argument.contains("=")) {
            throw new IllegalArgumentException("Arguments must use --name=value syntax. Invalid argument: " + argument);
        }

        int separatorIndex = argument.indexOf('=');
        return new Argument(argument.substring(2, separatorIndex), argument.substring(separatorIndex + 1));
    }

    private static Path resolvePath(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static Path requirePathArgument(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument --" + name + "=<path>");
        }
        return resolvePath(value);
    }

    private static String inferContextPath(Path contextXml) {
        Path fileName = contextXml.getFileName();
        if (fileName == null) {
            return "";
        }

        String contextFileName = fileName.toString();
        int extensionIndex = contextFileName.lastIndexOf('.');
        String baseName = extensionIndex >= 0 ? contextFileName.substring(0, extensionIndex) : contextFileName;
        boolean rootContext = baseName.trim().isEmpty();
        if (!rootContext) {
            rootContext = "ROOT".equalsIgnoreCase(baseName);
        }
        if (rootContext) {
            return "";
        }

        return "/" + baseName;
    }
    private static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.trim().isEmpty() || "/".equals(contextPath)) {
            return "";
        }
        return contextPath.startsWith("/") ? contextPath : "/" + contextPath;
    }

    @FunctionalInterface
    private interface ResourceSetConsumer {
        void accept(WebResourceSet resourceSet);
    }
}
