#include <errno.h>
#include <fcntl.h>
#include <seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>


struct SYS_CALL {
    int sys_call_value;
    int *null_if_done;
};

static int NOT_END = 0;

/**
 * These are syscalls that bwrap uses, derived from manual testing.
 */
struct SYS_CALL allowed_bwrap_syscalls[] = {
    { SCMP_SYS(exit_group), &NOT_END }, // without this, bwrap-latex and bwrap-dvipng will hang forever
    { SCMP_SYS(wait4), &NOT_END } // without this, bwrap-latex and bwrap-dvipng will fail
};

/**
 * allowed_latex_syscalls and allowed_dvipng_syscalls are generated as follows:
 *
 * Inside of the container with --privileged (also modify Dockerfile so that the user is root), run this in
 * latex-container/sandbox:
 *
 *     podman run -it --privileged --rm -v "$PWD:/data" latex-bwrap-minimal /bin/bash
 *
 *  Use the results of
 *
 *     apk add --no-cache strace
 *     strace -c -S name latex -no-shell-escape -interaction=nonstopmode -halt-on-error eqn.tex
 *     strace -c -S name dvipng -D 800 -T tight eqn.dvi -o eqn.png
 *
 */
struct SYS_CALL allowed_latex_syscalls[] = {
    { SCMP_SYS(access), &NOT_END },
    { SCMP_SYS(arch_prctl), &NOT_END },
    { SCMP_SYS(brk), &NOT_END },
    { SCMP_SYS(clock_gettime), &NOT_END },
    { SCMP_SYS(close), &NOT_END },
    { SCMP_SYS(execve), &NOT_END },
    { SCMP_SYS(fcntl), &NOT_END },
    { SCMP_SYS(getcwd), &NOT_END },
    { SCMP_SYS(getdents64), &NOT_END },
    { SCMP_SYS(ioctl), &NOT_END },
    { SCMP_SYS(lseek), &NOT_END },
    { SCMP_SYS(lstat), &NOT_END },
    { SCMP_SYS(madvise), &NOT_END },
    { SCMP_SYS(mmap), &NOT_END },
    { SCMP_SYS(mprotect), &NOT_END },
    { SCMP_SYS(munmap), &NOT_END },
    { SCMP_SYS(open), &NOT_END },
    { SCMP_SYS(read), &NOT_END },
    { SCMP_SYS(readlink), &NOT_END },
    { SCMP_SYS(rt_sigaction), &NOT_END },
    { SCMP_SYS(rt_sigprocmask), &NOT_END },
    { SCMP_SYS(set_tid_address), &NOT_END },
    { SCMP_SYS(stat), &NOT_END },
    { SCMP_SYS(unlink), &NOT_END },
    { SCMP_SYS(writev), &NOT_END },
    { -1, NULL }
};

struct SYS_CALL allowed_dvipng_syscalls[] = {
    { SCMP_SYS(access), &NOT_END },
    { SCMP_SYS(arch_prctl), &NOT_END },
    { SCMP_SYS(brk), &NOT_END },
    { SCMP_SYS(close), &NOT_END },
    { SCMP_SYS(execve), &NOT_END },
    { SCMP_SYS(fcntl), &NOT_END },
    { SCMP_SYS(fstat), &NOT_END },
    { SCMP_SYS(getdents64), &NOT_END },
    { SCMP_SYS(ioctl), &NOT_END },
    { SCMP_SYS(lseek), &NOT_END },
    { SCMP_SYS(lstat), &NOT_END },
    { SCMP_SYS(madvise), &NOT_END },
    { SCMP_SYS(mmap), &NOT_END },
    { SCMP_SYS(mprotect), &NOT_END },
    { SCMP_SYS(munmap), &NOT_END },
    { SCMP_SYS(open), &NOT_END },
    { SCMP_SYS(read), &NOT_END },
    { SCMP_SYS(set_tid_address), &NOT_END },
    { SCMP_SYS(stat), &NOT_END },
    { SCMP_SYS(writev), &NOT_END },
    { -1, NULL }
};

int create_seccomp_files(const char *base_rule_name, struct SYS_CALL allowed_sys_calls[])
{
    printf("Creating seccomp files for %s\n", base_rule_name);
    int ret_code = -1;
    const scmp_filter_ctx ctx = seccomp_init(SCMP_ACT_ERRNO(EPERM));
    if (ctx == NULL) {
        puts("Context failed");
        return errno;
    }

    int i = 0;
    while (1) {
        struct SYS_CALL current_sys_call = allowed_sys_calls[i];
        if (current_sys_call.null_if_done == NULL) {
            break;
        }
        ret_code = seccomp_rule_add(ctx, SCMP_ACT_ALLOW, current_sys_call.sys_call_value, 0);
        if (ret_code != 0) {
            printf("Failed to add rule %d (%s)\n", i, strerror(-ret_code));
            goto out;
        }
        i++;
    }

    i = 0;
    while (1) {
        struct SYS_CALL current_bwrap_sys_call = allowed_bwrap_syscalls[i];
        if (current_bwrap_sys_call.null_if_done == NULL) {
            break;
        }
        ret_code = seccomp_rule_add(ctx, SCMP_ACT_ALLOW, current_bwrap_sys_call.sys_call_value, 0);
        if (ret_code != 0) {
            printf("Failed to add bwrap rule %d (%s)\n", i, strerror(-ret_code));
            goto out;
        }
        i++;
    }

    char buf[1000];
    if (snprintf(buf, 1000, "%s.bpf", base_rule_name) < 0) {
        printf("Failed to get bpf filename (%s)\n", strerror(errno));
        ret_code = -errno;
        goto out;
    }

    const int bpf_filter_fd = open(buf, O_WRONLY | O_CREAT | O_EXCL, 0644);
    if (bpf_filter_fd == -1) {
        printf("Failed to create BPF file %s (%s)\n", buf, strerror(errno));
        ret_code = -errno;
        goto out;
    }
    ret_code = seccomp_export_bpf(ctx, bpf_filter_fd);
    close(bpf_filter_fd);
    if (ret_code != 0) {
        printf("Failed to write to %s.bpf (%s)\n", base_rule_name, strerror(-ret_code));
        goto out;
    }

    if (snprintf(buf, 1000, "%s.pfc", base_rule_name) < 0) {
        printf("Failed to get pfc filename (%s)\n", strerror(errno));
        ret_code = -errno;
        goto out;
    }
    const int pfc_filter_fd = open(buf, O_WRONLY | O_CREAT | O_EXCL, 0644);
    if (pfc_filter_fd == -1) {
        printf("Failed to open Pseudo Filter Code file %s (%s)\n", buf, strerror(errno));
        ret_code = -errno;
        goto out;
    }
    ret_code = seccomp_export_pfc(ctx, pfc_filter_fd);
    close(pfc_filter_fd);
    if (ret_code != 0) {
        printf("Failed to write %s.pfc (%s)\n", base_rule_name, strerror(-ret_code));
        goto out;
    }
out:
    seccomp_release(ctx);
    return ret_code;
}

int main(int argc, char *argv[])
{
    int ret_code = -1;

    ret_code = create_seccomp_files("seccomp-latex", allowed_latex_syscalls);
    if (ret_code != 0) {
        goto out;
    }

    ret_code = create_seccomp_files("seccomp-dvipng", allowed_dvipng_syscalls);
    if (ret_code != 0) {
        goto out;
    }

out:
    if (ret_code == 0) {
        puts("Success");
    } else {
        printf("Failure: %d\n", -ret_code);
    }
    return -ret_code;
}