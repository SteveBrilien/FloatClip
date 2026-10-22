"""Reuse the approved desktop markup and CSS as the installed application's UI."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
source=(root/'docs/ui/floatclip-desktop-review.html').read_text(encoding='utf-8')
destination=root/'windows/FloatClip.Next/UI';destination.mkdir(parents=True,exist_ok=True)
markup=source.split('<style>')[0].replace('FloatClip 桌面交互预览','FloatClip').replace('添加演示文件','添加文件')
markup=re.sub(r'<input type="file"[^>]+/>','',markup)
css=re.search(r'<style>(.*?)</style>',source,re.S).group(1)
(destination/'approved.css').write_text(css,encoding='utf-8')
head='''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"><title>FloatClip 0.8.0</title><link rel="stylesheet" href="approved.css"><link rel="stylesheet" href="desktop.css"></head><body>'''
(destination/'index.html').write_text(head+markup+'<script src="lucide.min.js"></script><script src="app.js"></script></body></html>',encoding='utf-8')
print('Prepared installed desktop HTML/CSS from the approved design.')
