#!/usr/bin/env python3
"""
The TestFlight workflow's signing certificates, through the App Store Connect API (the same key the build uses).
Xcode's automatic signing creates an Apple Development certificate on every fresh runner, and an account holds only a
few, so the workflow notes which exist before it builds and revokes the ones it added afterwards.

    asc_certs.py list  > before.txt          ids of the development certificates now
    asc_certs.py prune before.txt             revoke development certificates not in before.txt
Needs ASC_KEY_ID, ASC_ISSUER_ID and the key file at ~/private_keys/AuthKey_<id>.p8.
"""
import os, sys, time, json, urllib.request, jwt

KID, ISS = os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"]
KEY = open(os.path.expanduser(f"~/private_keys/AuthKey_{KID}.p8")).read()
API = "https://api.appstoreconnect.apple.com/v1"
DEV = {"DEVELOPMENT", "IOS_DEVELOPMENT"}

def call(method, path):
    tok = jwt.encode({"iss": ISS, "iat": int(time.time()), "exp": int(time.time()) + 600, "aud": "appstoreconnect-v1"}, KEY, algorithm="ES256", headers={"kid": KID})
    req = urllib.request.Request(API + path, method=method, headers={"Authorization": f"Bearer {tok}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
        return json.loads(body) if body else None

def development():
    out, path = [], "/certificates?limit=200"
    while path:
        d = call("GET", path)
        out += [(c["id"], c["attributes"].get("name", ""), c["attributes"].get("certificateType")) for c in d["data"] if c["attributes"].get("certificateType") in DEV]
        nxt = d.get("links", {}).get("next"); path = nxt.replace(API, "") if nxt else None
    return out

if sys.argv[1] == "list":
    for i, n, t in development(): print(i)
elif sys.argv[1] == "prune":
    before = set(open(sys.argv[2]).read().split())
    for i, n, t in development():
        if i not in before:
            call("DELETE", f"/certificates/{i}"); print(f"revoked {t} {n} ({i}), made by this run")
