"""Validate only our generated test fixtures with CPython (never pass user saves here)."""
from pathlib import Path
import pickle
import zipfile


class Rollback:
    pass


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


if __name__ == "__main__":
    main()
