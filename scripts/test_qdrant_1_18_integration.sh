#!/bin/bash

# Exercises the Java Chat hybrid schema and citation path against local Qdrant 1.18.3.

set -euo pipefail

QDRANT_INTEGRATION_BASE_URL="${QDRANT_INTEGRATION_BASE_URL:-http://127.0.0.1:8087}"
QDRANT_INTEGRATION_COLLECTION="java-chat-local-qwen3-embedding-4b-2560-contract-$$"
QDRANT_INTEGRATION_OFFICIAL_POINT="11111111-1111-1111-1111-111111111111"
QDRANT_INTEGRATION_COMMUNITY_POINT="22222222-2222-2222-2222-222222222222"

fail_qdrant_integration_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

qdrant_integration_request() {
    local request_method="$1"
    local request_path="$2"
    local request_body="${3:-}"
    if [ -n "$request_body" ]; then
        curl --fail --silent --show-error \
            -X "$request_method" \
            -H "Content-Type: application/json" \
            --data "$request_body" \
            "$QDRANT_INTEGRATION_BASE_URL$request_path"
        return
    fi
    curl --fail --silent --show-error -X "$request_method" "$QDRANT_INTEGRATION_BASE_URL$request_path"
}

cleanup_qdrant_integration_collection() {
    qdrant_integration_request DELETE "/collections/$QDRANT_INTEGRATION_COLLECTION" >/dev/null 2>&1 || true
}
trap cleanup_qdrant_integration_collection EXIT

qdrant_version="$(qdrant_integration_request GET / | jq -r '.version')"
if [ "$qdrant_version" != "1.18.3" ]; then
    fail_qdrant_integration_test "expected Qdrant 1.18.3 but reached $qdrant_version"
fi

collection_schema_request='{
  "vectors": {"dense": {"size": 2560, "distance": "Cosine", "on_disk": true}},
  "sparse_vectors": {"bm25": {"modifier": "idf", "on_disk": true}},
  "on_disk_payload": true
}'
qdrant_integration_request PUT "/collections/$QDRANT_INTEGRATION_COLLECTION" "$collection_schema_request" >/dev/null

for indexed_field in url sourceKind docVersion javaApiTypePage anchor docSet; do
    payload_index_request="$(jq -nc --arg field "$indexed_field" \
        '{field_name: $field, field_schema: {type: "keyword"}}')"
    qdrant_integration_request PUT \
        "/collections/$QDRANT_INTEGRATION_COLLECTION/index?wait=true" \
        "$payload_index_request" >/dev/null
done

collection_state="$(qdrant_integration_request GET "/collections/$QDRANT_INTEGRATION_COLLECTION")"
if ! jq -e '
    .result.config.params.vectors.dense.size == 2560
    and .result.config.params.vectors.dense.distance == "Cosine"
    and (.result.config.params.sparse_vectors.bm25.modifier | ascii_downcase) == "idf"
    and .result.config.params.on_disk_payload == true
    and .result.payload_schema.url.data_type == "keyword"
    and .result.payload_schema.anchor.data_type == "keyword"
' <<< "$collection_state" >/dev/null; then
    fail_qdrant_integration_test "created collection does not expose the required hybrid schema"
fi

official_dense_vector="$(jq -nc '[range(0; 2560) | if . == 0 then 1 else 0 end]')"
community_dense_vector="$(jq -nc '[range(0; 2560) | if . == 1 then 1 else 0 end]')"
point_upsert_request="$(jq -nc \
    --arg officialPoint "$QDRANT_INTEGRATION_OFFICIAL_POINT" \
    --arg communityPoint "$QDRANT_INTEGRATION_COMMUNITY_POINT" \
    --argjson officialDense "$official_dense_vector" \
    --argjson communityDense "$community_dense_vector" '
    {points: [
      {
        id: $officialPoint,
        vector: {dense: $officialDense, bm25: {indices: [7, 101], values: [1.0, 2.0]}},
        payload: {
          doc_content: "List.of(E,E) returns an immutable list.",
          url: "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html",
          title: "List",
          package: "java.util",
          anchor: "of(E,E)",
          javaApiTypePage: "List.html",
          hash: "contract-point-one",
          docSet: "java/java25-complete",
          sourceKind: "official",
          docVersion: "25",
          docType: "java-api",
          chunkIndex: 0
        }
      },
      {
        id: $communityPoint,
        vector: {dense: $communityDense, bm25: {indices: [7], values: [1.0]}},
        payload: {
          doc_content: "Community list notes.",
          url: "https://example.test/list",
          title: "Community List",
          anchor: "community",
          javaApiTypePage: "List.html",
          hash: "contract-point-two",
          docSet: "community",
          sourceKind: "community",
          docVersion: "25",
          docType: "article",
          chunkIndex: 0
        }
      }
    ]}
