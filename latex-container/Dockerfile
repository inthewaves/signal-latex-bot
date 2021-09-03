FROM alpine:latest AS builder
RUN apk update && apk add --no-cache \
    build-base\
    curl \
    gnupg \
    libseccomp-dev \
    perl \
    wget

FROM builder AS build_seccomp
WORKDIR /opt/seccompfilters/
COPY seccompgen/seccompgen.c seccompgen/Makefile ./
RUN make

FROM builder AS build_texlive
RUN addgroup latex && adduser latex -G latex -D
WORKDIR /tmp/install-texlive
COPY texlive.profile ./
RUN curl -SL ftp://tug.org/historic/systems/texlive/2021/install-tl-unx.tar.gz \
    | tar --strip-components=1 -xzC . \
    && ./install-tl --profile texlive.profile
ENV PATH=/usr/local/texlive/2021/bin/x86_64-linuxmusl:$PATH
RUN tlmgr install \
    amsmath \
    dvipng \
    latex \
    latex-bin \
    siunitx \
    tools \
    ucs \
    xcolor

FROM alpine:latest
RUN apk update && apk add --no-cache \
    bash \
    bubblewrap
RUN addgroup latex && adduser latex -G latex -D
COPY --from=build_texlive /usr/local/texlive /usr/local/texlive
WORKDIR /opt/seccompfilters/
COPY --from=build_seccomp /opt/seccompfilters/*.bpf .
COPY bwrap-dvipng bwrap-latex bwrap-dvipng /bin/
RUN chmod +x /bin/bwrap-dvipng /bin/bwrap-latex
WORKDIR /data/
RUN chown -R latex /data/
USER latex
ENV PATH=/usr/local/texlive/2021/bin/x86_64-linuxmusl:$PATH

