#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

conversation_id="$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations" | sed -E 's/.*"id":([0-9]+).*/\1/')"
echo "conversation=${conversation_id}"
echo "models:"
curl -fsS "${BASE_URL}/api/v1/models"
echo
echo "stream:"
curl -N -fsS -X POST "${BASE_URL}/api/v1/conversations/${conversation_id}/messages/stream" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"content":"请用一句话返回健康检查结果","modelId":"mock"}'
