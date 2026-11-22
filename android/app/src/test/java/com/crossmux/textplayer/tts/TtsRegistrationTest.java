package com.crossmux.textplayer.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

public final class TtsRegistrationTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TTS_ACTION = "android.intent.action.TTS_SERVICE";

    @Test
    public void serviceIsDefaultDiscoverableAndPublishesEngineMetadata() throws Exception {
        Element service = onlyElement(readManifest().getDocumentElement(), "service");

        assertEquals("true", service.getAttributeNS(ANDROID, "exported"));
        assertEquals("android.permission.BIND_TEXT_TO_SPEECH_SERVICE",
                service.getAttributeNS(ANDROID, "permission"));
        assertTrue(hasDefaultIntent(service, TTS_ACTION));
        Element metadata = onlyElement(service, "meta-data");
        assertEquals("android.speech.tts", metadata.getAttributeNS(ANDROID, "name"));
        assertEquals("@xml/tts_engine", metadata.getAttributeNS(ANDROID, "resource"));
    }

    @Test
    public void voiceDataCheckHasTheStandardDefaultIntent() throws Exception {
        Element activity = elementByName(readManifest(), "activity", ".tts.TtsDataActivity");

        assertEquals("true", activity.getAttributeNS(ANDROID, "exported"));
        assertTrue(hasDefaultIntent(activity, "android.speech.tts.engine.CHECK_TTS_DATA"));
    }

    @Test
    public void metadataOpensTexpodSettings() throws Exception {
        Document metadata = readXml("src/main/res/xml/tts_engine.xml");

        assertEquals("com.crossmux.textplayer.SettingsActivity",
                metadata.getDocumentElement().getAttributeNS(ANDROID, "settingsActivity"));
        Element activity = elementByName(readManifest(), "activity", ".SettingsActivity");
        assertEquals("true", activity.getAttributeNS(ANDROID, "exported"));
    }

    @Test
    public void ttsServiceQueryIsDeclaredForAndroid11AndLater() throws Exception {
        Element queries = onlyElement(readManifest().getDocumentElement(), "queries");

        assertEquals(TTS_ACTION, onlyElement(queries, "action").getAttributeNS(ANDROID, "name"));
    }

    private Document readManifest() throws Exception {
        return readXml("src/main/AndroidManifest.xml");
    }

    private Document readXml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(Paths.get(path).toFile());
    }

    private Element onlyElement(Element parent, String tag) {
        NodeList elements = parent.getElementsByTagName(tag);
        assertEquals(1, elements.getLength());
        return (Element) elements.item(0);
    }

    private Element elementByName(Document document, String tag, String name) {
        NodeList elements = document.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (name.equals(element.getAttributeNS(ANDROID, "name"))) {
                return element;
            }
        }
        assertNotNull("Missing " + tag + " named " + name, null);
        return null;
    }

    private boolean hasDefaultIntent(Element parent, String expectedAction) {
        NodeList filters = parent.getElementsByTagName("intent-filter");
        for (int index = 0; index < filters.getLength(); index++) {
            Element filter = (Element) filters.item(index);
            if (!expectedAction.equals(onlyElement(filter, "action").getAttributeNS(ANDROID, "name"))) {
                continue;
            }
            NodeList categories = filter.getElementsByTagName("category");
            for (int category = 0; category < categories.getLength(); category++) {
                if ("android.intent.category.DEFAULT".equals(
                        ((Element) categories.item(category)).getAttributeNS(ANDROID, "name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
