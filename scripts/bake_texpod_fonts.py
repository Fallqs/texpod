"""Bake the pinned Texpod font into Android assets and an SD-card TPF1 bundle."""

import argparse
import hashlib
from pathlib import Path
import shutil
import struct
import zipfile
import zlib

import freetype

FONT_FILE = 'NotoSansCJKsc-Regular.otf'
FONT_SHA256 = '2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b'
LICENSE_SHA256 = '6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2'
SIZES = range(18, 37)
RENDER_FLAGS = freetype.FT_LOAD_RENDER | freetype.FT_LOAD_TARGET_MONO | freetype.FT_LOAD_NO_BITMAP
SD_DIR = '.crosspoint/texpod-fonts'


def check_source(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f'Unexpected source revision: {path}')


def record_for(face, glyph_id):
    face.load_glyph(glyph_id, RENDER_FLAGS)
    glyph = face.glyph
    bitmap = glyph.bitmap
    if bitmap.width == 0 or bitmap.rows == 0:
        record = bytes(8)
    else:
        stride = (bitmap.width + 7) // 8
        if bitmap.pixel_mode != freetype.FT_PIXEL_MODE_MONO or bitmap.pitch < stride or stride * bitmap.rows > 1024:
            raise ValueError(f'Unsupported bitmap for glyph {glyph_id}')
        pixels = bytes(bitmap.buffer)
        ink = b''.join(pixels[i:i + stride] for i in range(0, len(pixels), bitmap.pitch))
        record = struct.pack('<HHhh', bitmap.width, bitmap.rows, glyph.bitmap_left, -glyph.bitmap_top) + ink
    return record + struct.pack('<I', zlib.crc32(record))


def pack_at_size(face, pixel_size):
    face.set_pixel_sizes(0, pixel_size)
    records = [record_for(face, glyph_id) for glyph_id in range(face.num_glyphs)]
    stride = max(map(len, records))
    directory = struct.pack('<IHH', face.num_glyphs, stride, 0)
    body = b''.join(record.ljust(stride, b'\0') for record in records)
    length = 32 + len(directory) + len(body)
    if length > 64 * 1024 * 1024:
        raise ValueError(f'Pack too large at {pixel_size}px')
    # The ID identifies both the glyph numbering and the exact raster data.
    digest = hashlib.sha256(bytes.fromhex(FONT_SHA256))
    digest.update(struct.pack('<H', pixel_size))
    digest.update(directory)
    digest.update(body)
    generation = digest.digest()[:16]
    header = struct.pack('<4s16sHHI', b'TPF1', generation, pixel_size, 1, length)
    return generation, header + struct.pack('<I', zlib.crc32(header + directory)) + directory + body


def bake(source, output):
    font = source / FONT_FILE
    license_file = source / 'OFL.txt'
    check_source(font, FONT_SHA256)
    check_source(license_file, LICENSE_SHA256)
    if freetype.version() != (2, 13, 2):
        raise ValueError('This profile is built with FreeType 2.13.2')
    face = freetype.Face(str(font))
    if not 1 <= face.num_glyphs <= 65_536:
        raise ValueError('Font glyph IDs are outside the TPF1 range')

    output.mkdir(parents=True, exist_ok=True)
    assets = output / 'android-assets' / 'texpod'
    assets.mkdir(parents=True, exist_ok=True)
    generations = bytearray()
    with zipfile.ZipFile(output / 'texpod-sd-fonts.zip', 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr(f'{SD_DIR}/OFL.txt', license_file.read_bytes())
        for size in SIZES:
            generation, pack = pack_at_size(face, size)
            generations.extend(generation)
            bundle.writestr(f'{SD_DIR}/{generation.hex()}.tpf', pack)
            print(f'{size}px: {len(pack):,} bytes, {generation.hex()}', flush=True)
    shutil.copyfile(font, assets / 'reader.otf')
    shutil.copyfile(license_file, assets / 'OFL.txt')
    (assets / 'generations.bin').write_bytes(generations)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help=f'directory containing the pinned {FONT_FILE} and OFL.txt')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    bake(args.source, args.output)
