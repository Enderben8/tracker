"""Build the exam-board topic catalogue from the boards' own specifications.

Nothing here ships in the app. It runs occasionally, by hand, when a board
publishes a new specification:

    python tools/spec_tool.py fetch aqa        # download the pages (cached on disk)
    python tools/spec_tool.py extract aqa      # HTML -> tools/catalogue/*.json
    python tools/spec_tool.py generate         # JSON -> Kotlin in core/
    python tools/spec_tool.py verify           # every Kotlin title really appears in the source

The point of keeping the downloaded pages in tools/sources/ is `verify`: it
checks the committed Kotlin against the board's own words, so a typo or an
invented topic cannot slip in unnoticed. CI runs `verify`.
"""

from __future__ import annotations

import html
import json
import re
import sys
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "tools" / "sources"          # downloaded pages, ~60MB, not committed
EVIDENCE = ROOT / "tools" / "evidence"        # their text, committed so `verify` works anywhere
CATALOGUE = ROOT / "tools" / "catalogue"
KOTLIN = ROOT / "core" / "src" / "commonMain" / "kotlin" / "revision" / "core" / "catalogue"

USER_AGENT = "revision-tracker-catalogue-builder (+https://github.com/Enderben8/tracker)"

# Subjects to build, per board. `key` must match across boards: it is how the
# setup wizard lists a subject once and offers the boards that publish it.
# `layout` says how that subject's pages are shaped — see "extracting" below.
AQA_SUBJECTS = [
    {"key": "biology", "name": "Biology", "spec_code": "8461", "path": "biology/gcse/biology-8461"},
    {"key": "chemistry", "name": "Chemistry", "spec_code": "8462", "path": "chemistry/gcse/chemistry-8462"},
    {"key": "physics", "name": "Physics", "spec_code": "8463", "path": "physics/gcse/physics-8463"},
    {"key": "maths", "name": "Maths", "spec_code": "8300", "path": "mathematics/gcse/mathematics-8300",
     "layout": {"topics": "refs", "group_level": 3, "topic_level": 4}},
    {"key": "computer-science", "name": "Computer Science", "spec_code": "8525",
     "path": "computer-science/gcse/computer-science-8525"},
    {"key": "english-literature", "name": "English Literature", "spec_code": "8702",
     "path": "english/gcse/english-8702",
     "layout": {"topics": "texts", "group_level": 3}},
    {"key": "english-language", "name": "English Language", "spec_code": "8700",
     "path": "english/gcse/english-8700"},
    {"key": "geography", "name": "Geography", "spec_code": "8035", "path": "geography/gcse/geography-8035",
     "layout": {"group_level": 3, "topic_level": 4}},
    {"key": "history", "name": "History", "spec_code": "8145", "path": "history/gcse/history-8145",
     "layout": {"group_level": 4, "topic_level": 5, "choice_level": 3}},
    # French: themes are bullet lists, grammar is a pair of tier headings, and the
    # vocabulary page is guidance about word counts rather than anything to revise.
    {"key": "french", "name": "French", "spec_code": "8652", "path": "french/gcse/french-8652",
     "layout": {"topics": "bullets", "group_level": 3},
     "skip_pages": ["vocabulary"],
     "page_layouts": {"grammar": {"topics": "headings", "group_level": 2, "topic_level": 3}}},
    {"key": "religious-studies", "name": "Religious Studies", "spec_code": "8062",
     "path": "religious-studies/gcse/religious-studies-8062"},
]

BOARDS = {"aqa": AQA_SUBJECTS}


# ---------------------------------------------------------------- downloading

