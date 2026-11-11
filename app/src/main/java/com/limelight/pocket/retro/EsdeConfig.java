package com.limelight.pocket.retro;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.SAXParserFactory;

/**
 * The game systems and emulators, read from ES-DE's own Android configuration files
 * (es_systems.xml and es_find_rules.xml, bundled in assets/esde, MIT licence): the same ROM
 * folder names, file extensions and launch commands (RetroArch cores and standalone emulators)
 * as ES-DE. Its custom_systems files, when present, add to them or replace systems by name.
 */
public final class EsdeConfig {

    /** One of a system's launch commands (ES-DE's "alternative emulators"). */
    public static final class Command {
        public final String label;
        public final String template;
        /** The find rule it uses (%EMULATOR_X%), or null (a plain app, or no emulator). */
        public final String emulator;

        Command(String label, String template) {
            this.label = label;
            this.template = template;
            this.emulator = emulatorOf(template);
        }

        public boolean isRetroArch() {
            return "RETROARCH".equals(emulator);
        }

        /** No emulator exists for it on Android (ES-DE's PLACEHOLDER). */
        public boolean isPlaceholder() {
            return template.trim().startsWith("PLACEHOLDER");
        }

        /** The label without "(Standalone)". */
        public String shortLabel() {
            String l = label.replace("(Standalone)", "").trim();
            return l.isEmpty() ? label : l;
        }

        static String emulatorOf(String template) {
            int i = template.indexOf("%EMULATOR_");
            if (i < 0) {
                return null;
            }
            int end = template.indexOf('%', i + 10);
            return end > i + 10 ? template.substring(i + 10, end) : null;
        }
    }

    /** A game system: its ROM folder and how its games start. */
    public static final class SystemDef {
        public final String name;
        public final String fullName;
        /** The folder under the ROM directory (or an absolute path, from a custom file). */
        public final String folder;
        public final boolean absolute;
        public final String platform;
        public final String theme;
        /** Lower case, with the dot (a lone "." means files without an extension). */
        public final Set<String> extensions;
        public final List<Command> commands;

        SystemDef(String name, String fullName, String path, String platform, String theme,
                  Set<String> extensions, List<Command> commands) {
            this.name = name;
            this.fullName = fullName == null || fullName.isEmpty() ? name : fullName;
            String p = path == null ? "" : path.trim();
            if (p.startsWith("%ROMPATH%")) {
                p = p.substring(9);
                while (p.startsWith("/") || p.startsWith("\\")) {
                    p = p.substring(1);
                }
                this.absolute = false;
            }
            else {
                this.absolute = p.startsWith("/");
            }
            this.folder = p.isEmpty() ? name : p;
            this.platform = platform == null ? "" : platform;
            this.theme = theme == null ? "" : theme;
            this.extensions = Collections.unmodifiableSet(extensions);
            this.commands = Collections.unmodifiableList(commands);
        }

        public Command command(String label) {
            if (label == null) {
                return null;
            }
            for (Command c : commands) {
                if (c.label.equals(label)) {
                    return c;
                }
            }
            return null;
        }

        /** True when a file with this name is one of the system's games. */
        public boolean accepts(String fileName) {
            return extensions.contains(extensionOf(fileName));
        }

