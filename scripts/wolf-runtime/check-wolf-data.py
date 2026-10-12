"""Desktop Win32 proof for Astra's original helper against copies of official samples.

Not an Android/Wine/device performance test. Never changes the original samples.
Both exact executable hashes must have been independently verified with REA.
"""
import json
import ctypes
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time

REPO = Path(__file__).resolve().parents[2]
OUTPUT = REPO / 'build/wolf-research/wolf-data-proof'
HELPER = REPO / 'app/src/main/assets/windows/astra-memory.exe'
MAGIC, DATA_MAGIC = 0x41535443, 0x41534445


class Connection:
    def __init__(self, game_dir, exe='Game.exe'):
        self.directory = game_dir / 'ipc'
        self.directory.mkdir(exist_ok=True)
        shutil.copyfile(HELPER, self.directory / 'astra-memory.exe')
        (self.directory / 'target.txt').write_text(str((game_dir / exe).resolve()), encoding='utf-16-le')
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        startup.wShowWindow = 0
        self.helper = subprocess.Popen([str(self.directory / 'astra-memory.exe')], cwd=self.directory, startupinfo=startup)
        self.sequence = 0

    def command(self, op, variable=0, token=0, category=0, bank=-1, query='', before='', after='', offset=0):
        self.sequence += 1
        parts = [text.encode('utf-8') for text in (query, before, after)]
        base = struct.pack('<6I3Q2I', MAGIC, self.sequence, op, 0, 0, 128, variable, 0, 0, offset, 0)
        extra = struct.pack('<2IQ6I', DATA_MAGIC, 1, token, category, bank & 0xffffffff, *(len(p) for p in parts), 0)
        return self.exchange(base + extra + b''.join(parts))

    def generic(self, op, address=0, value=0, before=0):
        self.sequence += 1
        return self.exchange(struct.pack('<6I3Q2I', MAGIC, self.sequence, op, 2, 0, 128, address, value, before, 0, 0))

    def exchange(self, request):
        temporary = self.directory / 'request.tmp'
        temporary.write_bytes(request)
        temporary.replace(self.directory / 'request.bin')
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            response = self.directory / 'response.bin'
            if response.is_file():
                raw = response.read_bytes()
                if len(raw) >= 8 and struct.unpack_from('<I', raw, 4)[0] == self.sequence:
                    return self.parse(raw)
            assert self.helper.poll() is None, 'Helper stopped'
            time.sleep(.04)
        raise TimeoutError('Helper response')

    @staticmethod
    def parse(raw):
        if struct.unpack_from('<I', raw)[0] == MAGIC:
            h = struct.unpack_from('<4I2Q4I', raw)
            assert len(raw) == 48 + h[8] * 16
            return dict(status=h[2], count=h[4], rows=[dict(address=a, value=v) for a,v in struct.iter_unpack('<2Q', raw[48:])])
        h = struct.unpack_from('<4IQ4IQ14I', raw)
        assert h[0] == DATA_MAGIC and h[3] == 1 and len(raw) == 104 + h[8]
        result = dict(status=h[2], token=h[4], layout=h[5], codepage=h[6], count=h[9],
                      bank_count=h[10], text_count=h[11], common_count=h[12], banks=list(h[14:24]), rows=[])
        at = 104
        for _ in range(h[7]):
            id_, cat, bank, index, type_, cap, token, n, flags = struct.unpack_from('<6IQ2I', raw, at)
            at += 40
            value = raw[at:at+n].decode('utf-8', errors='strict')
            at += n
            result['rows'].append(dict(id=id_, category=cat, bank=bank, index=index, type=type_, capacity=cap, token=token, flags=flags, value=value))
        assert at == len(raw)
        return result

    def read(self, id_):
        r = self.command(13, variable=id_)
        assert r['status'] == 0, r
        return r['rows'][0]

    def write(self, row, value):
        r = self.command(14, variable=row['id'], token=row['token'], before=row['value'], after=value)
        assert r['status'] == 0 and r['rows'][0]['value'] == value, r
        return r['rows'][0]

    def close(self):
        self.helper.terminate()
        self.helper.wait(timeout=10)


