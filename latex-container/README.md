# latex-container

A sandboxed container for generating LaTeX from arbitrary user input. Tested only with Fedora 34 with SELinux enabled.

## Setup

We must first use the `user_tmp_t` type, as the bot will be using a temporary directory as the mounted volume for the
containers it will create for LaTeX PNG generation. 

On your local machine, run in the `signal-latex-bot` repository root:

```
rsync -rpcv --delete --chmod=D755,F755 \
    latex-container \
    <yourserveruser>@<IP or hostname>:/home/<yourserveruser>/latex-container
```

Then, on the server, inside of `/home/<yourserveruser>/latex-container`, run as root

```
make
semodule -i latex_container.cil base_container.cil
```

Then, generate seccomp filters for the container based on the current system, using the base filters
(`podman-{latex,dvipng}-base.json`) to create a seccomp filter for the current system.

The hook needs to be installed first. On Fedora 32+, you can run `dnf install -y oci-seccomp-bpf-hook`. For other
distributions, you can try building from source (instructions at
https://www.redhat.com/sysadmin/container-security-seccomp). After the hook is installed, the following commands should
be run as root:

```bash
cd sandbox/
setenforce 0
make container_seccomp_json 
setenforce 1
```

If you see an error involving "CPU", follow these steps:
https://github.com/containers/podman/blob/84694170402ff699065382ba2d2fb172c3b6c88f/troubleshooting.md. It comes down to

> You can verify whether CPU limit delegation is enabled by running the following command:
>
>     cat "/sys/fs/cgroup/user.slice/user-$(id -u).slice/user@$(id -u).service/cgroup.controllers"
> 
> Example output might be:
>
>     memory pids
> 
> In the above example, `cpu` is not listed, which means the current user does not have permission to set CPU limits.
> 
> If you want to enable CPU limit delegation for all users, you can create the file
> `/etc/systemd/system/user@.service.d/delegate.conf` with the contents:
> 
>     [Service]
>     Delegate=memory pids cpu io
> 
> After logging out and logging back in, you should have permission to set CPU limits.

Check that `podman-latex.json` and `podman-dvipng.json` exist in the  `latex-sandbox` directory.

Remove the leftover container with `podman ps --all` to find it and `podman rm ID` to remove it.

The bot creates a temporary directory and mounts it to a new container for every LaTeX PNG request. Test that everything
works running these steps as a non-root user:

1. Copying `sandbox` to a temporary directory (`mktemp -d`)
2. `cd` into it
3. Run `make png_withselinux`

## Regenerating SELinux module

This isn't needed unless there's something wrong with the current policy.

Install [Udica](https://github.com/containers/udica) and the 
[`--append-rules` parameter](https://github.com/containers/udica/commit/40742ebaa2f459c40cf9617b7e81d18efed776a6)
in order to add denials from AVC.

Create a container.json by running the following as a *non-root* user:

```bash
make persistent_container
```

This will launch a detached container and show its ID in stdout. Create a `container.json` file using the ID via
`podman inspect ID > container.json`. Remove the container after with `podman rm ID`. 

Now, it's time to regenerate the SELinux policy. We first use a default policy from Udica so that we can catch all the
denial. Run as root to install the default policy for the container: 

```
udica -j container.json latex_container
semodule -i latex_container.cil base_container.cil
```

Run the following as root to regenerate the SELinux policy using the failures:

```bash
semodule -DB
setenforce 0
make png_withselinux
setenforce 1
semodule -B
cat /var/log/audit/audit.log | grep -i -e "latex_container\.process" > avc_file
udica -j container.json --append-rules avc_file latex_container
sed -i 's/user_home_t/user_tmp_t/g' latex_container.cil 
```

Explanation:

* `semodule -DB`: We are first making sure we show the silenced AVC denials by disabling `dontaudit` rules, rebuilding
  after to make sure it takes effect. This ensures we get all the rules we need.
* `setenforce 0` Enabling SELinux to run in permissive mode so that we can catch all the denials at once. 
* `make`: This runs the build process, which will fill up `/var/log/audit/audit.log` with denials that we need to allow.
* We turn enforcement back on with `setenforce 1` and turn back the `dontaudit` rules on with `semodule -B`
* Then use Udica to regenerate the SELinux policy.
* Since the bot will mount volumes with the `user_tmp_t` context, we replace `user_home_t` with `user_tmp_t`.
  * Why we didn't just copy `latex-container` to a temporary directory: There's a bug with Udica (or the way the system
    I used is setup) that results in Udica failing to generate a policy for a container that has a volume mounted to a
    temporary directory.

If changes need to be committed, copy `base_container.cil` from `/usr/share/udica/templates/base_container.cil` (or
refer to https://github.com/containers/udica/blob/main/udica/templates/base_container.cil) in this directory so that the
policy can be used without neeing Udica installed. Then, save the .cil files to your local machine and commit them to
the Git repository.

### Useful troubleshooting

* `dnf install policycoreutils-python-utils` for `semanage`, `audit2why`, etc.
* https://access.redhat.com/documentation/en-us/red_hat_enterprise_linux/8/html/using_selinux/troubleshooting-problems-related-to-selinux_using-selinux
