#!/usr/bin/env python3
"""Hand-engineered network design for the LCT 2026 obstacle dataset.

Standalone, stdlib-only.  Routes every oks_connection_point to the existing
network, avoiding forbidden restrictions, crossing roads as special passages,
computing flows / diameters / costs and emitting schema-valid GeoJSON (WGS84).

Design tool for verification, not a replacement for the Java service.
"""
import argparse
import heapq
import json
import math
import os
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "source", "Датасет с препятствиями OSM.geojson")
OUTDIR = os.path.join(ROOT, "data", "handcrafted")

LAT0, LON0 = 55.698, 37.636
MX = 111320.0 * math.cos(math.radians(LAT0))
MY = 111320.0


def to_xy(c):
    return ((c[0] - LON0) * MX, (c[1] - LAT0) * MY)


def to_ll(p):
    return [round(LON0 + p[0] / MX, 9), round(LAT0 + p[1] / MY, 9)]


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


DIAMS = [
    (50, 3.5, 181, 74023, 0.400), (65, 8.3, 245, 78631, 0.430),
    (80, 13.2, 327, 83530, 0.470), (100, 22.3, 419, 89748, 0.510),
    (125, 40.2, 554, 97275, 0.600), (150, 65.1, 696, 105507, 0.650),
    (200, 152.3, 1042, 120275, 0.880), (250, 274.9, 1379, 135323, 1.050),
    (300, 437.4, 1718, 150022, 1.150), (400, 943.1, 2477, 190299, 1.370),
    (500, 1663.4, 3245, 224137, 1.670), (600, 2627.7, 4037, 264790, 1.850),
    (700, 3735.1, 4775, 324298, 2.050), (800, 5296.8, 5644, 325996, 2.250),
    (900, 7165.0, 6518, 327693, 2.450), (1000, 9391.8, 7419, 418777, 2.650),
    (1200, 15012.8, 9288, 428074, 3.100), (1400, 22501.9, 11276, 683417, 3.450),
]
D = {d[0]: d for d in DIAMS}


def cap_diam(flow):
    for d in DIAMS:
        if d[1] >= flow - 1e-9:
            return d[0]
    return DIAMS[-1][0]


def min_diam_for_limit(lim):
    for d in DIAMS:
        if d[2] >= lim:
            return d[0]
    return DIAMS[-1][0]


def chamber_cost(d):
    if d <= 200:
        return 3_000_000
    if d <= 500:
        return 5_000_000
    if d <= 1000:
        return 8_000_000
    return 12_000_000


def oks_min_dist(d):
    return 5.0 if d < 500 else (7.0 if d < 900 else 9.0)


# ---------- geometry ----------
def point_in_ring(pt, ring):
    x, y = pt
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > y) != (yj > y):
            if x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def polys_of(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []


def lines_of(geom):
    if geom["type"] == "LineString":
        return [geom["coordinates"]]
    if geom["type"] == "MultiLineString":
        return geom["coordinates"]
    return []


def point_in_poly(pt, polygon):
    return point_in_ring(pt, polygon[0]) and not any(point_in_ring(pt, h) for h in polygon[1:])


def point_in_geom(pt, geom):
    return any(point_in_poly(pt, poly) for poly in polys_of(geom))


def point_seg_dist(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    l2 = dx * dx + dy * dy
    if l2 < 1e-12:
        return dist(p, a)
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2))
    return math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy))


def convex_hull(points):
    pts = sorted(set((round(x, 6), round(y, 6)) for x, y in points))
    if len(pts) <= 2:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lo = []
    for p in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    up = []
    for p in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]


def seg_polygon_intervals(a, b, poly):
    ts = [0.0, 1.0]
    r = (b[0] - a[0], b[1] - a[1])
    for ring in poly:
        n = len(ring)
        for i in range(n):
            c, d = ring[i], ring[(i + 1) % n]
            s = (d[0] - c[0], d[1] - c[1])
            den = r[0] * s[1] - r[1] * s[0]
            if abs(den) < 1e-12:
                continue
            q = (c[0] - a[0], c[1] - a[1])
            t = (q[0] * s[1] - q[1] * s[0]) / den
            u = (q[0] * r[1] - q[1] * r[0]) / den
            if -1e-9 <= t <= 1 + 1e-9 and -1e-9 <= u <= 1 + 1e-9:
                ts.append(min(1.0, max(0.0, t)))
    ts = sorted(set(round(t, 9) for t in ts))
    ivs = []
    for i in range(len(ts) - 1):
        t0, t1 = ts[i], ts[i + 1]
        if t1 - t0 < 1e-9:
            continue
        tm = (t0 + t1) / 2
        m = (a[0] + tm * r[0], a[1] + tm * r[1])
        if point_in_poly(m, poly):
            if ivs and abs(ivs[-1][1] - t0) < 1e-6:
                ivs[-1] = (ivs[-1][0], t1)
            else:
                ivs.append((t0, t1))
    return ivs


