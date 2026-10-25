"""A mountain placed in one tile must shade that tile, not its neighbour. Synthetic DEM, no network.
Run: python3 packer/terrain/test_alignment.py"""
import math, sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import build_terrain as bt

def test_alignment():
    z = 13; cols = [100, 101, 102, 103]            # one apron column on each side, two wanted tiles: 101 and 102
    def fetch(zz, x, y):
        dem = np.zeros((256, 256), np.float32)
        if x == 102 and y == 200:                  # a cone in the middle of tile 102
            yy, xx = np.mgrid[0:256, 0:256]; dem = np.maximum(0, 900 - np.hypot(xx - 128, yy - 128) * 8).astype(np.float32)
        return dem
    rows = {y: np.concatenate([fetch(z, x, y) for x in cols], axis=1) for y in (199, 200, 201)}
    strip = np.concatenate([rows[199], rows[200], rows[201]], axis=0)
    shade = bt.hillshade(strip[255:513, :], 20.0, 20.0, 1.0)
    flat = math.cos(math.radians(90.0 - bt.ALTITUDE))
    def shaded(i):                                  # the production crop for wanted tile i
        return int((np.abs(shade[:, 255 + i * 256:255 + (i + 1) * 256] - flat) > 1e-3).sum())
    n101, n102 = shaded(0), shaded(1)
    assert n102 > 10000, f"the mountain's own tile has only {n102} shaded pixels"
    assert n101 == 0, f"the tile to the west has {n101} shaded pixels: the crop is shifted"
    print(f"ok: tile 102 shaded {n102} px, tile 101 shaded {n101} px")

if __name__ == "__main__":
    test_alignment()
