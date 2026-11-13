#!/usr/bin/env python3
"""
Place-name packs: a region's named places for the apps' offline search, beside its map pack (not inside it, so adding
them never means downloading the map again). A pack of kind "names" is a stored zip of pack.txt and search.tsv, the
same sorted index the roads pack carries (key, kind, name, context, latE7, lonE7), so the apps' search reads it as it
reads the roads index. Kind 'N'.

Sources: GNIS (USGS Geographic Names Information System, public domain) for US states, with its variant names; and,
everywhere, OpenStreetMap's named natural features that riders look for (hot springs, passes, saddles, peaks,
viewpoints, trailheads, waterfalls…), whose names are often the everyday ones.

    names_pack.py split DomesticNames_National.txt AllNames_National.txt OUTDIR     → OUTDIR/<State name>.tsv
    names_pack.py build --name "California" [--gnis gnis/California.tsv] [--osm extras.geojsonseq] --out r-names.mappack
"""
import sys, os, re, json, csv, unicodedata, zipfile, datetime, argparse, math

# ---- the apps' TextNorm.normalize, exactly: the index is binary-searched by these keys ----
ABBREV = {"street": "st", "avenue": "ave", "road": "rd", "drive": "dr", "lane": "ln", "boulevard": "blvd", "court": "ct", "circle": "cir",
          "place": "pl", "highway": "hwy", "parkway": "pkwy", "trail": "trl", "terrace": "ter", "north": "n", "south": "s", "east": "e",
          "west": "w", "northeast": "ne", "northwest": "nw", "southeast": "se", "southwest": "sw", "saint": "st", "mount": "mt",
          "fort": "ft", "route": "rte", "county": "co", "square": "sq", "heights": "hts"}
DIRECTIONS = {"n", "s", "e", "w", "ne", "nw", "se", "sw"}
def normalize(s):
    d = "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) not in ("Mn", "Mc", "Me"))
    d = d.replace("-", " ").replace(".", " ")
    d = re.sub(r"[^a-z0-9 ]+", " ", d)
    d = re.sub(r"\s+", " ", d).strip()
    return " ".join(ABBREV.get(w, w) for w in d.split(" ")) if d else ""
def clean(s): return s.replace("\t", " ").replace("\n", " ").strip()
def lines(name, ctx, lat, lon, keyctx=None):
    """SearchFile.lines: the key, and the key without a leading direction ("N Fork …" also under "fork …")"""
    name, ctx = clean(name), clean(ctx)
    key = normalize(name + " " + (ctx if keyctx is None else keyctx))
    rest = f"\tN\t{name}\t{ctx}\t{round(lat * 1e7)}\t{round(lon * 1e7)}"
    out = [key + rest]
    sp = key.find(" ")
    if sp > 0 and key[:sp] in DIRECTIONS: out.append(key[sp + 1:] + rest)
    return out

# ---- GNIS ----
SKIP = {"Building", "Church", "School", "Hospital", "Post Office", "Census", "Civil", "Tower", "Military", "Harbor", "Bay", "Sea",
        "Channel", "Gut", "Swamp", "Area", "Unknown", "Populated Place"}   # towns are the roads pack's own
LABEL = {"Gap": "Pass", "Stream": "Creek", "Locale": "Place", "Airport": "Airstrip", "Cape": "Point", "Lava": "Lava flow"}
ST = dict(Alabama="AL", Alaska="AK", Arizona="AZ", Arkansas="AR", California="CA", Colorado="CO", Connecticut="CT", Delaware="DE",
    Florida="FL", Georgia="GA", Hawaii="HI", Idaho="ID", Illinois="IL", Indiana="IN", Iowa="IA", Kansas="KS", Kentucky="KY", Louisiana="LA",
    Maine="ME", Maryland="MD", Massachusetts="MA", Michigan="MI", Minnesota="MN", Mississippi="MS", Missouri="MO", Montana="MT", Nebraska="NE",
    Nevada="NV", Ohio="OH", Oklahoma="OK", Oregon="OR", Pennsylvania="PA", Tennessee="TN", Texas="TX", Utah="UT", Vermont="VT", Virginia="VA",
    Washington="WA", Wisconsin="WI", Wyoming="WY")
ST.update({"New Hampshire": "NH", "New Jersey": "NJ", "New Mexico": "NM", "New York": "NY", "North Carolina": "NC", "North Dakota": "ND",
    "Rhode Island": "RI", "South Carolina": "SC", "South Dakota": "SD", "West Virginia": "WV", "District of Columbia": "DC"})

def rows(path):
    with open(path, encoding="utf-8-sig", errors="replace", newline="") as f:
        r = csv.reader(f, delimiter="|")
        head = [h.strip().lower() for h in next(r)]
        for row in r:
            if len(row) == len(head): yield dict(zip(head, row))

