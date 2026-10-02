#!/usr/bin/env bash
# Points the environment's Dokploy application at one published image digest,
# deploys it, and waits until the public endpoint serves the deployed commit.
# CI runs this after publishing the multi-platform image [DP1b].
set -euo pipefail

: "${DOKPLOY_URL:?}" "${DOKPLOY_API_KEY:?}" "${DOKPLOY_APPLICATION_ID:?}"
: "${IMAGE_REFERENCE:?}" "${SOURCE_COMMIT:?}" "${PUBLIC_URL:?}"

if [[ ! "$IMAGE_REFERENCE" =~ @sha256:([a-f0-9]{64})$ ]]; then
  printf 'IMAGE_REFERENCE must end in an exact @sha256 digest: %s\n' "$IMAGE_REFERENCE" >&2
  exit 1
fi
image_digest="${BASH_REMATCH[1]}"
dokploy_api="${DOKPLOY_URL%/}/api"
# Dokploy resolves its ${DOKPLOY_SOURCE_REVISION} label placeholder only for Git
# sources, so an image deployment carries the commit in the label itself.
revision_label="otel.service.version"

dokploy_get() {
  curl --fail-with-body --silent --show-error --connect-timeout 10 --max-time 30 \
    --header "x-api-key: ${DOKPLOY_API_KEY}" "${dokploy_api}/$1"
}

dokploy_post() {
  curl --fail-with-body --silent --show-error --connect-timeout 10 --max-time 30 \
    --header "x-api-key: ${DOKPLOY_API_KEY}" --header 'content-type: application/json' \
    --data "$2" "${dokploy_api}/$1"
}

application="$(dokploy_get "application.one?applicationId=${DOKPLOY_APPLICATION_ID}")"
current_labels="$(jq --compact-output '.labelsSwarm // {}' <<<"$application")"
target_labels="$(jq --compact-output --arg key "$revision_label" --arg commit "$SOURCE_COMMIT" \
  '.[$key] = $commit' <<<"$current_labels")"

if [[ "$(jq --raw-output .sourceType <<<"$application")" != docker ]]; then
  # A Git-built application has no image to compare against; switch its source.
  update_request="$(jq --null-input --compact-output \
    --arg applicationId "$DOKPLOY_APPLICATION_ID" --arg dockerImage "$IMAGE_REFERENCE" \
    --argjson labelsSwarm "$target_labels" \
    '{applicationId: $applicationId, sourceType: "docker", dockerImage: $dockerImage, labelsSwarm: $labelsSwarm}')"
else
  update_request="$(jq --null-input --compact-output \
    --arg applicationId "$DOKPLOY_APPLICATION_ID" --arg dockerImage "$IMAGE_REFERENCE" \
    --arg expectedDockerImage "$(jq --raw-output .dockerImage <<<"$application")" \
    --argjson expectedLabelsSwarm "$current_labels" --argjson labelsSwarm "$target_labels" \
    '{applicationId: $applicationId, expectedDockerImage: $expectedDockerImage,
      expectedLabelsSwarm: $expectedLabelsSwarm, dockerImage: $dockerImage, labelsSwarm: $labelsSwarm}')"
fi
dokploy_post application.update "$update_request" >/dev/null

deploy_request="$(jq --null-input --compact-output \
  --arg applicationId "$DOKPLOY_APPLICATION_ID" --arg expectedDockerImage "$IMAGE_REFERENCE" \
  --argjson expectedLabelsSwarm "$target_labels" --arg idempotencyKey "$image_digest" \
  --arg title "CI deploy ${SOURCE_COMMIT:0:12}" \
  '{applicationId: $applicationId, expectedDockerImage: $expectedDockerImage,
    expectedLabelsSwarm: $expectedLabelsSwarm, idempotencyKey: $idempotencyKey, title: $title}')"
deployment_id="$(dokploy_post application.deploy "$deploy_request" | jq --exit-status --raw-output .deploymentId)"
printf 'Submitted Dokploy deployment %s for %s\n' "$deployment_id" "$IMAGE_REFERENCE"

deployment_status=""
for _ in $(seq 1 120); do
  deployment_status="$(dokploy_get "deployment.all?applicationId=${DOKPLOY_APPLICATION_ID}" \
    | jq --raw-output --arg id "$deployment_id" '.[] | select(.deploymentId == $id) | .status')"
  [[ "$deployment_status" == "done" || "$deployment_status" == "error" ]] && break
  sleep 5
done
if [[ "$deployment_status" != "done" ]]; then
  printf 'Dokploy deployment %s ended as %s\n' "$deployment_id" "${deployment_status:-missing}" >&2
  exit 1
fi

# Dokploy records `done` once Swarm accepts the update; the rollout is proven
# only when the public endpoint reports the deployed commit.
served_commit=""
for _ in $(seq 1 90); do
  served_commit="$(curl --silent --connect-timeout 5 --max-time 15 "${PUBLIC_URL}/actuator/info" \
    | jq --raw-output '.deployment.commit // empty' 2>/dev/null || true)"
  if [[ "$served_commit" == "$SOURCE_COMMIT" ]]; then
    printf '%s serves %s from %s\n' "$PUBLIC_URL" "$SOURCE_COMMIT" "$IMAGE_REFERENCE"
    exit 0
  fi
  sleep 10
done
printf '%s still serves %s, expected %s\n' "$PUBLIC_URL" "${served_commit:-nothing}" "$SOURCE_COMMIT" >&2
exit 1
