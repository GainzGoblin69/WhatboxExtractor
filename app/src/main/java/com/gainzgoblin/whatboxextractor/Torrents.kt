package com.gainzgoblin.whatboxextractor

import org.json.JSONObject

data class TorrentMatch(val hash: String, val name: String, val path: String)

/**
 * Talks to rTorrent through its local SCGI socket on the seedbox, using a small Python
 * helper that the app uploads to ~/.whatbox-extractor/. No extra login is needed because
 * the helper runs as your own user over SSH.
 */
object Torrents {
    fun helperPath(home: String) = home.trimEnd('/') + "/.whatbox-extractor/rtorrent_helper.py"

    fun findCommand(home: String, paths: List<String>) =
        "python3 -I ${shQuote(helperPath(home))} find " + paths.joinToString(" ") { shQuote(it) }

    fun removeCommand(home: String, hashes: List<String>) =
        "python3 -I ${shQuote(helperPath(home))} remove " + hashes.joinToString(" ") { shQuote(it) }

    /** Parses the helper's JSON output. Throws with a readable message on failure. */
    fun parse(out: String, err: String): List<TorrentMatch> {
        val line = out.lines().lastOrNull { it.trim().startsWith("{") }
            ?: throw IllegalStateException(
                if (err.contains("python3", true) && err.contains("not found", true)) "python3 isn't available on the server"
                else err.trim().ifBlank { "No answer from the rTorrent helper" },
            )
        val json = JSONObject(line)
        if (!json.optBoolean("ok")) throw IllegalStateException(json.optString("error", "rTorrent helper failed"))
        val arr = json.optJSONArray("torrents") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TorrentMatch(o.getString("hash"), o.optString("name"), o.optString("path"))
        }
    }

    val HELPER_SCRIPT = """
import glob, json, os, re, socket, stat, sys, xmlrpc.client

HOME = os.path.expanduser("~")


def candidates():
    found = []
    for rc in (HOME + "/.config/rtorrent/rtorrent.rc", HOME + "/.rtorrent.rc"):
        try:
            with open(rc) as f:
                txt = f.read()
        except OSError:
            continue
        for m in re.finditer(r"^\s*(?:network\.scgi\.open_local|scgi_local)\s*=\s*(.+?)\s*${'$'}", txt, re.M):
            found.append(m.group(1).strip().strip('"').replace("~", HOME))
    pats = [
        HOME + "/.config/rtorrent/*",
        HOME + "/.config/rtorrent/*/*",
        HOME + "/.rtorrent*",
        HOME + "/.session*/*",
        HOME + "/.local/share/rtorrent/*",
        "/run/user/%d/*" % os.getuid(),
        "/tmp/*",
    ]
    for p in pats:
        found += sorted(glob.glob(p))
    out = []
    for p in found:
        try:
            if stat.S_ISSOCK(os.stat(p).st_mode) and p not in out and os.access(p, os.W_OK):
                out.append(p)
        except OSError:
            pass
    return out


def call(sock, method, *params):
    body = xmlrpc.client.dumps(params, method).encode()
    headers = [("CONTENT_LENGTH", str(len(body))), ("SCGI", "1"),
               ("REQUEST_METHOD", "POST"), ("REQUEST_URI", "/RPC2")]
    h = b"".join(k.encode() + b"\0" + v.encode() + b"\0" for k, v in headers)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(20)
    s.connect(sock)
    s.sendall(str(len(h)).encode() + b":" + h + b"," + body)
    data = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        data += chunk
    s.close()
    for sep in (b"\r\n\r\n", b"\n\n"):
        if sep in data:
            data = data.split(sep, 1)[1]
            break
    return xmlrpc.client.loads(data)[0][0]


def connect():
    for s in candidates():
        try:
            call(s, "system.client_version")
            return s
        except Exception:
            continue
    return None


def variants(p):
    p = os.path.normpath(p)
    return {p, os.path.realpath(p)}


def find(sock, targets):
    rows = call(sock, "d.multicall2", "", "main",
                "d.hash=", "d.name=", "d.base_path=", "d.directory=", "d.is_multi_file=")
    tv = [variants(t) for t in targets]
    res = []
    for h, name, base, d, multi in rows:
        data = base or (d if multi else os.path.join(d, name))
        if not data:
            continue
        pv = variants(data)
        hit = False
        for ts in tv:
            for t in ts:
                for p in pv:
                    if p == t or p.startswith(t.rstrip("/") + "/"):
                        hit = True
        if hit:
            res.append({"hash": h, "name": name, "path": os.path.normpath(data)})
    return res


def remove(sock, hashes):
    done = []
    for h in hashes:
        for m in ("d.stop", "d.close"):
            try:
                call(sock, m, h)
            except Exception:
                pass
        # Delete the .torrent it was loaded from (e.g. in a watch folder) so it
        # doesn't get added again, then remove the torrent from rTorrent.
        try:
            call(sock, "d.delete_tied", h)
        except Exception:
            pass
        call(sock, "d.erase", h)
        done.append({"hash": h, "name": "", "path": ""})
    return done


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"ok": False, "error": "usage"}))
        return
    sock = connect()
    if not sock:
        print(json.dumps({"ok": False, "error": "Couldn't reach rTorrent. Is it running?"}))
        return
    try:
        if sys.argv[1] == "find":
            out = find(sock, sys.argv[2:])
        elif sys.argv[1] == "remove":
            out = remove(sock, sys.argv[2:])
        else:
            raise ValueError("unknown command")
        print(json.dumps({"ok": True, "torrents": out}))
    except Exception as e:
        print(json.dumps({"ok": False, "error": "rTorrent: %s" % e}))


main()
""".trimIndent() + "\n"
}
