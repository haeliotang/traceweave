#!/usr/bin/env python3
"""Print the normalizer source lines that can influence the named continuous outputs."""

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from traceform.representation.feature_dataflow import backward_slice


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--features', required=True, help='comma-separated output names')
    parser.add_argument('--normalizer-file', type=Path, default=ROOT / 'traceform/representation/normalizer.py')
    args = parser.parse_args()
    source = args.normalizer_file.read_text()
    text = source.splitlines()
    lines = backward_slice(source, [name for name in args.features.split(',') if name])
    previous = None
    for number in lines:
        if previous is not None and number != previous + 1:
            print('    ...')
        print(f'{number:5d}  {text[number - 1]}')
        previous = number
    print(f'# {len(lines)} lines', file=sys.stderr)


if __name__ == '__main__':
    main()
