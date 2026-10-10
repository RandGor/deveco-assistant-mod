import argparse
from pathlib import Path

RECEIVER = """        <receiver android:name="com.huawei.deveco.assistant.automation.HapRequestReceiver" android:exported="true" android:permission="android.permission.DUMP">
            <intent-filter>
                <action android:name="com.huawei.deveco.assistant.action.HAP_REQUEST"/>
                <action android:name="com.huawei.deveco.assistant.action.HAP_STATUS"/>
            </intent-filter>
        </receiver>
"""
SERVICE = '        <service android:name="com.huawei.deveco.assistant.automation.HapProbeService" android:exported="false"/>\n'
PERMISSION = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>\n'

parser = argparse.ArgumentParser(description="Patch an external Apktool decode of DevEco Assistant")
parser.add_argument("decode_dir", type=Path)
args = parser.parse_args()
manifest_path = args.decode_dir / "AndroidManifest.xml"
layout_path = args.decode_dir / "res/layout/fragment_home.xml"

manifest = manifest_path.read_text(encoding="utf-8")
if 'android.permission.FOREGROUND_SERVICE' not in manifest:
    anchor = '    <uses-permission android:name="android.permission.NFC"/>'
    if manifest.count(anchor) != 1:
        raise SystemExit("Cannot find permission insertion point")
    manifest = manifest.replace(anchor, PERMISSION + anchor, 1)
if 'com.huawei.deveco.assistant.automation.HapRequestReceiver' not in manifest:
    anchor = '        <meta-data android:name="com.huawei.hms.client.appid"'
    if manifest.count(anchor) != 1:
        raise SystemExit("Cannot find receiver insertion point")
    manifest = manifest.replace(anchor, RECEIVER + anchor, 1)
manifest = manifest.replace(
    'android:name="com.huawei.deveco.assistant.automation.HapRequestReceiver" android:exported="true">',
    'android:name="com.huawei.deveco.assistant.automation.HapRequestReceiver" android:exported="true" android:permission="android.permission.DUMP">'
)
if 'com.huawei.deveco.assistant.action.HAP_STATUS' not in manifest:
    old = '<action android:name="com.huawei.deveco.assistant.action.HAP_REQUEST"/>'
    new = old + '\n                <action android:name="com.huawei.deveco.assistant.action.HAP_STATUS"/>'
    if manifest.count(old) != 1:
        raise SystemExit("Cannot find status action insertion point")
    manifest = manifest.replace(old, new, 1)
if 'com.huawei.deveco.assistant.automation.HapProbeService' not in manifest:
    anchor = '        <receiver android:name="com.huawei.deveco.assistant.automation.HapRequestReceiver"'
    if manifest.count(anchor) != 1:
        raise SystemExit("Cannot find service insertion point")
    manifest = manifest.replace(anchor, SERVICE + anchor, 1)
if manifest.count('android:label="@string/app_name"') != 1:
    raise SystemExit("Cannot find original application label")
ORIGINAL_STARTUP = 'android:name="com.huawei.deveco.assistant.activity.StartUpActivity"'
MOD_STARTUP = 'android:name="com.huawei.deveco.assistant.automation.ModStartUpActivity"'
if ORIGINAL_STARTUP in manifest:
    if manifest.count(ORIGINAL_STARTUP) != 1:
        raise SystemExit("Unexpected number of launcher activities")
    manifest = manifest.replace(ORIGINAL_STARTUP, MOD_STARTUP, 1)
elif manifest.count(MOD_STARTUP) != 1:
    raise SystemExit("Cannot find launcher activity")
layout = layout_path.read_text(encoding="utf-8")
original_toolbar = '    <include android:id="@id/hwToolbar" layout="@layout/toolbar_main" />'
legacy_toolbar = '''    <android.widget.Toolbar android:id="@id/hwToolbar" android:fitsSystemWindows="true" android:layout_width="fill_parent" android:layout_height="wrap_content">
        <TextView android:layout_gravity="end|center_vertical" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginEnd="16.0dip" android:paddingLeft="7.0dip" android:paddingTop="3.0dip" android:paddingRight="7.0dip" android:paddingBottom="3.0dip" android:background="@drawable/log_list_bg" android:backgroundTint="#ff3478f6" android:text="Mod by egor20002012" android:textColor="#ffffffff" android:textSize="10.0sp" android:textStyle="bold" android:singleLine="true" />
    </android.widget.Toolbar>'''
patched_toolbar = legacy_toolbar.replace('<TextView ', '<com.huawei.deveco.assistant.automation.ModLinkView ', 1)
if layout.count(original_toolbar) == 1:
    layout = layout.replace(original_toolbar, patched_toolbar, 1)
elif layout.count(legacy_toolbar) == 1:
    layout = layout.replace(legacy_toolbar, patched_toolbar, 1)
elif layout.count(patched_toolbar) != 1:
    raise SystemExit("Cannot find home toolbar insertion point")
manifest_path.write_text(manifest, encoding="utf-8", newline="\n")
layout_path.write_text(layout, encoding="utf-8", newline="\n")
print("Manifest and home layout patched")