def enter_game(game):
    """Input is sent only while our disposable game's HWND actually owns focus."""
    user = ctypes.WinDLL('user32', use_last_error=True)
    user.GetForegroundWindow.restype = ctypes.c_void_p
    user.SetForegroundWindow.argtypes = [ctypes.c_void_p]
    user.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
    previous = user.GetForegroundWindow()
    windows = []
    callback_type = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_ssize_t)
    def collect(hwnd, _):
        pid = ctypes.c_ulong()
        user.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(pid))
        if pid.value == game.pid:
            windows.append(hwnd)
        return 1
    callback = callback_type(collect)
    user.EnumWindows(callback, 0)
    try:
        for hwnd in windows:
            user.ShowWindow(hwnd, 5)
            user.SetForegroundWindow(hwnd)
            time.sleep(.4)
            if user.GetForegroundWindow() != hwnd:
                continue
            for key in [0x0D, 0x5A, 0x0D]:
                if user.GetForegroundWindow() != hwnd:
                    break
                user.keybd_event(key, 0, 0, 0)
                time.sleep(.09)
                user.keybd_event(key, 0, 2, 0)
                time.sleep(2)
    finally:
        if previous:
            user.SetForegroundWindow(previous)
        for hwnd in windows:
            user.ShowWindow(hwnd, 0)


