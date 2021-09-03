FROM alpine:latest AS builder
RUN apk update && apk add --no-cache \
    curl \
    gnupg \
    perl \
    wget
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
    xcolor

FROM alpine:latest
RUN apk update && apk add --no-cache \
    bash \
    bubblewrap
RUN addgroup latex && adduser latex -G latex -D
COPY --from=builder /usr/local/texlive /usr/local/texlive
#COPY --from=builder /home/latex/.texlive2021 /home/latex/.texlive2021
#COPY --from=builder /home/latex/texmf /home/latex/texmf
WORKDIR /data/
RUN chown -R latex /data/
USER latex
ENV PATH=/usr/local/texlive/2021/bin/x86_64-linuxmusl:$PATH

