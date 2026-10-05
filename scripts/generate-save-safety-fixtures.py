"""Generate small, trusted pickle interoperability fixtures. Never loads user data."""
from pathlib import Path
import pickle
import struct


class KeywordState:
    def __new__(cls, name="state", *, flag=False):
        return super().__new__(cls)

    def __getnewargs_ex__(self):
        return ("state",), {"flag": True}


root = Path(__file__).resolve().parents[1] / "app/src/test/resources/saves"
for protocol in (0, 2, 4, 5):
    state = {"money": 3, "large": 2**256 + 17, "unicode": "日本語 😀", "buffer": bytearray(b"\x00\xff\x07")}
    if protocol >= 4:
        state["custom"] = KeywordState()
    (root / f"renpy-state-{protocol}.pickle").write_bytes(pickle.dumps(state, protocol=protocol))

keys = {1: 10, "1": 20, "int:1": 30, "int%3A1": 40, b"1": 50, ("tuple", 1): 60, "escaped": "é"}
(root / "renpy-keys.pickle").write_bytes(pickle.dumps(keys, protocol=4))
shared = "shared hero name"
alias = {"name": shared, "other": shared, shared: 17, "money": 3}
(root / "renpy-alias.pickle").write_bytes(pickle.dumps(alias, protocol=4))

# Protocol 2 Python 2 str opcodes, independently checked using Ren'Py's Python 3 loading options.
# Modern Python deliberately emits bytes through different opcodes, so assemble these small old forms.
legacy = (b"\x80\x02}(U\x05moneyK\x03"
          b"T" + struct.pack("<I", 4) + b"name"
          b"S'Joueur \\xc3\\xa9'\n"
          b"U\x06binaryU\x01\xffu.")
assert pickle.loads(legacy, encoding="utf-8", errors="surrogateescape") == {
    "money": 3, "name": "Joueur é", "binary": "\udcff"
}
(root / "renpy-python2-strings.pickle").write_bytes(legacy)
