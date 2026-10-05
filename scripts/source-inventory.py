"""Reproduce the source inventory without running a build or changing application sources."""
from collections import Counter
from pathlib import Path
import argparse
import hashlib
import subprocess

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
KOTLIN = APP / "java/fr/astragames/app"
BINARY = {".ttf", ".otf", ".png", ".jpg", ".jpeg", ".webp", ".jar"}


def record(path):
    data = path.read_bytes()
    lines = None if path.suffix.lower() in BINARY else len(data.decode("utf-8-sig").splitlines())
    return path, lines, len(data), hashlib.sha256(data).hexdigest()


def scope(path):
    relative = path.relative_to(KOTLIN).as_posix()
    if relative.startswith("data/saves/"):
        return "Sauvegardes"
    if relative.startswith("translation/"):
        return "Traduction"
    if relative.startswith("ui/"):
        return "UI/état/thème"
    return "Core/catalogue/stockage/runtime"


def table(rows):
    return ["| Fichier | Lignes | Octets | SHA-256 |", "|---|---:|---:|---|"] + [
        f"| `{path.relative_to(ROOT).as_posix()}` | {lines if lines is not None else '—'} | {size} | `{digest}` |"
        for path, lines, size, digest in rows
    ]


def report():
    production = [record(path) for path in sorted(APP.rglob("*")) if path.is_file()]
    code = [row for row in production if row[0].suffix == ".kt"]
    resources = [row for row in production if row[0].suffix != ".kt"]
    counts = Counter(scope(row[0]) for row in code)
    configs = [record(ROOT / name) for name in (
        "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "app/build.gradle.kts",
        "app/proguard-rules.pro", "baselineprofile/build.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
    )]
    commit = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True).strip()
    branch = subprocess.check_output(["git", "branch", "--show-current"], cwd=ROOT, text=True).strip()
    lines = [
        "# Inventaire de sources — 5 octobre 2026", "",
        f"État local des sources, base Git `{commit}`, branche `{branch}`. Les empreintes SHA-256 portent sur les octets exacts du workspace, y compris les fins de ligne. Cet inventaire n’est ni une mesure de couverture des tests ni la preuve d’une exécution sur appareil.", "",
        f"**{len(production)} fichiers sous `app/src/main/`, dont {len(code)} fichiers Kotlin ({sum(row[1] for row in code)} lignes) et {len(resources)} manifestes, ressources ou profils embarqués.** Tous les fichiers de ce répertoire, y compris les nouvelles sources non encore commitées, figurent ci-dessous.", "",
        "Les lignes sont comptées avec `str.splitlines()` après décodage UTF-8 ; les fichiers binaires portent « — ». Les caches, dépendances téléchargées, artefacts de build, tests, source set debug et générateur Baseline Profile sont exclus du total de production.", "",
        "| Volet Kotlin | Fichiers | Lignes |", "|---|---:|---:|",
    ]
    for category, count in sorted(counts.items()):
        lines.append(f"| {category} | {count} | {sum(row[1] for row in code if scope(row[0]) == category)} |")
    lines += ["", "## Modules Kotlin les plus volumineux", "", "| Fichier | Lignes |", "|---|---:|"]
    for path, count, _, _ in sorted(code, key=lambda row: (-row[1], str(row[0])))[:12]:
        lines.append(f"| `{path.relative_to(KOTLIN).as_posix()}` | {count} |")
    lines += ["", "La localisation est une table multilingue ; sa taille ne mesure pas la complexité algorithmique. `AstraViewModel`, `GameRepository`, le DAO et les écrans métadonnées restent des candidats à une extraction progressive. Aucune réécriture intégrale de ces modules ni absence totale de code mort n’est revendiquée.", "",
              "## Code Kotlin de production", ""] + table(code)
    lines += ["", "## Manifestes, ressources et profil embarqués", ""] + table(resources)
    lines += ["", "## Configuration de construction et wrapper", "", "Ces fichiers influencent la compilation et sont inventoriés séparément du code livré dans l’application. `local.properties` et les secrets locaux sont exclus.", ""] + table(configs)
    lines += ["", "## Autres sources et exclusions", "", "| Périmètre | Fichiers | Statut dans cet inventaire |", "|---|---:|---|"]
    for prefix, description in (
        ("app/src/test", "Tests JVM et fixtures ; hors total production"),
        ("app/src/androidTest", "Tests instrumentés ; résultats dans le [rapport de validation](AUDIT_2026-10-05.md)"),
        ("app/src/debug", "Manifest/provider de test ; non livrés en release"),
        ("baselineprofile/src", "Générateur instrumenté ; hors application"),
        ("scripts", "Outillage de vérification et génération"),
        ("app/schemas", "Schémas Room historiques"),
    ):
        count = sum(path.is_file() for path in (ROOT / prefix).rglob("*") if "__pycache__" not in path.parts)
        lines.append(f"| `{prefix}/` | {count} | {description} |")
    lines += ["", "Les polices embarquées ont une empreinte mais ne sont pas auditées comme du code. Les maquettes `.pen`, captures historiques, contenus de jeux utilisateur, archives générées et JAR de vérification sous `build/` ne font pas partie des sources de production. Les dépendances sont inventoriées dans `DEPENDENCIES_2026-10-05.json`. Le [rapport de validation](AUDIT_2026-10-05.md) décrit les résultats de build, les tests effectivement exécutés et leurs limites, y compris sur Android.", "",
              "## Reproduction", "", "Depuis la racine du dépôt, avec Python 3 :", "", "```powershell", "python scripts/source-inventory.py", "python scripts/source-inventory.py --check", "```", "", "La première commande régénère uniquement ce document. La seconde vérifie que chaque chemin, compte et empreinte correspond toujours au workspace ; elle ne modifie rien. Relancer après tout dernier changement de source.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output = ROOT / "docs/SOURCE_INVENTORY_2026-10-05.md"
    expected = report()
    if args.check:
        if output.read_text(encoding="utf-8") != expected:
            raise SystemExit("Source inventory is stale; regenerate it after the final source changes.")
        print("Source inventory matches all current files, line counts and SHA-256 digests.")
    else:
        output.write_text(expected, encoding="utf-8", newline="\n")
        print(f"Wrote {output.relative_to(ROOT)}")
