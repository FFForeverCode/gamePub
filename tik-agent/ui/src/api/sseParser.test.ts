import { describe, expect, it } from 'vitest'
import { parseSseStream } from './sseParser'

describe('parseSseStream', () => {
  it('parses frames split across UTF-8 chunks', async () => {
    const bytes = new TextEncoder().encode('id: 1\nevent: delta\ndata: {\"content\":\"你好\"}\n\n')
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, bytes.length - 2))
        controller.enqueue(bytes.slice(bytes.length - 2))
        controller.close()
      },
    })
    const frames: unknown[] = []
    await parseSseStream(stream, (frame) => frames.push(frame))
    expect(frames).toEqual([{ id: '1', event: 'delta', data: '{\"content\":\"你好\"}' }])
  })
})
