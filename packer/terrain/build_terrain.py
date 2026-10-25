#!/usr/bin/env python3
"""
Builds a terrain .mappack: hillshade raster tiles (WebP with alpha) in a PMTiles archive, drawn by the
app under the roads of any pack that covers the same ground.

    python3 build_terrain.py --name "Montana terrain" --region Montana --bbox -116.1,44.3,-104.0,49.1 --out montana-terrain.mappack

Elevation comes from the AWS Terrain Tiles public dataset (Mapzen "terrarium" encoding, ~30 m in the
US; https://registry.opendata.aws/terrain-tiles/). Each output tile is shadow and light only: flat
ground is fully transparent, so the paper map shows through and the style never needs to know.

Needs: pip install numpy pillow pmtiles requests
"""
import argparse, io, math, os, sys, time, zipfile, datetime
from concurrent.futures import ThreadPoolExecutor
import numpy as np
import requests
from PIL import Image
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, TileType, Compression

SRC = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
AZIMUTH, ALTITUDE = 315.0, 45.0
SHADOW = (45, 35, 25)

def lon2x(lon, z): return (lon + 180.0) / 360.0 * (1 << z)
def lat2y(lat, z):
    r = math.radians(lat); return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)
def y2lat(y, z): return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))

session = requests.Session()
session.mount("https://", requests.adapters.HTTPAdapter(pool_connections=64, pool_maxsize=64, max_retries=3))

def fetch(z, x, y):
    """Elevation tile as float32 metres (256x256); water/no-data tiles are flat zero."""
    n = 1 << z
    if y < 0 or y >= n: return np.zeros((256, 256), np.float32)
    x %= n
    for attempt in range(4):
        try:
            r = session.get(SRC.format(z=z, x=x, y=y), timeout=30)
            if r.status_code == 404: return np.zeros((256, 256), np.float32)
            r.raise_for_status()
            a = np.asarray(Image.open(io.BytesIO(r.content)).convert("RGB"), dtype=np.float32)
            return a[:, :, 0] * 256.0 + a[:, :, 1] + a[:, :, 2] / 256.0 - 32768.0
        except Exception as e:
            if attempt == 3: raise
            time.sleep(1.5 * (attempt + 1))

def hillshade(dem, res_x, res_y, exaggeration=1.0):
    """Horn's method. dem has a 1-pixel apron; returns shade in [0,1] for the interior."""
    z = dem * exaggeration
    # Horn kernel
    dzdx = ((z[:-2, 2:] + 2 * z[1:-1, 2:] + z[2:, 2:]) - (z[:-2, :-2] + 2 * z[1:-1, :-2] + z[2:, :-2])) / (8.0 * res_x)
    dzdy = ((z[2:, :-2] + 2 * z[2:, 1:-1] + z[2:, 2:]) - (z[:-2, :-2] + 2 * z[:-2, 1:-1] + z[:-2, 2:])) / (8.0 * res_y)
    slope = np.arctan(np.hypot(dzdx, dzdy))
    aspect = np.arctan2(dzdy, -dzdx)
    zen = math.radians(90.0 - ALTITUDE); az = math.radians(360.0 - AZIMUTH + 90.0)
    shade = np.cos(zen) * np.cos(slope) + np.sin(zen) * np.sin(slope) * np.cos(az - aspect)
    return np.clip(shade, 0.0, 1.0)

def tile_image(shade):
    """Shadow only, as alpha over a constant dark colour: flat and sunlit ground is transparent, so the paper
    map shows through untouched. A constant colour keeps the WebP tiny (≈2 KB in mountains, bytes on plains)."""
    flat = math.cos(math.radians(90.0 - ALTITUDE))
    shadow = np.clip((flat - shade) * 2.1, 0.0, 0.85)
    h, w = shade.shape
    rgba = np.empty((h, w, 4), np.uint8)
    rgba[..., 0] = SHADOW[0]; rgba[..., 1] = SHADOW[1]; rgba[..., 2] = SHADOW[2]
    rgba[..., 3] = (shadow * 255).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")

