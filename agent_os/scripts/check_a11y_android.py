#!/usr/bin/env python3
"""Check that every clickable node on the current screen has a spoken label."""
import argparse
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    subprocess.run(adb + ['shell', 'uiautomator', 'dump', '/sdcard/a11y.xml'],
                   capture_output=True, check=True)
    xml = subprocess.run(adb + ['shell', 'cat', '/sdcard/a11y.xml'],
                         capture_output=True, check=True, text=True).stdout
    subprocess.run(adb + ['shell', 'rm', '-f', '/sdcard/a11y.xml'], capture_output=True)
    nodes = re.findall(r'<node[^>]*>', xml)
    speech_classes = ('Button', 'ImageButton', 'ImageView', 'CheckBox', 'Switch', 'TextView')
    clickable = [node for node in nodes if 'clickable="true"' in node
                 and any(name in node for name in speech_classes)]
    unlabeled = []
    for node in clickable:
        text = re.search(r'text="([^"]*)"', node)
        desc = re.search(r'content-desc="([^"]*)"', node)
        if not (text and text.group(1).strip()) and not (desc and desc.group(1).strip()):
            unlabeled.append(node[:120])
    print(f'clickable={len(clickable)} unlabeled={len(unlabeled)}')
    for node in unlabeled[:5]:
        print('  ', node)
    if unlabeled:
        sys.exit(1)


if __name__ == '__main__':
    main()
