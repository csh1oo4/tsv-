package com.choi.filemanager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The WebDAV XML the app has to speak, in one place.
 *
 * Only two shapes matter: the PROPFIND request body (asked for Depth 0 to test a login and
 * Depth 1 to list a folder) and the 207 Multi-Status answer. The answer is plain DOM work:
 *
 *   <d:multistatus xmlns:d="DAV:">
 *     <d:response>
 *       <d:href>/photo/a%20b.jpg</d:href>
 *       <d:propstat><d:prop>
 *         <d:displayname>a b.jpg</d:displayname>
 *         <d:resourcetype/>
 *         <d:getcontentlength>4312</d:getcontentlength>
 *         <d:getlastmodified>Tue, 24 Nov 2026 03:10:00 GMT</d:getlastmodified>
 *         <d:getcontenttype>image/jpeg</d:getcontenttype>
 *       </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 *     </d:response>
 *   </d:multistatus>
 *
 * Nothing here touches Android, so CoreTest can run it on a plain JVM (and the XML is parsed
 * with external entities switched off, because the document comes from the network).
 */
public final class DavXml {

    /** Body of a PROPFIND: the five properties the file list shows. */
    public static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:displayname/><d:resourcetype/><d:getcontentlength/>"
                    + "<d:getlastmodified/><d:getcontenttype/>"
                    + "</d:prop></d:propfind>";

    private DavXml() {
    }

    /** One <d:response> turned into what the page renders. */
    public static final class Entry {
        public String href = "";
        public String path = "";
        public String name = "";
        public boolean dir = false;
        public long size = 0L;
        public long mtime = 0L;
        public String contentType = "";

        @Override
        public String toString() {
            return (dir ? "dir " : "file ") + path + " (" + size + ")";
        }
    }

    /**
     * Parses a 207 body. Entries outside {@code baseUrl} are dropped, and so is the folder
     * itself (a Depth 1 answer always starts with the requested collection).
     */
    public static List<Entry> parse(String xml, String baseUrl) {
        List<Entry> out = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return out;
        }
        Document document;
        try {
            document = builder().parse(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return out;
        }
        List<Element> responses = byLocalName(document, "response");
        for (Element response : responses) {
            Entry entry = read(response, baseUrl);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The first entry of a Depth 0 answer, or null — used to name the server after login. */
    public static Entry first(String xml, String baseUrl) {
        List<Entry> entries = parse(xml, baseUrl);
        return entries.isEmpty() ? null : entries.get(0);
    }

    private static Entry read(Element response, String baseUrl) {
        String href = textOf(byLocalName(response, "href"));
        if (href.isEmpty()) {
            return null;
        }
        String path = Core.davPathOf(href, baseUrl);
        if (path.isEmpty()) {
            return null;
        }
        Entry entry = new Entry();
        entry.href = href;
        entry.path = path;
        entry.name = Core.nameOf(path);
        entry.dir = "/".equals(path) || hasCollection(response);

        List<Element> propstats = byLocalName(response, "propstat");
        String size = "";
        String modified = "";
        String type = "";
        String display = "";
        for (Element propstat : propstats) {
            String status = textOf(byLocalName(propstat, "status"));
            // A 404 propstat says "this property is not here"; a 200 one carries the value.
            if (!status.isEmpty() && status.indexOf("200") < 0) {
                continue;
            }
            for (Element prop : byLocalName(propstat, "prop")) {
                if (display.isEmpty()) {
                    display = textOf(byLocalName(prop, "displayname"));
                }
                if (size.isEmpty()) {
                    size = textOf(byLocalName(prop, "getcontentlength"));
                }
                if (modified.isEmpty()) {
                    modified = textOf(byLocalName(prop, "getlastmodified"));
                }
                if (type.isEmpty()) {
                    type = textOf(byLocalName(prop, "getcontenttype"));
                }
            }
        }
        if (display.isEmpty() || "/".equals(path)) {
            display = entry.name;
        }
        entry.name = display;
        entry.size = parseLong(size);
        entry.mtime = Core.parseHttpDate(modified);
        entry.contentType = type;
        return entry;
    }

    private static boolean hasCollection(Element response) {
        List<Element> types = byLocalName(response, "resourcetype");
        for (Element type : types) {
            if (!byLocalName(type, "collection").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** All elements with this local name, any namespace or prefix. */
    private static List<Element> byLocalName(Node root, String name) {
        List<Element> out = new ArrayList<>();
        collect(root, name, out);
        return out;
    }

    private static void collect(Node node, String name, List<Element> out) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String local = element.getLocalName();
            if (local == null) {
                local = localOf(element.getTagName());
            }
            if (name.equals(local)) {
                out.add(element);
            }
            collect(element, name, out);
        }
    }

    private static String localOf(String tagName) {
        int colon = tagName.indexOf(':');
        return colon < 0 ? tagName : tagName.substring(colon + 1);
    }

    private static String textOf(List<Element> elements) {
        if (elements.isEmpty()) {
            return "";
        }
        String text = elements.get(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    /** A hardened builder: no DOCTYPE, no external entities. */
    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException e) {
                // a warning never changes the answer
            }

            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return builder;
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // an implementation without this feature still parses fine
        }
    }
}
