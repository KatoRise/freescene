#!/usr/bin/env python3
"""Check TV pause/reopen against a real seed, without rediscovering or injecting peers."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fixture', ROOT / 'tests/stream_fixture.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def until(check, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.1)
    raise AssertionError('Timed out waiting for torrent state')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
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
        pointer = lib.fs_engine_command(handle, json.dumps(dict(method=method, **values)).encode())
        try:
            result = json.loads(ctypes.string_at(pointer))
        finally:
            lib.fs_engine_free(pointer)
        assert 'error' not in result, result
        return result['result']

    with tempfile.TemporaryDirectory(prefix='freescene-reconnect-') as directory:
        root = Path(directory)
        def create(name, streaming):
            handle = lib.fs_engine_create(json.dumps(dict(
                profile=str(root / name / 'profile'), downloads=str(root / name / 'data'),
                isolated=True, streamOnly=streaming, lite=streaming)).encode())
            assert handle
            handles.append(handle)
            return handle

        # Distinct pieces let the second read verify an uncached section of the torrent.
        piece_length = 256 * 1024
        data = b''.join(bytes([i]) * piece_length for i in range(128))
        info = dict(name='fixture.mp4', length=len(data), private=1,
                    **{'piece length': piece_length,
                       'pieces': b''.join(hashlib.sha1(data[i:i + piece_length]).digest()
                                          for i in range(0, len(data), piece_length))})
        infohash = hashlib.sha1(fixture.encode(info)).hexdigest()
        torrent = root / 'fixture.torrent'
        torrent.write_bytes(fixture.encode({'info': info}))
        seed_file = root / 'seed/data' / infohash / 'fixture.mp4'
        seed_file.parent.mkdir(parents=True)
        seed_file.write_bytes(data)
        try:
            seed = create('seed', False)
            client = create('tv', True)
            call(seed, 'add', source=str(torrent))
            until(lambda: call(seed, 'status')['tasks'][0]['phase'] == 'seeding')
            call(client, 'add', source=str(torrent))
            call(client, 'prepareStream', id=infohash)
            url = call(client, 'stream', id=infohash, file=0)['url']

            def read(offset, length=4096):
                request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-{offset + length - 1}'})
                with urllib.request.urlopen(request, timeout=30) as response:
                    assert response.read() == data[offset:offset + length]

            # Avoid the session-time-zero shortcut masking the peer reconnect cooldown.
            time.sleep(2)
            with ThreadPoolExecutor(max_workers=1) as worker:
                pending = worker.submit(read, 0)
                call(client, 'connectPeer', id=infohash, port=call(seed, 'status')['listenPort'])
                pending.result(timeout=35)
            reconnects = []
            # The last response crosses from cached data to an uncached gap
            # without opening another HTTP connection at that boundary.
            for offset, length in ((24 * 1024 * 1024, 4096), (12 * 1024 * 1024, 4096),
                                   (0, 12 * 1024 * 1024)):
                call(client, 'pause', id=infohash)
                until(lambda: call(client, 'status')['tasks'][0]['phase'] == 'paused')
                time.sleep(1)
                done = call(client, 'status')['tasks'][0]['files'][0]['done']
                time.sleep(1)
                assert call(client, 'status')['tasks'][0]['files'][0]['done'] == done
                assert done < len(data)
                assert call(client, 'add', source=str(torrent))['duplicate']
                call(client, 'prepareStream', id=infohash)
                assert call(client, 'stream', id=infohash, file=0)['url'] == url
                # No trackers, DHT or new connectPeer call: only retained peers can work.
                started = time.monotonic()
                read(offset, length)
                reconnects.append(round(time.monotonic() - started, 3))
            print(json.dumps(dict(passed=True, idleBytesDownloaded=0,
                                  resumedUncachedRange=True,
                                  reconnectSeconds=reconnects)))
        finally:
            for handle in reversed(handles):
                lib.fs_engine_destroy(handle)


if __name__ == '__main__':
    main()
