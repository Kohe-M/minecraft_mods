#!/usr/bin/env python3
"""開発用のコマンド。使い方は python tools/dev.py -h

  doctor   開発に要るもの（Python・git・JDK・Gradle）がそろっているかを確かめる
  test     変わった mod（または指定した mod）をビルドし、ゲームテストを走らせて結果をまとめる
  check    ソースと jar の食い違い（言語ファイル・jori の同梱一覧・文字化けなど）を確かめる
  shots    スクリーンショットを一覧の画像と HTML にまとめる（テストは走らせない）
  status   リリースしていない変更がある mod を出す
  release  mod_version を上げて jar を作り、確かめてからコミットしてタグを付ける
"""

import argparse
import html
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODS_DIR = ROOT / "mods"
OUT = ROOT / "build" / "dev"
RUNS = OUT / "runs"
KEEP_RUNS = 30
BUNDLE = "jori"
# jar の中身に響く共通の設定。変わったら全 mod
SHARED_PATHS = ("build.gradle", "settings.gradle", "gradle.properties", "gradle/")
# ゲームテストにだけ響く共通のもの。変わったら全 mod のテストを走らせるが、jar には入らない
TEST_SHARED_PATHS = ("testkit/",)
# port は版を変えずに、いまの MC のタグを付ける（MC の移植）
LEVELS = ("port", "patch", "minor", "major")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def rel(path):
    return Path(path).relative_to(ROOT).as_posix()


# ---- mod の一覧 ----

def all_mods():
    return sorted(p.name for p in MODS_DIR.iterdir() if (p / "gradle.properties").is_file())


def bundled_mods():
    return [m for m in all_mods() if m != BUNDLE]


def bundle_modules():
    """jori の build.gradle の modules（jori の jar に入れる mod）"""
    text = (MODS_DIR / BUNDLE / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"def modules = \[([^\]]*)\]", text)
    return re.findall(r"'([^']+)'", match.group(1)) if match else []


def has_client_tests(mod):
    build = MODS_DIR / mod / "build.gradle"
    return build.is_file() and re.search(r"enableClientGameTests\s*=\s*true", build.read_text(encoding="utf-8")) is not None


def mod_version(mod, ref=None):
    text = git("show", f"{ref}:mods/{mod}/gradle.properties") if ref else (MODS_DIR / mod / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^mod_version=(\S+)", text, re.M)
    return match.group(1) if match else None


def root_property(name):
    match = re.search(rf"^{re.escape(name)}=(.*)$", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M)
    return match.group(1).strip() if match else None


def minecraft_version():
    return root_property("minecraft_version")


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


def commit_exists(ref):
    return git_ok("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")


def default_base():
    upstream = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check=False).strip()
    return upstream or "origin/main"


def changed_files(base):
    files = set()
    files.update(git("diff", "--name-only", f"{base}...HEAD").split())
    files.update(git("diff", "--name-only", "HEAD").split())
    files.update(git("ls-files", "--others", "--exclude-standard").split())
    return files


def shared_prefix(path, prefixes):
    return next((p for p in prefixes if path == p or (p.endswith("/") and path.startswith(p))), None)


def select_mods(files):
    """変わったファイルから、走らせる mod と選んだわけ"""
    mods = all_mods()
    reasons = {}

    def add(mod, why):
        reasons.setdefault(mod, [])
        if why not in reasons[mod]:
            reasons[mod].append(why)

    for path in sorted(files):
        parts = path.split("/")
        shared = shared_prefix(path, SHARED_PATHS + TEST_SHARED_PATHS)
        if len(parts) > 2 and parts[0] == "mods" and parts[1] in mods:
            add(parts[1], f"mods/{parts[1]}/ の変更")
        elif shared:
            for mod in mods:
                add(mod, f"{shared} の変更（全 mod に響く）")
    # jori には中の mod の jar が入るので、どれかが変わったら jori も確かめる
    inner = sorted(set(reasons) & set(bundled_mods()))
    if inner:
        add(BUNDLE, f"中の mod の変更（{', '.join(inner)}）")
    return {mod: reasons[mod] for mod in sorted(reasons)}


# ---- gradle ----

def gradlew():
    return [str(ROOT / "gradlew.bat")] if os.name == "nt" else [str(ROOT / "gradlew")]


def decode(raw):
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp932", errors="replace")


PROGRESS = re.compile(r"^(> Task :\S+:(runClientGameTest|runGameTest|build)\b.*|BUILD (SUCCESSFUL|FAILED).*)$")
TASK_LINE = re.compile(r"^> Task (:\S+)(?: (\S.*))?$")


def read_log(path):
    """ゲームのログは JVM の既定の文字コードで書かれる（Windows では cp932 のことがある）"""
    return decode(path.read_bytes()).splitlines()


