"""uiautomator の画面の書き出しから、文字が一致する N 番目の部品の真ん中の座標を「x y」で出す。
使い方: python3 tap_text.py ui.xml "＋ 追加" 0
見つからない時は、画面を下へ送る必要があるので、何も出さない。
"""
import re
import sys
import xml.etree.ElementTree as ET

path, text, index = sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0
hits = []
for node in ET.parse(path).iter("node"):
    if node.get("text") == text or node.get("content-desc") == text:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) > index:
    print(*hits[index])
