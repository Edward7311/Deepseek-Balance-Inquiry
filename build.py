#!/usr/bin/env python3
"""
Build app/ into a signed, installable APK without Gradle.

Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner

Run:  python build.py
Out:  out/<name>-<version>.apk
"""

import glob
import os
import shutil
import subprocess
import sys
import zipfile

# Chinese Windows consoles default to GBK, which cannot encode every byte a
# toolchain emits. Force UTF-8 with replacement so logging never crashes a build.
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app")
TOOLS = os.path.join(ROOT, "tools")
BUILD = os.path.join(ROOT, "build")
OUT = os.path.join(ROOT, "out")

APK_BASENAME = "dsbalance"
VERSION_CODE = "2"
VERSION_NAME = "2.0"
MIN_SDK = "26"
TARGET_SDK = "35"

ANDROID_JAR = None
BUILD_TOOLS = None
AAPT2 = None
ZIPALIGN = None
APKSIGNER_JAR = None
D8_JAR = None
JAVA = None
JAVAC = None
KEYTOOL = None


def run(cmd, **kwargs):
    printable = " ".join('"%s"' % c if " " in c else c for c in cmd)
    print("  $ " + printable, flush=True)
    result = subprocess.run(cmd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if result.stdout.strip():
        print(_indent(result.stdout.strip()), flush=True)
    if result.returncode != 0:
        if result.stderr.strip():
            print(_indent(result.stderr.strip()), flush=True)
        raise SystemExit("FAILED: %s" % printable)
    if result.stderr.strip():
        print(_indent(result.stderr.strip()), flush=True)
    return result


def _indent(text):
    return "\n".join("    " + line for line in text.splitlines())


def find_java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    exe = name + (".exe" if os.name == "nt" else "")
    if java_home:
        path = os.path.join(java_home, "bin", exe)
        if os.path.isfile(path):
            return path
    found = shutil.which(name)
    if found:
        return found
    raise SystemExit("Could not find %s. Set JAVA_HOME to a JDK install." % name)


def locate_toolchain():
    global ANDROID_JAR, BUILD_TOOLS, AAPT2, ZIPALIGN, APKSIGNER_JAR, D8_JAR, JAVA, JAVAC, KEYTOOL

    jars = sorted(glob.glob(os.path.join(TOOLS, "**", "android.jar"), recursive=True))
    if not jars:
        raise SystemExit("No android.jar under tools/. Unzip a platform package there first.")
    ANDROID_JAR = jars[0]

    aapt2s = sorted(glob.glob(os.path.join(TOOLS, "**", "aapt2.exe"), recursive=True) +
                    glob.glob(os.path.join(TOOLS, "**", "aapt2"), recursive=True))
    if not aapt2s:
        raise SystemExit("aapt2 not found under tools/. Unzip build-tools there first.")
    AAPT2 = aapt2s[0]
    BUILD_TOOLS = os.path.dirname(AAPT2)

    zipaligns = sorted(glob.glob(os.path.join(BUILD_TOOLS, "zipalign*")))
    if not zipaligns:
        raise SystemExit("zipalign not found next to aapt2.")
    ZIPALIGN = zipaligns[0]

    APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")
    if not os.path.isfile(APKSIGNER_JAR):
        raise SystemExit("apksigner.jar not found in build-tools/lib.")
    D8_JAR = os.path.join(BUILD_TOOLS, "lib", "d8.jar")
    if not os.path.isfile(D8_JAR):
        raise SystemExit("d8.jar not found in build-tools/lib.")

    JAVA = find_java_tool("java")
    JAVAC = find_java_tool("javac")
    KEYTOOL = find_java_tool("keytool")


def ensure_keystore():
    """Self-signed key so the APK can be installed. Debug-grade: password is 'android'."""
    path = os.path.join(ROOT, "debug.keystore")
    if os.path.isfile(path):
        return path
    print("\n[0/7] Creating signing key")
    run([KEYTOOL, "-genkeypair", "-v",
         "-keystore", path,
         "-storepass", "android", "-keypass", "android",
         "-alias", "androiddebugkey",
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-dname", "CN=Android Debug,O=Android,C=US"])
    return path


def collect_sources():
    sources = glob.glob(os.path.join(APP, "src", "**", "*.java"), recursive=True)
    sources += glob.glob(os.path.join(BUILD, "gen", "**", "*.java"), recursive=True)
    if not sources:
        raise SystemExit("No .java sources found under app/src.")
    return sources


def main():
    locate_toolchain()
    print("Android SDK pieces:")
    print("  android.jar   %s" % ANDROID_JAR)
    print("  build-tools   %s" % BUILD_TOOLS)
    print("  java          %s" % JAVA)

    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    for d in (BUILD, os.path.join(BUILD, "gen"), os.path.join(BUILD, "classes"),
              os.path.join(BUILD, "dex"), OUT):
        os.makedirs(d, exist_ok=True)

    keystore = ensure_keystore()

    print("\n[1/7] Compiling resources (aapt2 compile)")
    res_zip = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip])

    print("\n[2/7] Linking resources + manifest (aapt2 link)")
    base_apk = os.path.join(BUILD, "base.apk")
    run([AAPT2, "link", "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "-R", res_zip,
         "--java", os.path.join(BUILD, "gen"),
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE,
         "--version-name", VERSION_NAME,
         "--auto-add-overlay"])

    print("\n[3/7] Compiling Java sources (javac)")
    classes_dir = os.path.join(BUILD, "classes")
    run([JAVAC, "-encoding", "UTF-8",
         "-source", "11", "-target", "11", "-nowarn",
         "-classpath", ANDROID_JAR,
         "-d", classes_dir] + collect_sources())

    print("\n[4/7] Dexing (d8)")
    dex_dir = os.path.join(BUILD, "dex")
    class_files = glob.glob(os.path.join(classes_dir, "**", "*.class"), recursive=True)
    run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", MIN_SDK,
         "--lib", ANDROID_JAR,
         "--output", dex_dir] + class_files)

    print("\n[5/7] Packaging APK")
    unsigned = os.path.join(BUILD, "unsigned.apk")
    shutil.copyfile(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.write(os.path.join(dex_dir, "classes.dex"), "classes.dex")

    print("\n[6/7] Aligning (zipalign)")
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])

    print("\n[7/7] Signing (apksigner)")
    final_apk = os.path.join(OUT, "%s-%s.apk" % (APK_BASENAME, VERSION_NAME))
    run([JAVA, "-jar", APKSIGNER_JAR, "sign",
         "--ks", keystore,
         "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true",
         "--out", final_apk, aligned])

    print("\nVerifying signature")
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--print-certs", final_apk])

    size_mb = os.path.getsize(final_apk) / 1024.0 / 1024.0
    print("\nBUILD OK -> %s  (%.2f MB)" % (final_apk, size_mb))
    print("Install with:  adb install -r \"%s\"" % final_apk)


if __name__ == "__main__":
    main()
