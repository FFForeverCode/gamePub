import { useState } from 'react'
import { AppHeader } from './components/AppHeader'
import { ChatComposer } from './components/ChatComposer'
import { ConversationSidebar } from './components/ConversationSidebar'
import { MessageList } from './components/MessageList'
import { useChatWorkspace } from './hooks/useChatWorkspace'

export default function App() {
  const chat = useChatWorkspace()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  return <div className="app-shell">
    <ConversationSidebar open={sidebarOpen} conversations={chat.conversations} activeId={chat.activeId}
      onClose={() => setSidebarOpen(false)} onCreate={() => void chat.create()}
      onSelect={(id) => void chat.selectConversation(id)} onRename={(id, title) => void chat.rename(id, title)}
      onDelete={(id) => void chat.remove(id)} />
    <main className="workspace">
      <AppHeader models={chat.models} modelId={chat.modelId} onModelChange={chat.setModelId} onMenu={() => setSidebarOpen(true)} />
      {chat.error && <div className="error-banner" role="alert">{chat.error}</div>}
      <section className="conversation-surface"><MessageList messages={chat.messages} loading={chat.loading} /></section>
      <ChatComposer generating={chat.generating} disabled={chat.loading} onSend={(content) => void chat.send(content)} onStop={chat.stop} />
    </main>
  </div>
}
