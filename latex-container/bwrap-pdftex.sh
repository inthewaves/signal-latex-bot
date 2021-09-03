#!/usr/bin/env bash

set -euo pipefail
(exec bwrap \
      --ro-bind /usr/local/texlive/texmf-local /usr/local/texlive/texmf-local \
      --ro-bind /usr/local/texlive/2021/bin/x86_64-linuxmusl/pdflatex /usr/local/texlive/2021/bin/x86_64-linuxmusl/pdflatex \
      --ro-bind /usr/local/texlive/2021/texmf-config /usr/local/texlive/2021/texmf-config \
      --ro-bind /usr/local/texlive/2021/texmf-dist /usr/local/texlive/2021/texmf-dist \
      --ro-bind /usr/local/texlive/2021/texmf-var /usr/local/texlive/2021/texmf-var \
      --ro-bind /lib /lib \
      --dir /tmp \
      --dir /var \
      --setenv openout_any "p" \
      --setenv openin_any "p" \
      --bind "$PWD" /sandbox \
      --symlink /usr/local/texlive/2021/bin/x86_64-linuxmusl/pdflatex /bin/pdflatex \
      --unshare-all \
      --die-with-parent \
      --chdir /sandbox \
      --new-session \
      --cap-drop all \
      --seccomp 12 \
      /bin/pdflatex "$@") \
    12< <(cat latexseccomp-pdftex.json)
