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
 * Derived from the JSON
 */
struct SYS_CALL allowed_sys_calls[] = {
    { SCMP_SYS(access), &NOT_END },
    { SCMP_SYS(arch_prctl), &NOT_END },
    { SCMP_SYS(brk), &NOT_END },
    { SCMP_SYS(capset), &NOT_END },
    { SCMP_SYS(chdir), &NOT_END },
    { SCMP_SYS(clone), &NOT_END },
    { SCMP_SYS(close), &NOT_END },
    { SCMP_SYS(dup2), &NOT_END },
    { SCMP_SYS(execve), &NOT_END },
    { SCMP_SYS(exit_group), &NOT_END },
    { SCMP_SYS(fchdir), &NOT_END },
    { SCMP_SYS(fcntl), &NOT_END },
    { SCMP_SYS(fstat), &NOT_END },
    { SCMP_SYS(fstatfs), &NOT_END },
    { SCMP_SYS(getcwd), &NOT_END },
    { SCMP_SYS(getdents), &NOT_END },
    { SCMP_SYS(getdents64), &NOT_END },
    { SCMP_SYS(getegid), &NOT_END },
    { SCMP_SYS(geteuid), &NOT_END },
    { SCMP_SYS(getgid), &NOT_END },
    { SCMP_SYS(getpid), &NOT_END },
    { SCMP_SYS(getppid), &NOT_END },
    { SCMP_SYS(getrlimit), &NOT_END },
    { SCMP_SYS(gettimeofday), &NOT_END },
    { SCMP_SYS(getuid), &NOT_END },
    { SCMP_SYS(lseek), &NOT_END },
    { SCMP_SYS(lstat), &NOT_END },
    { SCMP_SYS(mmap), &NOT_END },
    { SCMP_SYS(mount), &NOT_END },
    { SCMP_SYS(mprotect), &NOT_END },
    { SCMP_SYS(munmap), &NOT_END },
    { SCMP_SYS(open), &NOT_END },
    { SCMP_SYS(openat), &NOT_END },
    { SCMP_SYS(pivot_root), &NOT_END },
    { SCMP_SYS(prctl), &NOT_END },
    { SCMP_SYS(read), &NOT_END },
    { SCMP_SYS(readlink), &NOT_END },
    { SCMP_SYS(rt_sigaction), &NOT_END },
    { SCMP_SYS(rt_sigprocmask), &NOT_END },
    { SCMP_SYS(rt_sigreturn), &NOT_END },
    { SCMP_SYS(seccomp), &NOT_END },
    { SCMP_SYS(select), &NOT_END },
    { SCMP_SYS(set_robust_list), &NOT_END },
    { SCMP_SYS(set_tid_address), &NOT_END },
    { SCMP_SYS(sethostname), &NOT_END },
    { SCMP_SYS(setpgid), &NOT_END },
    { SCMP_SYS(setresgid), &NOT_END },
    { SCMP_SYS(setresuid), &NOT_END },
    { SCMP_SYS(setsid), &NOT_END },
    { SCMP_SYS(stat), &NOT_END },
    { SCMP_SYS(statx), &NOT_END },
    { SCMP_SYS(sysinfo), &NOT_END },
    { SCMP_SYS(timer_create), &NOT_END },
    { SCMP_SYS(timer_settime), &NOT_END },
    { SCMP_SYS(umask), &NOT_END },
    { SCMP_SYS(umount2), &NOT_END },
    { SCMP_SYS(unlink), &NOT_END },
    { SCMP_SYS(wait4), &NOT_END },
    { SCMP_SYS(write), &NOT_END },
    { -1, NULL },
};

int main(int argc, char *argv[])
{
    int ret_code = -1;
    
    const scmp_filter_ctx ctx = seccomp_init(SCMP_ACT_ERRNO(EPERM));
    if (ctx == NULL) {
        puts("Context failed");
        goto out;
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
    const int bpf_filter_fd = open("./latex_seccomp_filter.bpf", O_WRONLY | O_CREAT, 0644);
    if (bpf_filter_fd == -1) {
        printf("Failed to open BPF file (%s)\n", strerror(errno));
        ret_code = -errno;
        goto out;
    }
    ret_code = seccomp_export_bpf(ctx, bpf_filter_fd);
    close(bpf_filter_fd);

    const int pfc_filter_fd = open("./latex_seccomp_filter.pfc", O_WRONLY | O_CREAT, 0644);
    if (pfc_filter_fd == -1) {
        printf("Failed to open Pseudo Filter Code file (%s)\n", strerror(errno));
        ret_code = -errno;
        goto out;
    }
    ret_code = seccomp_export_pfc(ctx, pfc_filter_fd);
    close(pfc_filter_fd);
out:
    seccomp_release(ctx);
    if (ret_code == 0) {
        puts("Success");
    } else {
        printf("Failure: %d\n", -ret_code);
    }
    return -ret_code;
}
