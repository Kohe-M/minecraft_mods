#!/usr/bin/env python3
"""開発用のコマンド。使い方は python tools/dev.py -h

  test     変わった mod（または指定した mod）をビルドし、ゲームテストを走らせて結果をまとめる
  shots    スクリーンショットを一覧の画像と HTML にまとめる（テストは走らせない）
  status   リリースしていない変更がある mod を出す
  release  mod_version を上げてコミットし、タグを付けて、その状態の jar を作る
"""

import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODS_DIR = ROOT / "mods"
OUT = ROOT / "build" / "dev"
BUNDLE = "jori"
# ここが変わったら全 mod に響く
SHARED_PATHS = ("build.gradle", "settings.gradle", "gradle.properties", "gradle/")
LEVELS = ("patch", "minor", "major")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


# ---- mod の一覧 ----

def all_mods():
    return sorted(p.name for p in MODS_DIR.iterdir() if (p / "gradle.properties").is_file())


def bundled_mods():
    return [m for m in all_mods() if m != BUNDLE]


def has_client_tests(mod):
    build = MODS_DIR / mod / "build.gradle"
    return build.is_file() and re.search(r"enableClientGameTests\s*=\s*true", build.read_text(encoding="utf-8")) is not None


def mod_version(mod, ref=None):
    text = git("show", f"{ref}:mods/{mod}/gradle.properties") if ref else (MODS_DIR / mod / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^mod_version=(\S+)", text, re.M)
    return match.group(1) if match else None


def minecraft_version():
    return re.search(r"^minecraft_version=(\S+)", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M).group(1)


def check_mods(mods):
    unknown = [m for m in mods if m not in all_mods()]
    if unknown:
        sys.exit(f"知らない mod: {', '.join(unknown)}（あるのは {', '.join(all_mods())}）")


# ---- git ----

def git(*args, check=True):
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} が失敗しました:\n{result.stderr.strip()}")
    return result.stdout


def git_ok(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True).returncode == 0


def default_base():
    upstream = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check=False).strip()
    return upstream or "origin/main"


def changed_files(base):
    files = set()
    files.update(git("diff", "--name-only", f"{base}...HEAD", check=False).split())
    files.update(git("diff", "--name-only", "HEAD").split())
    files.update(git("ls-files", "--others", "--exclude-standard").split())
    return files


def mods_for_files(files):
    mods = set()
    for path in files:
        parts = path.split("/")
        if len(parts) > 2 and parts[0] == "mods" and parts[1] in all_mods():
            mods.add(parts[1])
        elif any(path == p or (p.endswith("/") and path.startswith(p)) for p in SHARED_PATHS):
            mods.update(all_mods())
    # jori には中の mod の jar が入るので、どれかが変わったら jori も確かめる
    if mods & set(bundled_mods()):
        mods.add(BUNDLE)
    return sorted(mods)


# ---- gradle ----

def gradlew():
    return [str(ROOT / "gradlew.bat")] if os.name == "nt" else [str(ROOT / "gradlew")]


def decode(raw):
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp932", errors="replace")


PROGRESS = re.compile(r"^(> Task :\S+:(runClientGameTest|runGameTest|build)\b.*|BUILD (SUCCESSFUL|FAILED).*)$")


def read_log(path):
    """ゲームのログは JVM の既定の文字コード（Windows では cp932 のことがある）で書かれる"""
    return decode(path.read_bytes()).splitlines()


def run_gradle(tasks, options, log):
    """gradle を走らせ、全部の出力を log に書く。画面には進み具合だけ出す"""
    command = gradlew() + tasks + ["--continue", "--console=plain"] + options
    print("$ gradlew " + " ".join(tasks + options), flush=True)
    lines = []
    shown = set()
    with open(log, "a", encoding="utf-8") as out:
        out.write("$ gradlew " + " ".join(tasks + options) + "\n")
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for raw in process.stdout:
            line = decode(raw).rstrip("\r\n")
            lines.append(line)
            out.write(line + "\n")
            # 並列に走るタスクは、出力が混ざるたびに見出しが繰り返されるので 1 回だけ出す
            if PROGRESS.match(line) and line not in shown:
                shown.add(line)
                print("  " + line, flush=True)
        process.wait()
    return lines