class Attempt:
    """gradle を 1 回走らせた結果。合否は終了コードとタスクの結果で決め、ログは失敗の中身を出すのに使う"""

    def __init__(self, tasks, options, exit_code, lines, seconds):
        self.tasks = tasks
        self.options = options
        self.exit_code = exit_code
        self.lines = lines
        self.seconds = seconds
        # 走ったタスクと、その結果（OK・UP-TO-DATE・FAILED など）
        self.outcomes = {}
        self.failed = set()
        self.timed_out = set()
        current = None
        for line in lines:
            match = TASK_LINE.match(line)
            if match:
                task, outcome = match.group(1), (match.group(2) or "").strip()
                if outcome == "FAILED":
                    self.failed.add(task)
                if outcome or task not in self.outcomes:
                    self.outcomes[task] = outcome or "OK"
                continue
            match = re.match(r"^Execution failed for task '(:\S+)'", line)
            if match:
                current = match.group(1)
                self.failed.add(current)
                self.outcomes[current] = "FAILED"
            elif current and "Timeout has been exceeded" in line:
                self.timed_out.add(current)

    def ran(self, task):
        return task in self.outcomes

    def passed(self, task):
        return self.ran(task) and task not in self.failed

    def unexplained(self):
        """終了コードが失敗なのに、失敗したタスクが分からない（設定の段階で落ちた、など）"""
        return self.exit_code != 0 and not self.failed

    def what_went_wrong(self, limit=12):
        out = []
        capture = False
        for line in self.lines:
            if line.startswith("* What went wrong:"):
                capture = True
                continue
            if capture:
                if line.startswith("* Try:") or line.startswith("* Exception is:") or line.startswith("====="):
                    capture = False
                    continue
                if line.strip():
                    out.append(line.strip())
                if len(out) >= limit:
                    break
        return out or [f"（gradle が終了コード {self.exit_code} で終わった。gradle のログを見る）"]

    def record(self):
        interesting = {t: o for t, o in self.outcomes.items()
                       if o == "FAILED" or t.rsplit(":", 1)[-1] in ("build", "runGameTest", "runClientGameTest", "assemble")}
        return {
            "command": ["gradlew", *self.tasks, *self.options],
            "exit_code": self.exit_code,
            "seconds": round(self.seconds, 1),
            "tasks": interesting,
            "failed": sorted(self.failed),
            "timed_out": sorted(self.timed_out),
        }


def run_gradle(tasks, options, log):
    """gradle を走らせ、全部の出力を log に書く。画面には進み具合だけ出す"""
    command = gradlew() + tasks + ["--continue", "--console=plain"] + options
    print("$ gradlew " + " ".join(tasks + options), flush=True)
    lines = []
    shown = set()
    started = time.time()
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
        out.write(f"（終了コード {process.returncode}）\n")
    return Attempt(tasks, options, process.returncode, lines, time.time() - started)


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


