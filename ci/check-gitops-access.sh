#!/usr/bin/env bash
set -euo pipefail
umask 077
key="$RUNNER_TEMP/police-video-access-key"
hosts="$RUNNER_TEMP/police-video-access-hosts"
trap 'rm -f "$key" "$hosts"' EXIT
printf '%s\n' "$DEPLOY_KEY" | tr -d '\r' > "$key"
printf '%s\n' "$KNOWN_HOSTS" | tr -d '\r' > "$hosts"
ssh-keygen -y -f "$key" > /dev/null
export GIT_SSH_COMMAND="ssh -i $key -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$hosts"
git ls-remote "$GITOPS_REPOSITORY" HEAD > /dev/null
echo 'GitOps authentication verified.'
