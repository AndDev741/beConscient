#!/usr/bin/env bash
# Builds the backend and frontend images and pushes them to a registry that
# listens only on the server's loopback (127.0.0.1:5000), through an SSH tunnel.
# The cluster pulls from localhost:5000 over plain HTTP, which containerd allows
# for loopback registries, so no registry credentials or root access are needed.
#
# Usage: deploy/scripts/publish-images.sh <ssh-host> [backend|frontend|all] [--jvm]
#
# Prints the pushed image references with their digests.
set -euo pipefail

host="${1:?usage: publish-images.sh <ssh-host> [backend|frontend|all] [--jvm]}"
what="${2:-all}"
jvm="${3:-}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tag="$(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet || echo -dirty)"
port=15000

ssh -o ExitOnForwardFailure=yes -fN -L "$port:127.0.0.1:5000" "$host"
tunnel_pid="$(pgrep -f "ssh -o ExitOnForwardFailure=yes -fN -L $port:127.0.0.1:5000 $host" | head -n1)"
trap 'kill "$tunnel_pid" 2>/dev/null || true' EXIT

push() {
  local name="$1"
  docker tag "$name:$tag" "localhost:$port/$name:$tag"
  docker tag "$name:$tag" "localhost:$port/$name:latest"
  docker push -q "localhost:$port/$name:$tag" >/dev/null
  docker push -q "localhost:$port/$name:latest" >/dev/null
  local digest
  digest="$(docker inspect --format '{{index .RepoDigests 0}}' "localhost:$port/$name:$tag" | sed 's/.*@//')"
  echo "localhost:5000/$name@$digest ($tag)"
}

if [[ "$what" == all || "$what" == backend ]]; then
  if [[ "$jvm" == --jvm ]]; then
    (cd "$root/backend" && ./mvnw -B -q package -DskipTests)
    docker build -q -f "$root/backend/src/main/docker/Dockerfile.jvm" -t "beconscient-backend:$tag" "$root/backend" >/dev/null
  else
    # x86-64-v2: the target node has no AVX2.
    (cd "$root/backend" && ./mvnw -B -q package -DskipTests -Dnative \
      -Dquarkus.native.container-build=true -Dquarkus.native.march=x86-64-v2)
    docker build -q -f "$root/backend/src/main/docker/Dockerfile.native-micro" -t "beconscient-backend:$tag" "$root/backend" >/dev/null
  fi
  push beconscient-backend
fi

if [[ "$what" == all || "$what" == frontend ]]; then
  docker build -q -t "beconscient-frontend:$tag" "$root/frontend" >/dev/null
  push beconscient-frontend
fi
