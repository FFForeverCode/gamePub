import type { ChatMessage, Conversation, ModelDescriptor } from '../types/api'
import { requestJson } from './http'

export const listModels = () => requestJson<ModelDescriptor[]>('/api/v1/models')
export const listConversations = () => requestJson<Conversation[]>('/api/v1/conversations')
export const createConversation = () => requestJson<Conversation>('/api/v1/conversations', { method: 'POST' })
export const listMessages = (id: number) => requestJson<ChatMessage[]>(`/api/v1/conversations/${id}/messages`)
export const renameConversation = (id: number, title: string) =>
  requestJson<Conversation>(`/api/v1/conversations/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) })
export const deleteConversation = (id: number) =>
  requestJson<void>(`/api/v1/conversations/${id}`, { method: 'DELETE' })
