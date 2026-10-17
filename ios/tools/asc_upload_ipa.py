#!/usr/bin/env python3
"""Upload an already-signed IPA using Apple's platform-independent Build Upload API.

Required: APP_ID and the AC_API_* authentication variables. Optional APP_IDENTIFIER.
Does not compile or sign; Apple must finish validating the uploaded binary.
"""
import os
import plistlib
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

from asc_api import api


def main():
    path = Path(sys.argv[1])
    with zipfile.ZipFile(path) as archive:
        infos = [n for n in archive.namelist()
                 if n.startswith('Payload/') and n.endswith('.app/Info.plist') and n.count('/') == 2]
        if len(infos) != 1:
            raise SystemExit('Expected exactly one app in IPA')
        info = plistlib.loads(archive.read(infos[0]))
    app_id = os.environ['APP_ID']
    app = api('GET', f'/v1/apps/{app_id}')['data']
    if info['CFBundleIdentifier'] != app['attributes']['bundleId']:
        raise SystemExit('IPA bundle ID does not match APP_ID')
    if os.environ.get('APP_IDENTIFIER') and info['CFBundleIdentifier'] != os.environ['APP_IDENTIFIER']:
        raise SystemExit('IPA bundle ID does not match APP_IDENTIFIER')
    upload = api('POST', '/v1/buildUploads', {'data': {
        'type': 'buildUploads', 'attributes': {
            'cfBundleShortVersionString': info['CFBundleShortVersionString'],
            'cfBundleVersion': info['CFBundleVersion'], 'platform': 'IOS'},
        'relationships': {'app': {'data': {'type': 'apps', 'id': app_id}}}}})['data']
    uid = upload['id']
    print(f"Upload {uid}: {info['CFBundleShortVersionString']} ({info['CFBundleVersion']})", flush=True)
    file = api('POST', '/v1/buildUploadFiles', {'data': {
        'type': 'buildUploadFiles', 'attributes': {
            'fileName': path.name, 'fileSize': path.stat().st_size,
            'assetType': 'ASSET', 'uti': 'com.apple.ipa'},
        'relationships': {'buildUpload': {'data': {'type': 'buildUploads', 'id': uid}}}}})['data']
    with path.open('rb') as source:
        for operation in file['attributes']['uploadOperations']:
            source.seek(operation['offset'])
            data = source.read(operation['length'])
            if len(data) != operation['length']:
                raise SystemExit('Upload chunk length exceeds IPA size')
            headers = {h['name']: h['value'] for h in operation['requestHeaders']}
            request = urllib.request.Request(operation['url'], method=operation['method'],
                                             headers=headers, data=data)
            with urllib.request.urlopen(request, timeout=120) as response:
                response.read()
    api('PATCH', f"/v1/buildUploadFiles/{file['id']}", {'data': {
        'type': 'buildUploadFiles', 'id': file['id'], 'attributes': {
            'uploaded': True}}})
    deadline = time.time() + int(os.environ.get('POLL_SECONDS', '900'))
    while True:
        upload = api('GET', f'/v1/buildUploads/{uid}?include=build')['data']
        details = upload['attributes']['state']
        print('Apple build upload state:', details['state'], flush=True)
        if details.get('errors'):
            raise SystemExit(str(details['errors']))
        if details['state'] == 'COMPLETE':
            print('Apple accepted and processed build:', upload.get('relationships', {}).get('build', {}).get('data'), flush=True)
            return
        if details['state'] == 'FAILED':
            raise SystemExit('Apple failed to process the IPA')
        if time.time() >= deadline:
            raise SystemExit(f'Timed out processing build upload {uid}; inspect before retrying upload')
        time.sleep(30)


if __name__ == '__main__':
    main()
