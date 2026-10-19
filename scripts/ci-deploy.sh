#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ "$GITHUB_REF" == refs/heads/main ]] || { echo 'Only main may deploy'; exit 1; }
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]] || exit 1
ssh_dir="$(mktemp -d "$RUNNER_TEMP/kampus-ssh.XXXXXX")"
payload="$(mktemp -d "$RUNNER_TEMP/kampus-release.XXXXXX")"
trap 'rm -rf "$ssh_dir" "$payload" "$payload.tar.gz"' EXIT
printf '%s\n' "$VPS_SSH_KEY" > "$ssh_dir/key"
printf '%s\n' "$VPS_KNOWN_HOSTS" > "$ssh_dir/known_hosts"
chmod 600 "$ssh_dir/key" "$ssh_dir/known_hosts"
git archive --format=tar --prefix=source/ "$GITHUB_SHA" | tar -xf - -C "$payload"
tar -czf "$payload.tar.gz" -C "$payload" source
ssh -i "$ssh_dir/key" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$ssh_dir/known_hosts" -o ServerAliveInterval=30 -o ServerAliveCountMax=10 "$VPS_USER@$VPS_HOST" "deploy $GITHUB_SHA" < "$payload.tar.gz"
rm -f "$payload.tar.gz"
