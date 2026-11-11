package com.limelight.pocket.retro;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

/**
 * ES-DE's gamelist.xml for a system (when ES-DE was used on this handheld): the names it
 * scraped, the favourites, the hidden games and the emulator picked for the system or a game.
 */
public final class EsdeGamelist {

    public static final class Game {
        public String name;
        public String description;
        public boolean favorite;
        public boolean hidden;
        /** The emulator picked for this game (a command label), or null. */
        public String altEmulator;
    }

    /** By path under the system folder ("sub/Game.zip"). */
    public final Map<String, Game> games = new HashMap<>();
    /** The emulator picked for the whole system (a command label), or null. */
    public String altEmulator;

    /** "./sub/Game.zip" -> "sub/Game.zip". */
    static String relPath(String path) {
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /**
     * ES-DE writes two top elements (alternativeEmulator, then gameList), which isn't one XML
     * document: they're read inside a made-up root.
     */
    public static EsdeGamelist parse(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bytes.write(buf, 0, n);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
        final EsdeGamelist list = new EsdeGamelist();
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.newSAXParser().parse(new InputSource(new StringReader("<pocketRoot>" + text + "</pocketRoot>")), new DefaultHandler() {
                final StringBuilder text = new StringBuilder();
                Game game;
                String path;
                boolean inAlt;

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    text.setLength(0);
                    if ("game".equals(qName) || "folder".equals(qName)) {
                        game = new Game();
                        path = null;
                    }
                    else if ("alternativeEmulator".equals(qName)) {
                        inAlt = true;
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    String t = text.toString().trim();
                    if (inAlt) {
                        if ("label".equals(qName) && !t.isEmpty()) {
                            list.altEmulator = t;
                        }
                        else if ("alternativeEmulator".equals(qName)) {
                            inAlt = false;
                        }
                    }
                    else if (game != null) {
                        switch (qName) {
                            case "path":
                                path = relPath(t);
                                break;
                            case "name":
                                game.name = t;
                                break;
                            case "desc":
                                game.description = t;
                                break;
                            case "favorite":
                                game.favorite = "true".equalsIgnoreCase(t);
                                break;
                            case "hidden":
                                game.hidden = "true".equalsIgnoreCase(t);
                                break;
                            case "altemulator":
                                game.altEmulator = t.isEmpty() ? null : t;
                                break;
                            case "game":
                            case "folder":
                                if (path != null && !path.isEmpty()) {
                                    list.games.put(path, game);
                                }
                                game = null;
                                break;
                            default:
                                break;
                        }
                    }
                    text.setLength(0);
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("gamelist: " + e.getMessage(), e);
        }
        return list;
    }
}
