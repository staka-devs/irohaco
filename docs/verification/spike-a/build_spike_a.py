"""Builds the Spike A evaluation set from COCO val2017: 200 openly licensed photos, 40 queries (ja/en).

Writes <out>/images/*.jpg, <out>/queries.json and <out>/ATTRIBUTION.tsv. Only images under CC BY,
CC BY-SA, "no known copyright restrictions" or US Government Work are used.
usage: python -I build_spike_a.py <instances_val2017.json> <out_dir>
"""
import json
import os
import sys
import urllib.request

ann_path, out = sys.argv[1], sys.argv[2]
OPEN_LICENSES = {4, 5, 7, 8}  # BY, BY-SA, no known restrictions, US Gov
PER_CATEGORY = 10
QUERIES = {  # COCO name -> Japanese query
    'dog': '犬', 'cat': '猫', 'pizza': 'ピザ', 'bicycle': '自転車', 'car': '車', 'airplane': '飛行機',
    'train': '電車', 'boat': '船', 'bird': '鳥', 'horse': '馬', 'elephant': '象', 'giraffe': 'キリン',
    'umbrella': '傘', 'cake': 'ケーキ', 'clock': '時計', 'bench': 'ベンチ', 'laptop': 'ノートパソコン',
    'bed': 'ベッド', 'toilet': 'トイレ', 'banana': 'バナナ',
}

data = json.load(open(ann_path, encoding='utf-8'))
licenses = {l['id']: l for l in data['licenses']}
images = {i['id']: i for i in data['images'] if i['license'] in OPEN_LICENSES}
cat_id = {c['name']: c['id'] for c in data['categories']}
present = {}   # image id -> set of category ids (any size)
largest = {}   # (image id, category id) -> largest instance share of the image
for a in data['annotations']:
    img = images.get(a['image_id'])
    if img is None or a.get('iscrowd'):
        continue
    present.setdefault(img['id'], set()).add(a['category_id'])
    share = a['area'] / (img['width'] * img['height'])
    key = (img['id'], a['category_id'])
    largest[key] = max(largest.get(key, 0), share)

chosen = []
for name in QUERIES:
    cid = cat_id[name]
    # The photos where this thing is largest, so each is clearly about it; ties by id keep it repeatable.
    pool = sorted(((share, i) for (i, c), share in largest.items() if c == cid and i not in chosen), key=lambda t: (-t[0], t[1]))
    chosen += [i for _, i in pool[:PER_CATEGORY]]

os.makedirs(os.path.join(out, 'images'), exist_ok=True)
rows = ['file\tlicense\tflickr_url\tcoco_url']
for iid in chosen:
    img = images[iid]
    dest = os.path.join(out, 'images', img['file_name'])
    if not os.path.exists(dest):
        urllib.request.urlretrieve(img['coco_url'], dest)
    rows.append(f"{img['file_name']}\t{licenses[img['license']]['name']}\t{img.get('flickr_url', '')}\t{img['coco_url']}")
open(os.path.join(out, 'ATTRIBUTION.tsv'), 'w', encoding='utf-8').write('\n'.join(rows) + '\n')

# Relevant: every chosen photo where the thing appears at all, not only the ones picked for it.
cases = []
for name, ja in QUERIES.items():
    cid = cat_id[name]
    relevant = sorted(images[i]['file_name'] for i in chosen if cid in present.get(i, set()))
    cases.append({'query': ja, 'lang': 'ja', 'relevant': relevant})
    cases.append({'query': f'a photo of a {name}', 'lang': 'en', 'relevant': relevant})
json.dump(cases, open(os.path.join(out, 'queries.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(f'{len(chosen)} images, {len(cases)} queries, relevant per query: '
      f'{min(len(c["relevant"]) for c in cases)}-{max(len(c["relevant"]) for c in cases)}')
