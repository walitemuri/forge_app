#!/usr/bin/env bash
set -Eeuo pipefail

repository_url="${1:-https://github.com/walitemuri/forge_app.git}"
deploy_dir="${FORGE_DEPLOY_DIR:-/opt/forge}"
deploy_user="${SUDO_USER:-$USER}"

if [[ "$(uname -s)" != Linux ]] || [[ ! -r /etc/os-release ]]; then
  echo "This bootstrap supports Ubuntu Linux only." >&2
  exit 1
fi

# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != ubuntu ]]; then
  echo "This bootstrap supports Ubuntu; detected ${ID:-unknown}." >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

architecture="$(dpkg --print-architecture)"
codename="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
docker_source="Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $codename
Components: stable
Architectures: $architecture
Signed-By: /etc/apt/keyrings/docker.asc"
printf '%s\n' "$docker_source" | sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$deploy_user"

if [[ ! -d "$deploy_dir/.git" ]]; then
  sudo install -d -o "$deploy_user" -g "$deploy_user" "$deploy_dir"
  git clone "$repository_url" "$deploy_dir"
fi

cd "$deploy_dir"
if [[ ! -f .env ]]; then
  cp .env.example .env
  chmod 600 .env
fi

echo
echo "Bootstrap complete. Before the first deployment:"
echo "  1. Edit $deploy_dir/.env with the real domain and a strong database password."
echo "  2. Sign out and back in so Docker group membership applies."
echo "  3. Run: cd $deploy_dir && ./deploy/deploy.sh \$(git rev-parse HEAD)"
