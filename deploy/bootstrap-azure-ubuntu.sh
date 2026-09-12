#!/usr/bin/env bash
set -Eeuo pipefail

repository_url="${1:-https://github.com/walitemuri/forge_app.git}"
deploy_dir="${FORGE_DEPLOY_DIR:-/opt/forge}"
deploy_user="${SUDO_USER:-$USER}"
swap_file=/swapfile

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

# The budget VM has 4 GiB RAM. Swap makes image builds and the heavier demo
# templates slower instead of letting the kernel kill them under short spikes.
if [[ ! -e "$swap_file" ]]; then
  sudo fallocate -l 4G "$swap_file"
  sudo chmod 600 "$swap_file"
  sudo mkswap "$swap_file"
fi
if ! sudo swapon --show=NAME --noheadings | grep -Fxq "$swap_file"; then
  sudo swapon "$swap_file"
fi
if ! grep -Fq "$swap_file none swap sw 0 0" /etc/fstab; then
  printf '%s\n' "$swap_file none swap sw 0 0" | sudo tee -a /etc/fstab >/dev/null
fi

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
echo "Azure bootstrap complete. Before the first deployment:"
echo "  1. Edit $deploy_dir/.env with the VM DNS name and a strong database password."
echo "  2. Sign out and back in so Docker group membership applies."
echo "  3. Run: cd $deploy_dir && ./deploy/deploy-azure.sh \$(git rev-parse HEAD)"