def split(dom, alln, out):
    """one file per state: name, class label, county, ST, lat, lon, official name (for a variant)"""
    os.makedirs(out, exist_ok=True)
    feats = {}
    for d in rows(dom):
        if d.get("feature_class") in SKIP: continue
        try: lat = float(d["prim_lat_dec"] or 0); lon = float(d["prim_long_dec"] or 0)
        except (ValueError, KeyError): continue
        if lat == 0 and lon == 0: continue
        cls = d["feature_class"]
        feats[d["feature_id"]] = (d["state_name"], [d["feature_name"], LABEL.get(cls, cls), d.get("county_name", ""), ST.get(d["state_name"], ""), f"{lat:.6f}", f"{lon:.6f}", ""])
    by = {}
    for st, f in feats.values(): by.setdefault(st, []).append(f)
    if alln:
        for d in rows(alln):
            x = feats.get(d.get("feature_id")); n = d.get("feature_name", "")
            if x and n and n != x[1][0]: by[x[0]].append([n] + x[1][1:6] + [x[1][0]])
    for st, fs in by.items():
        with open(os.path.join(out, st + ".tsv"), "w", encoding="utf-8") as fh:
            for f in fs: fh.write("\t".join(clean(v) for v in f) + "\n")
    print(f"{len(feats)} places in {len(by)} states", file=sys.stderr)

# ---- OpenStreetMap ----
OSM_KINDS = [("natural", "hot_spring", "Hot spring"), ("natural", "geyser", "Geyser"), ("natural", "spring", "Spring"),
             ("mountain_pass", "yes", "Pass"), ("natural", "saddle", "Saddle"), ("natural", "peak", "Peak"), ("natural", "volcano", "Volcano"),
             ("natural", "arch", "Arch"), ("natural", "cave_entrance", "Cave"), ("waterway", "waterfall", "Waterfall"),
             ("tourism", "viewpoint", "Viewpoint"), ("highway", "trailhead", "Trailhead")]
def osm_places(path):
    with open(path, encoding="utf-8") as fh:
        for ln in fh:
            ln = ln.strip().lstrip("\x1e")
            if not ln: continue
            try: f = json.loads(ln)
            except ValueError: continue
            p = f.get("properties") or {}
            name = p.get("name")
            if not name: continue
            label = next((l for k, v, l in OSM_KINDS if p.get(k) == v), None)
            if not label: continue
            g = f.get("geometry") or {}
            c = g.get("coordinates")
            if g.get("type") == "Point": lon, lat = c
            else:
                pts = c if g.get("type") == "LineString" else (c[0] if g.get("type") == "Polygon" else None)
                if not pts: continue
                lon = sum(q[0] for q in pts) / len(pts); lat = sum(q[1] for q in pts) / len(pts)
            yield name, label, lat, lon

def km(a, b, c, d): return math.hypot((c - a) * 110.57, (d - b) * 111.32 * math.cos(math.radians((a + c) / 2)))

def build(a):
    out = []; seen = {}
    def note(name, lat, lon):
        k = normalize(name)
        for (la, lo) in seen.get(k, ()):
            if km(lat, lon, la, lo) < 1.5: return False
        seen.setdefault(k, []).append((lat, lon)); return True
    bbox = [90, 180, -90, -180]
    def grow(lat, lon): bbox[0] = min(bbox[0], lat); bbox[1] = min(bbox[1], lon); bbox[2] = max(bbox[2], lat); bbox[3] = max(bbox[3], lon)
    n_g = n_o = 0
    if a.gnis and os.path.exists(a.gnis):
        with open(a.gnis, encoding="utf-8") as fh:
            for ln in fh:
                f = ln.rstrip("\n").split("\t")
                if len(f) < 7: continue
                name, label, county, st, lat, lon, official = f[0], f[1], f[2], f[3], float(f[4]), float(f[5]), f[6]
                where = ", ".join(x for x in (county and county + " County", st) if x)
                base = f"{label} · {where}" if where else label
                note(name, lat, lon)
                out += lines(name, base + (f" · officially {official}" if official else ""), lat, lon, keyctx=base); grow(lat, lon); n_g += 1
    if a.osm and os.path.exists(a.osm):
        for name, label, lat, lon in osm_places(a.osm):
            if not note(name, lat, lon): continue            # GNIS has it already, by that name, right there
            out += lines(name, f"{label} · {a.region or a.name}", lat, lon); grow(lat, lon); n_o += 1
    if not out: sys.exit("nothing to index")
    out.sort()
    body = ("\n".join(out) + "\n").encode("utf-8")
    created = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    src = " and ".join(x for x in ("GNIS (USGS)" if n_g else "", "OpenStreetMap" if n_o else "") if x)
    manifest = (f"name={a.name}\nformat=3\nbbox={bbox[0]:.5f},{bbox[1]:.5f},{bbox[2]:.5f},{bbox[3]:.5f}\ncreated={created}\n"
                f"notes=place names: passes, springs, peaks and more, from {src}\nkind=names\n")
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_STORED) as z:
        z.writestr("pack.txt", manifest); z.writestr("search.tsv", body)
    print(f"{a.name}: {n_g} from GNIS, {n_o} from OpenStreetMap, {len(out)} lines, {len(body) >> 10} KB", file=sys.stderr)

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "split":
        split(sys.argv[2], sys.argv[3] if len(sys.argv) > 4 else None, sys.argv[-1])
    else:
        p = argparse.ArgumentParser(); p.add_argument("build"); p.add_argument("--name", required=True); p.add_argument("--region"); p.add_argument("--gnis"); p.add_argument("--osm"); p.add_argument("--out", required=True)
        build(p.parse_args())
