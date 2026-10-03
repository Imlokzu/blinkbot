"""A scannable dotted QR body with the bot's pixel-crab silhouette."""

from __future__ import annotations

import qrcode
from qrcode.constants import ERROR_CORRECT_H


def svg(payload: str) -> str:
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_H, border=4, box_size=1)
    qr.add_data(payload)
    qr.make(fit=True)
    matrix = qr.get_matrix()
    size = len(matrix)
    pad = 12
    width = size + pad * 2
    finders = ((4, 4), (size - 11, 4), (4, size - 11))
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {width}" role="img">',
             f'<rect width="{width}" height="{width}" rx="5" fill="#fff"/>',
             '<g fill="#1b1b20">']
    for y, row in enumerate(matrix):
        for x, dark in enumerate(row):
            if dark:
                if any(left <= x < left + 7 and top <= y < top + 7 for left, top in finders):
                    continue
                parts.append(f'<circle cx="{pad + x + .5}" cy="{pad + y + .5}" r=".48"/>')
    for x, y in finders:
        parts.append(f'<rect x="{pad + x}" y="{pad + y}" width="7" height="7" rx=".5"/>')
        parts.append(f'<rect x="{pad + x + 1}" y="{pad + y + 1}" width="5" height="5" fill="#fff" rx=".3"/>')
        parts.append(f'<rect x="{pad + x + 2}" y="{pad + y + 2}" width="3" height="3" rx=".3"/>')
    # Decorative dotted claws/legs stay outside the mandatory quiet zone.
    for side in (-1, 1):
        edge = pad - 5 if side < 0 else pad + size + 4
        for dy in range(9):
            for dx in range(3):
                x = edge + side * dx
                for y in (pad + size * .35 + dy, pad + size * .69 + dy):
                    parts.append(f'<circle cx="{x}" cy="{y:.2f}" r=".47"/>')
        for n in range(6):
            for x in (pad + size * .28, pad + size * .7):
                y = pad - 5 - n if side < 0 else pad + size + 4 + n
                parts.append(f'<circle cx="{x:.2f}" cy="{y}" r=".47"/>')
    parts.append('</g></svg>')
    return ''.join(parts)
