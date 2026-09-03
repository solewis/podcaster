import math

# --- the glyph, as convex pieces, in the 108dp adaptive-icon viewport (centre 54,54) ---
DY = 1.0  # nudge so the glyph is vertically centred

def rect(x0, y0, x1, y1):
    return [(x0, y0 + DY), (x1, y0 + DY), (x1, y1 + DY), (x0, y1 + DY)]

def pill(cx, top, bottom, r, steps=24):
    """A capsule: semicircle, straight sides, semicircle."""
    pts = []
    ty, by = top + r, bottom - r
    for i in range(steps + 1):
        a = math.pi + math.pi * i / steps
        pts.append((cx + r * math.cos(a), ty + r * math.sin(a) + DY))
    for i in range(steps + 1):
        a = math.pi * i / steps
        pts.append((cx + r * math.cos(a), by + r * math.sin(a) + DY))
    return pts

def arc_quads(cx, cy, r_in, r_out, a0, a1, segments=10):
    """The yoke's bottom sweep, cut into convex quads."""
    out = []
    for i in range(segments):
        b0 = a0 + (a1 - a0) * i / segments
        b1 = a0 + (a1 - a0) * (i + 1) / segments
        out.append([
            (cx + r_in * math.cos(b0), cy + r_in * math.sin(b0) + DY),
            (cx + r_out * math.cos(b0), cy + r_out * math.sin(b0) + DY),
            (cx + r_out * math.cos(b1), cy + r_out * math.sin(b1) + DY),
            (cx + r_in * math.cos(b1), cy + r_in * math.sin(b1) + DY),
        ])
    return out

CAPSULE = pill(54, 22, 60, 11)
CROSS_L = rect(32, 40, 42, 43.5)
CROSS_R = rect(66, 40, 76, 43.5)
ARM_L = rect(35, 42, 39, 51)
ARM_R = rect(69, 42, 73, 51)
YOKE = arc_quads(54, 50, 15, 19, 0.0, math.pi, segments=12)
STEM = rect(51, 68, 57, 79)
BASE = rect(41, 78, 67, 83)

PIECES = [CAPSULE, CROSS_L, CROSS_R, ARM_L, ARM_R, STEM, BASE] + YOKE

def hull(points):
    pts = sorted(set((round(x, 3), round(y, 3)) for x, y in points))
    if len(pts) < 3:
        return pts
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]

def path(points, close=True):
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points)
    return d + " Z" if close else d

def swept(piece, distance):
    """
    The exact swept region of one *convex* piece along the 45 degree light direction.

    Convexity is why each piece is sweptseparately rather than the glyph as a whole: for a convex
    shape the swept region is precisely the hull of the shape and its translate, and the union of
    the pieces' sweeps is the union's sweep. Taking the hull of the whole glyph at once would bridge
    the gap between the capsule and the yoke arms and cast shadow the glyph never blocks.
    """
    d = distance / math.sqrt(2)
    return hull(list(piece) + [(x + d, y + d) for x, y in piece])