def failed_tasks(lines):
    failed = set()
    timed_out = set()
    current = None
    for line in lines:
        match = re.match(r"^> Task (:\S+) FAILED", line)
        if match:
            failed.add(match.group(1))
        match = re.match(r"^Execution failed for task '(:\S+)'", line)
        if match:
            current = match.group(1)
            failed.add(current)
        elif current and "Timeout has been exceeded" in line:
            timed_out.add(current)
    return failed, timed_out


def ran(lines, task):
    return any(re.match(rf"^> Task {re.escape(task)}( |$)", line) for line in lines)


# ---- 失敗の中身 ----

def client_failure(mod):
    """クライアントのゲームテストのログから、例外とこのリポジトリの行を抜き出す"""
    log = MODS_DIR / mod / "build" / "run" / "clientGameTest" / "logs" / "latest.log"
    if not log.is_file():
        return ["（ログがない）"]
    text = read_log(log)
    for i, line in enumerate(text):
        if "Client gametests failed with an exception" in line:
            detail = []
            for rest in text[i + 1:]:
                stripped = rest.strip()
                if not stripped:
                    continue
                if stripped.startswith("at "):
                    if "com.omuraisu" in stripped:
                        detail.append("  " + stripped)
                    if len(detail) >= 7:
                        break
                elif stripped.startswith("Caused by") or not detail:
                    detail.append(stripped)
                elif detail and not stripped.startswith("..."):
                    break
            return detail
    if not any("Stopping!" in line for line in text):
        return [f"途中で止まった（最後の行: {text[-1].strip() if text else ''}）"]
    return ["（ログに例外がない。gradle のログを見る）"]


def server_failure(mod):
    log = MODS_DIR / mod / "build" / "run" / "gameTest" / "logs" / "latest.log"
    if not log.is_file():
        return []
    return [line.strip() for line in read_log(log) if "failed!" in line][:8]


def compile_errors(mod, lines):
    pattern = re.compile(rf"mods[\\/]{re.escape(mod)}[\\/].*\.java:\d+: (エラー|error)")
    return [line.strip() for line in lines if pattern.search(line)][:6]


# ---- スクリーンショット ----

def screenshots(mod):
    folder = MODS_DIR / mod / "build" / "run" / "clientGameTest" / "screenshots"
    return sorted(folder.glob("*.png")) if folder.is_dir() else []


def collect_shots(mods):
    """スクリーンショットを build/dev に写し（次のテストで消えるため）、一覧の画像と HTML を作る"""
    shots = {}
    shutil.rmtree(OUT / "screenshots", ignore_errors=True)
    for mod in mods:
        files = screenshots(mod)
        if not files:
            continue
        target = OUT / "screenshots" / mod
        target.mkdir(parents=True)
        copied = []
        for file in files:
            shutil.copy2(file, target / file.name)
            copied.append(target / file.name)
        shots[mod] = copied
    sheets = make_sheets(shots)
    return shots, sheets


SHEET_COLUMNS = 4
SHEET_ROWS = 5
THUMB_WIDTH = 320
THUMB_HEIGHT = 180
LABEL_HEIGHT = 16


