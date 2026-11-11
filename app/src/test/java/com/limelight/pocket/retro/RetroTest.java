package com.limelight.pocket.retro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RetroTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File asset(String name) {
        File f = new File("src/main/assets/esde/" + name);
        if (!f.exists()) {
            f = new File("app/src/main/assets/esde/" + name);
        }
        return f;
    }

    private static EsdeConfig bundled() throws IOException {
        try (InputStream s = new FileInputStream(asset("es_systems.xml"));
             InputStream r = new FileInputStream(asset("es_find_rules.xml"))) {
            return EsdeConfig.parse(s, r);
        }
    }

    /** A game at a fixed place, with made-up URIs. */
    private static EsdeCommand.FileEnv env(final String rom, final String pkg) {
        return new EsdeCommand.FileEnv() {
            @Override public String rom() { return rom; }
            @Override public String romRoot() { return "/storage/emulated/0/ROMs"; }
            @Override public String externalData() { return "/storage/emulated/0"; }
            @Override public String internalData() { return "/data/user/0"; }
            @Override public String androidPackage() { return pkg; }
            @Override public String saf() { return "content://saf/" + new File(rom).getName(); }
            @Override public String provider() { return "content://provider/" + new File(rom).getName(); }
        };
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void readsEsdeFiles() throws IOException {
        EsdeConfig c = bundled();
        assertTrue(c.systemCount() > 150);
        assertTrue(c.emulatorCount() > 80);
        EsdeConfig.SystemDef psx = c.system("psx");
        assertNotNull(psx);
        assertEquals("psx", psx.folder);
        assertTrue(psx.accepts("Crash Bandicoot (USA).CHD"));
        assertFalse(psx.accepts("readme.txt"));
        assertTrue(psx.playable());
        boolean duck = false;
        for (EsdeConfig.Command cmd : psx.commands) {
            duck |= "DUCKSTATION".equals(cmd.emulator);
        }
        assertTrue(duck);
        assertTrue(c.emulatorEntries("RETROARCH").get(0).startsWith("com.retroarch.aarch64/"));
        assertFalse(c.system("ags").playable());
    }

    @Test
    public void customSystemsReplaceAndAdd() throws IOException {
        EsdeConfig c = bundled();
        String custom = "<systemList><system><name>psx</name><fullname>My PS1</fullname><path>%ROMPATH%/ps1</path>"
                + "<extension>.chd</extension><command label=\"Mine\">%EMULATOR_MINE% %DATA%=%ROMPROVIDER%</command></system>"
                + "<system><name>new</name><fullname>New</fullname><path>/storage/X/new</path><extension>.x</extension>"
                + "<command>PLACEHOLDER %ROM%</command></system></systemList>";
        String rules = "<ruleList><emulator name=\"RETROARCH\"><rule type=\"androidpackage\"><entry>my.ra/.Main</entry></rule></emulator></ruleList>";
        int before = c.systemCount();
        c.merge(new ByteArrayInputStream(custom.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(rules.getBytes(StandardCharsets.UTF_8)));
        assertEquals(before + 1, c.systemCount());
        assertEquals("My PS1", c.system("psx").fullName);
        assertEquals("ps1", c.system("psx").folder);
        assertTrue(c.system("new").absolute);
        assertEquals("my.ra/.Main", c.emulatorEntries("RETROARCH").get(0));
        assertTrue(c.emulatorEntries("RETROARCH").size() > 1);
    }

    @Test
    public void retroArchCommand() {
        String cmd = "%EMULATOR_RETROARCH% %EXTRA_CONFIGFILE%=%EXTERNALDATA%/Android/data/%ANDROIDPACKAGE%/files/retroarch.cfg "
                + "%EXTRA_LIBRETRO%=%INTERNALDATA%/%ANDROIDPACKAGE%/cores/swanstation_libretro_android.so %EXTRA_ROM%=%ROM%";
        EsdeCommand.Spec s = EsdeCommand.resolve(cmd, env("/storage/emulated/0/ROMs/psx/Crash (USA).chd", "com.retroarch.aarch64"));
        assertEquals("RETROARCH", s.emulator);
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", s.extra("CONFIGFILE").value);
        assertEquals("/data/user/0/com.retroarch.aarch64/cores/swanstation_libretro_android.so", s.extra("LIBRETRO").value);
        assertEquals("/storage/emulated/0/ROMs/psx/Crash (USA).chd", s.extra("ROM").value);
        assertNull(s.action);
        assertFalse(s.usesProvider);
    }

    @Test
    public void standaloneCommands() {
        EsdeCommand.Spec duck = EsdeCommand.resolve("%EMULATOR_DUCKSTATION% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% "
                + "%EXTRABOOL_resumeState%=false %EXTRA_bootPath%=%ROMSAF%", env("/r/psx/a.chd", "x"));
        assertTrue(duck.clearTask && duck.clearTop);
        assertFalse(duck.extra("resumeState").bool());
        assertEquals(EsdeCommand.EXTRA_BOOL, duck.extra("resumeState").type);
        assertEquals("content://saf/a.chd", duck.extra("bootPath").value);
        assertTrue(duck.usesSaf);

        EsdeCommand.Spec ppsspp = EsdeCommand.resolve("%EMULATOR_PPSSPP% %ACTION%=android.intent.action.VIEW "
                + "%CATEGORY%=android.intent.category.DEFAULT %DATA%=%ROMSAF%", env("/r/psp/b.iso", "x"));
        assertEquals("android.intent.action.VIEW", ppsspp.action);
        assertEquals("android.intent.category.DEFAULT", ppsspp.category);
        assertEquals("content://saf/b.iso", ppsspp.data);

        EsdeCommand.Spec md = EsdeCommand.resolve("%EMULATOR_MD-EMU% %DATA%=%ROMPROVIDER%", env("/r/md/c.md", "x"));
        assertTrue(md.usesProvider);
        assertEquals("content://provider/c.md", md.data);

        EsdeCommand.Spec none = EsdeCommand.resolve("PLACEHOLDER %ROM%", env("/r/ags/d.zip", ""));
        assertTrue(none.placeholder);
    }

    @Test
    public void quotedValuesAndArrays() throws IOException {
        EsdeCommand.Spec mame = EsdeCommand.resolve("%EMULATOR_MAME4DROID-CURRENT% %ACTION%=android.intent.action.VIEW "
                + "%EXTRA_cli_params%=\"-rompath '%GAMEDIRRAW%;%ROMPATHRAW%/adam' -flop1 '%ROMRAW%'\" %DATA%=adam",
                env("/storage/emulated/0/ROMs/adam/My Game.dsk", "x"));
        assertEquals("-rompath '/storage/emulated/0/ROMs/adam;/storage/emulated/0/ROMs/adam' -flop1 '/storage/emulated/0/ROMs/adam/My Game.dsk'",
                mame.extra("cli_params").value);
        assertEquals("adam", mame.data);

        File dir = tmp.newFolder("psvita", "Gravity Rush.psvita");
        write(new File(dir, "Gravity Rush.psvita"), "PCSA00011\n");
        EsdeCommand.Spec vita = EsdeCommand.resolve("%EMULATOR_VITA3K% %EXTRAARRAY_AppStartParameters%=-r,%INJECT%=%BASENAME%.psvita",
                env(dir.getAbsolutePath(), "x"));
        String[] arr = vita.extra("AppStartParameters").array;
        assertEquals(2, arr.length);
        assertEquals("-r", arr[0]);
        assertEquals("PCSA00011", arr[1]);

        File steam = new File(tmp.getRoot(), "steam/Portal.steam");
        write(steam, "400");
        EsdeCommand.Spec gn = EsdeCommand.resolve("%EMULATOR_GAMENATIVE% %ACTION%=app.gamenative.LAUNCH_GAME "
                + "%EXTRA_game_source%=STEAM %EXTRAINTEGER_app_id%=%INJECT%=%ROM%", env(steam.getAbsolutePath(), "x"));
        assertEquals(Integer.valueOf(400), gn.extra("app_id").integer());
        assertEquals("STEAM", gn.extra("game_source").value);

        assertEquals(3, EsdeCommand.splitArray("pone,p\\,two,pthree").size());
        assertEquals("p,two", EsdeCommand.splitArray("pone,p\\,two,pthree").get(1));
    }

    @Test
    public void scansLikeEsde() throws IOException {
        EsdeConfig c = bundled();
        File psx = tmp.newFolder("ROMs", "psx");
        write(new File(psx, "Final Fantasy VII.m3u"), "Final Fantasy VII (USA) (Disc 1).cue\nFinal Fantasy VII (USA) (Disc 2).cue\n");
        write(new File(psx, "Final Fantasy VII (USA) (Disc 1).cue"), "FILE \"Final Fantasy VII (USA) (Disc 1).bin\" BINARY\n");
        write(new File(psx, "Final Fantasy VII (USA) (Disc 1).bin"), "x");
        write(new File(psx, "Final Fantasy VII (USA) (Disc 2).cue"), "FILE \"Final Fantasy VII (USA) (Disc 2).bin\" BINARY\n");
        write(new File(psx, "Final Fantasy VII (USA) (Disc 2).bin"), "x");
        write(new File(psx, "Crash Bandicoot (USA).chd"), "x");
        write(new File(psx, "readme.txt"), "x");
        write(new File(psx, "media/covers/Crash Bandicoot (USA).png"), "x");
        write(new File(psx, "Hacks/Some Hack.chd"), "x");
        write(new File(psx, "Skip/noload.txt"), "");
        write(new File(psx, "Skip/Hidden.chd"), "x");
        List<RomScanner.Rom> roms = RomScanner.scan(c.system("psx"), psx);
        List<String> paths = new ArrayList<>();
        for (RomScanner.Rom r : roms) {
            paths.add(r.relPath);
        }
        assertEquals(3, roms.size());
        assertTrue(paths.contains("Final Fantasy VII.m3u"));
        assertTrue(paths.contains("Crash Bandicoot (USA).chd"));
        assertTrue(paths.contains("Hacks/Some Hack.chd"));

        File ps3 = tmp.newFolder("ROMs", "ps3");
        assertTrue(new File(ps3, "Demon's Souls.ps3").mkdirs());
        write(new File(ps3, "Demon's Souls.ps3/PS3_GAME/PARAM.SFO"), "x");
        List<RomScanner.Rom> folderGames = RomScanner.scan(c.system("ps3"), ps3);
        assertEquals(1, folderGames.size());
        assertTrue(folderGames.get(0).directory);
        assertEquals("Demon's Souls", folderGames.get(0).name);
    }

    @Test
    public void cleansNames() {
        assertEquals("The Legend of Zelda - A Link to the Past",
                RomScanner.cleanName("Legend of Zelda, The - A Link to the Past (USA) [!].sfc", false));
        assertEquals("Final Fantasy VII · Disc 2", RomScanner.cleanName("Final Fantasy VII (USA) (Disc 2).chd", false));
        assertEquals("Final Fantasy VII", RomScanner.searchName("Final Fantasy VII · Disc 2"));
        assertEquals("Super Mario World", RomScanner.cleanName("Super_Mario_World.smc", false));
        assertEquals("(Prototype)", RomScanner.cleanName("(Prototype).zip", false));
        assertTrue(RomScanner.idFor("psx", "a.chd") > 0);
    }

    @Test
    public void readsGamelist() throws IOException {
        // Exactly how ES-DE writes it: two top elements
        String doc = "<?xml version=\"1.0\"?>\n<alternativeEmulator>\n\t<label>DuckStation (Standalone)</label>\n</alternativeEmulator>\n"
                + "<gameList>\n<game><path>./Crash Bandicoot (USA).chd</path><name>Crash Bandicoot</name><favorite>true</favorite>"
                + "<altemulator>Beetle PSX HW</altemulator></game><game><path>./sub/Hidden.chd</path><hidden>true</hidden></game>"
                + "<game><path>./Tom &amp; Jerry.chd</path><name>Tom &amp; Jerry</name></game>"
                + "</gameList>\n";
        EsdeGamelist g = EsdeGamelist.parse(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));
        assertEquals("DuckStation (Standalone)", g.altEmulator);
        assertEquals("Crash Bandicoot", g.games.get("Crash Bandicoot (USA).chd").name);
        assertTrue(g.games.get("Crash Bandicoot (USA).chd").favorite);
        assertEquals("Beetle PSX HW", g.games.get("Crash Bandicoot (USA).chd").altEmulator);
        assertTrue(g.games.get("sub/Hidden.chd").hidden);
        assertEquals("Tom & Jerry", g.games.get("Tom & Jerry.chd").name);
    }
}
