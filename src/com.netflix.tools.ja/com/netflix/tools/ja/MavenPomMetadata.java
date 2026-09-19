/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.ja;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Merges publication metadata into a generated Maven consumer POM. */
final class MavenPomMetadata {
    private static final List<String> ELEMENTS = List.of("name", "description", "url", "licenses", "developers", "scm");

    private MavenPomMetadata() {}

    static void merge(Path consumerPom, Path publicationMetadata, Path output) throws IOException {
        try {
            Document consumer = parse(consumerPom);
            Document metadata = parse(publicationMetadata);
            Element project = consumer.getDocumentElement();
            Node insertionPoint = directChild(project, "dependencies");
            for (String name : ELEMENTS) {
                Node value = directChild(metadata.getDocumentElement(), name);
                if (value != null) {
                    project.insertBefore(consumer.importNode(value, true), insertionPoint);
                }
            }
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            try (var stream = Files.newOutputStream(output)) {
                transformer.transform(new DOMSource(consumer), new StreamResult(stream));
            }
        } catch (ParserConfigurationException | SAXException | TransformerException e) {
            throw new IOException("Failed to merge Maven publication metadata", e);
        }
    }

    private static Document parse(Path path) throws ParserConfigurationException, SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Node directChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild();
             child != null;
             child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }
}
