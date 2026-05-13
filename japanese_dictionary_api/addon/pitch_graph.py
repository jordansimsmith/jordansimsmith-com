"""Python port of Yomitan's `createPronunciationGraph` simple-style graph.

Mirrors `tmp/yomitan/ext/js/display/pronunciation-generator.js` plus
`isMoraPitchHigh` from `tmp/yomitan/ext/js/language/ja/japanese.js`. The
emitted SVG carries inline `style="..."` per shape so it renders without
external CSS (mirroring how the SPA's `PitchGraph` component is
self-contained).
"""

import xml.etree.ElementTree as ET

_SMALL_KANA = set("ゃゅょゎァィゥェォャュョヮ")

_LINE_STYLE = "fill:none;stroke:currentColor;stroke-width:5;"
_TAIL_STYLE = "fill:none;stroke:currentColor;stroke-width:5;stroke-dasharray:5 5;"
_TRIANGLE_STYLE = "fill:none;stroke:currentColor;stroke-width:5;"
_DOT_STYLE = "fill:currentColor;stroke:currentColor;stroke-width:5;"
_DOWNSTEP_INNER_STYLE = "fill:none;stroke:currentColor;stroke-width:5;"


def morae(reading):
    """Split a kana reading into morae.

    Small ya/yu/yo characters merge into the preceding mora; ん, っ and the
    long-vowel mark stand alone. Matches `japanese_dictionary_web/src/domain/morae.ts`.
    """
    out = []
    for ch in reading:
        if ch in _SMALL_KANA and out:
            out[-1] = out[-1] + ch
        else:
            out.append(ch)
    return out


def is_mora_pitch_high(mora_index, pitch_position):
    """Mirror of Yomitan's `isMoraPitchHigh` (only the integer-pitch branch)."""
    if pitch_position == 0:
        return mora_index > 0
    if pitch_position == 1:
        return mora_index < 1
    return 0 < mora_index < pitch_position


def render(reading, pitch):
    """Render the pitch-accent graph for `(reading, pitch)` as SVG."""
    mora_list = morae(reading)
    n = len(mora_list)
    svg = ET.Element("svg")
    svg.set("xmlns", "http://www.w3.org/2000/svg")
    svg.set("class", "pronunciation-graph")
    svg.set("focusable", "false")
    svg.set("viewBox", f"0 0 {50 * (n + 1)} 100")
    if n == 0:
        return ET.tostring(svg, encoding="unicode")

    path_points = []
    for i in range(n):
        x = i * 50 + 25
        y = 25 if is_mora_pitch_high(i, pitch) else 75
        if is_mora_pitch_high(i, pitch) and not is_mora_pitch_high(i + 1, pitch):
            _add_downstep_dot(svg, x, y)
        else:
            _add_dot(svg, x, y)
        path_points.append((x, y))

    line = ET.SubElement(svg, "path")
    line.set("class", "pronunciation-graph-line")
    line.set("d", _points_to_path(path_points))
    line.set("style", _LINE_STYLE)

    tail_x = n * 50 + 25
    tail_y = 25 if is_mora_pitch_high(n, pitch) else 75
    _add_triangle(svg, tail_x, tail_y)

    tail = ET.SubElement(svg, "path")
    tail.set("class", "pronunciation-graph-line-tail")
    tail.set("d", _points_to_path([path_points[-1], (tail_x, tail_y)]))
    tail.set("style", _TAIL_STYLE)

    return ET.tostring(svg, encoding="unicode")


def _add_dot(parent, x, y):
    circle = ET.SubElement(parent, "circle")
    circle.set("class", "pronunciation-graph-dot")
    circle.set("cx", str(x))
    circle.set("cy", str(y))
    circle.set("r", "15")
    circle.set("style", _DOT_STYLE)


def _add_downstep_dot(parent, x, y):
    outer = ET.SubElement(parent, "circle")
    outer.set("class", "pronunciation-graph-dot-downstep1")
    outer.set("cx", str(x))
    outer.set("cy", str(y))
    outer.set("r", "15")
    outer.set("style", _DOT_STYLE)
    inner = ET.SubElement(parent, "circle")
    inner.set("class", "pronunciation-graph-dot-downstep2")
    inner.set("cx", str(x))
    inner.set("cy", str(y))
    inner.set("r", "5")
    inner.set("style", _DOWNSTEP_INNER_STYLE)


def _add_triangle(parent, x, y):
    triangle = ET.SubElement(parent, "path")
    triangle.set("class", "pronunciation-graph-triangle")
    triangle.set("d", "M0 13 L15 -13 L-15 -13 Z")
    triangle.set("transform", f"translate({x},{y})")
    triangle.set("style", _TRIANGLE_STYLE)


def _points_to_path(points):
    return "M" + " L".join(f"{x} {y}" for x, y in points)
