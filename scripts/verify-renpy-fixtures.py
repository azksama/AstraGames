"""Validate only our generated test fixtures with CPython (never pass user saves here)."""
from pathlib import Path
import pickle
import zipfile


class Rollback:
    pass


class KeywordState:
    def __new__(cls, name="state", *, flag=False):
        return super().__new__(cls)


def main():
    root = Path(__file__).resolve().parents[1] / "app/build/verification"
    for protocol in (2, 4, 5):
        with zipfile.ZipFile(root / f"renpy-patched-{protocol}.save") as archive:
            state, rollback = pickle.loads(archive.read("log"))
            assert state["store.money"] == 9999999
            assert state["store.name"] == "Joueur 日本語 😀 plus long"
            assert state["store.flag"] is True
            assert rollback.state is state
            assert rollback.self is rollback
            assert archive.read("signatures") == b""
        print(f"Protocol {protocol}: CPython readback, Unicode and references OK")
    for protocol in (0, 2, 4, 5):
        state = pickle.loads((root / f"renpy-state-patched-{protocol}.pickle").read_bytes())
        assert state["money"] == 999
        assert state["large"] == 2**256 + 17
        assert state["unicode"] == "日本語 😀"
        assert state["buffer"] == bytearray(b"\x00\xff\x07")
        if protocol >= 4:
            assert isinstance(state["custom"], KeywordState)
        print(f"Protocol {protocol}: large integers, bytearrays and custom state preserved")
    legacy = (root / "renpy-python2-strings-patched.pickle").read_bytes()
    state = pickle.loads(legacy, encoding="utf-8", errors="surrogateescape")
    assert state == {"money": 999, "name": "Joueur 日本語 😀", "binary": "\udcff"}
    raw = pickle.loads(legacy, encoding="bytes")
    assert raw[b"name"] == "Joueur 日本語 😀".encode("utf-8")
    assert raw[b"binary"] == b"\xff"
    print("Python 2 strings: editable UTF-8 text, original str type and opaque bytes preserved")


if __name__ == "__main__":
    main()
