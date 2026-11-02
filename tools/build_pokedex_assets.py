#!/usr/bin/env python3
"""
Builds the app's Pokédex assets from a texture pack plus PokeAPI's open CSV data.

  python3 -I tools/build_pokedex_assets.py <textures>/pokemon <pokemon_species.csv> <pokemon_species_names.csv>

Writes (relative to the project root):
  app/src/main/assets/pokemon/<dex>.png     normal sprite
  app/src/main/assets/pokemon/<dex>_s.png   shiny sprite (only when the pack has one)
  app/src/main/assets/species.tsv           dex, name, capture_rate, flags, has_shiny

Flags: L = legendary, M = mythical, B = baby, - = none.
Only the base form of each species is used for now.
"""
import csv
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets")


PREFERRED_FORMS = ["base", "none", "altered", "normal", "standard", "ordinary", "incarnate", "average",
                   "solo", "single", "midday", "meteor", "disguised", "full", "zero", "hero", "amped",
                   "natural", "plant", "red", "blue", "a"]
SKIP_FORMS = {"mega", "megax", "megay", "gmax", "primal"}


def pick_form(species_dir):
    """Returns (group, form): the base form if the pack has one, otherwise a sensible default form."""
    for group in ("all", "male", "female"):
        gdir = os.path.join(species_dir, group)
        if not os.path.isdir(gdir):
            continue
        forms = sorted(f for f in os.listdir(gdir)
                       if f not in SKIP_FORMS and os.path.isfile(os.path.join(gdir, f, "none", "sprite.png")))
        for pref in PREFERRED_FORMS:
            if pref in forms:
                return group, pref
        if forms:
            return group, forms[0]
    return None


def sprite_path(species_dir, group, form, variant):
    p = os.path.join(species_dir, group, form, variant, "sprite.png")
    return p if os.path.isfile(p) else None


PREFERRED_PATTERNS = ["meadow", "east", "red", "blue", "white"]


def pick_pattern(species_dir):
    """Species like Vivillon or Flabébé keep their patterns as variants (x and xshiny) inside base/."""
    bdir = os.path.join(species_dir, "all", "base")
    if not os.path.isdir(bdir):
        return None
    names = sorted(v for v in os.listdir(bdir)
                   if not v.endswith("shiny") and os.path.isfile(os.path.join(bdir, v, "sprite.png")))
    if not names:
        return None
    pick = next((p for p in PREFERRED_PATTERNS if p in names), names[0])
    shiny = os.path.join(bdir, pick + "shiny", "sprite.png")
    return os.path.join(bdir, pick, "sprite.png"), (shiny if os.path.isfile(shiny) else None), pick


def main():
    tex_root, species_csv, names_csv = sys.argv[1:4]

    names = {}
    with open(names_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["local_language_id"] == "9":
                names[int(row["pokemon_species_id"])] = row["name"]

    species = {}
    with open(species_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            species[int(row["id"])] = row

    folders = {}
    for d in os.listdir(tex_root):
        p = os.path.join(tex_root, d)
        if os.path.isdir(p) and "_" in d and d.split("_")[0].isdigit():
            folders[int(d.split("_")[0])] = p

    sprite_dir = os.path.join(OUT, "pokemon")
    if os.path.isdir(sprite_dir):
        shutil.rmtree(sprite_dir)
    os.makedirs(sprite_dir)

    rows, skipped, no_shiny, used_form = [], [], [], []
    for dex in sorted(species):
        if dex not in folders:
            skipped.append((dex, species[dex]["identifier"], "no texture folder"))
            continue
        pick = pick_form(folders[dex])
        if pick is not None:
            normal = sprite_path(folders[dex], *pick, "none")
            shiny = sprite_path(folders[dex], *pick, "shiny")
            if pick[1] != "base":
                used_form.append((dex, species[dex]["identifier"], pick[1]))
        else:
            pat = pick_pattern(folders[dex])
            if pat is None:
                skipped.append((dex, species[dex]["identifier"], "no usable sprite"))
                continue
            normal, shiny, pname = pat
            used_form.append((dex, species[dex]["identifier"], pname))
        shutil.copyfile(normal, os.path.join(sprite_dir, f"{dex}.png"))
        if shiny:
            shutil.copyfile(shiny, os.path.join(sprite_dir, f"{dex}_s.png"))
        else:
            no_shiny.append(dex)
        s = species[dex]
        flags = "".join(
            c for c, key in (("L", "is_legendary"), ("M", "is_mythical"), ("B", "is_baby")) if s[key] == "1"
        ) or "-"
        name = names.get(dex) or s["identifier"].replace("-", " ").title()
        rows.append((dex, name, int(s["capture_rate"] or 45), flags, 1 if shiny else 0))

    with open(os.path.join(OUT, "species.tsv"), "w", encoding="utf-8", newline="") as f:
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")

    print(f"species written: {len(rows)}")
    print(f"non-base form used for {len(used_form)} species, e.g. {used_form[:6]}")
    print(f"skipped: {len(skipped)} -> {skipped[:10]}")
    print(f"without shiny sprite: {len(no_shiny)} -> {no_shiny[:20]}")
    print("flags:", {k: sum(1 for r in rows if k in r[3]) for k in "LMB"})
    size = sum(os.path.getsize(os.path.join(sprite_dir, x)) for x in os.listdir(sprite_dir))
    print(f"sprite assets: {len(os.listdir(sprite_dir))} files, {size/1024:.0f} KB")


if __name__ == "__main__":
    main()
