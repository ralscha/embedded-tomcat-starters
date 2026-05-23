package ch.rasc.tomcat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class ContextXmlParser {

    private static final String DIR_RESOURCE_SET_CLASS_NAME = "org.apache.catalina.webresources.DirResourceSet";

    private static final String JAR_RESOURCE_SET_CLASS_NAME = "org.apache.catalina.webresources.JarResourceSet";

    private static final String FILE_RESOURCE_SET_CLASS_NAME = "org.apache.catalina.webresources.FileResourceSet";

    private ContextXmlParser() {
    }

    static ContextConfiguration parse(Path contextXml) throws IOException {
        try (InputStream inputStream = Files.newInputStream(contextXml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            Document document = factory.newDocumentBuilder().parse(inputStream);
            Element contextElement = document.getDocumentElement();

            ContextConfiguration configuration = new ContextConfiguration(
                parseBooleanAttribute(contextElement, "reloadable", false),
                parseBooleanAttribute(contextElement, "crossContext", false),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
            );

            NodeList childNodes = contextElement.getChildNodes();
            for (int index = 0; index < childNodes.getLength(); index++) {
                Node childNode = childNodes.item(index);
                if (childNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                Element childElement = (Element) childNode;
                String tagName = childElement.getTagName();
                if ("Resource".equals(tagName)) {
                    configuration.resources().add(new ResourceConfiguration(attributes(childElement)));
                } else if ("Environment".equals(tagName)) {
                    configuration.environments().add(new EnvironmentConfiguration(attributes(childElement)));
                } else if ("Parameter".equals(tagName)) {
                    configuration.parameters().add(new ParameterConfiguration(attributes(childElement)));
                } else if ("Resources".equals(tagName)) {
                    parseResourcesElement(childElement, configuration);
                }
            }

            return configuration;
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Failed to parse context XML " + contextXml, exception);
        }
    }

    private static void parseResourcesElement(Element resourcesElement, ContextConfiguration configuration) {
        NodeList childNodes = resourcesElement.getChildNodes();
        for (int index = 0; index < childNodes.getLength(); index++) {
            Node childNode = childNodes.item(index);
            if (childNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element childElement = (Element) childNode;
            Map<String, String> resourceAttributes = attributes(childElement);
            switch (childElement.getTagName()) {
                case "PreResources" -> configuration.preResources()
                    .add(new ResourceSetConfiguration("PreResources", resourceAttributes));
                case "JarResources" -> configuration.jarResources()
                    .add(new ResourceSetConfiguration("JarResources", resourceAttributes));
                case "PostResources" -> configuration.postResources()
                    .add(new ResourceSetConfiguration("PostResources", resourceAttributes));
                default -> {
                }
            }
        }
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> attributes = new LinkedHashMap<>();
        NamedNodeMap namedNodeMap = element.getAttributes();
        for (int index = 0; index < namedNodeMap.getLength(); index++) {
            Node attribute = namedNodeMap.item(index);
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return attributes;
    }

    private static boolean parseBooleanAttribute(Element element, String attributeName, boolean defaultValue) {
        if (!element.hasAttribute(attributeName)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(element.getAttribute(attributeName));
    }

    record ContextConfiguration(
        boolean reloadable,
        boolean crossContext,
        List<ResourceConfiguration> resources,
        List<EnvironmentConfiguration> environments,
        List<ParameterConfiguration> parameters,
        List<ResourceSetConfiguration> preResources,
        List<ResourceSetConfiguration> jarResources,
        List<ResourceSetConfiguration> postResources
    ) {
    }

    record ResourceConfiguration(Map<String, String> attributes) {
    }

    record EnvironmentConfiguration(Map<String, String> attributes) {
    }

    record ParameterConfiguration(Map<String, String> attributes) {
    }

    record ResourceSetConfiguration(String elementName, Map<String, String> attributes) {
        boolean isSupported() {
            String className = attributes.get("className");
            return DIR_RESOURCE_SET_CLASS_NAME.equals(className)
                || JAR_RESOURCE_SET_CLASS_NAME.equals(className)
                || FILE_RESOURCE_SET_CLASS_NAME.equals(className);
        }
    }
}