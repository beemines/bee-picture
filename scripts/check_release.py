"""Check publishable source for local artifacts, common secrets and broken README images.

Not a substitute for a secret scanner or security review. No values are printed.
"""
from pathlib import Path
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', 'node_modules', 'target', 'dist', '.idea', '.vscode', '.agents', '.codex', '__pycache__'}
PATTERNS = {
    'cloud credential': re.compile(r'\b(?:AKID[A-Za-z0-9]{24,}|sk-[A-Za-z0-9_-]{24,})\b'),
    'private COS account endpoint': re.compile(r'[a-z0-9-]+-\d{5,}\.cos\.[a-z0-9-]+\.myqcloud\.com'),
    'hardcoded credential': re.compile(r'''(?i)(?:secretId|secretKey|apiKey|accessToken|refreshToken)\s*[:=]\s*["']([A-Za-z0-9+/_=-]{20,})["']'''),
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'captured signed query': re.compile(r'''(?i)[?&](?:session_id|sign|tk|access_token|refresh_token)=[^&\s"'<>]{12,}'''),
}

def main():
    errors = []
    count = 0
    for directory, dirs, files in os.walk(ROOT, followlinks=False):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for name in files:
            path = Path(directory) / name
            relative = path.relative_to(ROOT)
            if name in {'.env', 'application-local.yml', 'application-local.yaml', 'application-prod.yml'}:
                errors.append(f'{relative}: local configuration must not be published')
            if path.suffix in {'.pem', '.key', '.p12', '.jks', '.rdb', '.dump'}:
                errors.append(f'{relative}: private or runtime artifact')
            try:
                text = path.read_text(encoding='utf-8-sig')
            except (UnicodeError, OSError):
                continue
            count += 1
            # The scanner source contains its patterns, not actual private values.
            if path == Path(__file__).resolve():
                continue
            for number, line in enumerate(text.splitlines(), 1):
                for label, pattern in PATTERNS.items():
                    if pattern.search(line):
                        errors.append(f'{relative}:{number}: {label}')
    readme = (ROOT / 'README.md').read_text(encoding='utf-8')
    images = re.findall(r'!\[[^\]]*\]\(([^)]+)\)', readme)
    for image in images:
        if not image.startswith(('https://', 'http://')) and not (ROOT / image).is_file():
            errors.append(f'README image missing: {image}')
    for path in (ROOT / 'docs').rglob('*.svg'):
        try:
            ET.parse(path)
        except ET.ParseError:
            errors.append(f'{path.relative_to(ROOT)}: invalid SVG')
    if 'MIT License' not in (ROOT / 'LICENSE').read_text(encoding='utf-8'):
        errors.append('MIT license text missing')
    if errors:
        print('\n'.join(errors))
        return 1
    print(f'Release checks passed: {count} text files, {len(images)} README images, MIT license')
    return 0

if __name__ == '__main__':
    sys.exit(main())
