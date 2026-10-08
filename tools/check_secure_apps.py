"""Offline integration checks only; does NOT compile Kotlin or execute JUnit."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def head(path):
    return subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=ROOT).decode("utf-8").replace("\r\n", "\n")


def check():
    # Parse resources; missing IDs otherwise become opaque view-binding compile errors.
    xml_files = list((ROOT / "app/src/main").rglob("*.xml"))
    for path in xml_files:
        ET.parse(path)
    manifest = ET.fromstring(read("app/src/main/AndroidManifest.xml"))
    grants = [x for x in manifest.findall("uses-permission")
              if x.get(ANDROID + "name") == "android.permission.WRITE_SECURE_SETTINGS"]
    assert len(grants) == 1 and grants[0].get(TOOLS + "ignore") == "ProtectedPermissions"

    layout = ET.fromstring(read("app/src/main/res/layout/activity_main.xml"))
    ids = [x.get(ANDROID + "id") for x in layout.iter() if x.get(ANDROID + "id")]
    assert len(ids) == len(set(ids)), "Duplicate layout IDs"
    assert "@+id/secureApps" in ids and "@+id/pickSecureApps" in ids
    apps = next(x for x in layout.iter() if x.get(ANDROID + "id") == "@+id/appsSection")
    assert any(x.get(ANDROID + "id") == "@+id/secureApps" for x in apps.iter())

    aidl_path = "app/src/main/aidl/com/shunp/vitmobile/IShizukuTouchService.aidl"
    method = r"(?:ParcelFileDescriptor|void|int|String)\s+(\w+)\([^;]*?\)\s*=\s*(\d+);"
    old_ids = dict(re.findall(method, head(aidl_path)))
    new_ids = dict(re.findall(method, read(aidl_path)))
    assert all(new_ids.get(name) == value for name, value in old_ids.items())
    assert new_ids["runShell"] == "4"
    assert len(new_ids.values()) == len(set(new_ids.values())), "AIDL transaction collision"
    service = read("app/src/main/java/com/shunp/vitmobile/ShizukuTouchService.kt")
    for name in new_ids:
        assert f"override fun {name}(" in service, f"Missing AIDL implementation: {name}"

    # Compile/target SDK, dependency versions, app versionCode and versionName stay untouched.
    for path in ("app/build.gradle.kts", "build.gradle.kts"):
        assert read(path).replace("\r\n", "\n") == head(path), f"Unexpected build config change: {path}"
    tracked = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "HEAD", "app/src/test"], cwd=ROOT).decode().splitlines()
    for path in tracked:
        assert read(path).replace("\r\n", "\n") == head(path), f"Existing test modified: {path}"

    prefs = read("app/src/main/java/com/shunp/vitmobile/Prefs.kt")
    block = prefs.split("private val DEFAULT_SECURE_APPS = listOf(", 1)[1].split(').joinToString', 1)[0]
    packages = re.findall(r'"([\w.]+)"', block)
    assert len(packages) == len(set(packages)), "Duplicate defaults"
    sources = read("docs/secure-apps.md")
    for package in packages:
        assert f"https://play.google.com/store/apps/details?id={package}" in sources, f"Missing package source: {package}"

    tests = read("app/src/test/java/com/shunp/vitmobile/SecureAppModeTest.kt")
    print(f"PASS: {len(xml_files)} XML files; permission + picker IDs; append-only AIDL; build config + {len(tracked)} existing test files unchanged; {len(packages)} default/source pairs")
    print(f"NOT RUN: Kotlin/Android compilation and JUnit ({tests.count('@Test fun')} new transition tests)")


if __name__ == "__main__":
    check()
