import { Menu, Sparkles } from 'lucide-react'
import type { ModelDescriptor } from '../types/api'

interface Props {
  models: ModelDescriptor[]
  modelId: string
  onModelChange: (id: string) => void
  onMenu: () => void
}

export function AppHeader({ models, modelId, onModelChange, onMenu }: Props) {
  return <header className="app-header">
    <button className="icon-button mobile-only" onClick={onMenu} title="打开会话列表"><Menu size={19} /></button>
    <div className="brand"><span className="brand-mark"><Sparkles size={17} /></span><span>tik-agent</span></div>
    <label className="model-control">
      <span>模型</span>
      <select value={modelId} onChange={(event) => onModelChange(event.target.value)}>
        {models.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}
      </select>
    </label>
  </header>
}
