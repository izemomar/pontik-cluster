# SPDX-License-Identifier: MPL-2.0

# --------------------------------------------------------------------------
# Development Image
# --------------------------------------------------------------------------
#
# This image gives you everything you need to build Pontik Cluster: JDK 21,
# the Docker CLI with Compose for integration testing, and a warmed Gradle
# distribution. Your workspace is mounted at runtime by devcontainer.json.
#
# The base image is pinned by digest, so the tag can never move underneath
# you. Resolved 2026-08-16: eclipse-temurin 21.0.11_10-jdk-noble, published
# 2026-08-04.
#

FROM eclipse-temurin:21-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15

LABEL org.opencontainers.image.title="pontik-cluster development environment" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/izemomar/pontik-cluster"

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8

# --------------------------------------------------------------------------
# System Packages
# --------------------------------------------------------------------------
#
# Here we install the handful of tools the build and the integration tests
# call directly. If you add a package, make sure something really uses it.
#

RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y \
        ca-certificates \
        curl \
        git \
        jq \
        make; \
    rm -rf /var/lib/apt/lists/*

# --------------------------------------------------------------------------
# Docker CLI and Compose
# --------------------------------------------------------------------------
#
# Only the clients are installed here. The daemon is your host's, reached
# over a mounted socket, so test containers run beside this one instead of
# inside it.
#
# Each download is verified against a pinned checksum. If a release is ever
# republished under the same version, the build will fail instead of quietly
# accepting it. The CLI checksums were resolved 2026-08-16, and the Compose
# checksums come from the release's checksums.txt, resolved 2026-09-27.
#

ARG DOCKER_CLI_VERSION=29.5.3
ARG DOCKER_CLI_SHA256_ARM64=02eea44490f1f39ce340fe0bc74124002d957bacaa574d66345fbb353381362d
ARG DOCKER_CLI_SHA256_AMD64=34eea64e9c3435f5af1b760827a56a561cd67fc2d6e9cd1813b8bb1e3ff7930b
ARG COMPOSE_VERSION=5.5.1
ARG COMPOSE_SHA256_ARM64=732e3a84c1a0f67256ce80bc2598a24546b10ca05f9faa97efceb1171ece2ef7
ARG COMPOSE_SHA256_AMD64=db1889184726840f75c4f9c001048430d4f25b3be3cb084d3ddd762bc0aed576
ARG TARGETARCH

RUN set -eux; \
    arch="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    case "${arch}" in \
        arm64) dockerArch='aarch64'; cliSha="${DOCKER_CLI_SHA256_ARM64}"; composeSha="${COMPOSE_SHA256_ARM64}" ;; \
        amd64) dockerArch='x86_64';  cliSha="${DOCKER_CLI_SHA256_AMD64}"; composeSha="${COMPOSE_SHA256_AMD64}" ;; \
        *) echo "unsupported architecture: ${arch}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/docker.tgz \
        "https://download.docker.com/linux/static/stable/${dockerArch}/docker-${DOCKER_CLI_VERSION}.tgz"; \
    echo "${cliSha}  /tmp/docker.tgz" | sha256sum -c -; \
    tar -xzf /tmp/docker.tgz -C /tmp docker/docker; \
    install -o root -g root -m 0755 /tmp/docker/docker /usr/local/bin/docker; \
    curl -fsSL -o /tmp/docker-compose \
        "https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}/docker-compose-linux-${dockerArch}"; \
    echo "${composeSha}  /tmp/docker-compose" | sha256sum -c -; \
    install -o root -g root -m 0755 -D /tmp/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose; \
    rm -rf /tmp/docker.tgz /tmp/docker /tmp/docker-compose; \
    docker --version; \
    docker compose version

# --------------------------------------------------------------------------
# Gradle Home
# --------------------------------------------------------------------------
#
# A named volume is mounted here at runtime, so your resolved dependencies
# survive a container rebuild. On first use, Docker copies the distribution
# warmed below into the empty volume.
#

ENV GRADLE_USER_HOME=/opt/gradle-home
RUN mkdir -p "${GRADLE_USER_HOME}"

# --------------------------------------------------------------------------
# Gradle Distribution
# --------------------------------------------------------------------------
#
# Here we warm the Gradle distribution from the wrapper, so your first build
# doesn't have to download it. The wrapper verifies the download against the
# distributionSha256Sum in gradle-wrapper.properties. The copied files are
# removed afterwards, since the workspace arrives as a mount.
#

WORKDIR /tmp/warm
COPY gradlew ./
COPY gradle/wrapper/ ./gradle/wrapper/
RUN set -eux; \
    ./gradlew --version; \
    cd /; \
    rm -rf /tmp/warm

WORKDIR /workspace

# The container stays alive by default, so you can start it once and exec into it.
CMD ["sleep", "infinity"]
