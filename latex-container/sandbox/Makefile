PODMANFLAGS=--cap-drop all \
	--net none \
	--userns=keep-id \
	--read-only \
	--memory 25m \
	--cpus 0.25 \
	--timeout 5 \
	-v "$(shell pwd):/data"

LATEXOPTS=-no-shell-escape -no-parse-first-line -interaction=nonstopmode -halt-on-error eqn.tex

DVIPNGOPTS=-D 800 -T tight eqn.dvi -o eqn.png

all: png

png:
	podman run $(PODMANFLAGS) \
		--rm \
		--security-opt seccomp="../podman-latex.json" \
		latex-bwrap-minimal \
		/bin/bwrap-latex $(LATEXOPTS) \
	&& podman run $(PODMANFLAGS) \
		--rm \
		--security-opt seccomp="../podman-dvipng.json" \
		latex-bwrap-minimal \
		/bin/bwrap-dvipng $(DVIPNGOPTS)

png_withselinux:
	podman run $(PODMANFLAGS) \
		--security-opt seccomp="../podman-latex.json" \
		--security-opt label=type:latex_container.process \
		latex-bwrap-minimal \
		/bin/bwrap-latex $(LATEXOPTS) \
	&& podman run $(PODMANFLAGS) \
		--security-opt seccomp="../podman-dvipng.json" \
		--security-opt label=type:latex_container.process \
		latex-bwrap-minimal \
		/bin/bwrap-dvipng $(DVIPNGOPTS)

persistent_container:
	podman run $(PODMANFLAGS) \
		--detach \
		--security-opt seccomp="../podman-latex.json" \
		latex-bwrap-minimal \
		/bin/bwrap-latex $(LATEXOPTS)

# Generated from these steps. On Fedora 34, just run dnf install oci-seccomp-bpf-hook. On Fedora 33:
#
#    $ curl -O https://kojipkgs.fedoraproject.org//packages/oci-seccomp-bpf-hook/1.2.3/1.fc33/x86_64/oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
#    $ sudo dnf install oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
#
# Then, run sudo make container_seccomp_json. Root is needed according to the limitations given in
# https://github.com/containers/oci-seccomp-bpf-hook. You should remove the podman containers after.
# More information: [https://www.redhat.com/sysadmin/container-security-seccomp]
container_seccomp_json:
	podman run $(PODMANFLAGS) \
		--annotation io.containers.trace-syscall="if:$(shell pwd)/../podman-latex-base.json;of:$(shell pwd)/../podman-latex.json" \
		latex-bwrap-minimal \
		/bin/bwrap-latex $(LATEXOPTS) \
	&& podman run $(PODMANFLAGS) \
		--annotation io.containers.trace-syscall="if:$(shell pwd)/../podman-dvipng-base.json;of:$(shell pwd)/../podman-dvipng.json" \
		latex-bwrap-minimal \
		/bin/bwrap-dvipng $(DVIPNGOPTS)

clean:
	rm -f eqn.aux eqn.dvi eqn.log eqn.png
