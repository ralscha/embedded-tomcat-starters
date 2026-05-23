package ch.rasc.tomcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ch.rasc.tomcat.ContextXmlParser.ContextConfiguration;
import ch.rasc.tomcat.ContextXmlParser.ResourceSetConfiguration;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.FileResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(DirResourceSet.class.getName(), configuration.preResources().getFirst().attributes().get("className"));
        assertEquals(JarResourceSet.class.getName(), configuration.jarResources().getFirst().attributes().get("className"));
        assertEquals(FileResourceSet.class.getName(), configuration.postResources().getFirst().attributes().get("className"));
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

    @SuppressWarnings("unchecked")
    private static List<Path> parseSharedLibDirectories(String value, Path contextXml) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("parseSharedLibDirectories", String.class, Path.class);
        method.setAccessible(true);
        return (List<Path>) method.invoke(null, value, contextXml);
    }

    private static WebResourceSet createResourceSet(StandardRoot resources, ResourceSetConfiguration configuration) throws Exception {
        Method method = EmbeddedTomcatMain.class.getDeclaredMethod("createResourceSet", StandardRoot.class,
            ResourceSetConfiguration.class);
        method.setAccessible(true);
        return (WebResourceSet) method.invoke(null, resources, configuration);
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