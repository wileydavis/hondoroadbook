# layers_sun.json from layers_light.json: the noon-sun handlebar style. A white ground, because the glass mirrors the
# sky and a dark map has to out-shine that reflection while a bright one out-shines it; nothing drawn that does not
# help you ride (no landuse, buildings, pois, address labels, hillshade), roads half again as wide in a few dark
# colours that stay apart in glare, labels bigger in black with a thick white halo. Fewer layers is less GPU work.
import json, sys, copy
src, outs = sys.argv[1], sys.argv[2:]
L = json.load(open(src))
DROP = {'landcover', 'landuse_park', 'landuse_urban_green', 'landuse_hospital', 'landuse_industrial', 'landuse_school', 'landuse_beach', 'landuse_zoo',
        'landuse_aerodrome', 'landuse_runway', 'landuse_pedestrian', 'landuse_pier', 'roads_taxiway', 'roads_pier', 'buildings', 'address_label',
        'earth_label_islands', 'water_label_ocean', 'pois', 'fold_sidewalk', 'roads_oneway'}
DROP |= {l['id'] for l in L if l['id'].endswith('_casing')}
COLORS = {  # road layer -> line colour: dark enough to beat the sky's reflection, apart enough to tell the class
    'fold_motorway': '#b7410e', 'fold_link': '#b7410e', 'fold_primary': '#9a5b00', 'fold_secondary': '#5a4a00',
    'fold_tertiary': '#000000', 'fold_minor': '#2a2a2a', 'fold_service': '#6a6a6a', 'fold_ov_minor': '#2a2a2a', 'fold_ov_service': '#6a6a6a',
    'fold_track': '#a0522d', 'fold_ov_track': '#a0522d', 'fold_trail': '#a0522d', 'fold_ov_path': '#a0522d', 'fold_cycleway': '#1a7a3c',
    'fold_rail': '#555555', 'roads_runway': '#c8c8c8', 'water_stream': '#4a8fc8', 'water_river': '#4a8fc8',
    'boundaries': '#7a63b8', 'boundaries_country': '#7a63b8',
}
def scale(expr, f):
    """multiplies the output numbers of a width/size expression (interpolate stops, case/match outputs), leaving the inputs alone"""
    if isinstance(expr, (int, float)): return round(expr * f, 2)
    if not isinstance(expr, list) or not expr: return expr
    op = expr[0]
    if op == '*' and len(expr) == 3 and isinstance(expr[2], (int, float)): return [op, expr[1], round(expr[2] * f, 3)]
    if op == 'interpolate': return expr[:3] + [scale(x, f) if i % 2 == 0 else x for i, x in enumerate(expr[3:], 3)]
    if op == 'step': return [op, expr[1], scale(expr[2], f)] + [scale(x, f) if i % 2 == 1 else x for i, x in enumerate(expr[3:], 3)]
    if op == 'case': return [op] + [scale(x, f) if (i % 2 == 0 or i == len(expr) - 1) else x for i, x in enumerate(expr[1:], 1)]
    if op == 'match': return [op, expr[1]] + [scale(x, f) if (i % 2 == 1 or i == len(expr) - 1) else x for i, x in enumerate(expr[2:], 2)]
    return expr
out = []
for l in L:
    if l['id'] in DROP: continue
    l = copy.deepcopy(l); p = l.setdefault('paint', {}); lay = l.setdefault('layout', {})
    if l['id'] in ('background', 'earth'): p[('background-color' if l['type'] == 'background' else 'fill-color')] = '#ffffff'
    elif l['id'] == 'water': p['fill-color'] = '#a9cde8'
    elif l['type'] == 'line':
        if l['id'] in COLORS: p['line-color'] = COLORS[l['id']]
        if 'line-width' in p: p['line-width'] = scale(p['line-width'], 1.5)
        if l['id'].startswith('fold_') and 'line-opacity' in p: p['line-opacity'] = 1
    elif l['type'] == 'symbol':
        if 'text-color' in p and l['id'] != 'roads_shields': p['text-color'] = '#000000'
        if 'text-halo-color' in p: p['text-halo-color'] = '#ffffff'; p['text-halo-width'] = 2.5
        if 'text-opacity' in p: p['text-opacity'] = max(0.85, p['text-opacity']) if isinstance(p['text-opacity'], (int, float)) else p['text-opacity']
        if 'text-size' in lay: lay['text-size'] = scale(lay['text-size'], 1.15)
        if 'text-font' in lay: lay['text-font'] = ['Noto Sans Medium']
    elif l['type'] == 'circle':
        p['circle-color'] = '#000000'; p['circle-stroke-color'] = '#ffffff'
    out.append(l)
for o in outs: json.dump(out, open(o, 'w'), separators=(',', ':'))
print(len(L), '->', len(out), 'layers')
