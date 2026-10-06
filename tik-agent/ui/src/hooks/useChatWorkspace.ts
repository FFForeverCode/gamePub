import { useCallback, useEffect, useRef, useState } from 'react'
import * as api from '../api/conversations'
import { streamChat } from '../api/chatStream'
import type { ChatMessage, Conversation, ModelDescriptor } from '../types/api'

export function useChatWorkspace() {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [models, setModels] = useState<ModelDescriptor[]>([])
  const [modelId, setModelId] = useState('mock')
  const [loading, setLoading] = useState(true)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const reloadConversations = useCallback(async () => {
    const result = await api.listConversations()
    setConversations(result)
    return result
  }, [])

  const selectConversation = useCallback(async (id: number) => {
    setActiveId(id)
    setError(null)
    setMessages(await api.listMessages(id))
  }, [])

  const create = useCallback(async () => {
    const created = await api.createConversation()
    setConversations((current) => [created, ...current])
    setActiveId(created.id)
    setMessages([])
    return created
  }, [])

  useEffect(() => {
    Promise.all([api.listModels(), reloadConversations()])
      .then(async ([availableModels, existing]) => {
        setModels(availableModels)
        setModelId(availableModels.find((model) => model.defaultModel)?.id ?? availableModels[0]?.id ?? 'mock')
        if (existing[0]) await selectConversation(existing[0].id)
      })
      .catch((reason: Error) => setError(reason.message))
      .finally(() => setLoading(false))
  }, [reloadConversations, selectConversation])

  const send = useCallback(async (content: string) => {
    let conversationId = activeId
    if (!conversationId) conversationId = (await create()).id
    const controller = new AbortController()
    abortRef.current = controller
    setGenerating(true)
    setError(null)
    try {
      await streamChat(conversationId, content, modelId, controller.signal, (event) => {
        if (event.type === 'message') {
          setMessages((current) => [...current, event.data.userMessage, event.data.assistantMessage])
        } else if (event.type === 'delta') {
          setMessages((current) => current.map((message) =>
            message.id === event.data.messageId ? { ...message, content: message.content + event.data.content } : message))
        } else if (event.type === 'complete' || event.type === 'cancelled') {
          setMessages((current) => current.map((message) =>
            message.id === event.data.message.id ? event.data.message : message))
        } else if (event.type === 'error') {
          setMessages((current) => current.map((message) =>
            message.id === event.data.messageId ? { ...message, status: 'FAILED', errorCode: event.data.code } : message))
          setError(event.data.message)
        }
      })
      await reloadConversations()
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '生成失败')
      if (controller.signal.aborted) {
        await api.listMessages(conversationId).then(setMessages).catch(() => undefined)
      }
    } finally {
      abortRef.current = null
      setGenerating(false)
    }
  }, [activeId, create, modelId, reloadConversations])

  const stop = useCallback(() => abortRef.current?.abort(), [])

  const rename = useCallback(async (id: number, title: string) => {
    const updated = await api.renameConversation(id, title)
    setConversations((current) => current.map((item) => item.id === id ? updated : item))
  }, [])

  const remove = useCallback(async (id: number) => {
    await api.deleteConversation(id)
    const remaining = conversations.filter((item) => item.id !== id)
    setConversations(remaining)
    if (activeId === id) {
      if (remaining[0]) await selectConversation(remaining[0].id)
      else { setActiveId(null); setMessages([]) }
    }
  }, [activeId, conversations, selectConversation])

  return {
    conversations, activeId, messages, models, modelId, loading, generating, error,
    setModelId, selectConversation, create, send, stop, rename, remove,
  }
}
