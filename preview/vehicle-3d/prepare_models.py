"""Pack the downloaded glTF packages locally; preserve original files and credits.

Usage: python preview/vehicle-3d/prepare_models.py zd8 [zc6]
Only invisible primitives and non-surface helper lines are removed. No decimation.
"""
import copy
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parent / 'models'


def prepare(key):
    if key not in ('zd8', 'zc6'):
        raise ValueError('Unknown vehicle')
    folder = ROOT / key
    original = folder / 'original'
    document = json.loads((original / 'scene.gltf').read_text(encoding='utf-8'))
    materials = document.get('materials', [])
    kept_meshes, mesh_mapping = [], {}
    removed_primitives = 0
    triangle_count = 0
    for old_index, original_mesh in enumerate(document['meshes']):
        item = copy.deepcopy(original_mesh)
        keep = []
        for primitive in item['primitives']:
            material = materials[primitive.get('material', 0)]
            alpha = material.get('pbrMetallicRoughness', {}).get('baseColorFactor', [1, 1, 1, 1])[3]
            if primitive.get('mode', 4) != 4 or (material.get('alphaMode') == 'BLEND' and alpha == 0):
                removed_primitives += 1
                continue
            keep.append(primitive)
            accessor = primitive.get('indices', primitive['attributes']['POSITION'])
            triangle_count += document['accessors'][accessor]['count'] // 3
        if keep:
            item['primitives'] = keep
            mesh_mapping[old_index] = len(kept_meshes)
            kept_meshes.append(item)
    for node in document['nodes']:
        if 'mesh' in node:
            old_index = node.pop('mesh')
            if old_index in mesh_mapping:
                node['mesh'] = mesh_mapping[old_index]
    document['meshes'] = kept_meshes
    if document.get('animations') or document.get('skins'):
        raise ValueError('This static packing workflow does not support animated/skinned models')
    used_accessors = set()
    for item in kept_meshes:
        for primitive in item['primitives']:
            used_accessors.update(primitive['attributes'].values())
            if 'indices' in primitive:
                used_accessors.add(primitive['indices'])
            if primitive.get('targets'):
                raise ValueError('Morph targets require explicit handling')
    accessor_mapping = {old: new for new, old in enumerate(sorted(used_accessors))}
    accessors = [copy.deepcopy(document['accessors'][i]) for i in sorted(used_accessors)]
    for item in kept_meshes:
        for primitive in item['primitives']:
            primitive['attributes'] = {k: accessor_mapping[v] for k, v in primitive['attributes'].items()}
            if 'indices' in primitive:
                primitive['indices'] = accessor_mapping[primitive['indices']]
    buffers = []
    for buffer in document['buffers']:
        path = (original / buffer['uri']).resolve()
        if not path.is_relative_to(original.resolve()):
            raise ValueError('Buffer outside asset directory')
        data = path.read_bytes()
        if len(data) < buffer['byteLength']:
            raise ValueError('Incomplete buffer')
        buffers.append(data)
    binary = bytearray()
    views, view_mapping = [], {}
    def append(data, extra=None):
        binary.extend(b'\0' * (-len(binary) % 4))
        view = dict(extra or {}, buffer=0, byteOffset=len(binary), byteLength=len(data))
        views.append(view)
        binary.extend(data)
        return len(views) - 1
    for accessor in accessors:
        if 'sparse' in accessor:
            raise ValueError('Sparse accessor needs explicit handling')
        old = accessor['bufferView']
        if old not in view_mapping:
            view = document['bufferViews'][old]
            start = view.get('byteOffset', 0)
            data = buffers[view['buffer']][start:start + view['byteLength']]
            extra = {k: v for k, v in view.items() if k not in ('buffer', 'byteOffset', 'byteLength')}
            view_mapping[old] = append(data, extra)
        accessor['bufferView'] = view_mapping[old]
    for image in document.get('images', []):
        path = (original / image.pop('uri')).resolve()
        if not path.is_relative_to(original.resolve()):
            raise ValueError('Texture outside asset directory')
        image['bufferView'] = append(path.read_bytes())
        image['mimeType'] = 'image/png' if path.suffix.lower() == '.png' else 'image/jpeg'
    document['accessors'] = accessors
    document['bufferViews'] = views
    document['buffers'] = [{'byteLength': len(binary)}]
    document.setdefault('asset', {})['extras'] = {'modifications': 'Removed invisible helper primitives; packed as GLB. Original license retained beside asset.'}
    text = json.dumps(document, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    text += b' ' * (-len(text) % 4)
    binary.extend(b'\0' * (-len(binary) % 4))
    output = struct.pack('<III', 0x46546C67, 2, 28 + len(text) + len(binary))
    output += struct.pack('<II', len(text), 0x4E4F534A) + text
    output += struct.pack('<II', len(binary), 0x004E4942) + binary
    (folder / 'vehicle.glb').write_bytes(output)
    audit = {'vehicle': key, 'triangles': triangle_count, 'meshes': len(kept_meshes), 'removedHelperPrimitives': removed_primitives,
             'bytes': len(output), 'decimated': False, 'materials': [m.get('name') for m in materials]}
    (folder / 'audit.json').write_text(json.dumps(audit, indent=2), encoding='utf-8')
    source = json.loads((folder / 'source.json').read_text(encoding='utf-8-sig'))
    source['assetStatus'] = 'downloaded-and-packed'
    source['modifications'] = document['asset']['extras']['modifications']
    (folder / 'source.json').write_text(json.dumps(source, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in audit.items() if k != 'materials'}))


if __name__ == '__main__':
    for key in sys.argv[1:] or ['zd8', 'zc6']:
        prepare(key)