def build(name, region, bbox, out, min_zoom, max_zoom, quality, workers):
    min_lon, min_lat, max_lon, max_lat = bbox
    tmp = out + ".pmtiles"
    total = 0
    with open(tmp, "wb") as f:
        w = Writer(f)
        for z in range(min_zoom, max_zoom + 1):
            x0, x1 = int(lon2x(min_lon, z)), int(lon2x(max_lon, z))
            y0, y1 = int(lat2y(max_lat, z)), int(lat2y(min_lat, z))
            cols = list(range(x0 - 1, x1 + 2))
            rows = {}
            def row(y):
                if y not in rows:
                    with ThreadPoolExecutor(workers) as ex: tiles = list(ex.map(lambda x: fetch(z, x, y), cols))
                    rows[y] = np.concatenate(tiles, axis=1)
                return rows[y]
            t0 = time.time(); n = 0
            for y in range(y0, y1 + 1):
                strip = np.concatenate([row(y - 1), row(y), row(y + 1)], axis=0)   # 768 x W
                for k in list(rows.keys()):
                    if k < y: del rows[k]
                lat_c = y2lat(y + 0.5, z)
                res = 156543.03392 * math.cos(math.radians(lat_c)) / (1 << z)   # metres per pixel (256-px tiles)
                # coarse zooms flatten slopes; exaggerate so the ranges still read when zoomed out
                exag = min(4.0, 1.35 ** max(0, 12 - z))
                shade = hillshade(strip[255:513, :], res, res, exag)           # one-pixel apron around the row
                for i, x in enumerate(cols[1:-1]):
                    img = tile_image(shade[:, i * 256:(i + 1) * 256])
                    buf = io.BytesIO(); img.save(buf, "WEBP", quality=90, alpha_quality=quality, method=4)
                    w.write_tile(zxy_to_tileid(z, x % (1 << z), y), buf.getvalue()); n += 1
                if (y - y0) % 10 == 0: print(f"z{z} row {y - y0 + 1}/{y1 - y0 + 1} · {n} tiles · {time.time() - t0:.0f}s", file=sys.stderr, flush=True)
            total += n
            print(f"z{z}: {n} tiles", file=sys.stderr, flush=True)
        w.finalize({"tile_type": TileType.WEBP, "tile_compression": Compression.NONE, "min_zoom": min_zoom, "max_zoom": max_zoom,
                    "min_lon_e7": int(min_lon * 1e7), "min_lat_e7": int(min_lat * 1e7), "max_lon_e7": int(max_lon * 1e7), "max_lat_e7": int(max_lat * 1e7),
                    "center_zoom": min_zoom + 2, "center_lon_e7": int((min_lon + max_lon) / 2 * 1e7), "center_lat_e7": int((min_lat + max_lat) / 2 * 1e7)},
                   {"name": name, "attribution": "Elevation: AWS Terrain Tiles (Mapzen terrarium; USGS 3DEP, SRTM, and others)", "type": "overlay", "format": "webp"})
    manifest = f"name={name}\nformat=2\nbbox={min_lat},{min_lon},{max_lat},{max_lon}\ncreated={datetime.date.today().isoformat()}\nnotes=hillshade from AWS Terrain Tiles; region={region}\nkind=terrain\n"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("pack.txt", manifest)
        zf.write(tmp, "terrain.pmtiles")
    os.remove(tmp)
    print(f"wrote {out}: {total} tiles, {os.path.getsize(out) // (1 << 20)} MB", file=sys.stderr)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True); ap.add_argument("--region", required=True)
    ap.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat"); ap.add_argument("--out", required=True)
    ap.add_argument("--min-zoom", type=int, default=5); ap.add_argument("--max-zoom", type=int, default=13)
    ap.add_argument("--quality", type=int, default=50, help="WebP alpha quality (the shade lives in alpha)"); ap.add_argument("--workers", type=int, default=32)
    a = ap.parse_args()
    build(a.name, a.region, [float(v) for v in a.bbox.split(",")], a.out, a.min_zoom, a.max_zoom, a.quality, a.workers)