def write_report(results, shots, title, errors=()):
    rows = []
    for result in results:
        mod = result["mod"]
        status = "OK" if result["ok"] else "NG"
        detail = "<br>".join(html.escape(line) for line in result["detail"])
        rows.append(f"<tr class='{status}'><td>{mod}</td><td>{html.escape(result['build'])}</td><td>{html.escape(result['client'])}</td>"
                    f"<td>{len(shots.get(mod, []))}</td><td><code>{detail}</code></td></tr>")
    table = (f"<table><tr><th>mod</th><th>ビルド</th><th>クライアントのテスト</th><th>画面</th><th>失敗の中身</th></tr>{''.join(rows)}</table>"
             if results else "")
    gradle_errors = "".join(f"<pre class='NG'>{html.escape(chr(10).join(lines))}</pre>" for lines in errors)
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
tr.NG td:first-child,pre.NG{{color:#ff7b72}}tr.OK td:first-child{{color:#7ee787}}code{{font-size:12px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:12px}}
figure{{margin:0}}img{{width:100%;display:block;border:1px solid #3a3f48}}figcaption{{font-size:12px;color:#aab}}a{{color:inherit}}
</style>
<h1>{html.escape(title)}</h1>
{gradle_errors}{table}
{''.join(sections)}
"""
    report = OUT / "report.html"
    report.write_text(page, encoding="utf-8")
    return report


# ---- test ----

def plan_test(args):
    """何を、なぜ、どう走らせるか。--plan で出し、実行の記録にも残す"""
    base = None
    if args.mods:
        check_mods(args.mods)
        reasons = {m: ["指定"] for m in sorted(set(args.mods))}
    elif args.all:
        reasons = {m: ["--all"] for m in all_mods()}
    else:
        base = args.base or default_base()
        if not commit_exists(base):
            sys.exit(f"比べる先 {base} が見つからない。--base <ref> で指定するか、mod の指定か --all で走らせる")
        reasons = select_mods(changed_files(base))
    mods = list(reasons)
    client_mods = [] if args.no_client else [m for m in mods if has_client_tests(m)]
    options = [f"-PclientSlots={args.slots}", f"-PclientTimeout={args.timeout}"]
    if args.no_sodium:
        options.append("-PnoSodium")
    if args.scenario:
        options.append(f"-Pscenarios={','.join(args.scenario)}")
    tasks = [f":{m}:build" for m in mods] + [f":{m}:runClientGameTest" for m in client_mods]
    return {
        "base": base,
        "base_commit": git("rev-parse", "--short", base).strip() if base else None,
        "reasons": reasons,
        "mods": mods,
        "client_mods": client_mods,
        "render": "vanilla" if args.no_sodium else "sodium+iris",
        "scenarios": args.scenario or [],
        "tasks": tasks,
        "options": options,
    }


def print_plan(plan, command=True):
    if plan["base"]:
        print(f"比べる先: {plan['base']}（{plan['base_commit']}）")
    if not plan["mods"]:
        print("変わった mod はない。全部なら --all、mod を指定するなら test <mod>")
        return
    width = max(len(m) for m in plan["mods"])
    for mod in plan["mods"]:
        client = "クライアントも" if mod in plan["client_mods"] else "ビルドのみ"
        print(f"  {mod:<{width}}  {client:<8} {'、'.join(plan['reasons'][mod])}")
    print(f"描画: {'Sodium + Iris' if plan['render'] == 'sodium+iris' else 'バニラ'}"
          + (f"  シナリオ: {', '.join(plan['scenarios'])}" if plan["scenarios"] else ""))
    if command:
        print("$ gradlew " + " ".join(plan["tasks"] + plan["options"]))


def mod_result(mod, plan, main, retry, retried):
    build_task = f":{mod}:build"
    client_task = f":{mod}:runClientGameTest"
    failed = sorted(t for t in main.failed if t.startswith(f":{mod}:") and t != client_task)
    detail = []
    if failed:
        build = "NG " + " ".join(t.split(":")[-1] for t in failed)
        detail += compile_errors(mod, main.lines)
        if any(t.endswith(":runGameTest") for t in failed):
            detail += server_failure(mod)
    elif main.passed(build_task):
        build = "OK"
    else:
        build = "走らず"

    # 走らせ直したものは、走らせ直した回の結果だけで決める
    attempt = retry if mod in retried else main
    if mod not in plan["client_mods"]:
        client = "-"
    elif client_task in attempt.timed_out:
        client = "時間切れ"
        detail += client_failure(mod)
    elif client_task in attempt.failed:
        client = "NG"
        detail += client_failure(mod)
    elif not attempt.ran(client_task):
        client = "走らず"
    else:
        client = "OK（時間切れの後に走らせ直し）" if mod in retried else "OK"
    ok = build == "OK" and (client == "-" or client.startswith("OK"))
    return {"mod": mod, "build": build, "client": client, "ok": ok, "detail": detail}


def write_run(record):
    """実行ごとの記録。別のセッションが前の結果を取り違えないよう、コミットと成果物の場所も残す"""
    RUNS.mkdir(parents=True, exist_ok=True)
    text = json.dumps(record, ensure_ascii=False, indent=2)
    path = RUNS / f"{record['id']}.json"
    path.write_text(text, encoding="utf-8")
    (OUT / "last-run.json").write_text(text, encoding="utf-8")
    for old in sorted(RUNS.glob("*.json"))[:-KEEP_RUNS]:
        old.unlink()
    return path


def command_test(args):
    plan = plan_test(args)
    # 走らせるときは run_gradle がコマンドを出す
    print_plan(plan, command=args.plan)
    if args.plan or not plan["mods"]:
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    log = OUT / "gradle.log"
    log.write_text("", encoding="utf-8")
    started = time.time()
    record = {
        "id": time.strftime("%Y%m%d-%H%M%S"),
        "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "commit": git("rev-parse", "HEAD").strip(),
        "branch": git("rev-parse", "--abbrev-ref", "HEAD").strip(),
        "uncommitted": [line[3:] for line in git("status", "--porcelain").splitlines()],
        "plan": plan,
    }

    main = run_gradle(plan["tasks"], plan["options"], log)
    attempts = [main]

    # 起動の途中で止まって時間切れになったものだけ、1 つずつ走らせ直す。テストの失敗は走らせ直さない
    retry = None
    retried = set()
    hung = [m for m in plan["client_mods"] if f":{m}:runClientGameTest" in main.timed_out]
    if hung and not args.no_retry:
        print(f"時間切れ: {', '.join(hung)}。1 つずつ走らせ直す", flush=True)
        retry_options = ["-PclientSlots=1"] + plan["options"][1:]
        retry = run_gradle([f":{m}:runClientGameTest" for m in hung], retry_options, log)
        attempts.append(retry)
        retried = set(hung)

    results = [mod_result(mod, plan, main, retry, retried) for mod in plan["mods"]]
    # タスクの外で落ちた（設定の段階・デーモンの起動など）ときは、どのタスクも OK にできない
    errors = [a.what_went_wrong() for a in attempts if a.unexplained()]
    # ソースどうしの食い違い（言語ファイルのキー・jori の一覧など）は数秒で分かるので、いつも確かめる
    problems = check_sources()

    # 走らなかった mod の画面は前回のものなので出さない
    final = {m: (retry if m in retried else main) for m in plan["client_mods"]}
    shots, sheets = collect_shots([m for m in plan["client_mods"] if final[m].ran(f":{m}:runClientGameTest")])
    report = write_report(results, shots, "テストの結果", errors + ([problems] if problems else []))
    ok = not errors and not problems and all(r["ok"] for r in results)

    print()
    for lines in errors:
        print("✗ gradle がタスクの外で失敗した")
        for line in lines:
            print(f"    {line}")
    if problems:
        print("✗ ソースの食い違い（python tools/dev.py check）")
        for line in problems:
            print(f"    {line}")
    width = max(len(m) for m in plan["mods"])
    for result in results:
        mark = "✓" if result["ok"] else "✗"
        count = len(shots.get(result["mod"], []))
        print(f"{mark} {result['mod']:<{width}}  ビルド {result['build']:<4}  クライアント {result['client']}  画面 {count}")
        for line in result["detail"]:
            print(f"    {line}")

    record.update({
        "seconds": round(time.time() - started, 1),
        "ok": ok,
        "attempts": [a.record() for a in attempts],
        "errors": errors,
        "source_problems": problems,
        "results": [dict(r, screenshots=len(shots.get(r["mod"], []))) for r in results],
        "artifacts": {
            "log": rel(log),
            "report": rel(report),
            "screenshots": {m: rel(OUT / "screenshots" / m) for m in shots},
            "sheets": {m: [rel(f) for f in files] for m, files in sheets.items()},
        },
    })
    run = write_run(record)
    print()
    print(f"{int(time.time() - started)} 秒。ログ: {rel(log)}  一覧: {rel(report)}  記録: {rel(run)}")
    for mod, files in sheets.items():
        print(f"  {mod}: " + " ".join(rel(f) for f in files))
    return 0 if ok else 1


# ---- check ----

def load_json(data, where, problems, built=False):
    """UTF-8 として厳しく読む。built なら、組み上がったものに残ってはいけないもの（置換文字・テンプレート変数）も見る"""
    if isinstance(data, Path):
        data = data.read_bytes()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        problems.append(f"{where}: UTF-8 として読めない（{e}）")
        return None
    if "�" in text:
        problems.append(f"{where}: 置換文字（U+FFFD）がある。文字化けしている")
    if built and "${" in text:
        problems.append(f"{where}: 埋めていないテンプレート変数がある")
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        problems.append(f"{where}: JSON として読めない（{e}）")
        return None


def png_size(path):
    data = path.read_bytes()[:24]
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")


def resources(mod):
    return MODS_DIR / mod / "src" / "main" / "resources"


def source_meta(mod):
    return json.loads((resources(mod) / "fabric.mod.json").read_text(encoding="utf-8"))


def jori_features():
    text = (MODS_DIR / BUNDLE / "src" / "client" / "java" / "com" / "omuraisu" / "jori" / "client" / "JoriClient.java").read_text(encoding="utf-8")
    match = re.search(r"FEATURES = List\.of\((.*?)\);", text, re.S)
    if not match:
        return []
    block = match.group(1)
    return re.findall(r'new Feature\("(\w+)"', block) + [m.lower() for m in re.findall(r"Feature\.([A-Z_]+)", block)]


def check_sources():
    """ソースどうしの食い違い。jar を作らなくても分かるもの"""
    problems = []
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    for mod in all_mods():
        meta = load_json(resources(mod) / "fabric.mod.json", f"{mod}: fabric.mod.json", problems)
        if meta is not None:
            if meta.get("id") != mod:
                problems.append(f"{mod}: fabric.mod.json の id が {meta.get('id')}（ディレクトリ名と同じにする）")
            if meta.get("version") != "${version}":
                problems.append(f"{mod}: fabric.mod.json の version は ${{version}} にする")
        keys = {}
        for lang in ("en_us", "ja_jp"):
            path = resources(mod) / "assets" / mod / "lang" / f"{lang}.json"
            if not path.is_file():
                problems.append(f"{rel(path)} がない")
                continue
            data = load_json(path, rel(path), problems)
            if data is not None:
                keys[lang] = set(data)
        if len(keys) == 2 and keys["en_us"] != keys["ja_jp"]:
            only_en = sorted(keys["en_us"] - keys["ja_jp"])
            only_ja = sorted(keys["ja_jp"] - keys["en_us"])
            problems.append(f"{mod}: 言語ファイルのキーが合わない（en_us だけ: {only_en or 'なし'}、ja_jp だけ: {only_ja or 'なし'}）")
        icon = resources(mod) / "assets" / mod / "icon.png"
        if not icon.is_file():
            problems.append(f"{rel(icon)} がない")
        elif png_size(icon) != (128, 128):
            problems.append(f"{rel(icon)} が 128 × 128 でない（{png_size(icon)}）")
        # MC に依存するバージョンはルートの gradle.properties にだけ書く。jar を作るときに java_version で埋める
        for path in sorted((MODS_DIR / mod / "src").glob("*/resources/*.mixins.json")):
            config = load_json(path, rel(path), problems)
            if config is not None and config.get("compatibilityLevel") != "JAVA_${java_version}":
                problems.append(f"{rel(path)}: compatibilityLevel は JAVA_${{java_version}} にする（{config.get('compatibilityLevel')}）")
        if not (ROOT / "docs" / "mods" / f"{mod}.md").is_file():
            problems.append(f"docs/mods/{mod}.md がない")
        if f"[{mod}](mods/{mod})" not in readme:
            problems.append(f"README の mod 表に {mod} がない")

    modules = bundle_modules()
    if sorted(modules) != bundled_mods():
        problems.append(f"jori の build.gradle の modules {modules} が mods/ の mod {bundled_mods()} と合わない")
    features = jori_features()
    for mod in bundled_mods():
        if mod not in features:
            problems.append(f"JoriClient.FEATURES に {mod} がない")
    for lang in ("en_us", "ja_jp"):
        path = resources(BUNDLE) / "assets" / BUNDLE / "lang" / f"{lang}.json"
        data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        missing = [m for m in bundled_mods() if f"jori.description.{m}" not in data]
        if missing:
            problems.append(f"{rel(path)} に jori.description.<mod> がない: {', '.join(missing)}")
    return problems


def jar_path(mod):
    return MODS_DIR / mod / "build" / "libs" / f"{mod}-{mod_version(mod)}+{minecraft_version()}.jar"


def check_mod_json(data, where, mod, problems):
    """jar の fabric.mod.json。id・版と、テンプレート変数のない項目（名前・説明）が元と同じか"""
    meta = load_json(data, where, problems, built=True)
    if meta is None:
        return None
    version = f"{mod_version(mod)}+{minecraft_version()}"
    if meta.get("id") != mod:
        problems.append(f"{where}: id が {meta.get('id')}（{mod} のはず）")
    if meta.get("version") != version:
        problems.append(f"{where}: version が {meta.get('version')}（{version} のはず）")
    source = source_meta(mod)
    for key in ("name", "description"):
        if isinstance(source.get(key), str) and "${" not in source[key] and meta.get(key) != source[key]:
            problems.append(f"{where}: {key} が元の fabric.mod.json と違う: {meta.get(key)!r}")
    return meta


def mixin_configs(meta):
    """fabric.mod.json の mixins に並ぶ設定ファイルの名前（文字列か {"config": ...}）"""
    return [entry if isinstance(entry, str) else entry.get("config") for entry in (meta or {}).get("mixins", [])]


def check_mixins(archive, where, meta, problems):
    """jar の mixins.json。compatibilityLevel を gradle.properties の java_version で埋めてあるか"""
    expected = f"JAVA_{root_property('java_version')}"
    names = set(archive.namelist())
    for name in mixin_configs(meta):
        if name not in names:
            problems.append(f"{where}: fabric.mod.json の mixins にある {name} が入っていない")
            continue
        config = load_json(archive.read(name), f"{where}!{name}", problems, built=True)
        if config is not None and config.get("compatibilityLevel") != expected:
            problems.append(f"{where}!{name}: compatibilityLevel が {config.get('compatibilityLevel')}（{expected} のはず）")


def check_jar(mod, problems):
    """組み上がった jar。jori は同梱した jar の一覧と中身も見る"""
    jar = jar_path(mod)
    if not jar.is_file():
        problems.append(f"{rel(jar)} がない（--build で作る）")
        return
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        meta = check_mod_json(archive.read("fabric.mod.json"), f"{jar.name}!fabric.mod.json", mod, problems)
        check_mixins(archive, jar.name, meta, problems)
        for lang in ("en_us", "ja_jp"):
            path = f"assets/{mod}/lang/{lang}.json"
            if path not in names:
                problems.append(f"{jar.name}: {path} がない")
            else:
                load_json(archive.read(path), f"{jar.name}!{path}", problems, built=True)
        if mod != BUNDLE or meta is None:
            return
        expected = [f"META-INF/jars/{m}-{mod_version(m)}+{minecraft_version()}.jar" for m in bundle_modules()]
        listed = [entry.get("file") for entry in meta.get("jars", [])]
        if sorted(listed) != sorted(expected):
            problems.append(f"{jar.name}: 同梱の一覧 {listed} が modules の今の版 {expected} と合わない")
        for inner, path in zip(bundle_modules(), expected):
            if path not in names:
                problems.append(f"{jar.name}: {path} が入っていない")
                continue
            with zipfile.ZipFile(io.BytesIO(archive.read(path))) as nested:
                inner_meta = check_mod_json(nested.read("fabric.mod.json"), f"{jar.name}!{path}!fabric.mod.json", inner, problems)
                check_mixins(nested, f"{jar.name}!{path}", inner_meta, problems)


def build_jars(mods):
    """jar を作る。テストは test で済ませているので走らせない"""
    tasks = [f":{m}:assemble" for m in mods]
    print("$ gradlew " + " ".join(tasks), flush=True)
    return subprocess.run(gradlew() + tasks + ["-q", "--console=plain"], cwd=ROOT).returncode == 0


def command_check(args):
    mods = sorted(set(args.mods)) if args.mods else all_mods()
    check_mods(mods)
    if args.build and not build_jars(mods):
        print("jar のビルドに失敗した")
        return 1
    problems = check_sources()
    for mod in mods:
        if args.build or jar_path(mod).is_file():
            check_jar(mod, problems)
        else:
            print(f"- {mod}: {rel(jar_path(mod))} がないので jar は見ない（--build で作る）")
    for problem in problems:
        print(f"✗ {problem}")
    if not problems:
        print("✓ 食い違いはない")
    return 1 if problems else 0


# ---- doctor ----

def command_doctor(args):
    failures = 0

    def report(ok, name, detail, required=True):
        nonlocal failures
        mark = "✓" if ok else ("✗" if required else "-")
        failures += 0 if ok or not required else 1
        print(f"{mark} {name}: {detail}")

    report(sys.version_info >= (3, 8), "Python", platform.python_version())
    try:
        import PIL
        report(True, "Pillow", PIL.__version__, required=False)
    except ImportError:
        report(False, "Pillow", "ない。スクリーンショットの一覧の画像を作らない（pip install pillow）", required=False)

    report(shutil.which("git") is not None, "git", shutil.which("git") or "ない")
    base = default_base()
    report(commit_exists(base), "test の比べる先", base if commit_exists(base) else f"{base} がない（test --base <ref> で指定する）", required=False)

    java_home = os.environ.get("JAVA_HOME")
    launcher = (Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    report(launcher is not None and Path(launcher).is_file(), "gradlew を起動する Java", str(launcher or "ない（JAVA_HOME か PATH の java）"))

    jvmargs = root_property("org.gradle.jvmargs") or ""
    report("-Dfile.encoding=UTF-8" in jvmargs, "Gradle デーモンの文字コード",
           "UTF-8" if "-Dfile.encoding=UTF-8" in jvmargs else "gradle.properties の org.gradle.jvmargs に -Dfile.encoding=UTF-8 がない")

    print("$ gradlew -q javaToolchains", flush=True)
    result = subprocess.run(gradlew() + ["-q", "javaToolchains", "--console=plain"], cwd=ROOT, capture_output=True)
    output = decode(result.stdout + result.stderr)
    report(result.returncode == 0, "Gradle", "起動できる" if result.returncode == 0 else (output.strip().splitlines() or ["起動できない"])[-1])
    daemon = re.search(r"^toolchainVersion=(\d+)", (ROOT / "gradle" / "gradle-daemon-jvm.properties").read_text(encoding="utf-8"), re.M)
    wanted = sorted({daemon.group(1) if daemon else None, root_property("java_version")} - {None})
    found = sorted(set(re.findall(r"Language Version:\s+(\d+)", output)), key=int)
    for version in wanted:
        report(version in found, f"JDK {version}（デーモン・コンパイル）", "見つかった" if version in found else f"見つからない（見つかったのは {', '.join(found) or 'なし'}）")
    return 1 if failures else 0


# ---- shots ----

def command_shots(args):
    mods = args.mods or [m for m in all_mods() if screenshots(m)]
    check_mods(mods)
    shots, sheets = collect_shots(mods)
    if not shots:
        print("スクリーンショットがない。先に test を走らせる")
        return 1
    report = write_report([], shots, "スクリーンショット")
    print(f"一覧: {rel(report)}")
    for mod, files in sheets.items():
        print(f"  {mod}: " + " ".join(rel(f) for f in files))
    return 0


# ---- status ----

def version_key(version):
    return tuple(int(x) for x in re.findall(r"\d+", version.split("+")[0]))


def last_tag(mod):
    """HEAD の祖先にある、いまの MC のタグのうち一番新しい版。旧 MC の保守ブランチでも、そのブランチの MC のタグを見る"""
    tags = [t for t in git("tag", "--merged", "HEAD", "-l", f"{mod}/*+{minecraft_version()}").split() if t]
    return max(tags, key=lambda t: version_key(t.split("/", 1)[1])) if tags else None


def unreleased(mod):
    """最後のタグの後に入った、jar に入る変更。jori は中の mod の版が変わったときも"""
    tag = last_tag(mod)
    if tag is None:
        if git("tag", "--merged", "HEAD", "-l", f"{mod}/*").strip():
            return None, [f"（{minecraft_version()} のタグがまだない。移植だけなら release {mod} port）"]
        return None, ["（まだタグがない）"]
    # ゲームテストは jar に入らないので数えない
    changes = [line for line in git("log", "--format=%h %s", f"{tag}..HEAD", "--", f"mods/{mod}",
                                    f":(exclude)mods/{mod}/src/gametest").splitlines() if line]
    if mod == BUNDLE:
        for inner in bundle_modules():
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
        print(f"\n* はリリースしていない変更がある。python tools/dev.py release <mod> <{'|'.join(LEVELS)}>")
    return 0


# ---- release ----

def bump(version, level):
    if level == "port":
        return version
    major, minor, patch = (int(x) for x in version.split("."))
    if level == "major":
        return f"{major + 1}.0.0"
    if level == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def untracked_sources():
    """jar に入りうる、追跡していないファイル。あるとタグと jar が食い違う。ゲームテストは jar に入らない"""
    files = git("ls-files", "--others", "--exclude-standard", "--", "mods", *SHARED_PATHS).split()
    return [f for f in files if "/src/gametest/" not in f]


def plan_release(args):
    """上げる mod・版・タグを先に決める。jori は中の mod の後に置く"""
    if len(args.items) % 2:
        sys.exit("mod と上げ方を組で書く（例: release sorter minor timber patch）")
    pairs = list(zip(args.items[0::2], args.items[1::2]))
    check_mods([m for m, _ in pairs])
    for mod, level in pairs:
        if level not in LEVELS:
            sys.exit(f"{mod} の上げ方 {level} は {' / '.join(LEVELS)} のどれか")
    levels = dict(pairs)
    inner = [l for m, l in pairs if m != BUNDLE]
    # jori の jar に入る版が変わるので、jori も同じだけ上げる（一番大きい上げ方に合わせる）
    if inner and BUNDLE not in levels and not args.no_bundle:
        levels[BUNDLE] = max(inner, key=LEVELS.index)
    plan = []
    for mod in sorted(levels, key=lambda m: (m == BUNDLE, m)):
        old = mod_version(mod)
        new = bump(old, levels[mod])
        tag = f"{mod}/{new}+{minecraft_version()}"
        if git_ok("rev-parse", "--verify", "--quiet", f"refs/tags/{tag}"):
            sys.exit(f"タグ {tag} はもうある。上げ方を変える")
        plan.append({"mod": mod, "level": levels[mod], "old": old, "new": new, "tag": tag})
    return plan


def set_version(mod, version):
    path = MODS_DIR / mod / "gradle.properties"
    text = path.read_text(encoding="utf-8")
    path.write_text(re.sub(r"^mod_version=\S+", f"mod_version={version}", text, flags=re.M), encoding="utf-8", newline="\n")
    return path


def command_release(args):
    plan = plan_release(args)
    for item in plan:
        change = "版はそのまま（移植）" if item["level"] == "port" else f"{item['old']} → {item['new']}"
        print(f"{item['mod']}: {change}（{item['tag']}）")
    mods = [item["mod"] for item in plan]
    if args.dry_run:
        print("jar: " + " ".join(f":{m}:assemble" for m in mods))
        return 0
    if git("status", "--porcelain", "--untracked-files=no").strip():
        sys.exit("コミットしていない変更がある。先にコミットする")
    untracked = untracked_sources()
    if untracked:
        sys.exit("追跡していないファイルがあり、jar に入るとタグと食い違う。コミットするか消す:\n  " + "\n  ".join(untracked))

    # 版を書き換えた状態で jar を作って確かめてから、コミットしてタグを付ける。
    # 途中で失敗したら版を元に戻すので、直してから同じコマンドをもう一度走らせればよい
    bumped = [item for item in plan if item["level"] != "port"]
    paths = [rel(set_version(item["mod"], item["new"])) for item in bumped]
    committed = False
    try:
        if not build_jars(mods):
            sys.exit("jar のビルドに失敗した。版は元に戻した")
        problems = check_sources()
        for mod in mods:
            check_jar(mod, problems)
        if problems:
            sys.exit("jar を確かめて食い違いが見つかった。版は元に戻した:\n  " + "\n  ".join(problems))
        for mod in mods:
            print(f"  {rel(jar_path(mod))}")

        # 移植のタグはいまのコミットに付ける。上げた mod は 1 つずつコミットしてタグを付け、jori は中の mod の後になる
        for item in plan:
            if item["level"] == "port":
                git("tag", "-a", item["tag"], "-m", f"{item['mod']} {item['new']}+{minecraft_version()}")
        for item in bumped:
            message = f"chore({item['mod']}): {item['new']} をリリース"
            if args.trailer:
                message += "\n\n" + "\n".join(args.trailer)
            git("add", "--", f"mods/{item['mod']}/gradle.properties")
            subprocess.run(["git", "commit", "-q", "-F", "-"], cwd=ROOT, input=message.encode("utf-8"), check=True)
            committed = True
            git("tag", "-a", item["tag"], "-m", f"{item['mod']} {item['new']}+{minecraft_version()}")
    finally:
        if not committed and paths:
            git("checkout", "--", *paths)

    if args.push:
        subprocess.run(["git", "push", "--follow-tags"], cwd=ROOT, check=True)
    else:
        print("push はまだ。git push --follow-tags")
    return 0


def main():
    parser = argparse.ArgumentParser(prog="python tools/dev.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    doctor = commands.add_parser("doctor", help="開発に要るもの（Python・git・JDK・Gradle）がそろっているかを確かめる")
    doctor.set_defaults(run=command_doctor)

    test = commands.add_parser("test", help="ビルドとゲームテストを走らせて結果をまとめる")
    test.add_argument("mods", nargs="*", help="走らせる mod。省くと変わった mod（中の mod が変わったら jori も）")
    test.add_argument("--all", action="store_true", help="全 mod")
    test.add_argument("--base", help="変わったかを比べる先（既定は上流のブランチ）")
    test.add_argument("--plan", action="store_true", help="何をなぜ走らせるかだけ出す")
    test.add_argument("--scenario", action="append", default=[], help="クライアントのゲームテストのシナリオを絞る（何度でも。mod の文書を見る）")
    test.add_argument("--no-sodium", action="store_true", help="Sodium / Iris を入れずにバニラ描画で走らせる")
    test.add_argument("--no-client", action="store_true", help="クライアントのゲームテストを走らせない")
    test.add_argument("--no-retry", action="store_true", help="時間切れのクライアントを走らせ直さない")
    test.add_argument("--slots", type=int, default=3, help="同時に起動するクライアントの数（既定 3）")
    test.add_argument("--timeout", type=int, default=10, help="クライアント 1 つの時間切れ（分、既定 10）")
    test.set_defaults(run=command_test)

    check = commands.add_parser("check", help="ソースと jar の食い違いを確かめる")
    check.add_argument("mods", nargs="*", help="jar を見る mod。省くと全部")
    check.add_argument("--build", action="store_true", help="先に jar を作る")
    check.set_defaults(run=command_check)

    shots = commands.add_parser("shots", help="スクリーンショットを一覧の画像と HTML にまとめる")
    shots.add_argument("mods", nargs="*")
    shots.set_defaults(run=command_shots)

    status = commands.add_parser("status", help="リリースしていない変更がある mod を出す")
    status.set_defaults(run=command_status)

    release = commands.add_parser("release", help="mod_version を上げて jar を作り、確かめてからコミットしてタグを付ける")
    release.add_argument("items", nargs="+", metavar="mod level", help=f"mod と上げ方（{' / '.join(LEVELS)}）の組")
    release.add_argument("--no-bundle", action="store_true", help="jori を一緒に上げない")
    release.add_argument("--push", action="store_true", help="最後に git push --follow-tags する")
    release.add_argument("--trailer", action="append", default=[], help="コミットメッセージの末尾に足す行（Co-Authored-By など）")
    release.add_argument("--dry-run", action="store_true", help="何を上げるかだけ出す")
    release.set_defaults(run=command_release)

    args = parser.parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
