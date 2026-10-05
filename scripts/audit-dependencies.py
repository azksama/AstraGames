"""Check resolved Gradle Maven dependencies against OSV; no account required.

Usage: python scripts/audit-dependencies.py dependencies.txt output.json
Input: gradlew :app:dependencies --configuration releaseRuntimeClasspath
The output records the resolved coordinates, query date and returned advisory IDs.
An empty result is not a guarantee that a dependency is vulnerability-free.
"""
import datetime
import json
import re
import sys
import urllib.request
from pathlib import Path


def coordinates(report):
    packages = set()
    for line in report.splitlines():
        match = re.search(r"(?:\+---|\\---) ([\w.-]+):([\w.-]+)(?::([^\s]+))?(?: -> ([^\s]+))?", line)
        if not match:
            continue
        group, name, version, resolved = match.groups()
        if resolved:
            if resolved.count(":") == 2:
                group, name, version = resolved.split(":")
            else:
                version = resolved
        if version and not version.startswith("{"):
            packages.add((group + ":" + name, version))
    return sorted(packages)


def query(queries):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if len(results) != len(queries):
        raise ValueError("Incomplete OSV batch response")
    return results


if __name__ == "__main__":
    packages = coordinates(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    if not packages:
        raise ValueError("No resolved dependencies found")
    rows = []
    for offset in range(0, len(packages), 100):
        chunk = packages[offset:offset + 100]
        queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in chunk]
        results = query(queries)
        for (name, version), request, result in zip(chunk, queries, results):
            vulns = result.get("vulns", [])
            while result.get("next_page_token"):
                result = query([{**request, "page_token": result["next_page_token"]}])[0]
                vulns.extend(result.get("vulns", []))
            rows.append({"package": name, "version": version, "vulnerabilities": vulns})
    output = {
        "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source": "https://api.osv.dev/v1/querybatch",
        "configuration": "releaseRuntimeClasspath",
        "packageCount": len(rows),
        "affectedPackageCount": sum(bool(row["vulnerabilities"]) for row in rows),
        "dependencies": rows,
    }
    Path(sys.argv[2]).write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in output.items() if key != "dependencies"}))
    for row in rows:
        if row["vulnerabilities"]:
            print(json.dumps(row))
