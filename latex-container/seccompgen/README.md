# seccompgen

This program is intended to be copied inside a Podman container so that it can build a seccomp filter (.bpf) in order
for the nested bwrap calls to use it.

Related: https://github.com/containers/bubblewrap/issues/284 ("Document running nested in docker/podman")