def make_sheets(shots):
    """1 枚に 20 枚ずつ並べた画像。ラベルはファイル名"""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("Pillow がないので一覧の画像は作らない（pip install pillow）。HTML は作る")
        return {}
    folder = OUT / "sheets"
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True)
    sheets = {}
    per_sheet = SHEET_COLUMNS * SHEET_ROWS
    for mod, files in shots.items():
        sheets[mod] = []
        for page in range(0, len(files), per_sheet):
            chunk = files[page:page + per_sheet]
            rows = (len(chunk) + SHEET_COLUMNS - 1) // SHEET_COLUMNS
            cell_w = THUMB_WIDTH + 8
            cell_h = THUMB_HEIGHT + LABEL_HEIGHT + 8
            sheet = Image.new("RGB", (cell_w * SHEET_COLUMNS, cell_h * rows), (32, 34, 40))
            draw = ImageDraw.Draw(sheet)
            for i, file in enumerate(chunk):
                x = (i % SHEET_COLUMNS) * cell_w + 4
                y = (i // SHEET_COLUMNS) * cell_h + 4
                with Image.open(file) as image:
                    image = image.convert("RGB")
                    image.thumbnail((THUMB_WIDTH, THUMB_HEIGHT))
                    sheet.paste(image, (x + (THUMB_WIDTH - image.width) // 2, y + (THUMB_HEIGHT - image.height) // 2))
                draw.text((x, y + THUMB_HEIGHT + 3), file.stem, fill=(230, 230, 230))
            name = folder / f"{mod}-{page // per_sheet + 1}.png"
            sheet.save(name)
            sheets[mod].append(name)
    return sheets


def write_report(results, shots, title):
    rows = []
    for result in results:
        mod = result["mod"]
        status = "OK" if result["ok"] else "NG"
        detail = "<br>".join(html.escape(line) for line in result["detail"])
        rows.append(f"<tr class='{status}'><td>{mod}</td><td>{html.escape(result['build'])}</td><td>{html.escape(result['client'])}</td>"
                    f"<td>{len(shots.get(mod, []))}</td><td><code>{detail}</code></td></tr>")
    sections = []
    for mod, files in shots.items():
        figures = "".join(
            f"<figure><a href='screenshots/{mod}/{f.name}'><img loading='lazy' src='screenshots/{mod}/{f.name}'></a>"
            f"<figcaption>{html.escape(f.stem)}</figcaption></figure>" for f in files)
        sections.append(f"<h2 id='{mod}'>{mod}</h2><div class='grid'>{figures}</div>")
    page = f"""<!doctype html><meta charset="utf-8"><title>{html.escape(title)}</title>
<style>
body{{font-family:system-ui,sans-serif;background:#16181d;color:#e6e6e6;margin:24px}}
table{{border-collapse:collapse;margin-bottom:24px}}td,th{{border:1px solid #3a3f48;padding:4px 10px;text-align:left;vertical-align:top}}
tr.NG td:first-child{{color:#ff7b72}}tr.OK td:first-child{{color:#7ee787}}code{{font-size:12px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:12px}}
figure{{margin:0}}img{{width:100%;display:block;border:1px solid #3a3f48}}figcaption{{font-size:12px;color:#aab}}a{{color:inherit}}
</style>
<h1>{html.escape(title)}</h1>
<table><tr><th>mod</th><th>ビルド</th><th>クライアントのテスト</th><th>画面</th><th>失敗の中身</th></tr>{''.join(rows)}</table>
{''.join(sections)}
"""
    report = OUT / "report.html"
    report.write_text(page, encoding="utf-8")
    return report


# ---- test ----

def command_test(args):
    if args.mods:
        check_mods(args.mods)
        mods = sorted(set(args.mods))
    elif args.all:
        mods = all_mods()
    else:
        base = args.base or default_base()
        mods = mods_for_files(changed_files(base))
        if not mods:
            print(f"{base} から変わった mod はない。全部なら --all、mod を指定するなら test <mod>")
            return 0
        print(f"{base} から変わった mod: {', '.join(mods)}")

    OUT.mkdir(parents=True, exist_ok=True)
    log = OUT / "gradle.log"
    log.write_text("", encoding="utf-8")
    options = [f"-PclientSlots={args.slots}", f"-PclientTimeout={args.timeout}"]
    if args.no_sodium:
        options.append("-PnoSodium")

    tasks = [f":{m}:build" for m in mods]
    client_mods = [] if args.no_client else [m for m in mods if has_client_tests(m)]
    tasks += [f":{m}:runClientGameTest" for m in client_mods]

    started = time.time()
    lines = run_gradle(tasks, options, log)
    failed, timed_out = failed_tasks(lines)

    # 起動の途中で止まって時間切れになったものだけ、1 つずつ走らせ直す。テストの失敗は走らせ直さない
    retried = set()
    hung = [m for m in client_mods if f":{m}:runClientGameTest" in timed_out]
    if hung and not args.no_retry:
        print(f"時間切れ: {', '.join(hung)}。1 つずつ走らせ直す", flush=True)
        retry_options = ["-PclientSlots=1"] + options[1:]
        retry_lines = run_gradle([f":{m}:runClientGameTest" for m in hung], retry_options, log)
        retry_failed, retry_timed_out = failed_tasks(retry_lines)
        for m in hung:
            task = f":{m}:runClientGameTest"
            retried.add(m)
            failed.discard(task)
            timed_out.discard(task)
            if task in retry_failed:
                failed.add(task)
            if task in retry_timed_out:
                timed_out.add(task)
        lines += retry_lines

    results = []
    for mod in mods:
        build_failed = sorted(t for t in failed if t.startswith(f":{mod}:") and not t.endswith(":runClientGameTest"))
        detail = []
        if build_failed:
            build = "NG " + " ".join(t.split(":")[-1] for t in build_failed)
            detail += compile_errors(mod, lines)
            if any(t.endswith(":runGameTest") for t in build_failed):
                detail += server_failure(mod)
        else:
            build = "OK"
        task = f":{mod}:runClientGameTest"
        if mod not in client_mods:
            client = "-"
        elif task in timed_out:
            client = "時間切れ"
            detail += client_failure(mod)
        elif task in failed:
            client = "NG"
            detail += client_failure(mod)
        elif not ran(lines, task):
            client = "走らず"
        else:
            client = "OK"
        if mod in retried and client == "OK":
            client = "OK（時間切れの後に走らせ直し）"
        ok = build == "OK" and (client == "-" or client.startswith("OK"))
        results.append({"mod": mod, "build": build, "client": client, "ok": ok, "detail": detail})

    # 走らなかった mod の画面は前回のものなので出さない
    shots, sheets = collect_shots([m for m in client_mods if ran(lines, f":{m}:runClientGameTest")])
    report = write_report(results, shots, "テストの結果")

    print()
    width = max(len(m) for m in mods)
    for result in results:
        mark = "✓" if result["ok"] else "✗"
        count = len(shots.get(result["mod"], []))
        print(f"{mark} {result['mod']:<{width}}  ビルド {result['build']:<4}  クライアント {result['client']}  画面 {count}")
        for line in result["detail"]:
            print(f"    {line}")
    print()
    print(f"{int(time.time() - started)} 秒。ログ: {log.relative_to(ROOT).as_posix()}  一覧: {report.relative_to(ROOT).as_posix()}")
    for mod, files in sheets.items():
        print(f"  {mod}: " + " ".join(str(f.relative_to(ROOT).as_posix()) for f in files))
    return 0 if all(r["ok"] for r in results) else 1


# ---- shots ----

def command_shots(args):
    mods = args.mods or [m for m in all_mods() if screenshots(m)]
    check_mods(mods)
    shots, sheets = collect_shots(mods)
    if not shots:
        print("スクリーンショットがない。先に test を走らせる")
        return 1
    report = write_report([], shots, "スクリーンショット")
    print(f"一覧: {report.relative_to(ROOT).as_posix()}")
    for mod, files in sheets.items():
        print(f"  {mod}: " + " ".join(str(f.relative_to(ROOT).as_posix()) for f in files))
    return 0


# ---- status ----

def version_key(version):
    return tuple(int(x) for x in re.findall(r"\d+", version.split("+")[0]))


def last_tag(mod):
    tags = [t for t in git("tag", "-l", f"{mod}/*").split() if t]
    return max(tags, key=lambda t: version_key(t.split("/", 1)[1])) if tags else None


def unreleased(mod):
    """最後のタグの後に入った、jar に入る変更。jori は中の mod の版が変わったときも"""
    tag = last_tag(mod)
    if tag is None:
        return None, ["（まだタグがない）"]
    # ゲームテストは jar に入らないので数えない
    changes = [line for line in git("log", "--format=%h %s", f"{tag}..HEAD", "--", f"mods/{mod}", f":(exclude)mods/{mod}/src/gametest").splitlines() if line]
    if mod == BUNDLE:
        for inner in bundled_mods():
            before = mod_version(inner, tag) if git_ok("cat-file", "-e", f"{tag}:mods/{inner}/gradle.properties") else None
            now = mod_version(inner)
            if before != now:
                changes.append(f"{inner} {before or '（なし）'} → {now}")
    return tag, changes


def command_status(args):
    width = max(len(m) for m in all_mods())
    pending = 0
    for mod in all_mods():
        tag, changes = unreleased(mod)
        mark = "*" if changes else " "
        pending += bool(changes)
        print(f"{mark} {mod:<{width}}  {mod_version(mod):<8} 最後のタグ {tag or '-'}")
        for change in changes:
            print(f"      {change}")
    if pending:
        print(f"\n* はリリースしていない変更がある。python tools/dev.py release <mod> <patch|minor|major>")
    return 0


# ---- release ----

def bump(version, level):
    major, minor, patch = (int(x) for x in version.split("."))
    if level == "major":
        return f"{major + 1}.0.0"
    if level == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def release_one(mod, level, trailers, dry_run):
    version = bump(mod_version(mod), level)
    tag = f"{mod}/{version}+{minecraft_version()}"
    message = f"chore({mod}): {version} をリリース"
    if trailers:
        message += "\n\n" + "\n".join(trailers)
    print(f"{mod}: {mod_version(mod)} → {version}（{tag}）")
    if dry_run:
        return tag
    path = MODS_DIR / mod / "gradle.properties"
    text = path.read_text(encoding="utf-8")
    path.write_text(re.sub(r"^mod_version=\S+", f"mod_version={version}", text, flags=re.M), encoding="utf-8", newline="\n")
    git("add", "--", str(path.relative_to(ROOT).as_posix()))
    subprocess.run(["git", "commit", "-q", "-F", "-"], cwd=ROOT, input=message.encode("utf-8"), check=True)
    git("tag", "-a", tag, "-m", f"{mod} {version}+{minecraft_version()}")
    return tag


def jar_path(mod):
    return MODS_DIR / mod / "build" / "libs" / f"{mod}-{mod_version(mod)}+{minecraft_version()}.jar"


def untracked_sources():
    """jar に入りうる、追跡していないファイル。あるとタグと jar が食い違う。ゲームテストは jar に入らない"""
    files = git("ls-files", "--others", "--exclude-standard", "--", "mods", *SHARED_PATHS).split()
    return [f for f in files if "/src/gametest/" not in f]


def build_jars(mods):
    """タグを付けた状態で jar を作る。CI はないので、配る jar はこれ。テストは test で済ませているので走らせない"""
    tasks = [f":{m}:assemble" for m in mods]
    print("$ gradlew " + " ".join(tasks), flush=True)
    if subprocess.run(gradlew() + tasks + ["-q", "--console=plain"], cwd=ROOT).returncode != 0:
        sys.exit("jar のビルドに失敗した。リリースのコミットとタグは手元にだけあり、push はしていない")
    for mod in mods:
        jar = jar_path(mod)
        if not jar.is_file():
            sys.exit(f"{jar.relative_to(ROOT).as_posix()} ができていない。push はしていない")
        print(f"  {jar.relative_to(ROOT).as_posix()}")


def command_release(args):
    if len(args.items) % 2:
        sys.exit("mod と上げ方を組で書く（例: release sorter minor timber patch）")
    # jori は中の mod を上げた後のコミットにタグを付ける（そうしないと jori のタグに古い中の mod が入る）
    pairs = sorted(zip(args.items[0::2], args.items[1::2]), key=lambda pair: pair[0] == BUNDLE)
    check_mods([m for m, _ in pairs])
    for mod, level in pairs:
        if level not in LEVELS:
            sys.exit(f"{mod} の上げ方 {level} は patch / minor / major のどれか")
    if not args.dry_run:
        if git("status", "--porcelain", "--untracked-files=no").strip():
            sys.exit("コミットしていない変更がある。先にコミットする")
        untracked = untracked_sources()
        if untracked:
            sys.exit("追跡していないファイルがあり、jar に入るとタグと食い違う。コミットするか消す:\n  " + "\n  ".join(untracked))

    tags = [release_one(mod, level, args.trailer, args.dry_run) for mod, level in pairs]
    inner = [(m, l) for m, l in pairs if m != BUNDLE]
    # jori の jar に入る版が変わるので、jori も同じだけ上げる（一番大きい上げ方に合わせる）
    if inner and BUNDLE not in dict(pairs) and not args.no_bundle:
        level = max((l for _, l in inner), key=LEVELS.index)
        tags.append(release_one(BUNDLE, level, args.trailer, args.dry_run))

    # 中の mod のタグから後は jori の版しか変わらないので、最後のタグの状態で全部作ればどれもタグと同じ中身になる
    released = [tag.split("/", 1)[0] for tag in tags]
    if args.dry_run:
        print("jar: " + " ".join(f":{m}:assemble" for m in released))
    else:
        build_jars(released)

    if args.push and not args.dry_run:
        subprocess.run(["git", "push", "--follow-tags"], cwd=ROOT, check=True)
    elif not args.dry_run:
        print("push はまだ。git push --follow-tags")
    return 0


def main():
    parser = argparse.ArgumentParser(prog="python tools/dev.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    test = commands.add_parser("test", help="ビルドとゲームテストを走らせて結果をまとめる")
    test.add_argument("mods", nargs="*", help="走らせる mod。省くと変わった mod（中の mod が変わったら jori も）")
    test.add_argument("--all", action="store_true", help="全 mod")
    test.add_argument("--base", help="変わったかを比べる先（既定は上流のブランチ）")
    test.add_argument("--no-sodium", action="store_true", help="Sodium / Iris を入れずにバニラ描画で走らせる")
    test.add_argument("--no-client", action="store_true", help="クライアントのゲームテストを走らせない")
    test.add_argument("--no-retry", action="store_true", help="時間切れのクライアントを走らせ直さない")
    test.add_argument("--slots", type=int, default=3, help="同時に起動するクライアントの数（既定 3）")
    test.add_argument("--timeout", type=int, default=10, help="クライアント 1 つの時間切れ（分、既定 10）")
    test.set_defaults(run=command_test)

    shots = commands.add_parser("shots", help="スクリーンショットを一覧の画像と HTML にまとめる")
    shots.add_argument("mods", nargs="*")
    shots.set_defaults(run=command_shots)

    status = commands.add_parser("status", help="リリースしていない変更がある mod を出す")
    status.set_defaults(run=command_status)

    release = commands.add_parser("release", help="mod_version を上げてコミットし、タグを付けて jar を作る")
    release.add_argument("items", nargs="+", metavar="mod level", help="mod と上げ方（patch / minor / major）の組")
    release.add_argument("--no-bundle", action="store_true", help="jori を一緒に上げない")
    release.add_argument("--push", action="store_true", help="最後に git push --follow-tags する")
    release.add_argument("--trailer", action="append", default=[], help="コミットメッセージの末尾に足す行（Co-Authored-By など）")
    release.add_argument("--dry-run", action="store_true", help="何を上げるかだけ出す")
    release.set_defaults(run=command_release)

    args = parser.parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
