// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.model.MetaData;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class PackagingTest {

    private static final String ZIP = System.getProperty("pontik.zip");
    private static final String SERVER = "pontik-cluster-server.jar";
    private static final String SHARED = "pontik-cluster-shared.jar";

    @Test
    void theZipHoldsOneFolderWithThePluginFileAndBothJars() throws Exception {
        List<String> names = new ArrayList<>();

        try (ZipFile zip = new ZipFile(ZIP)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
        }

        assertEquals("pontik-cluster/", names.get(0));
        assertEquals(
            new HashSet<>(Arrays.asList("pontik-cluster/", "pontik-cluster/plugin.xml", "pontik-cluster/" + SERVER,
                "pontik-cluster/" + SHARED)),
            new HashSet<>(names));
    }

    @Test
    void thePluginFileNamesTheExactEngineVersionAndBothJars() throws Exception {
        Element root = pluginXml().getDocumentElement();

        assertEquals("pontik-cluster", root.getAttribute("path"));
        assertEquals(engineVersion(), text(root, "mirthVersion"));
        assertFalse(text(root, "pluginVersion").contains("@"));

        Map<String, String> libraries = new HashMap<>();
        NodeList list = root.getElementsByTagName("library");
        for (int i = 0; i < list.getLength(); i++) {
            Element library = (Element) list.item(i);
            libraries.put(library.getAttribute("path"), library.getAttribute("type"));
        }
        Map<String, String> expected = new HashMap<>();
        expected.put(SERVER, "SERVER");
        expected.put(SHARED, "SHARED");
        assertEquals(expected, libraries);
    }

    @Test
    void theEngineReadsThePluginFile() throws Exception {
        // The engine reads plugin.xml with its own serializer when it installs the zip, so a header or
        // a token it cannot read would otherwise show up only on an engine.
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        serializer.init(engineVersion());

        MetaData plugin = serializer.deserialize(pluginXmlText(), MetaData.class);

        assertEquals(PluginMetaData.class, plugin.getClass());
        assertEquals("pontik-cluster", plugin.getPath());
        assertEquals(engineVersion(), plugin.getMirthVersion());
    }

    @Test
    void everyClassThePluginFileNamesIsInTheJarTheEngineLoadsItFrom() throws Exception {
        Element root = pluginXml().getDocumentElement();
        Set<String> server = classes(SERVER);
        Set<String> shared = classes(SHARED);

        NodeList plugins = root.getElementsByTagName("string");
        assertEquals(1, plugins.getLength());
        for (int i = 0; i < plugins.getLength(); i++) {
            assertTrue(server.contains(plugins.item(i).getTextContent().trim()));
        }

        NodeList providers = root.getElementsByTagName("apiProvider");
        assertEquals(2, providers.getLength());
        for (int i = 0; i < providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            String name = provider.getAttribute("name");
            assertTrue("SERVLET_INTERFACE".equals(provider.getAttribute("type")) ? shared.contains(name)
                : server.contains(name), name);
        }
    }

    @Test
    void theSharedJarHoldsOnlyTheRestInterfaceAndItsViews() throws Exception {
        for (String name : classes(SHARED)) {
            assertTrue(name.startsWith("dev.pontik.cluster.api.ClusterServletInterface")
                || name.startsWith("dev.pontik.cluster.api.ChannelView")
                || name.startsWith("dev.pontik.cluster.api.NodeView"), name);
        }
        Set<String> server = classes(SERVER);
        assertFalse(server.contains("dev.pontik.cluster.api.ClusterServletInterface"));
        assertTrue(server.contains("dev.pontik.cluster.api.ClusterServlet"));
    }

    @Test
    void bothJarsCarryTheLicenseText() throws Exception {
        byte[] license = Files.readAllBytes(Paths.get("../LICENSE"));

        for (String jar : Arrays.asList(SERVER, SHARED)) {
            assertArrayEquals(license, entry(jar, "META-INF/LICENSE"), jar);
        }
    }

    private static Set<String> classes(String jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(jarBytes(jar)))) {
            for (ZipEntry e; (e = entries.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class")) {
                    names.add(e.getName().replace('/', '.').replaceAll("\\.class$", ""));
                }
            }
        }
        return names;
    }

    private static byte[] jarBytes(String jar) throws Exception {
        try (ZipFile zip = new ZipFile(ZIP);
                InputStream in = zip.getInputStream(zip.getEntry("pontik-cluster/" + jar))) {
            return readAll(in);
        }
    }

    private static byte[] entry(String jar, String name) throws Exception {
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(jarBytes(jar)))) {
            for (ZipEntry e; (e = entries.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return readAll(entries);
                }
            }
        }
        throw new AssertionError(jar + " has no " + name);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private static Document pluginXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (ZipFile zip = new ZipFile(ZIP);
                InputStream in = zip.getInputStream(zip.getEntry("pontik-cluster/plugin.xml"))) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String pluginXmlText() throws Exception {
        try (ZipFile zip = new ZipFile(ZIP);
                InputStream in = zip.getInputStream(zip.getEntry("pontik-cluster/plugin.xml"))) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String text(Element root, String tag) {
        return root.getElementsByTagName(tag).item(0).getTextContent().trim();
    }

    // The engine installs a plugin only when plugin.xml names the mirth.version its own jar reports.
    private static String engineVersion() throws Exception {
        List<String> versions = new ArrayList<>();
        Enumeration<URL> files = PackagingTest.class.getClassLoader().getResources("version.properties");
        while (files.hasMoreElements()) {
            Properties properties = new Properties();
            try (InputStream in = files.nextElement().openStream()) {
                properties.load(in);
            }
            if (properties.getProperty("mirth.version") != null) {
                versions.add(properties.getProperty("mirth.version"));
            }
        }
        assertEquals(1, versions.size(), "mirth.version found in " + versions.size() + " files");
        return versions.get(0);
    }
}
