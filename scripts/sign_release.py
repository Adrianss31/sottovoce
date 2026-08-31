#!/usr/bin/env python3
"""Sign an APK built by Actions locally. Private keys never leave this computer."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(args):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--update-key', type=Path, required=True)
    parser.add_argument('--alias', default='sottovoce')
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', type=int, required=True)
    parser.add_argument('--notes-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    import re
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', args.version) or args.code < 1:
        parser.error('Use a numeric x.y.z version and a positive version code.')
    aapt = args.apksigner.with_name('aapt')
    metadata = run([aapt, 'dump', 'badging', args.apk]).decode('utf-8')
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    sdk = re.search(r"sdkVersion:'([0-9]+)'", metadata)
    if not package or package.groups() != ('it.sottovoce.app', str(args.code), args.version):
        parser.error('APK identity or version does not match this release. Do not use a debug APK.')
    if not sdk or sdk.group(1) != '26':
        parser.error('Unexpected minimum SDK in the APK. Review compatibility before publishing.')
    root = Path(__file__).resolve().parent.parent
    public = run(['openssl', 'pkey', '-in', args.update_key, '-pubout', '-outform', 'DER'])
    expected = base64.b64decode((root/'config/update-public-key.txt').read_text())
    if public != expected:
        parser.error('The update key does not match the public key embedded in the app.')
    notes = args.notes_file.read_text(encoding='utf-8')
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / f'sottovoce-{args.version}.apk'
    if apk.exists() or (args.output/'update.json').exists():
        parser.error('Use an empty output directory; existing releases are never overwritten.')
    run([args.apksigner, 'sign', '--v4-signing-enabled', 'false', '--ks', args.keystore, '--ks-key-alias', args.alias,
         '--ks-pass', f'file:{args.password_file.resolve()}',
         '--out', apk, args.apk])
    verification = run([args.apksigner, 'verify', '--verbose', '--print-certs', apk])
    (args.output/'apk-verification.txt').write_bytes(verification)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    payload = json.dumps(dict(versionName=args.version, versionCode=args.code, minSdk=26,
        apkUrl=f'https://github.com/Adrianss31/sottovoce/releases/download/v{args.version}/{apk.name}',
        size=apk.stat().st_size, sha256=digest, notes=notes), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    with tempfile.TemporaryDirectory() as scratch:
        plain = Path(scratch)/'payload.json'; plain.write_bytes(payload)
        signature = run(['openssl', 'dgst', '-sha256', '-sign', args.update_key, plain])
        sig = Path(scratch)/'signature'; sig.write_bytes(signature)
        pub = Path(scratch)/'public.pem'; pub.write_bytes(run(['openssl','pkey','-in',args.update_key,'-pubout']))
        run(['openssl','dgst','-sha256','-verify',pub,'-signature',sig,plain])
    envelope = dict(payload=base64.b64encode(payload).decode(), signature=base64.b64encode(signature).decode())
    manifest = args.output/'update.json'
    manifest.write_text(json.dumps(envelope, indent=2)+'\n', encoding='utf-8')
    (args.output/'SHA256SUMS').write_text(f'{digest}  {apk.name}\n{hashlib.sha256(manifest.read_bytes()).hexdigest()}  update.json\n')
    print(f'Signed and verified: {apk.name} ({apk.stat().st_size} bytes)')
    print('Publish only the APK, update.json and SHA256SUMS. Keep the keys and password file private.')


if __name__ == '__main__':
    main()