def get(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def fetch_aqa(subject: dict) -> None:
    """Save a subject's subject-content index and every section page under it."""
    base = f"https://www.aqa.org.uk/subjects/{subject['path']}/specification/subject-content"
    folder = SOURCES / "aqa" / subject["key"]
    folder.mkdir(parents=True, exist_ok=True)

    index = get(base)
    (folder / "_index.html").write_text(index, encoding="utf-8")

    for slug in section_slugs(index):
        page = get(f"{base}/{slug}")
        (folder / f"{slug}.html").write_text(page, encoding="utf-8")
        print(f"  {subject['key']}/{slug}")


# Slugs are usually words ("cell-biology") but some subjects number them ("3.1-number"), so
# dots count. The subject part of the path is left open because AQA redirects some subjects
# to a path other than the one we asked for (english-literature-8702 -> english-8702).
SECTION_LINK = re.compile(
    r"/subjects/[a-z0-9\-]+/gcse/[a-z0-9.\-]+/specification/subject-content/([a-z0-9.\-]+)"
)


def section_slugs(index_html: str) -> list[str]:
    """The subject-content sub-pages linked from the index, in page order."""
    seen: list[str] = []
    for slug in SECTION_LINK.findall(index_html):
        if slug not in seen:
            seen.append(slug)
    return seen


# ---------------------------------------------------------------- extracting
#
# AQA lays subjects out differently, so each one declares its shape above.
# Five shapes cover every GCSE subject:
#
#   headings  the common case: a group heading with topic headings beneath
#             (Biology "4.1 Cell biology" > "4.1.1 Cell structure")
#   refs      topics are lettered references whose wording sits in a table, and
#             the table column says whether it is Higher-only content (Maths)
#   texts     topics are the rows of the set-text tables (English Literature)
#   bullets   topics are the bullet list under each group heading (French)
#   plus `choice_level` for subjects where the group is one of several options
#   the student picks between (History).

TAG = re.compile(r"<[^>]+>")
HEADING = re.compile(r"<(h[2-5])\b[^>]*>(.*?)</\1>", re.DOTALL)
LIST_ITEM = re.compile(r"<li\b[^>]*>(.*?)</li>", re.DOTALL)
TABLE = re.compile(r"<table\b[^>]*>(.*?)</table>", re.DOTALL)
ROW = re.compile(r"<tr\b[^>]*>(.*?)</tr>", re.DOTALL)
CELL = re.compile(r"<(t[dh])\b[^>]*>(.*?)</\1>", re.DOTALL)
# Headings read like "4.1.1 <!-- -->Cell structure"; the number is optional.
NUMBERED = re.compile(r"^((?:\d+\.)+\d+|\d+)\s+(.*)$")
# The page's own content ends where the previous/next links begin.
CONTENT_END = '<div class="flex flex-row border-y-4'


def text_of(fragment: str) -> str:
    plain = html.unescape(TAG.sub(" ", fragment)).replace(" ", " ")
    return re.sub(r"\s+", " ", plain).strip()


def split_code(heading: str) -> tuple[str | None, str]:
    match = NUMBERED.match(heading)
    if match:
        return match.group(1), match.group(2).strip()
    return None, heading


def content_of(markup: str) -> str:
    """The page body, with the site header and footer (both full of headings) removed."""
    start = -1
    for match in HEADING.finditer(markup):
        if match.group(1) == "h2" and split_code(text_of(match.group(2)))[0]:
            start = match.start()
            break
    if start < 0:
        return ""
    end = markup.find(CONTENT_END, start)
    return markup[start: end if end > 0 else len(markup)]


def tokens_of(content: str) -> list[tuple]:
    """Headings, bullets and tables, in the order they appear."""
    found: list[tuple[int, tuple]] = []
    for match in HEADING.finditer(content):
        code, title = split_code(text_of(match.group(2)))
        if title:
            found.append((match.start(), ("heading", int(match.group(1)[1]), code, title)))
    for match in LIST_ITEM.finditer(content):
        text = text_of(match.group(1))
        if text:
            found.append((match.start(), ("bullet", text)))
    for match in TABLE.finditer(content):
        rows = [[text_of(cell) for _, cell in CELL.findall(row)] for row in ROW.findall(match.group(1))]
        found.append((match.start(), ("table", rows)))
    return [token for _, token in sorted(found, key=lambda pair: pair[0])]


def headings_at(tokens: list[tuple], level: int) -> list[tuple[str | None, str, int]]:
    """Every heading at `level`, as (code, title, position)."""
    return [(token[2], token[3], index) for index, token in enumerate(tokens)
            if token[0] == "heading" and token[1] == level]


def topics_between(tokens: list[tuple], start: int, end: int, layout: dict) -> list[dict]:
    """The topics belonging to one group, in whichever shape this subject uses."""
    style = layout["topics"]
    window = tokens[start + 1:end]
    topics: list[dict] = []

    if style == "headings":
        for token in window:
            if token[0] == "heading" and token[1] == layout["topic_level"]:
                topics.append({"code": token[2], "title": token[3]})

    elif style == "bullets":
        for token in window:
            if token[0] == "bullet":
                topics.append({"code": None, "title": token[1]})

    elif style == "texts":
        # Set texts appear either as an Author/Title table or, for Shakespeare, a plain list.
        for token in window:
            if token[0] == "table":
                for row in token[1]:
                    if len(row) >= 2 and row[0] and row[1] and row[0].lower() not in ("author", "poet"):
                        topics.append({"code": None, "title": f"{row[1]} ({row[0]})"})
            elif token[0] == "bullet":
                topics.append({"code": None, "title": token[1].rstrip(" .")})

    elif style == "refs":
        # "N1" followed by a table whose first wording becomes the title.
        code = None
        for token in window:
            if token[0] == "heading" and token[1] == layout["topic_level"]:
                code = token[3]
            elif token[0] == "table" and code:
                statement, tier = first_statement(token[1])
                if statement:
                    topics.append({"code": code, "title": statement, "tier": tier})
                code = None

    return topics


def first_statement(rows: list[list[str]]) -> tuple[str | None, str | None]:
    """The first wording in a Maths reference table, and whether it is Higher-only."""
    if not rows:
        return None, None
    header = [cell.lower() for cell in rows[0]]
    for row in rows[1:]:
        for column, cell in enumerate(row):
            if not cell:
                continue
            tier = "HIGHER" if column < len(header) and "higher" in header[column] else None
            sentence = cell.split(". ")[0].strip()
            return (sentence[:117] + "…") if len(sentence) > 118 else sentence, tier
    return None, None


def last_before(headings: list[tuple[str | None, str, int]], index: int) -> str | None:
    earlier = [title for _, title, position in headings if position < index]
    return earlier[-1] if earlier else None


def extract_aqa(subject: dict) -> dict:
    folder = SOURCES / "aqa" / subject["key"]
    index_html = (folder / "_index.html").read_text(encoding="utf-8")
    base = f"https://www.aqa.org.uk/subjects/{subject['path']}/specification/subject-content"
    layout = {"topics": "headings", "group_level": 2, "topic_level": 3, **subject.get("layout", {})}

    groups = []
    for slug in section_slugs(index_html):
        page = folder / f"{slug}.html"
        if not page.exists() or slug in subject.get("skip_pages", ()):
            continue
        # A few subjects mix shapes: French lists its themes as bullets but its grammar
        # as headings, so a page can override the subject's layout.
        page_layout = {**layout, **subject.get("page_layouts", {}).get(slug, {})}
        tokens = tokens_of(content_of(page.read_text(encoding="utf-8")))
        if not tokens:
            continue

        starts = headings_at(tokens, page_layout["group_level"])
        if not starts:   # a section page with no sub-structure is one group on its own
            starts = headings_at(tokens, 2)
        # Where a group is one of several options, the heading above it names the choice.
        choices = headings_at(tokens, page_layout["choice_level"]) if "choice_level" in page_layout else []

        for position, (code, title, index) in enumerate(starts):
            end = starts[position + 1][2] if position + 1 < len(starts) else len(tokens)
            topics = topics_between(tokens, index, end, page_layout)
            if not topics:
                # A section with nothing beneath it is itself the thing to revise.
                topics = [{"code": code, "title": title}]
            group = {
                "code": code,
                "title": title,
                "sourceUrl": f"{base}/{slug}",
                "topics": topics,
            }
            choice = last_before(choices, index)
            if choice:
                group["choice"] = {"group": choice, "pick": 1}
            groups.append(group)

    return {
        "key": subject["key"],
        "ref": f"aqa/{subject['key']}-{subject['spec_code']}",
        "board": "AQA",
        "name": subject["name"],
        "specCode": subject["spec_code"],
        "sourceUrl": base,
        "checkedOn": date.today().isoformat(),
        "groups": groups,
    }


# ----------------------------------------------------------------- generating

def kotlin_string(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return f'"{escaped}"'


def kotlin_optional(value: str | None) -> str:
    return kotlin_string(value) if value else "null"


def kotlin_name(board: str, key: str) -> str:
    """The Kotlin `val` name, e.g. aqa + biology -> aqaBiology."""
    parts = key.split("-")
    return board.lower() + "".join(part.capitalize() for part in parts)


def kotlin_file_name(spec: dict) -> str:
    name = kotlin_name(spec["board"], spec["key"])
    return f"{name[0].upper()}{name[1:]}.kt"


def generate(spec: dict) -> str:
    board, key = spec["board"], spec["key"]
    uses_choice = any("choice" in group for group in spec["groups"])
    uses_tier = any(topic.get("tier") for group in spec["groups"] for topic in group["topics"])

    imports = ["Board", "SpecGroup", "SpecSubject", "SpecTopic"]
    if uses_choice:
        imports.append("Choice")
    if uses_tier:
        imports.append("Tier")

    lines = [
        "// GENERATED by tools/spec_tool.py from the exam board's own specification.",
        "// Do not edit by hand: run `python tools/spec_tool.py generate` instead.",
        f"// Source: {spec['sourceUrl']}",
        f"// Checked: {spec['checkedOn']}",
        "",
        f"package revision.core.catalogue.{board.lower()}",
        "",
    ]
    lines += [f"import revision.core.catalogue.{name}" for name in sorted(imports)]
    lines += [
        "",
        f"val {kotlin_name(board, key)}: SpecSubject = SpecSubject(",
        f"    key = {kotlin_string(key)},",
        f"    ref = {kotlin_string(spec['ref'])},",
        f"    board = Board.{board.upper()},",
        f"    name = {kotlin_string(spec['name'])},",
        f"    specCode = {kotlin_string(spec['specCode'])},",
        f"    sourceUrl = {kotlin_string(spec['sourceUrl'])},",
        f"    checkedOn = {kotlin_string(spec['checkedOn'])},",
        "    groups = listOf(",
    ]
    for group in spec["groups"]:
        lines.append("        SpecGroup(")
        lines.append(f"            code = {kotlin_optional(group['code'])},")
        lines.append(f"            title = {kotlin_string(group['title'])},")
        lines.append("            topics = listOf(")
        for topic in group["topics"]:
            parts = [kotlin_optional(topic["code"]), kotlin_string(topic["title"])]
            if topic.get("tier"):
                parts.append(f"Tier.{topic['tier']}")
            lines.append(f"                SpecTopic({', '.join(parts)}),")
        lines.append("            ),")
        if "choice" in group:
            choice = group["choice"]
            lines.append(
                f"            choice = Choice({kotlin_string(choice['group'])}, {choice['pick']}),"
            )
        lines.append("        ),")
    lines += ["    ),", ")", ""]
    return "\n".join(lines)


# ------------------------------------------------------------------ verifying

def evidence_path(spec: dict) -> Path:
    return EVIDENCE / f"{spec['board'].lower()}-{spec['key']}.txt"


def write_evidence(spec: dict) -> None:
    """Save the specification's wording, so `verify` needs neither the 60MB of HTML nor the network."""
    folder = SOURCES / spec["board"].lower() / spec["key"]
    pages = sorted(page for page in folder.glob("*.html") if page.name != "_index.html")
    # Only the specification content: the surrounding page is menus and scripts.
    text = "\n".join(text_of(content_of(page.read_text(encoding="utf-8"))) for page in pages)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    evidence_path(spec).write_text(text, encoding="utf-8")


def verify(spec: dict) -> list[str]:
    """Every title in the generated Kotlin must appear in the board's own words."""
    problems = []
    kotlin = KOTLIN / spec["board"].lower() / kotlin_file_name(spec)
    if not kotlin.exists():
        return [f"{spec['ref']}: {kotlin.name} has not been generated"]

    if kotlin.read_text(encoding="utf-8") != generate(spec):
        problems.append(f"{spec['ref']}: {kotlin.name} differs from the JSON — re-run `generate`")

    evidence = evidence_path(spec)
    if not evidence.exists():
        return problems + [f"{spec['ref']}: {evidence.name} is missing — re-run `extract`"]
    pages = evidence.read_text(encoding="utf-8")
    for group in spec["groups"]:
        titles = [group["title"]] + [topic["title"] for topic in group["topics"]]
        for title in titles:
            # Titles built from two cells ("An Inspector Calls (JB Priestley)") or trimmed to
            # length are checked by their leading words, which must still be the board's own.
            probe = title.split(" (")[0].rstrip("…")
            if probe and probe not in pages:
                problems.append(f"{spec['ref']}: {probe!r} is not in the downloaded specification")
    return problems


# ----------------------------------------------------------------------- main

def specs() -> list[dict]:
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(CATALOGUE.glob("*.json"))]


def main(argv: list[str]) -> int:
    command = argv[1] if len(argv) > 1 else ""
    board = argv[2] if len(argv) > 2 else "aqa"

    if command == "fetch":
        print(f"Downloading {board} specifications…")
        for subject in BOARDS[board]:
            fetch_aqa(subject)

    elif command == "extract":
        CATALOGUE.mkdir(parents=True, exist_ok=True)
        for subject in BOARDS[board]:
            spec = extract_aqa(subject)
            out = CATALOGUE / f"{board}-{subject['key']}.json"
            out.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            write_evidence(spec)
            topics = sum(len(group["topics"]) for group in spec["groups"])
            print(f"  {out.name}: {len(spec['groups'])} groups, {topics} topics")

    elif command == "generate":
        for spec in specs():
            folder = KOTLIN / spec["board"].lower()
            folder.mkdir(parents=True, exist_ok=True)
            out = folder / kotlin_file_name(spec)
            out.write_text(generate(spec), encoding="utf-8")
            print(f"  {out.relative_to(ROOT)}")

    elif command == "verify":
        problems = [problem for spec in specs() for problem in verify(spec)]
        for problem in problems:
            print(problem)
        print(f"{len(specs())} subject(s) checked, {len(problems)} problem(s)")
        return 1 if problems else 0

    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