def union_intervals(ivs):
    ivs = sorted(ivs)
    out = []
    for a, b in ivs:
        if out and a <= out[-1][1] + 1e-6:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


DIRS = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
DIRLEN = [1.0, math.sqrt(2)] * 4
TURN = [0, 45, 90, 135, 180, 135, 90, 45]
INF = float("inf")


class Grid:
    def __init__(self, x0, y0, w, h, cell):
        self.x0, self.y0, self.w, self.h, self.cell = x0, y0, w, h, cell
        self.mask = bytearray(w * h)
        self.road = bytearray(w * h)

    def in_road(self, ix, iy):
        return self.in_bounds(ix, iy) and self.road[iy * self.w + ix] == 1

    def ij(self, i):
        return i % self.w, i // self.w

    def in_bounds(self, ix, iy):
        return 0 <= ix < self.w and 0 <= iy < self.h

    def is_blocked(self, ix, iy):
        return not self.in_bounds(ix, iy) or self.mask[iy * self.w + ix] == 1

    def cell_of(self, p):
        return int((p[0] - self.x0) // self.cell), int((p[1] - self.y0) // self.cell)

    def center(self, i):
        ix, iy = self.ij(i)
        return (self.x0 + (ix + 0.5) * self.cell, self.y0 + (iy + 0.5) * self.cell)


def mark_seg(g, a, b, buf, val=1, arr=None):
    if arr is None:
        arr = g.mask
    xmin, xmax = min(a[0], b[0]) - buf, max(a[0], b[0]) + buf
    ymin, ymax = min(a[1], b[1]) - buf, max(a[1], b[1]) + buf
    ix0 = max(0, int((xmin - g.x0) // g.cell) - 1)
    ix1 = min(g.w - 1, int((xmax - g.x0) // g.cell) + 1)
    iy0 = max(0, int((ymin - g.y0) // g.cell) - 1)
    iy1 = min(g.h - 1, int((ymax - g.y0) // g.cell) + 1)
    b2 = buf * buf
    for iy in range(iy0, iy1 + 1):
        y = g.y0 + (iy + 0.5) * g.cell
        for ix in range(ix0, ix1 + 1):
            x = g.x0 + (ix + 0.5) * g.cell
            dx, dy = b[0] - a[0], b[1] - a[1]
            l2 = dx * dx + dy * dy
            if l2 < 1e-12:
                d2 = (x - a[0]) ** 2 + (y - a[1]) ** 2
            else:
                t = max(0.0, min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / l2))
                d2 = (x - (a[0] + t * dx)) ** 2 + (y - (a[1] + t * dy)) ** 2
            if d2 <= b2:
                arr[iy * g.w + ix] = val


def raster_poly(g, polygons, buf, arr=None, val=1):
    if arr is None:
        arr = g.mask
    for poly in polygons:
        ymin = min(p[1] for ring in poly for p in ring)
        ymax = max(p[1] for ring in poly for p in ring)
        iy0 = max(0, int((ymin - buf - g.y0) // g.cell) - 1)
        iy1 = min(g.h - 1, int((ymax + buf - g.y0) // g.cell) + 1)
        for iy in range(iy0, iy1 + 1):
            y = g.y0 + (iy + 0.5) * g.cell
            xs = []
            for ring in poly:
                n = len(ring)
                for i in range(n):
                    x1, y1 = ring[i]
                    x2, y2 = ring[(i + 1) % n]
                    if (y1 > y) != (y2 > y):
                        xs.append((x2 - x1) * (y - y1) / (y2 - y1) + x1)
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                ix0 = max(0, int((xs[i] - g.x0) // g.cell))
                ix1 = min(g.w - 1, int((xs[i + 1] - g.x0) // g.cell))
                for ix in range(ix0, ix1 + 1):
                    arr[iy * g.w + ix] = val
        if buf > 0:
            for ring in poly:
                n = len(ring)
                for i in range(n):
                    mark_seg(g, ring[i], ring[(i + 1) % n], buf, val, arr)


def raster_line(g, line, buf, val=1):
    for i in range(len(line) - 1):
        mark_seg(g, line[i], line[i + 1], buf, val)


def dijkstra(g, source_cells, turn_penalty=0.05):
    w, h = g.w, g.h
    n9 = 9
    dist = [INF] * (w * h * n9)
    parent = [-1] * (w * h * n9)
    heap = []
    for c in source_cells:
        s = c * n9 + 8
        if dist[s] > 0:
            dist[s] = 0.0
            heapq.heappush(heap, (0.0, s))
    while heap:
        d, state = heapq.heappop(heap)
        if d > dist[state]:
            continue
        c, dr = divmod(state, n9)
        ix, iy = g.ij(c)
        for nd in range(8):
            if dr != 8 and TURN[(nd - dr) % 8] > 90:
                continue
            dx, dy = DIRS[nd]
            nx, ny = ix + dx, iy + dy
            if g.is_blocked(nx, ny):
                continue
            if dx and dy and (g.is_blocked(ix + dx, iy) or g.is_blocked(ix, iy + dy)):
                continue
            ns = (ny * w + nx) * n9 + nd
            step = DIRLEN[nd] + (turn_penalty * TURN[(nd - dr) % 8] / 45.0 if dr != 8 else 0.0)
            nv = d + step
            if nv < dist[ns] - 1e-12:
                dist[ns] = nv
                parent[ns] = state
                heapq.heappush(heap, (nv, ns))
    return dist, parent


def los_free(g, a, b):
    n = max(2, int(dist(a, b) / (g.cell * 0.5)) + 1)
    for s in range(n + 1):
        t = s / n
        p = (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        ix, iy = g.cell_of(p)
        if g.is_blocked(ix, iy):
            return False
    return True


def angle_ok(p0, p1, p2):
    a = (p1[0] - p0[0], p1[1] - p0[1])
    b = (p2[0] - p1[0], p2[1] - p1[1])
    na, nb = math.hypot(*a), math.hypot(*b)
    if na < 1e-9 or nb < 1e-9:
        return True
    cosang = (a[0] * b[0] + a[1] * b[1]) / (na * nb)
    return cosang >= -1e-9


def simplify(g, pts):
    if len(pts) <= 2:
        return pts
    out = [pts[0]]
    i = 0
    n = len(pts)
    while i < n - 1:
        j = n - 1
        while j > i + 1 and not (los_free(g, pts[i], pts[j]) and (j == n - 1 or angle_ok(out[-1] if len(out) > 1 else pts[i], pts[j], pts[j + 1]))):
            j -= 1
        if j > i and los_free(g, pts[i], pts[j]):
            out.append(pts[j])
            i = j
        else:
            out.append(pts[i + 1])
            i += 1
    # collinear cleanup
    res = [out[0]]
    for p in out[1:]:
        if dist(res[-1], p) > 1e-6:
            res.append(p)
    return res


def split_special(poly, roads):
    segs = []
    for i in range(len(poly) - 1):
        a, b = poly[i], poly[i + 1]
        ivs = []
        for rp in roads:
            ivs += seg_polygon_intervals(a, b, rp)
        seglen = dist(a, b)
        ivs = [iv for iv in union_intervals(ivs) if (iv[1] - iv[0]) * seglen >= 0.5]
        segs.append((a, b, ivs))
    pts, flags = [], []
    for a, b, ivs in segs:
        subs = []
        prev = 0.0
        for t0, t1 in ivs:
            if t0 > prev:
                subs.append((prev, t0, False))
            subs.append((t0, t1, True))
            prev = t1
        if prev < 1.0:
            subs.append((prev, 1.0, False))
        for t0, t1, sp in subs:
            p0 = (a[0] + t0 * (b[0] - a[0]), a[1] + t0 * (b[1] - a[1]))
            p1 = (a[0] + t1 * (b[0] - a[0]), a[1] + t1 * (b[1] - a[1]))
            if not pts:
                pts.append(p0)
            elif dist(pts[-1], p0) > 1e-6:
                pts.append(p0)
                flags.append(flags[-1] if flags else False)
            pts.append(p1)
            flags.append(sp)
    pieces = []
    if not flags:
        return [(pts, False)]
    start = 0
    for i in range(1, len(flags)):
        if flags[i] != flags[i - 1]:
            pieces.append((pts[start:i + 1], flags[i - 1]))
            start = i
    pieces.append((pts[start:], flags[-1]))
    return pieces


# ---------- load ----------
def load():
    with open(SRC, encoding="utf-8") as f:
        data = json.load(f)
    net_lines, chambers, sources, restrictions, cps = [], [], [], [], []
    for feat in data["features"]:
        p = feat.get("properties", {})
        ot = p.get("object_type")
        geom = feat.get("geometry")
        if not geom:
            continue
        if ot == "heat_network":
            for line in lines_of(geom):
                net_lines.append([to_xy(c) for c in line])
        elif ot == "heat_chamber":
            chambers.append({"id": p["id"], "xy": to_xy(geom["coordinates"])})
        elif ot == "source":
            sources.append({"id": p["id"], "xy": to_xy(geom["coordinates"])})
        elif ot == "oks_connection_point":
            cps.append({"id": p["id"], "flow": float(p["flow_tph"]), "xy": to_xy(geom["coordinates"])})
        elif ot == "restriction":
            restrictions.append({"id": p.get("id"), "type": p.get("restriction_type"), "geom": geom})
    return net_lines, chambers, sources, restrictions, cps


def prepare(cps, restrictions):
    oks = []
    for r in restrictions:
        if r["type"] == "oks":
            polys = [[[to_xy(c) for c in ring] for ring in poly] for poly in polys_of(r["geom"])]
            pts = [p for poly in polys for ring in poly for p in ring]
            oks.append({"id": r["id"], "polys": polys, "xy": pts})

    def in_owner(pt, o):
        return any(point_in_poly(pt, poly) for poly in o["polys"])

    for cp in cps:
        cp["oks"] = next((o for o in oks if in_owner(cp["xy"], o)), None)
        d = cap_diam(cp["flow"])
        cp["design_d"] = d
        o = cp["oks"]
        if not o:
            cp["exit"] = cp["xy"]
            cp["outdir"] = (0.0, 0.0)
            cp["exit_clear"] = 0.0
            continue
        hull = convex_hull(o["xy"])
        best = None
        for i in range(len(hull)):
            a, b = hull[i], hull[(i + 1) % len(hull)]
            dx, dy = b[0] - a[0], b[1] - a[1]
            l2 = dx * dx + dy * dy
            if l2 < 1e-12:
                continue
            t = max(0.0, min(1.0, ((cp["xy"][0] - a[0]) * dx + (cp["xy"][1] - a[1]) * dy) / l2))
            q = (a[0] + t * dx, a[1] + t * dy)
            dd = dist(cp["xy"], q)
            if best is None or dd < best[0]:
                best = (dd, q)
        q = best[1]
        vx, vy = q[0] - cp["xy"][0], q[1] - cp["xy"][1]
        n = math.hypot(vx, vy) or 1.0
        off = oks_min_dist(d) + D[d][4] / 2.0
        cp["exit"] = (q[0] + vx / n * off, q[1] + vy / n * off)
        cp["outdir"] = (vx / n, vy / n)
        cp["exit_clear"] = off
    return oks


# ---------- routing ----------
def grow_forest(g, source_cells, cps, turn_penalty=0.05, road_penalty=2.0):
    """Greedy Steiner growth: connect terminals one by one to the current tree.

    Each connected terminal's path becomes part of the tree and is offered as a
    new source, so later terminals may join it at a T-junction (shared trunks).
    """
    w, h = g.w, g.h
    n9 = 9
    dist = [INF] * (w * h * n9)
    parent = [-1] * (w * h * n9)
    heap = []
    for c in source_cells:
        s = c * n9 + 8
        dist[s] = 0.0
        heapq.heappush(heap, (0.0, s))
    term_by_cell = {}
    for cp in cps:
        term_by_cell.setdefault(cp["cell"], cp)
    connected = {}
    parent_of = {}
    remaining = set(cp["id"] for cp in cps)
    while heap and remaining:
        d, state = heapq.heappop(heap)
        if d > dist[state]:
            continue
        c, dr = divmod(state, n9)
        cp = term_by_cell.get(c)
        if cp is not None:
            if cp["id"] in remaining:
                if dr == 8:
                    connected[cp["id"]] = [c]
                    remaining.discard(cp["id"])
                else:
                    path = []
                    s = state
                    while s != -1:
                        path.append(s // n9)
                        s = parent[s]
                    path = path[::-1]
                    connected[cp["id"]] = path
                    remaining.discard(cp["id"])
                    for i in range(1, len(path)):
                        parent_of[path[i]] = path[i - 1]
                    for i in range(1, len(path)):
                        cc = path[i]
                        ss = cc * n9 + 8
                        dist[ss] = 0.0
                        parent[ss] = -1
                        heapq.heappush(heap, (0.0, ss))
            continue  # terminals never relax (leaf, no transit)
        ix, iy = g.ij(c)
        for nd in range(8):
            if dr != 8 and TURN[(nd - dr) % 8] > 90:
                continue
            dx, dy = DIRS[nd]
            nx, ny = ix + dx, iy + dy
            if g.is_blocked(nx, ny):
                continue
            if dx and dy and (g.is_blocked(ix + dx, iy) or g.is_blocked(ix, iy + dy)):
                continue
            ns = (ny * w + nx) * n9 + nd
            step = DIRLEN[nd] + (turn_penalty * TURN[(nd - dr) % 8] / 45.0 if dr != 8 else 0.0)
            if g.road[ny * w + nx]:
                step += road_penalty
            nv = d + step
            if nv < dist[ns] - 1e-12:
                dist[ns] = nv
                parent[ns] = state
                heapq.heappush(heap, (nv, ns))
    return connected, parent_of, remaining, dist


def run_variant(name, g, source_cells, cps, chambers, roads, turn_penalty=0.05, road_penalty=2.0):
    for cp in cps:
        ix, iy = g.cell_of(cp["exit"])
        cp["cell"] = iy * g.w + ix
    connected, parent_of, remaining, dist_arr = grow_forest(g, source_cells, cps, turn_penalty, road_penalty)
    reach = [cp for cp in cps if cp["id"] in connected]
    cp_by_cell = {cp["cell"]: cp for cp in reach}

    children = defaultdict(list)
    for cell, p in parent_of.items():
        children[p].append(cell)
    term_cells = set(connected.keys())
    roots = {p for p in children if p not in parent_of}
    node_cells = set(roots) | term_cells
    for c in list(children):
        if len(children[c]) >= 2:
            node_cells.add(c)
    depth = {}
    stack = list(roots)
    for r in roots:
        depth[r] = 0
    while stack:
        c = stack.pop()
        for ch in children[c]:
            depth[ch] = depth[c] + 1
            stack.append(ch)

    raw_edges = []
    for nc in list(node_cells):
        for ch in children.get(nc, []):
            path = [nc, ch]
            cur = ch
            while cur not in node_cells:
                nxt = children.get(cur, [])
                if not nxt:
                    break
                cur = nxt[0]
                path.append(cur)
            raw_edges.append(path)

    # node coordinate map
    node_coord = {}
    for c in node_cells:
        node_coord[c] = g.center(c)
    for cp in reach:
        node_coord[cp["cell"]] = cp["xy"]

    E = []
    for path in raw_edges:
        if depth.get(path[0], 0) > depth.get(path[-1], 0):
            path = path[::-1]
        pts = [g.center(c) for c in path]
        b = path[-1]
        if b in cp_by_cell:
            cp = cp_by_cell[b]
            if len(pts) > 1 and pts[-2] != cp["exit"]:
                pts.insert(len(pts) - 1, cp["exit"])
            pts[-1] = cp["xy"]
        pts = simplify(g, pts)
        E.append({"from": path[0], "to": path[-1], "pts": pts, "flow": 0.0, "diam": None})

    out_edges = defaultdict(list)
    for e in E:
        out_edges[e["from"]].append(e)

    # flow accumulation over the whole tree (all cells), leaves upward
    flow_at = defaultdict(float)
    for cp in reach:
        flow_at[cp["cell"]] += cp["flow"]
    for c in sorted(parent_of.keys(), key=lambda x: -depth.get(x, 0)):
        flow_at[parent_of[c]] += flow_at[c]
    for e in E:
        e["flow"] = flow_at[e["to"]] if e["to"] in flow_at else 0.0
        if e["to"] in cp_by_cell and e["flow"] < cp_by_cell[e["to"]]["flow"] - 1e-9:
            e["flow"] = cp_by_cell[e["to"]]["flow"]

    # length-limit aware diameter
    for e in E:
        e["diam"] = cap_diam(e["flow"]) if e["flow"] > 0 else 50
        e["len"] = sum(dist(e["pts"][i], e["pts"][i + 1]) for i in range(len(e["pts"]) - 1))

    def down_max(e, memo):
        if id(e) in memo:
            return memo[id(e)]
        best = e["len"]
        for ch in out_edges.get(e["to"], []):
            best = max(best, e["len"] + down_max(ch, memo))
        memo[id(e)] = best
        return best

    memo = {}
    for e in E:
        need = min_diam_for_limit(down_max(e, memo))
        if need > e["diam"]:
            e["diam"] = need
    for e in sorted(E, key=lambda x: -depth.get(x["from"], 0)):
        p = parent_of.get(e["from"])
        if p is None:
            continue
        for pe in out_edges.get(p, []):
            if pe["to"] == e["from"] and pe["diam"] < e["diam"]:
                pe["diam"] = e["diam"]

    # split special (roads)
    out_edges2 = []
    tn_id = [0]
    for e in E:
        pieces = split_special(e["pts"], roads)
        if len(pieces) == 1:
            e["special"] = pieces[0][1]
            e["pts"] = pieces[0][0]
            out_edges2.append(e)
            continue
        cur_from = e["from"]
        for k, (pts, sp) in enumerate(pieces):
            last = k == len(pieces) - 1
            if not last:
                tn_id[0] += 1
                tn_node = ("TN", tn_id[0])
                node_coord[tn_node] = pts[-1]
                to = tn_node
            else:
                to = e["to"]
            ne = dict(e)
            ne["from"] = cur_from
            ne["to"] = to
            ne["pts"] = pts
            ne["special"] = sp
            ne["len"] = sum(dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
            out_edges2.append(ne)
            cur_from = to
    for e in out_edges2:
        if e["from"] not in node_coord:
            node_coord[e["from"]] = g.center(e["from"]) if isinstance(e["from"], int) else e["from"]
        if e["to"] not in node_coord:
            node_coord[e["to"]] = g.center(e["to"]) if isinstance(e["to"], int) else e["to"]

    # names for nodes
    next_c = [0]
    node_ids = {}
    tie_in_existing = []
    for e in out_edges2:
        for node in (e["from"], e["to"]):
            if node in node_ids:
                continue
            if node in cp_by_cell:
                node_ids[node] = cp_by_cell[node]["id"]
            elif isinstance(node, int) and node in roots:
                # root on network: existing chamber within 10m?
                xy = node_coord[node]
                ch = next((c for c in chambers if dist(c["xy"], xy) <= 10.0), None)
                if ch:
                    node_ids[node] = ch["id"]
                    node_coord[node] = ch["xy"]
                else:
                    next_c[0] += 1
                    node_ids[node] = f"C{next_c[0]}"
            elif isinstance(node, int) and len(children[node]) >= 2:
                next_c[0] += 1
                node_ids[node] = f"C{next_c[0]}"
            elif isinstance(node, tuple) and node[0] == "TN":
                node_ids[node] = f"TN{node[1]}"
            else:
                node_ids[node] = f"N{node}" if not isinstance(node, int) else f"N{node}"
    # branch node cells → chamber
    for c in node_cells:
        if isinstance(c, int) and len(children.get(c, [])) >= 2 and c not in cp_by_cell:
            if c not in node_ids:
                next_c[0] += 1
                node_ids[c] = f"C{next_c[0]}"
    return {
        "name": name,
        "edges": out_edges2,
        "node_ids": node_ids,
        "node_coord": node_coord,
        "reach": reach,
        "unreach": [cp for cp in cps if cp["id"] not in connected],
        "roots": roots,
        "chambers": chambers,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cell", type=float, default=1.0)
    ap.add_argument("--road-penalty", type=float, default=2.0)
    ap.add_argument("--turn-penalty", type=float, default=0.05)
    ap.add_argument("--out", default=os.path.join(OUTDIR, "engineer-variants.geojson"))
    args = ap.parse_args()

    net_lines, chambers, sources, restrictions, cps = load()
    prepare(cps, restrictions)

    x0, x1, y0, y1 = -360.0, 340.0, -240.0, 320.0
    w = int((x1 - x0) / args.cell) + 1
    h = int((y1 - y0) / args.cell) + 1
    g = Grid(x0, y0, w, h, args.cell)

    roads = []
    for r in restrictions:
        if r["type"] in ("road", None):
            continue
        if r["type"] == "oks":
            raster_poly(g, [[[to_xy(c) for c in ring] for ring in poly] for poly in polys_of(r["geom"])], 5.5)
        else:
            for poly in polys_of(r["geom"]):
                raster_poly(g, [[[to_xy(c) for c in ring] for ring in poly]], 1.5)
            for line in lines_of(r["geom"]):
                raster_line(g, [to_xy(c) for c in line], 1.5)
    for r in restrictions:
        if r["type"] == "road":
            polys = [[[to_xy(c) for c in ring] for ring in poly] for poly in polys_of(r["geom"])]
            for poly in polys:
                roads.append(poly)
            raster_poly(g, polys, 0.0, arr=g.road)

    # sources
    all_sources, chamber_sources = set(), set()
    for line in net_lines:
        for i in range(len(line) - 1):
            a, b = line[i], line[i + 1]
            steps = max(1, int(dist(a, b) / (args.cell * 0.5)))
            for s in range(steps + 1):
                t = s / steps
                p = (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
                ix, iy = g.cell_of(p)
                if g.in_bounds(ix, iy):
                    g.mask[iy * g.w + ix] = 0
                    all_sources.add(iy * g.w + ix)
    for ch in chambers:
        ix, iy = g.cell_of(ch["xy"])
        if g.in_bounds(ix, iy):
            g.mask[iy * g.w + ix] = 0
            chamber_sources.add(iy * g.w + ix)
    for cp in cps:
        ox, oy = cp["exit"]
        dx, dy = cp["outdir"]
        for t in range(0, int(cp["exit_clear"]) + 4):
            cx, cy = ox + dx * t, oy + dy * t
            ix, iy = g.cell_of((cx, cy))
            for ax in range(-2, 3):
                for ay in range(-2, 3):
                    if g.in_bounds(ix + ax, iy + ay):
                        g.mask[(iy + ay) * g.w + ix + ax] = 0
    print(f"grid {g.w}x{g.h} blocked={sum(g.mask)} sources={len(all_sources)}")

    three = set()
    for want in (108, 110, 106):
        ch = next((c for c in chambers if c["id"] == want), None)
        if ch:
            ix, iy = g.cell_of(ch["xy"])
            for dx in range(-1, 2):
                for dy in range(-1, 2):
                    if g.in_bounds(ix + dx, iy + dy):
                        three.add((iy + dy) * g.w + ix + dx)

    variants = []
    for name, src in (("all-network", all_sources), ("existing-chambers", chamber_sources), ("three-tie-ins", three)):
        v = run_variant(name, g, src, cps, chambers, roads, args.turn_penalty, args.road_penalty)
        # economics
        L = sum(e["len"] for e in v["edges"])
        cost = 0.0
        for e in v["edges"]:
            k = 1.6 if e["special"] else 1.0
            cost += e["len"] * D[e["diam"]][3] * k
        # chambers
        seen_ch = set()
        cham_cost = 0.0
        tie_count = 0
        tie_cost = 0.0
        for e in v["edges"]:
            for node in (e["from"], e["to"]):
                nid = v["node_ids"].get(node)
                if isinstance(nid, str) and nid.startswith("C") and nid not in seen_ch:
                    seen_ch.add(nid)
                    maxd = max([x["diam"] for x in v["edges"] if x["from"] == node or x["to"] == node] or [50])
                    cham_cost += chamber_cost(maxd)
                if isinstance(node, int) and node in v["roots"]:
                    if not isinstance(nid, str) or not nid.startswith("C"):
                        tie_count += 1
                        tie_cost += 5_000_000
        unreach = v["unreach"]
        penalty = sum(100_000_000 + 500_000 * cp["flow"] for cp in unreach)
        C = cost + cham_cost + tie_cost + penalty
        Sv = 0.7 * C / 25_000_000 + 0.3 * L / 100
        v["L"] = L
        v["cost"] = cost
        v["cham_cost"] = cham_cost
        v["tie_count"] = tie_count
        v["tie_cost"] = tie_cost
        v["penalty"] = penalty
        v["C"] = C
        v["S"] = Sv
        variants.append(v)
        print(f"[{name}] edges={len(v['edges'])} L={L:.1f} cost={cost/1e6:.2f}M chambers={cham_cost/1e6:.1f}M ties={tie_count} C={C/1e6:.2f}M S={Sv:.4f} unreach={[c['id'] for c in unreach]}")

    variants = [v for v in variants if not v["unreach"]]
    variants.sort(key=lambda v: v["S"])
    for i, v in enumerate(variants):
        v["rank"] = i + 1
        v["variant_id"] = f"v{i+1}"
    best = variants[:3]

    os.makedirs(OUTDIR, exist_ok=True)
    features = []
    for v in best:
        vid = v["variant_id"]
        for k, e in enumerate(v["edges"], 1):
            sid = v["node_ids"][e["from"]]
            eid = v["node_ids"][e["to"]]
            geom = [to_ll(p) for p in e["pts"]]
            if not (abs(geom[0][0] - to_ll(v["node_coord"][e["from"]])[0]) < 1e-9):
                geom[0] = to_ll(v["node_coord"][e["from"]])
            geom[-1] = to_ll(v["node_coord"][e["to"]])
            kk = 1.6 if e["special"] else 1.0
            features.append({
                "type": "Feature",
                "properties": {
                    "id": f"{vid}_net_{k}", "object_type": "heat_network", "variant_id": vid,
                    "start_node_id": sid, "end_node_id": eid,
                    "flow_tph": round(e["flow"], 3), "diameter": e["diam"],
                    "length": round(e["len"], 2), "laying_method": "special" if e["special"] else "base",
                    "depth_start": None, "depth_end": None,
                    "cost": round(e["len"] * D[e["diam"]][3] * kk),
                },
                "geometry": {"type": "LineString", "coordinates": geom},
            })
        seen = {}
        for e in v["edges"]:
            for node in (e["from"], e["to"]):
                nid = v["node_ids"][node]
                if isinstance(nid, str) and nid.startswith("C") and nid not in seen:
                    seen[nid] = v["node_coord"][node]
        for nid, xy in seen.items():
            maxd = max([x["diam"] for x in v["edges"] if v["node_ids"][x["from"]] == nid or v["node_ids"][x["to"]] == nid] or [50])
            features.append({
                "type": "Feature",
                "properties": {"id": nid, "object_type": "heat_chamber", "variant_id": vid,
                               "diameter": maxd, "cost": chamber_cost(maxd)},
                "geometry": {"type": "Point", "coordinates": to_ll(xy)},
            })
        for node, nid in v["node_ids"].items():
            if isinstance(nid, str) and nid.startswith("TN"):
                features.append({
                    "type": "Feature",
                    "properties": {"id": nid, "object_type": "technical_node", "variant_id": vid},
                    "geometry": {"type": "Point", "coordinates": to_ll(v["node_coord"][node])},
                })
        features.append({
            "type": "Feature",
            "properties": {
                "id": f"{vid}_summary", "object_type": "variant_summary", "variant_id": vid,
                "rank": v["rank"], "construction_cost": round(v["C"] - v["penalty"]),
                "chamber_construction_cost": round(v["cham_cost"]),
                "existing_chamber_tie_in_count": v["tie_count"],
                "existing_chamber_tie_in_cost": round(v["tie_cost"]),
                "unconnected_penalty": round(v["penalty"]),
                "calculated_cost": round(v["C"]),
                "new_network_length": round(v["L"], 2),
                "score": round(v["S"], 6),
                "unconnected_oks_ids": [],
            },
            "geometry": None,
        })
    fc = {"type": "FeatureCollection", "features": features}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(fc, f, ensure_ascii=False)
    print("wrote", args.out, "features", len(features))


if __name__ == "__main__":
    main()
