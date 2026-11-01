"""Optional administrative exporter; uploads already normalize automatically."""
import argparse
from pathlib import Path

from services.dashboard_metrics import validate_source_rows
from services.analysis_input import read_analysis_csv, write_analysis_csv


def convert(source, target, *, encoding=None):
    fields, rows = read_analysis_csv(Path(source), encoding=encoding)
    validate_source_rows(rows)
    # Validation completes before creating output. Existing files are never overwritten.
    write_analysis_csv(Path(target), fields, rows, exclusive=True)
    return len(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    parser.add_argument('--encoding', help='Optional source encoding; otherwise UTF-8/CP949 is detected automatically')
    args = parser.parse_args()
    try:
        count = convert(args.source, args.target, encoding=args.encoding)
    except (ValueError, OSError) as exc:
        parser.exit(2, f'{exc}\n')
    print(f'Converted {count} response rows')


if __name__ == '__main__':
    main()
