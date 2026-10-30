# layers_sun.json from layers_dark.json: the noon-sun handlebar style. Pure black ground (OLED pixels off), nothing
# drawn that does not help you ride (no landuse, buildings, pois, address labels), roads half again as wide in a few
# bright colours that stay apart in glare, labels bigger in white on black. Fewer layers is also less GPU work.
import json, sys, copy
src, outs = sys.argv[1], sys.argv[2:]
L = json.load(open(src))
DROP = {'landcover', 'landuse_park', 'landuse_urban_green', 'landuse_hospital', 'landuse_industrial', 'landuse_school', 'landuse_beach', 'landuse_zoo',
        'landuse_aerodrome', 'landuse_runway', 'landuse_pedestrian', 'landuse_pier', 'roads_taxiway', 'roads_pier', 'buildings', 'address_label',
        'earth_label_islands', 'water_label_ocean', 'pois', 'fold_sidewalk', 'roads_oneway'}
DROP |= {l['id'] for l in L if l['id'].endswith('_casing')}
COLORS = {  # road layer -> line colour
    'fold_motorway': '#ffc400', 'fold_link': '#ffc400', 'fold_primary': '#ffd64a', 'fold_secondary': '#fff2a8',
    'fold_tertiary': '#ffffff', 'fold_minor': '#e6e6e6', 'fold_service': '#a8a8a8', 'fold_ov_minor': '#e6e6e6', 'fold_ov_service': '#a8a8a8',
    'fold_track': '#ff9d3a', 'fold_ov_track': '#ff9d3a', 'fold_trail': '#ff9d3a', 'fold_ov_path': '#ff9d3a', 'fold_cycleway': '#6dff9e',
    'fold_rail': '#8a8a8a', 'roads_runway': '#3a3a3a', 'water_stream': '#3d8ad0', 'water_river': '#3d8ad0',
    'boundaries': '#8f7fc0', 'boundaries_country': '#8f7fc0',
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
    if l['id'] in ('background', 'earth'): p[('background-color' if l['type'] == 'background' else 'fill-color')] = '#000000'
    elif l['id'] == 'water': p['fill-color'] = '#0b2a47'
    elif l['type'] == 'line':
        if l['id'] in COLORS: p['line-color'] = COLORS[l['id']]
        if 'line-width' in p: p['line-width'] = scale(p['line-width'], 1.5)
        if l['id'].startswith('fold_') and 'line-opacity' in p: p['line-opacity'] = 1
    elif l['type'] == 'symbol':
        if 'text-color' in p and l['id'] != 'roads_shields': p['text-color'] = '#ffffff'
        if 'text-halo-color' in p: p['text-halo-color'] = '#000000'; p['text-halo-width'] = 2.5
        if 'text-opacity' in p: p['text-opacity'] = max(0.85, p['text-opacity']) if isinstance(p['text-opacity'], (int, float)) else p['text-opacity']
        if 'text-size' in lay: lay['text-size'] = scale(lay['text-size'], 1.15)
        if 'text-font' in lay: lay['text-font'] = ['Noto Sans Medium']
    elif l['type'] == 'circle':
        p['circle-color'] = '#ffffff'; p['circle-stroke-color'] = '#000000'
    out.append(l)
for o in outs: json.dump(out, open(o, 'w'), separators=(',', ':'))
print(len(L), '->', len(out), 'layers')
