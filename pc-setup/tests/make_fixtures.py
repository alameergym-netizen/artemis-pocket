"""Makes a small made-up Steam folder for PocketNexus-PC.ps1 -SelfTest (run in CI on Windows).

tests/fixtures/steam            the Steam folder: libraries, manifests, account files,
                                appinfo.vdf (v29), library art cache
tests/fixtures/steamlib2        a second library folder (a game downloading)
tests/fixtures/appinfo_v28.vdf  the same apps in the older formats
tests/fixtures/appinfo_v27.vdf
"""
import os
import shutil
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
FX = os.path.join(HERE, "fixtures")
STEAM = os.path.join(FX, "steam")
LIB2 = os.path.join(FX, "steamlib2")

APPS = [
    (100, "Test Installed Game", "Game"),
    (200, "Owned Not Installed", "game"),
    (300, "Some DLC", "DLC"),
    (400, "Some SDK", "Tool"),
    (228980, "Steamworks Common Redistributables", "Tool"),
]


def write(path, data, mode="w"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if mode == "w":
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(data)
    else:
        with open(path, "wb") as f:
            f.write(data)


def vdf_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


# ---- binary KeyValues ------------------------------------------------------------------------

class Kv:
    """Binary KeyValues writer; keys as C strings, or (v29) as indexes into a string table."""

    def __init__(self, table=None):
        self.table = table

    def key(self, k):
        if self.table is None:
            return k.encode("utf-8") + b"\0"
        if k not in self.table:
            self.table.append(k)
        return struct.pack("<i", self.table.index(k))

    def obj(self, k, items):
        out = b"\x00" + self.key(k)
        for item in items:
            out += item
        return out + b"\x08"

    def s(self, k, v):
        return b"\x01" + self.key(k) + v.encode("utf-8") + b"\0"

    def i(self, k, v):
        return b"\x02" + self.key(k) + struct.pack("<i", v)


def appinfo(version):
    table = [] if version >= 0x29 else None
    kv = Kv(table)
    body = b""
    for appid, name, typ in APPS:
        data = kv.obj("appinfo", [
            kv.i("appid", appid),
            kv.obj("common", [kv.s("name", name), kv.s("type", typ), kv.i("gameid", appid)]),
            kv.obj("extended", [kv.s("developer", "Somebody")]),
        ]) + b"\x08"
        header = struct.pack("<IIQ", 2, 1700000000, 0) + b"\x11" * 20 + struct.pack("<I", 5)
        if version >= 0x28:
            header += b"\x22" * 20
        entry = header + data
        body += struct.pack("<II", appid, len(entry)) + entry
    body += struct.pack("<I", 0)
    magic = 0x07564400 | version
    if version >= 0x29:
        start = struct.pack("<II", magic, 1)
        table_at = len(start) + 8 + len(body)
        out = start + struct.pack("<q", table_at) + body
        out += struct.pack("<I", len(table)) + b"".join(t.encode("utf-8") + b"\0" for t in table)
        return out
    return struct.pack("<II", magic, 1) + body


def shortcuts():
    kv = Kv()
    entries = [
        kv.obj("0", [
            kv.i("appid", -1294967295),
            kv.s("AppName", "My Emulator"),
            kv.s("Exe", '"C:\\Emu\\emu.exe"'),
            kv.s("StartDir", '"C:\\Emu\\"'),
            kv.s("icon", ""),
            kv.s("LaunchOptions", ""),
            kv.i("IsHidden", 0),
            kv.i("LastPlayTime", 1700000200),
            kv.obj("tags", [kv.s("0", "favorite")]),
        ]),
        kv.obj("1", [
            kv.i("appid", 5),
            kv.s("AppName", "Hidden One"),
            kv.s("Exe", "x.exe"),
            kv.i("IsHidden", 1),
        ]),
    ]
    return kv.obj("shortcuts", entries) + b"\x08"


def manifest(appid, name, flags, size, to_get=0, got=0, last=0):
    return ('"AppState"\n{\n\t"appid"\t\t"%d"\n\t"name"\t\t"%s"\n\t"StateFlags"\t\t"%d"\n\t"installdir"\t\t"%s"\n'
            '\t"LastPlayed"\t\t"%d"\n\t"SizeOnDisk"\t\t"%d"\n\t"BytesToDownload"\t\t"%d"\n\t"BytesDownloaded"\t\t"%d"\n'
            '\t"UserConfig"\n\t{\n\t\t"name"\t\t"ignored"\n\t}\n}\n') % (appid, name, flags, name.replace(" ", ""), last, size,
                                                                      to_get, got)


def main():
    shutil.rmtree(FX, ignore_errors=True)
    write(os.path.join(STEAM, "steamapps", "libraryfolders.vdf"),
          '"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t""\n\t}\n\t"1"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n}\n'
          % (vdf_escape(STEAM), vdf_escape(LIB2)))
    write(os.path.join(STEAM, "steamapps", "appmanifest_100.acf"),
          manifest(100, "Test Installed Game", 4, 2048 * 1024 * 1024, last=1690000000))
    write(os.path.join(STEAM, "steamapps", "appmanifest_228980.acf"),
          manifest(228980, "Steamworks Common Redistributables", 4, 1024))
    write(os.path.join(LIB2, "steamapps", "appmanifest_101.acf"),
          manifest(101, "Downloading Game", 1026, 0, to_get=1000, got=400))
    write(os.path.join(STEAM, "config", "loginusers.vdf"),
          '"users"\n{\n\t"76561197960265729"\n\t{\n\t\t"AccountName"\t\t"tester"\n\t\t"MostRecent"\t\t"1"\n\t}\n}\n')
    write(os.path.join(STEAM, "userdata", "1", "config", "localconfig.vdf"),
          '"UserLocalConfigStore"\n{\n\t"Software"\n\t{\n\t\t"Valve"\n\t\t{\n\t\t\t"Steam"\n\t\t\t{\n\t\t\t\t"apps"\n\t\t\t\t{\n'
          '\t\t\t\t\t"100"\n\t\t\t\t\t{\n\t\t\t\t\t\t"LastPlayed"\t\t"1700000100"\n\t\t\t\t\t\t"Playtime"\t\t"125"\n\t\t\t\t\t}\n'
          '\t\t\t\t\t"400"\n\t\t\t\t\t{\n\t\t\t\t\t\t"LastPlayed"\t\t"1690000000"\n\t\t\t\t\t\t"Playtime"\t\t"30"\n\t\t\t\t\t}\n'
          '\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n')
    write(os.path.join(STEAM, "userdata", "1", "config", "shortcuts.vdf"), shortcuts(), "b")
    cache = os.path.join(STEAM, "appcache", "librarycache")
    for name in ("100_header.jpg", "200_library_600x900.jpg", "500_header.jpg"):
        write(os.path.join(cache, name), b"", "b")
    os.makedirs(os.path.join(cache, "300"), exist_ok=True)
    write(os.path.join(STEAM, "appcache", "appinfo.vdf"), appinfo(0x29), "b")
    write(os.path.join(FX, "appinfo_v28.vdf"), appinfo(0x28), "b")
    write(os.path.join(FX, "appinfo_v27.vdf"), appinfo(0x27), "b")
    print("fixtures in", FX)


if __name__ == "__main__":
    main()
