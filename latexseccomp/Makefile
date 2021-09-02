all: latexseccompgen.c
	gcc -o latexseccompgen latexseccompgen.c -lseccomp && ./latexseccompgen

clean:
	rm latexseccompgen latex_seccomp_filter.bpf latex_seccomp_filter.pfc
