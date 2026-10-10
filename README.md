# DevEco Assistant mod

This is a modified HUAWEI DevEco Assistant 1.2.1.600 that can install and uninstall HAP apps on a watch using commands from a computer. The watch must be connected in Huawei Health. To let the re-signed DevEco app access Health, install [WearEngine Bridge](https://github.com/RandGor/wearengine-bridge), a separate LSPosed/Vector module, and enable it for Huawei Health.

## Usage

Because it has a different signature, the modified APK cannot be installed over the official DevEco app. Uninstalling the official app may erase its data.

Copy the HAP to `/sdcard/haps/`, then request installation:

```sh
adb shell am broadcast -n com.huawei.deveco.assistant/com.huawei.deveco.assistant.automation.HapRequestReceiver -a com.huawei.deveco.assistant.action.HAP_REQUEST --es request_id build-001 --es operation install --es hap_path /sdcard/haps/example-watch.hap
```

Check the result using the same `request_id`:

```sh
adb shell am broadcast -n com.huawei.deveco.assistant/com.huawei.deveco.assistant.automation.HapRequestReceiver -a com.huawei.deveco.assistant.action.HAP_STATUS --es request_id build-001
```

To uninstall an app, provide its actual `bundleName` on the watch:

```sh
adb shell am broadcast -n com.huawei.deveco.assistant/com.huawei.deveco.assistant.automation.HapRequestReceiver -a com.huawei.deveco.assistant.action.HAP_REQUEST --es request_id remove-001 --es operation uninstall --es bundle_name com.example.watchapp
```

Replace `hap_path`, `bundle_name`, and `request_id` with your own values. Keep the component after `-n` and the action after `-a` as shown. `operation=probe` checks the file and watch connection without installing anything. The response to `HAP_REQUEST` only confirms that the command was received. Query `HAP_STATUS` for the final result (`INSTALL_SUCCEEDED`, `UNINSTALL_SUCCEEDED`, `*_FAILED`, or `*_UNKNOWN`). For details, filter Logcat by the `DevEcoHapMod` tag.

## Build

You need Python 3.10+, JDK 17+, Apktool 2.11.1, and Android SDK platform 35 with build-tools. Pass the original APK for version 1.2.1.600 (SHA-256 `1215e81240a65336c4adb3149aa29a4af6755c032a16366972e1e29b116c39b6`):

```sh
python3 build.py --original-apk /path/to/original.apk
```

On Windows, use `py -3` instead of `python3`. If needed, specify `--sdk-root`, `--build-tools`, `--jdk`, `--apktool`, or `--cache-dir`. Decoded and intermediate files are kept in a user cache outside the repository; the resulting APK is written to `output/`. To build an installable APK, add `--keystore /path/to/key.p12 --password-file /path/to/password-file` and, if needed, `--ks-key-alias ALIAS`. Without these options, the APK remains unsigned. Use the same signing key for updates.

The repository contains only Java patch sources, SDK signatures needed for compilation, and build scripts. It does not contain the original APK, decoded files, signing keys, or built APKs. The build has been tested on Windows; macOS and Linux have not yet been tested.

The receiver requires `android.permission.DUMP` and is intended for commands sent through `adb shell`. It supports one connected watch and one operation at a time. The watch protocol has no `request_id`, so installing through the DevEco UI at the same time could mix up responses.
