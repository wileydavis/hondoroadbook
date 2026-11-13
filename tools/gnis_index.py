#!/usr/bin/env python3
"""
The place-name index behind the planner's search bar: every named US place in GNIS (USGS Geographic Names Information
System, public domain) that a rider might look for: passes, springs, summits, canyons, mines, flats, lakes, creeks…
with the variant names GNIS knows (AllNames), so "Saline Valley Hot Springs" finds what the official list calls
something else.

    gnis_index.py DomesticNames_National.txt [AllNames_National.txt] OUTDIR

Writes OUTDIR/<k3>.json for each three-letter key: [[name, class, county, ST, lat, lon, official-or-""], …], each place
filed under the first three letters of each distinctive word of its name (not "creek", "mountain", "the"…), so a query
reads one small file. web/api.js (placeNames) reads them from the bucket under names/v1/.
"""
import sys, os, re, json, csv, collections, unicodedata

GENERIC = set("""the of and de del la el los las le du des a an at in on to
mount mountain mountains mt peak peaks summit hill hills butte buttes knob ridge ridges point bluff cliff cliffs rock rocks
creek creeks river run brook branch fork forks spring springs well wells lake lakes pond ponds reservoir tank tanks dam falls
canyon canyons valley valleys gulch draw wash hollow ravine basin flat flats meadow meadows park pass gap saddle notch
upper lower north south east west middle little big old new great
mine mines ranch camp trail road cave arch island bay cove beach point lookout tunnel bridge station""".split())
SKIP_CLASSES = {"Building", "Church", "School", "Hospital", "Post Office", "Census", "Civil", "Tower", "Military",
                "Harbor", "Bay", "Sea", "Channel", "Gut", "Swamp", "Area", "Unknown"}
STATES = dict(Alabama="AL", Alaska="AK", Arizona="AZ", Arkansas="AR", California="CA", Colorado="CO", Connecticut="CT", Delaware="DE",
    Florida="FL", Georgia="GA", Hawaii="HI", Idaho="ID", Illinois="IL", Indiana="IN", Iowa="IA", Kansas="KS", Kentucky="KY", Louisiana="LA",
    Maine="ME", Maryland="MD", Massachusetts="MA", Michigan="MI", Minnesota="MN", Mississippi="MS", Missouri="MO", Montana="MT", Nebraska="NE",
    Nevada="NV", Ohio="OH", Oklahoma="OK", Oregon="OR", Pennsylvania="PA", Tennessee="TN", Texas="TX", Utah="UT", Vermont="VT", Virginia="VA",
    Washington="WA", Wisconsin="WI", Wyoming="WY")
STATES.update({"New Hampshire": "NH", "New Jersey": "NJ", "New Mexico": "NM", "New York": "NY", "North Carolina": "NC", "North Dakota": "ND",
    "Rhode Island": "RI", "South Carolina": "SC", "South Dakota": "SD", "West Virginia": "WV", "District of Columbia": "DC", "Puerto Rico": "PR"})

def fold(s):
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c)).lower()
def words(s):
    return re.findall(r"[a-z0-9]+", fold(s))
def keys(name):
    w = [x for x in words(name) if len(x) >= 3]
    k = [x for x in w if x not in GENERIC] or w[:1]
    return {x[:3] for x in k}

def rows(path):
    with open(path, encoding="utf-8-sig", errors="replace", newline="") as f:
        r = csv.reader(f, delimiter="|")
        head = [h.strip().lower() for h in next(r)]
        print(path, "columns:", head, file=sys.stderr)
        for row in r:
            if len(row) == len(head): yield dict(zip(head, row))

def main():
    dom = sys.argv[1]; alln = sys.argv[2] if len(sys.argv) > 3 else None; out = sys.argv[-1]
    os.makedirs(out, exist_ok=True)
    feats = {}
    classes = collections.Counter()
    for d in rows(dom):
        cls = d.get("feature_class", "")
        classes[cls] += 1
        if cls in SKIP_CLASSES: continue
        try: lat = float(d.get("prim_lat_dec") or 0); lon = float(d.get("prim_long_dec") or 0)
        except ValueError: continue
        if lat == 0 and lon == 0: continue
        st = STATES.get(d.get("state_name", ""), d.get("state_name", ""))
        feats[d.get("feature_id")] = [d["feature_name"], cls, d.get("county_name", ""), st, round(lat, 5), round(lon, 5)]
    print("classes:", classes.most_common(), file=sys.stderr)
    shards = collections.defaultdict(list)
    for f in feats.values():
        for k in keys(f[0]): shards[k].append(f + [""])
    variants = 0
    if alln:
        for d in rows(alln):
            f = feats.get(d.get("feature_id"))
            n = d.get("feature_name", "")
            if not f or not n or n == f[0]: continue
            variants += 1
            for k in keys(n): shards[k].append([n] + f[1:] + [f[0]])
    for k, v in shards.items():
        with open(os.path.join(out, k + ".json"), "w") as fh: json.dump(v, fh, separators=(",", ":"), ensure_ascii=False)
    big = sorted(((len(v), k) for k, v in shards.items()), reverse=True)[:8]
    print(f"{len(feats)} places, {variants} variant names, {len(shards)} files; largest {big}", file=sys.stderr)

if __name__ == "__main__":
    main()
