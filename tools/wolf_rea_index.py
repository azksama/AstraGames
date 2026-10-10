#!/usr/bin/env python3
"""Produce a source-free research index from complete REA evidence bundles.

This does not run, decompile or interpret a game. Original bundles remain the
authority for observations; this index is a derived, reviewable projection.
Python standard library only. Output must be a new file outside input folders.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

MAX_BUNDLE_BYTES = 256 * 1024 * 1024


def summary(record: dict) -> dict:
    result = record.get("normalized_result")
    if isinstance(result, str):
        return {"pseudocodeCharacters": len(result), "originalSource": False}
    if isinstance(result, list):
        return {"matches": len(result)}
    if not isinstance(result, dict):
        return {}
    selected = {k: result[k] for k in (
        "document", "segment_count", "procedure_count", "string_count",
        "query_address", "found",
    ) if k in result}
    procedure = result.get("procedure")
    if isinstance(procedure, dict):
        selected["procedure"] = {k: procedure[k] for k in (
            "name", "address", "body", "classification",
        ) if k in procedure}
    for key in ("callers", "callees"):
        if isinstance(result.get(key), list):
            selected[key] = [{k: item.get(k) for k in ("name", "address")}
                             for item in result[key]]
    if isinstance(result.get("basic_blocks"), list):
        selected["basicBlockCount"] = len(result["basic_blocks"])
    native_api = result.get("native_api")
    if isinstance(native_api, dict) and native_api.get("available"):
        selected["jumpTables"] = native_api.get("jump_tables", [])
        selected["nativeApiLimitations"] = native_api.get("limitations", [])
    if isinstance(result.get("instructions"), list):
        selected["instructionCount"] = len(result["instructions"])
    if "limitations" in result:
        selected["resultLimitations"] = result["limitations"]
    return selected


def direct_dispatch_calls(record: dict) -> list[dict]:
    """Observe calls until the first branch in each recovered case block.

    No proximity guesses are made when the exact target instruction is absent.
    A case label is not necessarily an on-disk opcode: internal variants and
    sub-switches are included, with table identity and REA limitations retained.
    """
    result = record.get("normalized_result")
    if not isinstance(result, dict):
        return []
    api = result.get("native_api") or {}
    tables = api.get("jump_tables", [])
    instructions = result.get("assembly", [])
    positions = {line.split(":", 1)[0]: i for i, line in enumerate(instructions)}
    cases = []
    for table in tables:
        for mapping in table.get("mappings", []):
            target = mapping["target_address"]
            start = positions.get(target)
            calls = []
            if start is not None:
                for line in instructions[start:]:
                    op = line.split(":", 1)[1].strip()
                    if op.startswith("CALL "):
                        dest = op.split(" ", 1)[1]
                        if re.fullmatch(r"0x[0-9a-fA-F]+", dest):
                            calls.append(f"0x{int(dest, 16):x}")
                        else:
                            calls.append({"unresolvedOperand": dest})
                    if re.match(r"(?:J[A-Z]+|RET|IRET|INT)\b", op):
                        break
            cases.append({
                "dispatchAddress": table["dispatch_address"],
                "caseValue": mapping.get("case_value"),
                "targetAddress": target,
                "confidence": mapping.get("confidence"),
                "directCallsBeforeFirstBranch": calls,
                "exactTargetInstructionPresent": start is not None,
                "evidenceId": record["evidence_id"],
            })
    return cases


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundles", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    inputs = [p.absolute() for p in args.bundles]
    output = args.output.absolute()
    for path in [*inputs, output]:
        if any(p.is_symlink() or p.is_junction() for p in (path, *path.parents)):
            parser.error("research paths cannot traverse a symlink or junction")
    if any(output == p or p.parent in output.parents for p in inputs):
        parser.error("output must be outside every evidence input folder")
    bundles = []
    for path in inputs:
        if path.stat().st_size > MAX_BUNDLE_BYTES:
            parser.error(f"bundle exceeds {MAX_BUNDLE_BYTES} bytes: {path}")
        raw = path.read_bytes()
        bundle = json.loads(raw.decode("utf-8-sig"))
        if not isinstance(bundle, dict) or not isinstance(bundle.get("records"), list):
            parser.error(f"not a complete REA evidence bundle: {path}")
        records = bundle["records"]
        limitations = list(dict.fromkeys(s for r in records for s in r.get("limitations", [])))
        profiles = {r["analysis_profile"]["digest"]: r["analysis_profile"]
                    for r in records if r.get("analysis_profile")}
        bundles.append({
            "bundleFile": f"{path.parent.name}/{path.name}",
            "bundleSha256": hashlib.sha256(raw).hexdigest(),
            "bundleBytes": len(raw),
            "evidenceRecordCount": len(records),
            "artifacts": bundle.get("artifacts", []),
            "providers": bundle.get("providers", []),
            "analysisProfiles": profiles,
            "limitationDefinitions": limitations,
            "unknowns": bundle.get("unknowns", []),
            "records": [{
                "evidenceId": r["evidence_id"],
                "operation": r.get("operation"),
                "parameters": r.get("parameters"),
                "subjectSha256": (r.get("subject") or {}).get("digest", {}).get("sha256"),
                "providerId": (r.get("provider") or {}).get("id"),
                "analysisProfileDigest": (r.get("analysis_profile") or {}).get("digest"),
                "confidence": r.get("confidence"),
                "authority": r.get("authority"),
                "evidenceLinks": r.get("evidence_links", []),
                "locations": r.get("locations", []),
                "limitationIds": [limitations.index(s) for s in r.get("limitations", [])],
                "summary": summary(r),
            } for r in records],
            "dispatchCases": [case for r in records for case in direct_dispatch_calls(r)],
        })
    document = {"schemaVersion": 1,
                "scope": "Derived index; no original source, game runtime or behavior parity proof",
                "bundles": bundles}
    encoded = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(encoded)
    print(f"Indexed {sum(b['evidenceRecordCount'] for b in bundles)} evidence records -> {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
