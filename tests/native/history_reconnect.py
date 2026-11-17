#!/usr/bin/env python3
"""History-owned peer hints restore a new engine without discovery or peer injection."""
import argparse
import ctypes
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fixture', ROOT / 'tests/stream_fixture.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--legacy-cache', action='store_true')
    parser.add_argument('--desktop', action='store_true')
    args = parser.parse_args()
    lib = ctypes.CDLL(str(args.engine.resolve()))
    lib.fs_engine_create.argtypes = [ctypes.c_char_p]
    lib.fs_engine_create.restype = ctypes.c_void_p
    lib.fs_engine_command.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lib.fs_engine_command.restype = ctypes.c_void_p
    lib.fs_engine_free.argtypes = [ctypes.c_void_p]
    lib.fs_engine_destroy.argtypes = [ctypes.c_void_p]
    handles = []
    def call(handle, method, **values):
        raw = lib.fs_engine_command(handle, json.dumps(dict(method=method, **values)).encode())
        try: result = json.loads(ctypes.string_at(raw))
        finally: lib.fs_engine_free(raw)
        assert 'error' not in result, result
        return result['result']
    with tempfile.TemporaryDirectory(prefix='freescene-history-peer-') as temp:
        root = Path(temp)
        cache = root / 'history-network'
        def create(name, owners=()):
            config = dict(profile=str(root/name/'profile'), downloads=str(root/name/'data'), isolated=True,
                          streamOnly=name != 'seed' and not args.desktop, lite=True)
            if name != 'seed': config.update(historyCache=str(cache), historyIds=list(owners))
            handle = lib.fs_engine_create(json.dumps(config).encode()); assert handle; handles.append(handle)
            return handle
        def destroy(handle):
            lib.fs_engine_destroy(handle); handles.remove(handle)
        piece = 256*1024
        data = b''.join(bytes([i])*piece for i in range(64))
        split = len(data)//2
        info = dict(name='fixture', files=[{'length':split,'path':['S01E01.mp4']},{'length':split,'path':['S01E02.mp4']}], private=int(not args.legacy_cache), **{'piece length':piece,
                    'pieces':b''.join(hashlib.sha1(data[i:i+piece]).digest() for i in range(0,len(data),piece))})
        task = hashlib.sha1(fixture.encode(info)).hexdigest()
        owner = hashlib.sha256(f'torrent:{task}:1'.encode()).hexdigest()
        torrent = root/'fixture.torrent';torrent.write_bytes(fixture.encode({'info':info}))
        seed_path = root/'seed/data'/task/'fixture';seed_path.mkdir(parents=True)
        (seed_path/'S01E01.mp4').write_bytes(data[:split]);(seed_path/'S01E02.mp4').write_bytes(data[split:])
        def read(url, offset=0):
            req = urllib.request.Request(url, headers={'Range':f'bytes={offset}-{offset+4095}'})
            with urllib.request.urlopen(req, timeout=35) as response: assert response.read() == data[split+offset:split+offset+4096]
        try:
            seed = create('seed'); call(seed,'add',source=str(torrent))
            deadline = time.monotonic()+30
            while call(seed,'status')['tasks'][0]['phase'] != 'seeding':
                assert time.monotonic()<deadline;time.sleep(.1)
            first = create('first')
            # Keep a desktop download active while history becomes eligible; a
            # tiny local fixture would otherwise finish before the first tick.
            if args.desktop: call(first,'limits',download=256*1024,upload=0,active=3)
            call(first,'add',source=str(torrent));call(first,'prepareStream',id=task)
            url = call(first,'stream',id=task,file=1)['url']
            with ThreadPoolExecutor(max_workers=1) as worker:
                pending = worker.submit(read,url)
                call(first,'connectPeer',id=task,port=call(seed,'status')['listenPort']);pending.result(timeout=40)
            assert not cache.exists()
            assert not call(first,'historyCacheRecord',id=task,file=1)['saved']
            assert not cache.exists()
            # The native player issues this grant only after a successful 3-second history write.
            call(first,'historyCacheSync',ids=[owner]);assert not cache.exists()
            assert call(first,'historyCacheRecord',id=task,file=1)['saved']
            assert (cache/owner/'peers.json').exists()
            assert json.loads((cache/owner/'peers.json').read_text())['peers'], 'No connected peer at history eligibility'
            destroy(first)
            assert (cache/owner/'source.torrent').exists()
            if args.legacy_cache: (cache/owner/'source.torrent').unlink()
            second = create('second',[owner])
            replay = call(second,'historyReplay',owner=owner);assert replay == {'id':task,'file':1}
            call(second,'prepareStream',id=task)
            deadline = time.monotonic()+30
            while not call(second,'status')['tasks'][0]['metadata']:
                assert time.monotonic()<deadline;time.sleep(.1)
            url = call(second,'stream',id=task,file=1)['url']
            started = time.monotonic();read(url,4*1024*1024)
            restored = round(time.monotonic()-started,3)
            call(second,'historyCacheRecord',id=task,file=1)
            call(second,'historyCacheSync',ids=[])
            assert not cache.exists()
            assert not call(second,'historyCacheRecord',id=task,file=1)['saved']
            try: call(second,'historyReplay',owner=owner)
            except AssertionError: pass
            else: raise AssertionError('Deleted history replayed')
            destroy(second);assert not cache.exists()
            print(json.dumps(dict(passed=True,createdBeforeEligible=False,restoredAcrossEngineRestart=True,
                                  discoveryDisabled=True,secondPeerInjection=False,restoreSeconds=restored,exactFileReplayed=1,metadataRestored=True,legacyCache=args.legacy_cache,desktop=args.desktop,
                                  deletedWithHistory=True,recreatedAfterDeletion=False)))
        finally:
            for handle in reversed(handles): lib.fs_engine_destroy(handle)
if __name__=='__main__': main()
