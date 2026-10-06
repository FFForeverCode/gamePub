import { Bot } from 'lucide-react'

export function EmptyState() {
  return <section className="empty-state">
    <div className="empty-icon"><Bot size={26} /></div>
    <h1>开始一段新对话</h1>
  </section>
}
