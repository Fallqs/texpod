import configparser
import fnmatch
from pathlib import Path
import runpy
import unittest


class NimblePsramMiddlewareTest(unittest.TestCase):
    def test_all_s3_ble_targets_share_psram_without_changing_sticky_core(self):
        config = configparser.ConfigParser(interpolation=None)
        config.read(Path(__file__).resolve().parents[2] / "platformio.ini")
        devices = ("sticky", "x4pro", "x4c", "papermono", "lilygo_t5s3", "murphy_m4", "waveshare_epaper_397", "metalio_eink4")
        for device in devices:
            hardware = f"{device}_hardware"
            for option in ("lib_deps", "extra_scripts", "build_flags"):
                self.assertIn(f"${{s3_ble_psram.{option}}}", config[hardware][option])
            for name in config.sections():
                if name.startswith("env:") and config[name].get("extends") == hardware:
                    with self.subTest(target=name):
                        self.assertIn(f"${{{hardware}.build_flags}}", config[name]["build_flags"])
                        self.assertNotIn("lib_deps", config[name])
                        self.assertNotIn("extra_scripts", config[name])
        self.assertEqual(
            config["sticky_hardware"]["custom_sdkconfig"].split(),
            ["${firmware_tuned.custom_sdkconfig}", "${s3_ble_controller.custom_sdkconfig}"],
        )
        self.assertNotIn("BOARD_HAS_PSRAM", config["sticky_hardware"]["build_flags"])
        self.assertEqual(config["eego_a4_hardware"]["extends"], "base")
        self.assertEqual(config["eego_a4_hardware"]["board_build.arduino.memory_type"], "dio_opi")
        self.assertNotIn("custom_sdkconfig", config["eego_a4_hardware"])
        source = (Path(__file__).resolve().parents[2] / "src/main.cpp").read_text()
        self.assertIn("SET_LOOP_TASK_STACK_SIZE(24 * 1024)", source)
        sdk_ui = (Path(__file__).resolve().parents[2] /
                  "freeink-sdk/libs/ui/FreeInkUI/src/FreeInkUI.cpp").read_text()
        self.assertIn("getArduinoLoopTaskStackSize(void) { return 16 * 1024; }", sdk_ui)
        self.assertIn("CONFIG_BT_CONTROLLER_ONLY=y", config["s3_ble_controller"]["custom_sdkconfig"])
        self.assertIn("CONFIG_BT_NIMBLE_ENABLED=n", config["s3_ble_controller"]["custom_sdkconfig"])

    def test_generic_base_and_simulators_do_not_inherit_radio_configuration(self):
        config = configparser.ConfigParser(interpolation=None)
        config.read(Path(__file__).resolve().parents[2] / "platformio.ini")
        for name in ("base", "firmware_tuned", "env:simulator", "env:simulator_x3",
                     "env:simulator_eego_a4", "env:simulator_murphy_m4"):
            with self.subTest(profile=name):
                for value in config[name].values():
                    self.assertNotIn("s3_ble", value)
                    self.assertNotIn("FREEINK_CAP_BLE_HID_HOST", value)
                    self.assertNotIn("CROSSPOINT_BLE_HOST_PSRAM", value)
                    self.assertNotIn("NimBLE-Arduino", value)

    def test_nimble_and_texpod_share_config_without_affecting_other_sources(self):
        root = Path(__file__).resolve().parents[2]

        class BuildEnv(dict):
            def GetProjectOption(self, name, default):
                return self.get(name, default)

            def subst(self, value):
                return str(root) if value == "$PROJECT_DIR" else value

            def AddBuildMiddleware(self, callback, pattern):
                self["middleware"][pattern] = callback

            def Object(self, node, **kwargs):
                self.object_flags = kwargs["CCFLAGS"]
                return [node]

            def Depends(self, node, config):
                self.dependency = (node, config)

        for header in ("NimbleC3Config.h", "NimblePsramConfig.h"):
            with self.subTest(header=header):
                relative_config = f"src/platform/{header}"
                env = BuildEnv(CCFLAGS=["-Os"], middleware={})
                if header == "NimbleC3Config.h":
                    env["custom_nimble_config"] = relative_config
                runpy.run_path(
                    str(root / "scripts/configure_nimble_psram.py"),
                    init_globals={"env": env, "Import": lambda _: None},
                )

                def matching(path):
                    return [callback for pattern, callback in env["middleware"].items()
                            if fnmatch.fnmatch(str(root / path), pattern)]

                for path in (".pio/libdeps/default/NimBLE-Arduino/src/nimble/mem.c",
                             "lib/TextPlayer/NimbleTransport.cpp"):
                    callbacks = matching(path)
                    self.assertEqual(len(callbacks), 1)
                    node = object()
                    self.assertIs(callbacks[0](env, node), node)
                    config = str(root / relative_config)
                    self.assertIs(env.dependency[0], node)
                    self.assertEqual(Path(env.dependency[1]), Path(config))
                    self.assertEqual(env.object_flags[:2], ["-Os", "-include"])
                    self.assertEqual(Path(env.object_flags[2]), Path(config))
                self.assertEqual(env["CCFLAGS"], ["-Os"])
                self.assertFalse(matching("src/BleInput.cpp"))
                self.assertFalse(matching("framework/cores/esp32/esp32-hal-bt.c"))


if __name__ == "__main__":
    unittest.main()
