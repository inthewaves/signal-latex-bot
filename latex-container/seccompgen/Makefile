all: seccompgen
	./seccompgen

seccompgen:
	gcc -o seccompgen seccompgen.c -lseccomp

clean:
	rm seccompgen *.bpf *.pfc
