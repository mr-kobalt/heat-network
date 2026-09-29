#!/usr/bin/env python3
"""Собирает reference-pptx для pandoc на базе его дефолтного шаблона.

Применяет тему организатора (шрифт Montserrat, палитра ЛЦТ 2026) к
`reference.pptx`, который pandoc печатает через `--print-default-data-file`.
Файл pandoc не распространяется в репозитории — только используется локально.

Использование:
    pandoc --print-default-data-file reference.pptx > default.pptx
    python3 scripts/build-presentation-reference.py default.pptx reference.pptx
"""
import re
import sys
import zipfile

CLR_SCHEME = (
    '<a:clrScheme name="LCT2026">'
    '<a:dk1><a:sysClr val="windowText" lastClr="1C1D22"/></a:dk1>'
    '<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>'
    '<a:dk2><a:srgbClr val="310F53"/></a:dk2>'
    '<a:lt2><a:srgbClr val="FFD6E4"/></a:lt2>'
    '<a:accent1><a:srgbClr val="FF0053"/></a:accent1>'
    '<a:accent2><a:srgbClr val="310F53"/></a:accent2>'
    '<a:accent3><a:srgbClr val="8A83D1"/></a:accent3>'
    '<a:accent4><a:srgbClr val="FC3777"/></a:accent4>'
    '<a:accent5><a:srgbClr val="FFD6E4"/></a:accent5>'
    '<a:accent6><a:srgbClr val="1C1D22"/></a:accent6>'
    '<a:hlink><a:srgbClr val="FF0053"/></a:hlink>'
    '<a:folHlink><a:srgbClr val="520978"/></a:folHlink>'
    '</a:clrScheme>'
)
FONT = 'Montserrat'


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    source, target = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if re.match(r'ppt/theme/theme\d+\.xml$', item.filename):
                xml = data.decode('utf-8')
                xml = re.sub(r'<a:clrScheme.*?</a:clrScheme>', CLR_SCHEME, xml, flags=re.S)
                xml = xml.replace('typeface="Calibri"', 'typeface="%s"' % FONT)
                data = xml.encode('utf-8')
            zout.writestr(item, data)
    print('[presentation] reference <- %s (тема: %s, #FF0053)' % (source, FONT))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
