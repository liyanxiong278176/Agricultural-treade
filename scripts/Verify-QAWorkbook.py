"""Independently inspect the actual exported OOXML workbook with Python stdlib."""
import json
import os
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

root = Path(os.environ['REGRESSION_REPORT_DIR']) / 'evidence' if os.environ.get('REGRESSION_REPORT_DIR') else Path(__file__).resolve().parents[1] / '.local' / 'e2e' / 'evidence'
ns = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
expected = json.loads((root / 'payroll-expected.json').read_text(encoding='utf-8'))
with ZipFile(root / 'payroll-2025-08.xlsx') as book:
    assert book.testzip() is None, 'ZIP checksum failed'
    workbook = ET.fromstring(book.read('xl/workbook.xml'))
    sheets = [s.attrib['name'] for s in workbook.findall('s:sheets/s:sheet', ns)]
    assert sheets == ['工资汇总', '已确认结算快照'], sheets
    strings = [''.join(s.itertext()) for s in ET.fromstring(book.read('xl/sharedStrings.xml')).findall('s:si', ns)]
    def rows(sheet):
        result = []
        for row in ET.fromstring(book.read(sheet)).findall('s:sheetData/s:row', ns):
            cells = []
            for cell in row.findall('s:c', ns):
                value = cell.findtext('s:v', '', ns)
                cells.append(strings[int(value)] if cell.attrib.get('t') == 's' else value)
            result.append(cells)
        return result
    summary = rows('xl/worksheets/sheet1.xml')
    worker = next(r for r in summary if r[0] == expected['code'])
    for index, key in [(2, 'pieces'), (4, 'earned'), (5, 'earned'), (7, 'paid'), (8, 'unpaid')]:
        assert float(worker[index]) == expected[key], (key, worker[index], expected[key])
    details = rows('xl/worksheets/sheet2.xml')
    assert len(details) >= 3, 'Missing settlement snapshots'
result = {'status': 'PASS', 'sheets': sheets, 'workerCode': expected['code'], 'actualRow': worker,
          'checks': ['ZIP CRC', 'OOXML parsing', 'two sheets', 'pieces=7', 'earned=21', 'settled=21', 'paid=10', 'unpaid=11', 'settlement snapshots']}
(root / 'workbook-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