def main():
    assert os.name == 'nt', 'Desktop Windows only'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    proofs = []
    for sample, expected_layout in [('sample22961/WOLF_RPG_Editor2', 1), ('sample3729/WOLF_RPG_Editor3', 2)]:
        original = REPO / 'build/wolf-research' / sample
        shared = REPO / 'build/mtool-implementation/save-fixtures' / ('game22961' if expected_layout == 1 else 'game3729')
        src = shared if shared.is_dir() else original
        directory = OUTPUT / f'official-{expected_layout}-{time.time_ns()}'
        assert not directory.exists(), f'Use a fresh output directory: {directory}'
        directory.mkdir()
        for name in ['Game.exe', 'Game.ini', 'GdiPlus.dll', 'GuruGuruSMF4.dll']:
            if (src / name).is_file():
                shutil.copyfile(src / name, directory / name)
        shutil.copytree(src / 'Data', directory / 'Data')
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        startup.wShowWindow = 0
        game = subprocess.Popen([str(directory / 'Game.exe')], cwd=directory, startupinfo=startup)
        connection = None
        try:
            connection = Connection(directory)
            deadline = time.monotonic() + 50
            while True:
                probe = connection.command(11)
                if probe['status'] == 0:
                    break
                if time.monotonic() > deadline:
                    raise AssertionError(probe)
                assert game.poll() is None, 'Official game stopped'
                time.sleep(1)
            assert probe['layout'] == expected_layout, probe
            if probe['text_count'] == 0:
                enter_game(game)
                probe = connection.command(11)
            category_proofs = []
            text_candidates = []
            for category in range(1, 5):
                page = connection.command(12, category=category)
                assert page['status'] == 0, page
                category_proofs.append(dict(category=category, count=page['count'], page_size=len(page['rows'])))
                if category in (2, 4):
                    text_candidates += page['rows']
                print(json.dumps(dict(sample=sample, category=category, count=page['count'])), flush=True)
            bank = next(i for i, n in enumerate(probe['banks']) if n)
            numeric = connection.read(2000000 + bank * 100000 + probe['banks'][bank] - 1)
            changed = connection.write(numeric, str(int(numeric['value']) + 1))
            assert connection.read(numeric['id'])['value'] == changed['value']
            stale = connection.command(14, variable=numeric['id'], token=numeric['token'], before=numeric['value'], after='67')
            assert stale['status'] == 10, stale
            assert connection.read(numeric['id'])['value'] == changed['value']
            restored = connection.write(changed, numeric['value'])
            assert connection.read(numeric['id'])['value'] == numeric['value']
            invalid_token = connection.command(14, variable=numeric['id'], token=numeric['token'] ^ 1, before=numeric['value'], after='67')
            assert invalid_token['status'] == 25, invalid_token
            assert text_candidates, 'No existing strings: enter a game event before testing'
            text = connection.read(next(row['id'] for row in text_candidates if not row['flags']))
            assert text['capacity'] >= 15 and not text['flags']
            changed_text = connection.write(text, 'Astra')
            assert connection.read(text['id'])['value'] == 'Astra'
            connection.write(changed_text, text['value'])
            japanese = connection.write(connection.read(text['id']), '星')
            assert connection.read(text['id'])['value'] == '星'
            connection.write(japanese, text['value'])
            too_long = connection.command(14, variable=text['id'], token=text['token'], before=text['value'], after='x'*(min(text['capacity'], 4095)+1))
            assert too_long['status'] == 27, too_long
            matches = connection.command(12, query=str(numeric['id']), category=1)
            assert matches['status'] == 0 and any(r['id'] == numeric['id'] for r in matches['rows'])
            proof = dict(sample=sample, platform='Desktop Windows Win32, official sample copy', probe={k:v for k,v in probe.items() if k!='rows'},
                         directory=str(directory),
                         categories=category_proofs, numeric_id=numeric['id'], text_id=text['id'],
                         checks=['numeric read/write/reread/undo', 'stale expected rejected', 'stale layout token rejected',
                                 'text read/write/reread/undo', 'Japanese text encoding round trip', 'capacity growth refused', 'ID search'])
            proofs.append(proof)
            (OUTPUT / 'proof.json').write_text(json.dumps(proofs, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(proof, ensure_ascii=False), flush=True)
        finally:
            if connection is not None:
                connection.close()
            if game.poll() is None:
                game.terminate()
                game.wait(timeout=10)

    fixture = REPO / 'build/wolf-research/memory-fixture/Game32.exe'
    assert fixture.is_file(), 'Build --fixture32 first'
    directory = OUTPUT / f'unknown-original-fixture-{time.time_ns()}'
    directory.mkdir()
    shutil.copyfile(fixture, directory / 'Game.exe')
    startup = subprocess.STARTUPINFO(); startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW; startup.wShowWindow = 0
    game = subprocess.Popen([str(directory / 'Game.exe')], cwd=directory, startupinfo=startup)
    connection = None
    try:
        deadline = time.monotonic() + 10
        while not (directory / 'fixture.bin').is_file():
            assert time.monotonic() < deadline
            time.sleep(.05)
        address = struct.unpack_from('<Q', (directory / 'fixture.bin').read_bytes())[0]
        connection = Connection(directory)
        assert connection.command(11)['status'] == 20, 'Unknown executable must not receive a guessed Wolf layout'
        assert connection.generic(5, address=address)['rows'][0]['value'] == 123456789
        written = connection.generic(4, address=address, value=55555555, before=123456789)
        assert written['status'] == 0 and written['rows'][0]['value'] == 55555555
        assert connection.generic(4, address=address, value=9, before=123456789)['status'] == 10
        assert connection.generic(4, address=address, value=123456789, before=55555555)['status'] == 0
        assert connection.generic(1, value=123456789)['count'] > 0
        (directory / 'finish').write_text('1')
        game.wait(timeout=10)
        time.sleep(.15)
        assert connection.helper.poll() is not None, 'Session helper must stop when its exact game stops'
        proofs.append(dict(platform='Desktop Windows Win32, original non-Wolf fixture', checks=['unknown identity refusal', 'generic read/write/reread', 'stale generic write refusal', 'generic undo', 'scanner remains available', 'helper closes with game']))
        (OUTPUT / 'proof.json').write_text(json.dumps(proofs, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(proofs[-1]), flush=True)
    finally:
        if connection is not None and connection.helper.poll() is None:
            connection.close()
        if game.poll() is None:
            game.terminate(); game.wait(timeout=10)


if __name__ == '__main__':
    main()
