import { Bot, CircleAlert, UserRound } from 'lucide-react'
import type { ChatMessage } from '../types/api'
import { EmptyState } from './EmptyState'

export function MessageList({ messages, loading }: { messages: ChatMessage[]; loading: boolean }) {
  if (loading) return <div className="center-state">正在加载会话...</div>
  if (messages.length === 0) return <EmptyState />
  return <div className="message-list">
    {messages.map((message) => <article key={message.id} className={`message ${message.role.toLowerCase()}`}>
      <div className="avatar">{message.role === 'USER' ? <UserRound size={17} /> : <Bot size={18} />}</div>
      <div className="message-body">
        <div className="message-meta">{message.role === 'USER' ? '你' : message.modelId ?? 'Agent'}</div>
        <div className="message-content">{message.content || (message.status === 'STREAMING' ? <span className="typing">正在思考</span> : '')}</div>
        {(message.status === 'FAILED' || message.status === 'CANCELLED') && <div className="message-status">
          <CircleAlert size={14} />{message.status === 'FAILED' ? '生成失败' : '已停止生成'}
        </div>}
      </div>
    </article>)}
  </div>
}
