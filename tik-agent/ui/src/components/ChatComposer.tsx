import { Send, Square } from 'lucide-react'
import { useState } from 'react'

interface Props { generating: boolean; disabled?: boolean; onSend: (content: string) => void; onStop: () => void }

export function ChatComposer({ generating, disabled, onSend, onStop }: Props) {
  const [content, setContent] = useState('')
  const submit = () => {
    const value = content.trim()
    if (!value || generating || disabled) return
    setContent('')
    onSend(value)
  }
  return <div className="composer-wrap">
    <div className="composer">
      <textarea value={content} disabled={disabled} rows={1} maxLength={10000} placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        onChange={(event) => setContent(event.target.value)}
        onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); submit() } }} />
      {generating
        ? <button className="send-button stop" onClick={onStop} title="停止生成"><Square size={16} fill="currentColor" /></button>
        : <button className="send-button" disabled={!content.trim() || disabled} onClick={submit} title="发送"><Send size={17} /></button>}
    </div>
  </div>
}
