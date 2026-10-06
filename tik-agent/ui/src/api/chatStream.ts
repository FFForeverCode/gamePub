import type { ChatStreamEvent } from '../types/api'
import { ApiError } from './http'
import { parseSseStream } from './sseParser'

export async function streamChat(
  conversationId: number,
  content: string,
  modelId: string,
  signal: AbortSignal,
  onEvent: (event: ChatStreamEvent) => void,
) {
  const response = await fetch(`/api/v1/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ content, modelId }),
    signal,
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { code?: string; message?: string }
    throw new ApiError(body.code ?? 'STREAM_ERROR', body.message ?? '无法开始生成', response.status)
  }
  if (!response.body) throw new ApiError('EMPTY_STREAM', '服务端没有返回消息流', 502)
  await parseSseStream(response.body, (frame) => {
    if (!frame.event || !frame.data) return
    onEvent({ type: frame.event, data: JSON.parse(frame.data) } as ChatStreamEvent)
  })
}
