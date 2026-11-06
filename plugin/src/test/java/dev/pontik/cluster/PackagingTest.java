// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class PackagingTest {

    private static final String ZIP = System.getProperty("pontik.zip");

    @Test
    void theZipHoldsOneFolderWithThePluginFileAndTheJar() throws Exception {
        List<String> names = new ArrayList<>();

        try (ZipFile zip = new ZipFile(ZIP)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
        }

        assertEquals("pontik-cluster/", names.get(0));
        assertEquals(
            new HashSet<>(Arrays.asList(
                "pontik-cluster/", "pontik-cluster/plugin.xml", "pontik-cluster/pontik-cluster-server.jar")),
            new HashSet<>(names));
    }

    @Test
    void thePluginFileNamesTheExactEngineVersionAndTheServerJar() throws Exception {
        Element root = pluginXml().getDocumentElement();

        assertEquals("pontik-cluster", root.getAttribute("path"));
        assertEquals("4.6.0", text(root, "mirthVersion"));
        assertFalse(text(root, "pluginVersion").contains("@"));

        Element library = (Element) root.getElementsByTagName("library").item(0);
        assertEquals("SERVER", library.getAttribute("type"));
        assertEquals("pontik-cluster-server.jar", library.getAttribute("path"));
    }

    private static Document pluginXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (ZipFile zip = new ZipFile(ZIP);
                InputStream in = zip.getInputStream(zip.getEntry("pontik-cluster/plugin.xml"))) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String text(Element root, String tag) {
        return root.getElementsByTagName(tag).item(0).getTextContent().trim();
    }
}
