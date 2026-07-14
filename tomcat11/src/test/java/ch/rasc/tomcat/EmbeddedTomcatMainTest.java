package ch.rasc.tomcat;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;

import org.apache.catalina.WebResourceSet;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.FileResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.rasc.tomcat.ContextXmlParser.ContextConfiguration;
import ch.rasc.tomcat.ContextXmlParser.ResourceSetConfiguration;

class EmbeddedTomcatMainTest {

    @TempDir
    Path tempDir;

    @Test
    void parseContextXmlSupportsAllTomcatResourceSections() throws Exception {
        Path contextXml = tempDir.resolve("context.xml");
        Files.writeString(contextXml, """
                <Context>
                  <Resources>
                    <PreResources className="org.apache.catalina.webresources.DirResourceSet" webAppMount="/pre" base="pre-base" internalPath="/"/>
                    <JarResources className="org.apache.catalina.webresources.JarResourceSet" base="library.jar" webAppMount="/WEB-INF/lib/library.jar" internalPath="/"/>
                    <PostResources className="org.apache.catalina.webresources.FileResourceSet" webAppMount="/WEB-INF/web.xml" base="web.xml" internalPath="/"/>
                  </Resources>
                </Context>
                """);

        ContextConfiguration configuration = ContextXmlParser.parse(contextXml);

        assertEquals(1, configuration.preResources().size());
        assertEquals(1, configuration.jarResources().size());
        assertEquals(1, configuration.postResources().size());
        assertEquals(DirResourceSet.class.getName(), configuration.preResources().get(0).attributes().get("className"));
        assertEquals(JarResourceSet.class.getName(), configuration.jarResources().get(0).attributes().get("className"));
        assertEquals(FileResourceSet.class.getName(), configuration.postResources().get(0).attributes().get("className"));
    }

