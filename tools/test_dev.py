"""tools/dev.py のテスト。git や Gradle は動かさず、mods/ と記録は一時フォルダに作る。

  python -m unittest discover -s tools      （dev.py test は tools/ が変わったときにこれを走らせる）
"""

import argparse
import io
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dev  # noqa: E402

INNER = ["fullbright", "minimap", "sorter", "timber", "xray"]


class Workspace(unittest.TestCase):
    """一時フォルダにリポジトリの形（gradle.properties・mods/<mod>/gradle.properties・jori の build.gradle）を作り、dev.py の場所を差し替える"""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        (self.root / "gradle.properties").write_text("minecraft_version=26.3\njava_version=25\n", encoding="utf-8")
        self.write_mod("jori", "1.9.2", client=True)
        modules = ", ".join(f"'{m}'" for m in INNER)
        (self.root / "mods" / "jori" / "build.gradle").write_text(f"def modules = [{modules}]\n", encoding="utf-8")
        self.write_mod("fullbright", "1.1.0", client=True, vanilla=True)
        self.write_mod("minimap", "3.8.2", client=True, vanilla=True)
        self.write_mod("sorter", "1.4.0", server=True, client=True)
        self.write_mod("timber", "1.1.0", server=True)
        self.write_mod("xray", "1.3.0", client=True, vanilla=True)
        for name, value in {"ROOT": self.root, "MODS_DIR": self.root / "mods", "OUT": self.root / "build" / "dev",
                            "RUNS": self.root / "build" / "dev" / "runs"}.items():
            patcher = mock.patch.object(dev, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def write_mod(self, mod, version, server=False, client=False, vanilla=False):
        folder = self.root / "mods" / mod
        folder.mkdir(parents=True, exist_ok=True)
        lines = [f"mod_version={version}", f"server_gametest={str(server).lower()}", f"client_gametest={str(client).lower()}"]
        if vanilla:
            lines.append("vanilla_render_test=true")
        (folder / "gradle.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")


class SelectModsTest(Workspace):
    def test_mod_change_selects_the_mod_and_jori(self):
        selected = dev.select_mods({"mods/xray/src/client/java/A.java"})
        self.assertEqual(["jori", "xray"], list(selected))
        self.assertIn("中の mod の変更（xray）", selected["jori"])

    def test_jori_change_selects_only_jori(self):
        self.assertEqual(["jori"], list(dev.select_mods({"mods/jori/build.gradle"})))

    def test_shared_paths_select_every_mod(self):
        for path in ("build.gradle", "settings.gradle", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "testkit/java/A.java"):
            with self.subTest(path=path):
                self.assertEqual(dev.all_mods(), list(dev.select_mods({path})))

    def test_docs_and_tools_select_nothing(self):
        self.assertEqual({}, dev.select_mods({"docs/mods/xray.md", "README.md", "AGENTS.md", "tools/dev.py", "mods/unknown/x"}))

    def test_tools_changed(self):
        self.assertTrue(dev.tools_changed({"tools/dev.py", "docs/a.md"}))
        self.assertFalse(dev.tools_changed({"mods/xray/a", "docs/tools/a.md"}))


class PropertiesTest(Workspace):
    def test_client_tests_come_from_gradle_properties(self):
        self.assertTrue(dev.has_client_tests("sorter"))
        self.assertFalse(dev.has_client_tests("timber"))
        (self.root / "mods" / "timber" / "gradle.properties").write_text("mod_version=1.1.0\n", encoding="utf-8")
        self.assertFalse(dev.has_client_tests("timber"))

    def test_vanilla_render_test_mark(self):
        self.assertTrue(dev.vanilla_render_test("xray"))
        self.assertFalse(dev.vanilla_render_test("sorter"))

    def test_mod_version(self):
        self.assertEqual("3.8.2", dev.mod_version("minimap"))


class BumpTest(unittest.TestCase):
    def test_levels(self):
        self.assertEqual("1.2.4", dev.bump("1.2.3", "patch"))
        self.assertEqual("1.3.0", dev.bump("1.2.3", "minor"))
        self.assertEqual("2.0.0", dev.bump("1.2.3", "major"))
        self.assertEqual("1.2.3", dev.bump("1.2.3", "port"))


class PlanReleaseTest(Workspace):
    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(dev, "git_ok", return_value=False)
        self.git_ok = patcher.start()
        self.addCleanup(patcher.stop)

    def plan(self, *items, no_bundle=False):
        return dev.plan_release(argparse.Namespace(items=list(items), no_bundle=no_bundle))

    def test_inner_mod_bumps_jori_last(self):
        plan = self.plan("xray", "patch")
        self.assertEqual([("xray", "patch", "1.3.1"), ("jori", "patch", "1.9.3")], [(p["mod"], p["level"], p["new"]) for p in plan])
        self.assertEqual("jori/1.9.3+26.3", plan[-1]["tag"])

    def test_jori_follows_the_largest_level(self):
        plan = self.plan("xray", "patch", "sorter", "minor")
        self.assertEqual(("jori", "minor", "1.10.0"), (plan[-1]["mod"], plan[-1]["level"], plan[-1]["new"]))
        self.assertEqual(["sorter", "xray", "jori"], [p["mod"] for p in plan])

    def test_port_keeps_versions(self):
        plan = self.plan("xray", "port")
        self.assertEqual([("xray", "1.3.0"), ("jori", "1.9.2")], [(p["mod"], p["new"]) for p in plan])

    def test_no_bundle(self):
        self.assertEqual(["xray"], [p["mod"] for p in self.plan("xray", "patch", no_bundle=True)])

    def test_explicit_jori_level_wins(self):
        plan = self.plan("xray", "patch", "jori", "major")
        self.assertEqual(("jori", "2.0.0"), (plan[-1]["mod"], plan[-1]["new"]))

    def test_existing_tag_stops(self):
        self.git_ok.return_value = True
        with self.assertRaises(SystemExit):
            self.plan("xray", "patch")

    def test_bad_input_stops(self):
        for items in (["xray"], ["xray", "huge"], ["nothing", "patch"]):
            with self.subTest(items=items), self.assertRaises(SystemExit):
                self.plan(*items)


class AttemptTest(unittest.TestCase):
    def test_failed_task(self):
        attempt = dev.Attempt([":xray:build"], [], 1, [
            "> Task :xray:compileJava",
            "> Task :xray:build",
            "> Task :xray:runClientGameTest FAILED",
            "FAILURE: Build failed with an exception.",
            "* What went wrong:",
            "Execution failed for task ':xray:runClientGameTest'.",
        ], 10)
        self.assertTrue(attempt.passed(":xray:build"))
        self.assertIn(":xray:runClientGameTest", attempt.failed)
        self.assertFalse(attempt.timed_out)
        self.assertFalse(attempt.unexplained())

    def test_timeout(self):
        attempt = dev.Attempt([], [], 1, [
            "> Task :minimap:runClientGameTest",
            "* What went wrong:",
            "Execution failed for task ':minimap:runClientGameTest'.",
            "> Timeout has been exceeded",
        ], 600)
        self.assertEqual({":minimap:runClientGameTest"}, attempt.timed_out)
        self.assertEqual("FAILED", attempt.outcomes[":minimap:runClientGameTest"])

    def test_up_to_date_is_passed(self):
        attempt = dev.Attempt([], [], 0, ["> Task :xray:build UP-TO-DATE"], 1)
        self.assertTrue(attempt.passed(":xray:build"))
        self.assertFalse(attempt.ran(":xray:runClientGameTest"))

    def test_failure_outside_tasks(self):
        attempt = dev.Attempt([], [], 1, [
            "FAILURE: Build failed with an exception.",
            "* What went wrong:",
            "A problem occurred evaluating project ':xray'.",
            "> Could not find method foo()",
            "* Try:",
            "> Run with --stacktrace option.",
        ], 3)
        self.assertTrue(attempt.unexplained())
        self.assertEqual(["A problem occurred evaluating project ':xray'.", "> Could not find method foo()"], attempt.what_went_wrong())


def make_args(**overrides):
    values = dict(mods=[], all=False, base=None, scenario=[], no_sodium=False, no_vanilla=False, no_client=False, slots=3, timeout=10)
    values.update(overrides)
    return argparse.Namespace(**values)


class PlanTestTest(Workspace):
    def test_hooked_mods_also_run_in_vanilla(self):
        plan = dev.plan_test(make_args(mods=["xray", "sorter", "timber"]))
        self.assertEqual(["sorter", "xray"], plan["client_mods"])
        first, second = plan["passes"]
        self.assertEqual(dev.SODIUM, first["render"])
        self.assertNotIn("-PnoSodium", first["options"])
        self.assertIn(":timber:build", first["tasks"])
        self.assertEqual((dev.VANILLA, ["xray"], [":xray:runClientGameTest"]), (second["render"], second["client_mods"], second["tasks"]))
        self.assertIn("-PnoSodium", second["options"])

    def test_no_sodium_runs_only_vanilla(self):
        plan = dev.plan_test(make_args(mods=["xray"], no_sodium=True))
        self.assertEqual([dev.VANILLA], [step["render"] for step in plan["passes"]])
        self.assertIn("-PnoSodium", plan["passes"][0]["options"])

    def test_no_vanilla_skips_the_second_pass(self):
        plan = dev.plan_test(make_args(mods=["xray"], no_vanilla=True))
        self.assertEqual(1, len(plan["passes"]))

    def test_options_are_built_by_name(self):
        plan = dev.plan_test(make_args(mods=["minimap"], scenario=["map", "hud"], slots=2, timeout=7))
        self.assertEqual(["-PclientSlots=2", "-PclientTimeout=7", "-Pscenarios=map,hud"], plan["passes"][0]["options"])
        self.assertEqual(["-PclientSlots=1", "-PclientTimeout=7", "-PnoSodium", "-Pscenarios=map,hud"],
                         dev.gradle_options(plan, dev.VANILLA, slots=1))


def fake_pass(render, lines, clients):
    attempt = dev.Attempt([], [], 0, lines, 1)
    return {"render": render, "attempts": [attempt], "main": attempt, "clients": clients, "shots": {}}


class ModResultTest(unittest.TestCase):
    def test_both_renders_must_pass(self):
        first = fake_pass(dev.SODIUM, ["> Task :xray:build", "> Task :xray:runClientGameTest"], {"xray": {"status": "OK", "detail": []}})
        second = fake_pass(dev.VANILLA, [], {"xray": {"status": "NG", "detail": ["boom"], "seconds": 30, "scenarios": {}}})
        result = dev.mod_result("xray", [first, second])
        self.assertEqual(("OK", "OK", "NG"), (result["build"], result["client"], result["vanilla"]))
        self.assertFalse(result["ok"])
        self.assertEqual(["（バニラ描画）boom"], result["detail"])

    def test_build_failure(self):
        first = fake_pass(dev.SODIUM, ["> Task :timber:runGameTest FAILED"], {})
        result = dev.mod_result("timber", [first])
        self.assertEqual("NG runGameTest", result["build"])
        self.assertEqual(("-", "-"), (result["client"], result["vanilla"]))
        self.assertFalse(result["ok"])

    def test_not_run(self):
        result = dev.mod_result("timber", [fake_pass(dev.SODIUM, [], {})])
        self.assertEqual("走らず", result["build"])


class ClientTimingsTest(Workspace):
    def test_reads_game_log(self):
        log = self.root / "mods" / "minimap" / "build" / "run" / "clientGameTest" / "logs" / "latest.log"
        log.parent.mkdir(parents=True)
        log.write_text("\n".join([
            "[23:59:50] [main/INFO] (FabricLoader) Loading 80 mods",
            "[23:59:58] [Client gametest/INFO] (testkit) scenario hud: done in 41.5 s",
            "  at some.stack.Line",
            "[00:01:10] [Client gametest/INFO] (testkit) scenario map: failed in 70.0 s",
            "[00:01:20] [Render thread/INFO] (Minecraft) Stopping!",
        ]) + "\n", encoding="utf-8")
        self.assertEqual({"seconds": 90, "scenarios": {"hud": 41.5, "map": 70.0}}, dev.client_timings("minimap"))

    def test_describe(self):
        self.assertEqual("OK（90 秒: hud 42・map 70）", dev.describe_client("OK", {"seconds": 90, "scenarios": {"hud": 41.5, "map": 70.0}}))
        self.assertEqual("走らず", dev.describe_client("走らず", None))


class ReleaseCheckTest(Workspace):
    def record(self, mod, key, client="OK", vanilla="-", filtered=False, render=dev.SODIUM, ok=True):
        return {"id": f"{mod}-{key}", "content": {"keys": {mod: key}}, "filtered": filtered, "plan": {"render": render},
                "results": [{"mod": mod, "ok": ok, "client": client, "vanilla": vanilla}]}

    def test_matching_record(self):
        record = self.record("sorter", "abc")
        self.assertIs(record, dev.passing_record("sorter", "abc", [record]))

    def test_rejects_other_content_filters_and_failures(self):
        for record in (self.record("sorter", "old"), self.record("sorter", "abc", filtered=True),
                       self.record("sorter", "abc", ok=False), self.record("sorter", "abc", render=dev.VANILLA),
                       self.record("sorter", "abc", client="-")):
            with self.subTest(record=record):
                self.assertIsNone(dev.passing_record("sorter", "abc", [record]))

    def test_old_records_without_content_do_not_count(self):
        record = self.record("sorter", "abc")
        del record["filtered"]
        self.assertIsNone(dev.passing_record("sorter", "abc", [record]))

    def test_hooked_mod_needs_vanilla(self):
        self.assertIsNone(dev.passing_record("xray", "k", [self.record("xray", "k")]))
        self.assertIsNotNone(dev.passing_record("xray", "k", [self.record("xray", "k", vanilla="OK")]))

    def test_server_only_mod_needs_no_client(self):
        self.assertIsNotNone(dev.passing_record("timber", "k", [self.record("timber", "k", client="-")]))

    def test_records_are_read_newest_first(self):
        dev.RUNS.mkdir(parents=True)
        for name in ("20261101-000000", "20261102-000000"):
            (dev.RUNS / f"{name}.json").write_text(json.dumps({"id": name}), encoding="utf-8")
        (dev.RUNS / "20261103-000000.json").write_text("{broken", encoding="utf-8")
        self.assertEqual(["20261102-000000", "20261101-000000"], [r["id"] for r in dev.test_records()])

    def test_content_keys_cover_jar_and_tests(self):
        listing = "\n".join([
            "100644 blob 1111\tLICENSE",
            "100644 blob 2222\tbuild.gradle",
            "040000 tree 3333\tmods/sorter",
            "040000 tree 4444\tmods/xray",
            "040000 tree 5555\ttestkit",
        ])
        with mock.patch.object(dev, "git", return_value=listing) as git:
            keys = dev.content_keys(["sorter", "xray", "jori"], "tree")
        self.assertIn("mods/sorter", git.call_args.args)
        self.assertNotIn("docs", git.call_args.args)
        self.assertEqual(3, len(set(keys.values())))
        changed = listing.replace("4444", "9999")
        with mock.patch.object(dev, "git", return_value=changed):
            again = dev.content_keys(["sorter", "xray", "jori"], "tree")
        # xray が変わると、xray と、xray を入れる jori だけが変わる
        self.assertEqual(keys["sorter"], again["sorter"])
        self.assertNotEqual(keys["xray"], again["xray"])
        self.assertNotEqual(keys["jori"], again["jori"])


class CommandReleaseTest(Workspace):
    """release は版を書き換える前に、テストの記録を確かめて止まる。git と Gradle は差し替える"""

    def setUp(self):
        super().setUp()
        self.keys = {"xray": "x", "jori": "j"}
        for name, value in {"git_ok": False, "content_tree": "tree", "git": "", "untracked_sources": []}.items():
            patcher = mock.patch.object(dev, name, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(dev, "content_keys", side_effect=lambda mods, tree: {m: self.keys[m] for m in mods})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.set_version = mock.patch.object(dev, "set_version", side_effect=lambda mod, version: dev.MODS_DIR / mod / "gradle.properties").start()
        self.build_jars = mock.patch.object(dev, "build_jars", return_value=False).start()
        self.addCleanup(mock.patch.stopall)

    def release(self, **overrides):
        values = dict(items=["xray", "patch"], no_bundle=False, dry_run=False, push=False, trailer=[], no_test_check=False)
        values.update(overrides)
        with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
            try:
                dev.command_release(argparse.Namespace(**values))
            except SystemExit as stop:
                return out.getvalue(), str(stop.code)
        return out.getvalue(), None

    def write_record(self, mod, key, **result):
        dev.RUNS.mkdir(parents=True, exist_ok=True)
        record = {"id": f"20261112-00000{len(list(dev.RUNS.iterdir()))}", "content": {"keys": {mod: key}}, "filtered": False,
                  "plan": {"render": dev.SODIUM}, "results": [dict({"mod": mod, "ok": True, "client": "OK", "vanilla": "OK"}, **result)]}
        (dev.RUNS / f"{record['id']}.json").write_text(json.dumps(record), encoding="utf-8")

    def test_stops_before_bumping_without_records(self):
        out, stop = self.release()
        self.assertIn("テストを通していない: xray, jori", stop)
        self.set_version.assert_not_called()
        self.build_jars.assert_not_called()

    def test_stops_when_only_jori_is_untested(self):
        self.write_record("xray", "x")
        out, stop = self.release()
        self.assertIn("テストを通していない: jori", stop)
        self.assertIn("テスト xray: 通った記録", out)

    def test_goes_on_with_records(self):
        self.write_record("xray", "x")
        self.write_record("jori", "j")
        out, stop = self.release()
        # jar のビルドまで進む（ここでは失敗させて止める）
        self.assertIn("jar のビルドに失敗した", stop)
        self.assertEqual(2, self.set_version.call_count)

    def test_changed_content_needs_a_new_test(self):
        self.write_record("xray", "old")
        self.write_record("jori", "j")
        out, stop = self.release()
        self.assertIn("テストを通していない: xray", stop)

    def test_skip_with_option(self):
        out, stop = self.release(no_test_check=True)
        self.assertIn("jar のビルドに失敗した", stop)

    def test_dry_run_only_reports(self):
        out, stop = self.release(dry_run=True)
        self.assertIsNone(stop)
        self.assertIn("テスト jori: いまの中身で", out)
        self.set_version.assert_not_called()


class CheckMixinsTest(Workspace):
    def jar(self, files):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, text in files.items():
                archive.writestr(name, text)
        return zipfile.ZipFile(io.BytesIO(data.getvalue()))

    def test_expanded_level_passes(self):
        problems = []
        archive = self.jar({"a.mixins.json": '{"compatibilityLevel": "JAVA_25"}'})
        dev.check_mixins(archive, "a.jar", {"mixins": ["a.mixins.json"]}, problems)
        self.assertEqual([], problems)

    def test_wrong_or_unexpanded_level_and_missing_config(self):
        problems = []
        archive = self.jar({"a.mixins.json": '{"compatibilityLevel": "JAVA_21"}',
                            "b.mixins.json": '{"compatibilityLevel": "JAVA_${java_version}"}'})
        dev.check_mixins(archive, "a.jar", {"mixins": ["a.mixins.json", {"config": "b.mixins.json", "environment": "client"}, "c.mixins.json"]},
                         problems)
        self.assertEqual(4, len(problems), problems)


if __name__ == "__main__":
    unittest.main()
