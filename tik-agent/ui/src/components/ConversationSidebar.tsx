import { MessageSquare, MoreHorizontal, Pencil, Plus, Trash2, X } from 'lucide-react'
import type { Conversation } from '../types/api'

interface Props {
  open: boolean
  conversations: Conversation[]
  activeId: number | null
  onClose: () => void
  onCreate: () => void
  onSelect: (id: number) => void
  onRename: (id: number, title: string) => void
  onDelete: (id: number) => void
}

export function ConversationSidebar(props: Props) {
  return <>
    {props.open && <button className="sidebar-backdrop mobile-only" onClick={props.onClose} aria-label="关闭会话列表" />}
    <aside className={`sidebar ${props.open ? 'is-open' : ''}`}>
      <div className="sidebar-heading">
        <span>会话</span>
        <button className="icon-button mobile-only" onClick={props.onClose} title="关闭"><X size={18} /></button>
      </div>
      <button className="new-chat-button" onClick={props.onCreate}><Plus size={17} />新建会话</button>
      <div className="conversation-list">
        {props.conversations.map((item) => <div key={item.id} className={`conversation-row ${item.id === props.activeId ? 'active' : ''}`}>
          <button className="conversation-main" onClick={() => { props.onSelect(item.id); props.onClose() }}>
            <MessageSquare size={16} /><span>{item.title}</span>
          </button>
          <details className="conversation-actions">
            <summary title="更多操作"><MoreHorizontal size={16} /></summary>
            <div className="action-menu">
              <button onClick={() => { const title = window.prompt('输入会话标题', item.title); if (title?.trim()) props.onRename(item.id, title) }}><Pencil size={14} />重命名</button>
              <button className="danger" onClick={() => { if (window.confirm('确定删除该会话及其历史消息吗？')) props.onDelete(item.id) }}><Trash2 size={14} />删除</button>
            </div>
          </details>
        </div>)}
      </div>
    </aside>
  </>
}