    @Test
    void createResourceSetSupportsDirJarAndFileVariants() throws Exception {
        Path directoryBase = Files.createDirectories(tempDir.resolve("pre-base"));
        Path jarBase = tempDir.resolve("library.jar");
        Path fileBase = tempDir.resolve("web.xml");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarBase))) {
        }
        Files.writeString(fileBase, "<web-app/>");

        StandardRoot resources = new StandardRoot(new StandardContext());

        assertInstanceOf(DirResourceSet.class, createResourceSet(resources,
            new ResourceSetConfiguration("PreResources", attributes(DirResourceSet.class.getName(), "/pre", directoryBase.toString()))));
        assertInstanceOf(JarResourceSet.class, createResourceSet(resources,
            new ResourceSetConfiguration("JarResources", attributes(JarResourceSet.class.getName(), "/WEB-INF/lib/app.jar", jarBase.toString()))));
        assertInstanceOf(FileResourceSet.class, createResourceSet(resources,
            new ResourceSetConfiguration("PostResources", attributes(FileResourceSet.class.getName(), "/WEB-INF/web.xml", fileBase.toString()))));
    }

    @Test
    void parseSharedLibDirectoriesUsesPlatformPathSeparator() throws Exception {
        Path contextXml = tempDir.resolve("conf").resolve("Catalina").resolve("localhost").resolve("backend.xml");
        Files.createDirectories(contextXml.getParent());
        Files.writeString(contextXml, "<Context/>");

        Path first = tempDir.resolve("lib-one");
        Path second = tempDir.resolve("lib-two");
        String sharedLibDirectories = first + File.pathSeparator + second;

        assertEquals(List.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
            parseSharedLibDirectories(sharedLibDirectories, contextXml));
    }

    @Test
    void normalizeResourceAttributesDefaultsDataSourcesToDbcp() throws Exception {
        Map<String, String> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put("name", "jdbc/backend");
        resourceAttributes.put("type", "javax.sql.DataSource");
        resourceAttributes.put("maxActive", "20");
        resourceAttributes.put("maxWait", "1000");
        resourceAttributes.put("removeAbandoned", "true");

        Map<String, String> normalizedAttributes = normalizeResourceAttributes(resourceAttributes);

        assertEquals("org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory", normalizedAttributes.get("factory"));
        assertEquals("20", normalizedAttributes.get("maxTotal"));
        assertEquals("1000", normalizedAttributes.get("maxWaitMillis"));
        assertEquals("true", normalizedAttributes.get("removeAbandonedOnBorrow"));
        assertEquals("true", normalizedAttributes.get("removeAbandonedOnMaintenance"));
    }

    @Test
    void normalizeResourceAttributesPreservesTomcatJdbcPoolProperties() throws Exception {
        Map<String, String> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put("name", "jdbc/backend");
        resourceAttributes.put("type", "javax.sql.DataSource");
        resourceAttributes.put("factory", "org.apache.tomcat.jdbc.pool.DataSourceFactory");
        resourceAttributes.put("maxActive", "20");
        resourceAttributes.put("maxWait", "1000");
        resourceAttributes.put("removeAbandoned", "true");

        Map<String, String> normalizedAttributes = normalizeResourceAttributes(resourceAttributes);

        assertEquals("org.apache.tomcat.jdbc.pool.DataSourceFactory", normalizedAttributes.get("factory"));
        assertEquals("20", normalizedAttributes.get("maxActive"));
        assertEquals("1000", normalizedAttributes.get("maxWait"));
        assertEquals("true", normalizedAttributes.get("removeAbandoned"));
    }

    @Test
    void normalizeResourceAttributesDoesNotDefaultNonDataSourceResourcesToDbcp() throws Exception {
        Map<String, String> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put("name", "mail/session");
        resourceAttributes.put("type", "jakarta.mail.Session");

        Map<String, String> normalizedAttributes = normalizeResourceAttributes(resourceAttributes);

        assertEquals(resourceAttributes, normalizedAttributes);
    }

    @Test
    void launcherArgumentsRequireAppProjectAndContextXml() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> parseLauncherArguments());
        assertEquals("Missing required argument --appProject=<path>", exception.getMessage());
    }

    @Test
    void launcherArgumentsInferContextPathFromContextXmlFileName() throws Exception {
        Path appProject = Files.createDirectories(tempDir.resolve("app"));
        Path contextXml = tempDir.resolve("conf").resolve("Catalina").resolve("localhost").resolve("demo.xml");
        Files.createDirectories(contextXml.getParent());
        Files.writeString(contextXml, "<Context/>");

        Object launcherArguments = parseLauncherArguments(
            "--appProject=" + appProject,
            "--contextXml=" + contextXml
        );

        assertEquals("/demo", contextPath(launcherArguments));
    }

    @Test
    void launcherArgumentsInferRootContextForRootXml() throws Exception {
        Path appProject = Files.createDirectories(tempDir.resolve("root-app"));
        Path contextXml = tempDir.resolve("conf").resolve("Catalina").resolve("localhost").resolve("ROOT.xml");
        Files.createDirectories(contextXml.getParent());
        Files.writeString(contextXml, "<Context/>");

        Object launcherArguments = parseLauncherArguments(
            "--appProject=" + appProject,
            "--contextXml=" + contextXml
        );

        assertEquals("", contextPath(launcherArguments));
    }

    @Test
    void launcherArgumentsDefaultToSourceWebappMode() throws Exception {
        Path appProject = Files.createDirectories(tempDir.resolve("source-app"));
        Path contextXml = Files.writeString(tempDir.resolve("source.xml"), "<Context/>");

        Object launcherArguments = parseLauncherArguments(
            "--appProject=" + appProject,
            "--contextXml=" + contextXml
        );

        assertFalse(explodedWebapp(launcherArguments));
    }

    @Test
    void launcherArgumentsRequireWebappDirectoryInExplodedMode() throws Exception {
        Path appProject = Files.createDirectories(tempDir.resolve("exploded-app"));
        Path contextXml = Files.writeString(tempDir.resolve("exploded.xml"), "<Context/>");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> parseLauncherArguments(
                "--appProject=" + appProject,
                "--contextXml=" + contextXml,
                "--explodedWebapp=true"
            ));

        assertEquals("--webappDir=<path> is required when --explodedWebapp=true", exception.getMessage());
    }

    @Test
    void explodedWebappModeDoesNotMountClassesOrLibrariesAgain() throws Exception {
        Path classesDirectory = Files.createDirectories(tempDir.resolve("classes"));
        Path runtimeJar = tempDir.resolve("runtime.jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(runtimeJar))) {
        }
        StandardRoot resources = new StandardRoot(new StandardContext());

        mountApplicationResources(resources, classesDirectory, List.of(runtimeJar), true);

        assertEquals(0, resources.getPostResources().length);
    }

    @Test
    void sourceWebappModeMountsClassesAndLibraries() throws Exception {
        Path classesDirectory = Files.createDirectories(tempDir.resolve("classes"));
        Path runtimeJar = tempDir.resolve("runtime.jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(runtimeJar))) {
        }
        StandardRoot resources = new StandardRoot(new StandardContext());

        mountApplicationResources(resources, classesDirectory, List.of(runtimeJar), false);

        assertEquals(2, resources.getPostResources().length);
        assertTrue(resources.getPostResources()[0] instanceof DirResourceSet);
        assertTrue(resources.getPostResources()[1] instanceof JarResourceSet);
    }

    @SuppressWarnings("unchecked")
    private static List<Path> parseSharedLibDirectories(String value, Path contextXml) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("parseSharedLibDirectories", String.class, Path.class);
        method.setAccessible(true);
        return (List<Path>) method.invoke(null, value, contextXml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> normalizeResourceAttributes(Map<String, String> attributes) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("normalizeResourceAttributes", Map.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(null, attributes);
    }

    private static WebResourceSet createResourceSet(StandardRoot resources, ResourceSetConfiguration configuration) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("createResourceSet", StandardRoot.class,
            ResourceSetConfiguration.class);
        method.setAccessible(true);
        return (WebResourceSet) method.invoke(null, resources, configuration);
    }

    private static Object parseLauncherArguments(String... arguments) throws Exception {
        Class<?> launcherArgumentsClass = Class.forName("ch.rasc.tomcat.EmbeddedTomcatMain$LauncherArguments");
        Method method = launcherArgumentsClass.getDeclaredMethod("parse", String[].class);
        method.setAccessible(true);
        try {
            return method.invoke(null, (Object) arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static String contextPath(Object launcherArguments) throws Exception {
        Method method = launcherArguments.getClass().getDeclaredMethod("contextPath");
        method.setAccessible(true);
        return (String) method.invoke(launcherArguments);
    }

    private static boolean explodedWebapp(Object launcherArguments) throws Exception {
        Method method = launcherArguments.getClass().getDeclaredMethod("explodedWebapp");
        method.setAccessible(true);
        return (boolean) method.invoke(launcherArguments);
    }

    private static void mountApplicationResources(StandardRoot resources, Path classesDirectory,
            List<Path> runtimeJars, boolean explodedWebapp) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("mountApplicationResources",
            StandardRoot.class, Path.class, List.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, resources, classesDirectory, runtimeJars, explodedWebapp);
    }

    private static Map<String, String> attributes(String className, String webAppMount, String base) {
        return Map.of(
            "className", className,
            "webAppMount", webAppMount,
            "base", base,
            "internalPath", "/"
        );
    }
}