        /** True when the system has a real emulator on Android. */
        public boolean playable() {
            for (Command c : commands) {
                if (!c.isPlaceholder()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** ".zip" for "Game (USA).zip", "." when there's none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return ".";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private final Map<String, SystemDef> systems = new LinkedHashMap<>();
    private final Map<String, List<String>> emulators = new LinkedHashMap<>();

    public List<SystemDef> systems() {
        return new ArrayList<>(systems.values());
    }

    public SystemDef system(String name) {
        return systems.get(name);
    }

    /** The emulator's "package/activity" entries, most wanted first (empty when unknown). */
    public List<String> emulatorEntries(String emulator) {
        List<String> e = emulator == null ? null : emulators.get(emulator);
        return e != null ? e : Collections.<String>emptyList();
    }

    public int systemCount() {
        return systems.size();
    }

    public int emulatorCount() {
        return emulators.size();
    }

    // ---- Reading ------------------------------------------------------------------------------

    public static EsdeConfig parse(InputStream systemsXml, InputStream findRulesXml) throws IOException {
        EsdeConfig c = new EsdeConfig();
        if (systemsXml != null) {
            for (SystemDef s : parseSystems(systemsXml)) {
                c.systems.put(s.name, s);
            }
        }
        if (findRulesXml != null) {
            c.emulators.putAll(parseFindRules(findRulesXml));
        }
        return c;
    }

    /**
     * ES-DE's custom files on top: a system of the same name is replaced, a new one added; an
     * emulator's custom entries are tried first.
     */
    public void merge(InputStream customSystems, InputStream customFindRules) throws IOException {
        if (customSystems != null) {
            for (SystemDef s : parseSystems(customSystems)) {
                systems.put(s.name, s);
            }
        }
        if (customFindRules != null) {
            for (Map.Entry<String, List<String>> e : parseFindRules(customFindRules).entrySet()) {
                List<String> merged = new ArrayList<>(e.getValue());
                List<String> before = emulators.get(e.getKey());
                if (before != null) {
                    for (String b : before) {
                        if (!merged.contains(b)) {
                            merged.add(b);
                        }
                    }
                }
                emulators.put(e.getKey(), merged);
            }
        }
    }

    private static void saxParse(InputStream in, DefaultHandler handler) throws IOException {
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            f.newSAXParser().parse(new InputSource(in), handler);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ES-DE file: " + e.getMessage(), e);
        }
    }

    static List<SystemDef> parseSystems(InputStream in) throws IOException {
        final List<SystemDef> out = new ArrayList<>();
        saxParse(in, new DefaultHandler() {
            final StringBuilder text = new StringBuilder();
            String name, fullName, path, platform, theme, ext, commandLabel;
            List<Command> commands;
            boolean inSystem;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                text.setLength(0);
                if ("system".equals(qName)) {
                    inSystem = true;
                    name = fullName = path = platform = theme = ext = null;
                    commands = new ArrayList<>();
                }
                else if ("command".equals(qName)) {
                    commandLabel = attributes.getValue("label");
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                text.append(ch, start, length);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if (!inSystem) {
                    return;
                }
                String t = text.toString().trim();
                switch (qName) {
                    case "name":
                        name = t;
                        break;
                    case "fullname":
                        fullName = t;
                        break;
                    case "path":
                        path = t;
                        break;
                    case "platform":
                        platform = t;
                        break;
                    case "theme":
                        theme = t;
                        break;
                    case "extension":
                        ext = t;
                        break;
                    case "command":
                        if (!t.isEmpty()) {
                            String label = commandLabel != null && !commandLabel.trim().isEmpty()
                                    ? commandLabel.trim() : ("#" + (commands.size() + 1));
                            commands.add(new Command(label, t));
                        }
                        commandLabel = null;
                        break;
                    case "system":
                        inSystem = false;
                        if (name != null && !name.isEmpty()) {
                            Set<String> exts = new LinkedHashSet<>();
                            if (ext != null) {
                                for (String e : ext.split("\\s+")) {
                                    if (e.isEmpty()) {
                                        continue;
                                    }
                                    exts.add(e.equals(".") ? "." : e.toLowerCase(Locale.ROOT));
                                }
                            }
                            out.add(new SystemDef(name, fullName, path, platform, theme, exts, commands));
                        }
                        break;
                    default:
                        break;
                }
                text.setLength(0);
            }
        });
        return out;
    }

    static Map<String, List<String>> parseFindRules(InputStream in) throws IOException {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        saxParse(in, new DefaultHandler() {
            final StringBuilder text = new StringBuilder();
            String emulator;
            boolean androidRule;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                text.setLength(0);
                if ("emulator".equals(qName)) {
                    emulator = attributes.getValue("name");
                    if (emulator != null && !out.containsKey(emulator)) {
                        out.put(emulator, new ArrayList<String>());
                    }
                }
                else if ("rule".equals(qName)) {
                    androidRule = "androidpackage".equals(attributes.getValue("type"));
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                text.append(ch, start, length);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("entry".equals(qName) && androidRule && emulator != null) {
                    String t = text.toString().trim();
                    if (!t.isEmpty() && !out.get(emulator).contains(t)) {
                        out.get(emulator).add(t);
                    }
                }
                else if ("emulator".equals(qName)) {
                    emulator = null;
                }
                text.setLength(0);
            }
        });
        return out;
    }
}