')"
qdrant_integration_request PUT \
    "/collections/$QDRANT_INTEGRATION_COLLECTION/points?wait=true" \
    "$point_upsert_request" >/dev/null

exact_count_request='{
  "exact": true,
  "filter": {"must": [{"key": "docSet", "match": {"value": "java/java25-complete"}}]}
}'
exact_count_state="$(qdrant_integration_request POST \
    "/collections/$QDRANT_INTEGRATION_COLLECTION/points/count" \
    "$exact_count_request")"
if ! jq -e '.result.count == 1' <<< "$exact_count_state" >/dev/null; then
    fail_qdrant_integration_test "exact docSet filter did not return one point"
fi

hybrid_query_request="$(jq -nc --argjson dense "$official_dense_vector" '
  {
    prefetch: [
      {
        query: $dense,
        using: "dense",
        limit: 20,
        filter: {must: [{key: "sourceKind", match: {value: "official"}}]}
      },
      {
        query: {indices: [7, 101], values: [1.0, 1.0]},
        using: "bm25",
        limit: 20,
        filter: {must: [{key: "sourceKind", match: {value: "official"}}]}
      }
    ],
    query: {rrf: {k: 60}},
    filter: {must: [{key: "sourceKind", match: {value: "official"}}]},
    limit: 3,
    with_payload: true,
    with_vector: false
  }
')"
hybrid_query_state="$(qdrant_integration_request POST \
    "/collections/$QDRANT_INTEGRATION_COLLECTION/points/query" \
    "$hybrid_query_request")"
if ! jq -e --arg expectedPoint "$QDRANT_INTEGRATION_OFFICIAL_POINT" '
    .result.points | length == 1
    and .[0].id == $expectedPoint
    and .[0].payload.sourceKind == "official"
' <<< "$hybrid_query_state" >/dev/null; then
    fail_qdrant_integration_test "hybrid filtered query did not return the official point"
fi

exact_citation_request='{
  "filter": {"must": [
    {"key": "sourceKind", "match": {"value": "official"}},
    {"key": "docVersion", "match": {"value": "25"}},
    {"key": "javaApiTypePage", "match": {"value": "List.html"}},
    {"key": "anchor", "match": {"value": "of(E,E)"}}
  ]},
  "limit": 3,
  "with_payload": true,
  "with_vector": false
}'
exact_citation_state="$(qdrant_integration_request POST \
    "/collections/$QDRANT_INTEGRATION_COLLECTION/points/scroll" \
    "$exact_citation_request")"
if ! jq -e --arg expectedPoint "$QDRANT_INTEGRATION_OFFICIAL_POINT" '
    .result.points | length == 1
    and .[0].id == $expectedPoint
    and .[0].payload.anchor == "of(E,E)"
    and .[0].payload.url == "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html"
    and .[0].payload.doc_content == "List.of(E,E) returns an immutable list."
' <<< "$exact_citation_state" >/dev/null; then
    fail_qdrant_integration_test "exact anchored citation scroll did not preserve payload identity"
fi

if ! qdrant_integration_request GET /collections \
    | jq -e --arg collection "$QDRANT_INTEGRATION_COLLECTION" \
        'any(.result.collections[]; .name == $collection)' >/dev/null; then
    fail_qdrant_integration_test "created collection was absent from discovery listing"
fi

printf 'PASS: Qdrant 1.18.3 schema, indexes, upsert, exact count, hybrid filter, discovery, and citation scroll.\n'
