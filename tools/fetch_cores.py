#!/usr/bin/env python3
"""
Downloads the extra cores for the "full" build and puts them where Android can run them:
app/src/full/jniLibs/<abi>/lib<name>.so (Android 10+ only executes files from the APK's native
library folder, never downloaded ones). Every file is checked against the SHA-256 its project publishes:
GitHub's asset digest (Xray, Hysteria, TrustTunnel) and Tor's sha256sums-signed-build.txt.

    py -3 tools/fetch_cores.py            # latest releases
    py -3 tools/fetch_cores.py --check    # only print what would be fetched

Cores: Xray (XHTTP, VLESS Encryption), Hysteria 2 (official), Tor + lyrebird (obfs4, webtunnel, meek,
snowflake), TrustTunnel client (Linux arm64 static build; arm64 only, experimental).
"""
import hashlib, io, json, os, re, shutil, sys, tarfile, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, "app", "src", "full", "jniLibs")
ASSETS = os.path.join(ROOT, "app", "src", "full", "assets")
UA = {"User-Agent": "SLMtune-build"}

ABIS = {
    # abi: (xray asset, hysteria asset, tor arch, trusttunnel asset)
    "arm64-v8a": ("Xray-android-arm64-v8a.zip", "hysteria-android-arm64", "aarch64", r"trusttunnel_client-v[\d.]+-linux-aarch64\.tar\.gz"),
    "armeabi-v7a": (None, "hysteria-android-armv7", "armv7", None),
    "x86_64": ("Xray-android-amd64.zip", "hysteria-android-amd64", "x86_64", None),
}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def release(repo):
    return json.loads(get(f"https://api.github.com/repos/{repo}/releases/latest"))


def asset(rel, pattern):
    for a in rel["assets"]:
        if re.fullmatch(pattern, a["name"]):
            d = a.get("digest") or ""
            if not d.startswith("sha256:"):
                sys.exit(f"{a['name']}: GitHub gave no SHA-256, refusing")
            return a["browser_download_url"], d[7:].lower(), a["name"]
    sys.exit(f"no asset {pattern} in {rel['tag_name']}")


def fetch_checked(url, sha):
    data = get(url)
    got = hashlib.sha256(data).hexdigest()
    if got != sha:
        sys.exit(f"SHA-256 mismatch for {url}: {got} != {sha}")
    return data


def put(abi, name, data):
    d = os.path.join(JNI, abi)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, f"lib{name}.so"), "wb") as f:
        f.write(data)
    print(f"  {abi}/lib{name}.so  {len(data) / 1048576:.1f} MB")


def main():
    check = "--check" in sys.argv
    xray = release("XTLS/Xray-core")
    hy = release("apernet/hysteria")
    tt = release("TrustTunnel/TrustTunnelClient")
    feed = json.loads(get("https://aus1.torproject.org/torbrowser/update_3/release/downloads.json"))
    torv = feed["version"]
    tor_base = f"https://archive.torproject.org/tor-package-archive/torbrowser/{torv}/"
    print(f"Xray {xray['tag_name']}, Hysteria {hy['tag_name']}, Tor {torv}, TrustTunnel {tt['tag_name']}")
    if check:
        return
    sums = get(tor_base + "sha256sums-signed-build.txt").decode()
    if os.path.isdir(JNI):
        shutil.rmtree(JNI)
    versions = {"xray": xray["tag_name"], "hysteria": hy["tag_name"], "tor": torv, "trusttunnel": tt["tag_name"]}

    for abi, (xa, ha, ta, tta) in ABIS.items():
        print(abi)
        if xa:
            url, sha, _ = asset(xray, re.escape(xa))
            with zipfile.ZipFile(io.BytesIO(fetch_checked(url, sha))) as z:
                put(abi, "xray", z.read("xray"))
        url, sha, _ = asset(hy, re.escape(ha))
        put(abi, "hysteria", fetch_checked(url, sha))
        name = f"tor-expert-bundle-android-{ta}-{torv}.tar.gz"
        line = next((l for l in sums.splitlines() if l.endswith("  " + name)), None)
        if not line:
            sys.exit(f"{name} not in Tor's checksum list")
        tgz = fetch_checked(tor_base + name, line.split()[0])
        with tarfile.open(fileobj=io.BytesIO(tgz), mode="r:gz") as t:
            put(abi, "tor", t.extractfile("tor/libTor.so").read())
            put(abi, "lyrebird", t.extractfile("tor/pluggable_transports/lyrebird").read())
            os.makedirs(os.path.join(ASSETS, "tor"), exist_ok=True)
            with open(os.path.join(ASSETS, "tor", "pt_config.json"), "wb") as f:
                f.write(t.extractfile("tor/pluggable_transports/pt_config.json").read())
        if tta:
            url, sha, _ = asset(tt, tta)
            with tarfile.open(fileobj=io.BytesIO(fetch_checked(url, sha)), mode="r:gz") as t:
                member = next(m for m in t.getmembers() if m.name.endswith("/trusttunnel_client"))
                put(abi, "trusttunnel", t.extractfile(member).read())

    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "cores.json"), "w") as f:
        json.dump(versions, f)
    print("versions:", versions)


if __name__ == "__main__":
    main()
