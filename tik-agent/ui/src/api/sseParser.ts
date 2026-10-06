export interface SseFrame {
  id?: string
  event?: string
  data: string
}

export async function parseSseStream(
  stream: ReadableStream<Uint8Array>,
  onFrame: (frame: SseFrame) => void,
): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const frame = parseBlock(block)
      if (frame) onFrame(frame)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
  const frame = parseBlock(buffer.trim())
  if (frame) onFrame(frame)
}

function parseBlock(block: string): SseFrame | null {
  if (!block || block.startsWith(':')) return null
  const frame: SseFrame = { data: '' }
  const data: string[] = []
  for (const line of block.split('\n')) {
    const separator = line.indexOf(':')
    const field = separator >= 0 ? line.slice(0, separator) : line
    const value = separator >= 0 ? line.slice(separator + 1).replace(/^ /, '') : ''
    if (field === 'id') frame.id = value
    if (field === 'event') frame.event = value
    if (field === 'data') data.push(value)
  }
  frame.data = data.join('\n')
  return frame.event || frame.data ? frame : null
}
