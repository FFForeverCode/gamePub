export type MessageStatus = 'STREAMING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type MessageRole = 'USER' | 'ASSISTANT'

export interface Conversation {
  id: number
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: number
  conversationId: number
  role: MessageRole
  content: string
  status: MessageStatus
  modelId: string | null
  sequenceNo: number
  errorCode: string | null
  createdAt: string
  updatedAt: string
}

export interface ModelDescriptor {
  id: string
  displayName: string
  provider: string
  defaultModel: boolean
}

export type ChatStreamEvent =
  | { type: 'message'; data: { userMessage: ChatMessage; assistantMessage: ChatMessage } }
  | { type: 'delta'; data: { messageId: number; content: string } }
  | { type: 'complete'; data: { message: ChatMessage } }
  | { type: 'error'; data: { messageId: number; code: string; message: string } }
  | { type: 'cancelled'; data: { message: ChatMessage } }
