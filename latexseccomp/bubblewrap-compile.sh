#!/usr/bin/env bash
# Use bubblewrap to run /bin/sh reusing the host OS binaries (/usr), but with
# separate /tmp, /home, /var, /run, and /etc.
LATEX_CMD=$(cat <<-END
  latex -no-shell-escape -interaction=nonstopmode -halt-on-error eqn.tex && dvipng -D 1200 -T tight eqn.dvi -o eqn.png
END
)

set -euo pipefail
(exec bwrap \
      --ro-bind /usr/bin/sh /usr/bin/sh \
      --ro-bind /usr/bin/latex /usr/bin/latex \
      --ro-bind /usr/bin/dvipng /usr/bin/dvipng \
      --ro-bind /usr/share/fonts /usr/share/fonts \
      --ro-bind /usr/share/texlive /usr/share/texlive \
      --ro-bind /usr/lib /usr/lib \
      --ro-bind /usr/lib64 /usr/lib64 \
      --ro-bind /var/lib/texmf /var/lib/texmf \
      --dir /tmp \
      --dir /var \
      --setenv openout_any "p" \
      --setenv openin_any "p" \
      --bind "$HOME/sandbox" /sandbox \
      --symlink usr/lib /lib \
      --symlink usr/lib64 /lib64 \
      --symlink usr/bin /bin \
      --symlink usr/bin /sbin \
      --unshare-all \
      --die-with-parent \
      --seccomp 12 \
      --chdir /sandbox \
      --new-session \
      --cap-drop ALL \
      /bin/sh -c "$LATEX_CMD") \
    12< <(cat latex_seccomp_filter.bpf)
