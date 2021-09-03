PODMANFLAGS=--cap-drop all \
	--net none \
	--userns=keep-id \
	--rm \
	--read-only \
	--memory 25m \
	--cpus 0.25 \
	--timeout 5 \
	-v "$(shell pwd):/data"

all: png

png:
	podman run $(PODMANFLAGS) \
		--security-opt seccomp="../podmanseccomp-latex.json" \
		latex-bwrap-minimal \
		/bin/bwrap-latex -no-shell-escape -interaction=nonstopmode -halt-on-error eqn.tex \
	&& podman run $(PODMANFLAGS) \
		--security-opt seccomp="../podmanseccomp-dvipng.json" \
		latex-bwrap-minimal \
		/bin/bwrap-dvipng -D 800 -T tight eqn.dvi -o eqn.png

# Generated from these steps (on Fedora 33):
#
#    $ curl -O https://kojipkgs.fedoraproject.org//packages/oci-seccomp-bpf-hook/1.2.3/1.fc33/x86_64/oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
#    $ sudo dnf install oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
#    $ sudo make container_seccomp_json # Root is needed
#
# More information: [https://www.redhat.com/sysadmin/container-security-seccomp]
container_seccomp_json:
	podman run $(PODMANFLAGS) \
		--annotation io.containers.trace-syscall="of:$(shell pwd)/podmanseccomp-bwrap-latex.json" \
		latex-bwrap-minimal \
		/bin/bwrap-latex -no-shell-escape -interaction=nonstopmode -halt-on-error eqn.tex \
	&& podman run $(PODMANFLAGS) \
		--annotation io.containers.trace-syscall="of:$(shell pwd)/podmanseccomp-bwrap-dvipng.json" \
		latex-bwrap-minimal \
		/bin/bwrap-dvipng -D 800 -T tight eqn.dvi -o eqn.png

clean:
	rm -f eqn.aux eqn.dvi eqn.log eqn.png
