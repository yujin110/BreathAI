"""Automatic upload adaptation for analysis CSVs and original chatting_log exports."""
import csv
import io
import os


CANONICAL_COLUMNS = {'번호', '질문', '날짜'}
RAW_COLUMNS = {'seq_num', 'kind', 'msg_m', 'sysdate'}
ALIASES = {'seq_num': '번호', 'msg_m': '질문', 'sysdate': '날짜'}


def read_analysis_csv(path, *, encoding=None):
    content = path.read_bytes()
    if encoding:
        text = content.decode(encoding)
    else:
        try:
            text = content.decode('utf-8-sig')
        except UnicodeDecodeError:
            text = content.decode('cp949')
    reader = csv.DictReader(io.StringIO(text, newline=''), strict=True)
    fields = [name.replace('\n', '') for name in (reader.fieldnames or [])]
    reader.fieldnames = fields
    columns = set(fields)
    if CANONICAL_COLUMNS <= columns:
        return fields, list(reader)
    if not RAW_COLUMNS <= columns:
        raise ValueError('Expected analysis columns 번호, 질문, 날짜 or chatting_log columns seq_num, kind, msg_m, sysdate; daily aggregates are not conversation logs')
    if CANONICAL_COLUMNS & columns:
        raise ValueError('Ambiguous mixture of analysis and chatting_log column names')
    if not {'logType', 'keywordMatchPercent', 'ai_percent'} & columns:
        raise ValueError('chatting_log requires logType or a keyword matching score')
    output_fields = [ALIASES.get(name, name) for name in fields]
    rows = []
    for row in reader:
        kind = (row.get('kind') or '').strip()
        if kind == '0':
            continue
        if kind != '1':
            raise ValueError(f'CSV line {reader.line_num}: kind must be 0 or 1')
        rows.append({ALIASES.get(name, name): value for name, value in row.items()})
    return output_fields, rows


def write_analysis_csv(path, fields, rows, *, exclusive=False):
    with path.open('x' if exclusive else 'w', encoding='utf-8-sig', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
        output.flush()
        os.fsync(output.fileno())
