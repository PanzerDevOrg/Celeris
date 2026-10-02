"""Release metadata from mod.stonecutter.properties.toml.

    release_info.py <build> <mod version>  -> writes label= and game_versions= to $GITHUB_OUTPUT
    release_info.py --check <matrix list>  -> validates every [stonecutter] build

The label follows the published naming scheme: lowest version, then the rest of
the range in parentheses, e.g. 1.21 .. 1.21.6 -> "1.21(.0-.6)",
1.21.7 .. 1.21.10 -> "1.21(.7-.10)", 26.1 .. 26.3 -> "26(.1-.3)", a single
version stays as is ("1.21.11").
"""
import os
import sys
import tomllib

TOML = "mod.stonecutter.properties.toml"


def load():
    with open(TOML, "rb") as f:
        return tomllib.load(f)


def label(versions):
    if len(versions) == 1:
        return versions[0]
    first, last = versions[0].split("."), versions[-1].split(".")
    # Pad the shorter one with .0 first (1.21 vs 1.21.6 -> 1.21.0 vs 1.21.6).
    width = max(len(first), len(last))
    first += ["0"] * (width - len(first))
    last += ["0"] * (width - len(last))
    common = []
    for a, b in zip(first[:-1], last[:-1]):  # keep at least one part in the suffix
        if a != b:
            break
        common.append(a)
    head = ".".join(common)
    tail_first = "." + ".".join(first[len(common):])
    tail_last = "." + ".".join(last[len(common):])
    return f"{head}({tail_first}-{tail_last})"


def check(matrix):
    data = load()
    builds = data["stonecutter"]["versions"]
    errors = []
    if matrix != builds:
        errors.append(f"release matrix {matrix} != [stonecutter] versions {builds}")
    for build in builds:
        block = data.get(build)
        if not block or "game_versions" not in block:
            errors.append(f'["{build}"] has no game_versions')
            continue
        gv = block["game_versions"]
        lower = block["minecraft_version_range"].strip("[(").split(",")[0]
        if lower != gv[0]:
            errors.append(f'["{build}"] range starts at {lower} but game_versions starts at {gv[0]}')
        if build not in gv:
            errors.append(f'["{build}"] builds against {build}, which is not in its game_versions')
    for e in errors:
        print(f"::error::{e}")
    return 1 if errors else 0


def main():
    if sys.argv[1] == "--check":
        sys.exit(check([v.strip(' "') for v in sys.argv[2].split(",") if v.strip()]))
    build, mod_version = sys.argv[1], sys.argv[2]
    gv = load()[build]["game_versions"]
    out = os.environ.get("GITHUB_OUTPUT")
    text = f"label={mod_version}-{label(gv)}\ngame_versions<<EOF\n" + "\n".join(gv) + "\nEOF\n"
    if out:
        with open(out, "a") as f:
            f.write(text)
    print(text, end="")


if __name__ == "__main__":
    main()
