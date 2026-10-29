# The Linux userland that runs inside the Android app (under proot): Ubuntu with the OpenCode server and the tools
# agents and MCP servers expect. Build for arm64: docker build --platform linux/arm64 ...
ARG UBUNTU_TAG=24.04
FROM ubuntu:${UBUNTU_TAG}

ARG NODE_LINE=22
ENV DEBIAN_FRONTEND=noninteractive

# apt first: the base packages everything else relies on.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl wget git openssh-client ripgrep jq unzip zip xz-utils bzip2 less nano procps file tzdata \
      python3 python3-pip python3-venv locales gnupg libatomic1 \
 && rm -rf /var/lib/apt/lists/*

# Node.js from nodejs.org (the distribution's own package is too old for current MCP servers and plugins). The newest
# release of the line is found through SHASUMS256.txt and the download is checked against it.
RUN set -eux; \
    base="https://nodejs.org/dist/latest-v${NODE_LINE}.x"; \
    cd /tmp; \
    curl -fsSLO "$base/SHASUMS256.txt"; \
    file="$(grep -oE "node-v[0-9]+\.[0-9]+\.[0-9]+-linux-arm64\.tar\.xz" SHASUMS256.txt | head -1)"; \
    test -n "$file"; \
    curl -fsSLO "$base/$file"; \
    grep " $file\$" SHASUMS256.txt | sha256sum -c -; \
    tar -xJf "$file" -C /usr/local --strip-components=1 --no-same-owner --exclude='*/share/doc' --exclude='*/share/man'; \
    rm -f "$file" SHASUMS256.txt /usr/local/CHANGELOG.md /usr/local/README.md; \
    node --version; npm --version

# uv and uvx (Python tools and MCP servers started with uvx), checked against the checksum published with the release.
RUN set -eux; \
    cd /tmp; \
    name="uv-aarch64-unknown-linux-gnu.tar.gz"; \
    curl -fsSLO "https://github.com/astral-sh/uv/releases/latest/download/$name"; \
    curl -fsSL "https://github.com/astral-sh/uv/releases/latest/download/$name.sha256" -o "$name.sha256"; \
    sha256sum -c "$name.sha256"; \
    tar -xzf "$name" -C /usr/local/bin --strip-components=1 --no-same-owner; \
    rm -f "$name" "$name.sha256"; \
    uv --version

# The OpenCode server and the overlay with its Android instructions.
COPY opencode /usr/local/bin/opencode
COPY overlay/ /
RUN chmod 0755 /usr/local/bin/opencode \
 && mkdir -p /root /root/Download /tmp \
 && chmod 1777 /tmp \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /root/.cache /root/.npm
