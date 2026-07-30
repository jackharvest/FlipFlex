#!/bin/sh
# setup-mtkclient.sh -- reproduce vendor/mtkclient from scratch.
#
# Not committed as a submodule on purpose: we carry two local fixes, and a
# patch file that fails to apply is a loud, readable failure, whereas a
# submodule pointing at a moved commit is a quiet one.
#
# Prerequisites (already done once on this Mac):
#   brew install libusb openssl
#
# NOT needed: macFUSE. Their README asks for it, but it is only for the
# `fs` mount command, and the patch below is what makes its absence survivable.

set -e
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/.."

[ -d vendor/mtkclient ] || git clone --depth 1 https://github.com/bkerler/mtkclient.git vendor/mtkclient

cd vendor/mtkclient
git apply --check ../mtkclient-macos-fuse.patch 2>/dev/null && \
	git apply ../mtkclient-macos-fuse.patch && echo "patch applied" || \
	echo "patch already applied (or upstream changed -- check it)"

# Python 3.10+ is required despite their README saying 3.9: heapbait.py uses
# PEP 604 `X | None` annotations, which are a syntax error before 3.10.
PY=${PY:-/opt/homebrew/bin/python3.14}
[ -d .venv ] || "$PY" -m venv .venv
.venv/bin/pip install -q --upgrade pip wheel setuptools

# pyside6/shiboken6 are in their requirements.txt but are GUI-only and are the
# most fragile part of the install. The CLI needs none of it.
.venv/bin/pip install -q pyusb pycryptodome pycryptodomex colorama pyserial \
	capstone keystone-engine unicorn mfusepy

echo
.venv/bin/python mtk.py --help >/dev/null 2>&1 && echo "mtkclient OK" || echo "mtkclient FAILED"
