import { useState, useRef, useEffect, useCallback } from 'react'
import type { AiChatMessage, ChatMessage, ChatResponse } from '../types'

// ─── Constants ────────────────────────────────────────────────────────────────

const CHAT_ENDPOINT = '/inference/chat'
const MAX_HISTORY   = 20  // max messages to send as context
const SUGGESTED_QUESTIONS = [
  'What are common causes of high vibration in industrial motors?',
  'What maintenance steps should be taken when temperature exceeds alarm threshold?',
  'How do I interpret CRITICAL sensor status readings?',
  'What is the recommended inspection interval for rotating equipment?',
]

// ─── ConfidenceChip ───────────────────────────────────────────────────────────

function ConfidenceChip({ score, chunksUsed }: { score: number; chunksUsed?: number }) {
  const pct = Math.round(score * 100)
  const color =
    pct >= 80 ? 'text-emerald-400 border-emerald-500/30 bg-emerald-500/10' :
    pct >= 50 ? 'text-amber-400 border-amber-500/30 bg-amber-500/10' :
                'text-slate-500 border-slate-700 bg-slate-800'

  if (score === 0) return null

  return (
    <div className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full border text-[10px] font-medium mono ${color}`}>
      <span>⬢</span>
      <span>{pct}% RAG</span>
      {chunksUsed != null && chunksUsed > 0 && (
        <span className="opacity-60">· {chunksUsed} chunks</span>
      )}
    </div>
  )
}

// ─── SourcesPill ──────────────────────────────────────────────────────────────

function SourcesPill({ sources }: { sources: string[] }) {
  if (!sources || sources.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1 mt-1.5">
      {sources.map((src) => (
        <span
          key={src}
          className="mono text-[10px] px-1.5 py-0.5 rounded bg-cyan-500/10
                     text-cyan-400/80 border border-cyan-500/20"
        >
          📄 {src}
        </span>
      ))}
    </div>
  )
}

// ─── ChatBubble ───────────────────────────────────────────────────────────────

function ChatBubble({ message }: { message: AiChatMessage }) {
  const isUser = message.role === 'user'

  return (
    <div className={`flex flex-col gap-1 animate-fade-in ${isUser ? 'items-end' : 'items-start'}`}>
      {/* Bubble */}
      <div
        className={`max-w-[85%] rounded-xl px-3.5 py-2.5 text-sm leading-relaxed ${
          isUser
            ? 'bg-gradient-to-br from-cyan-600 to-blue-700 text-white shadow-lg shadow-cyan-900/30'
            : message.error
              ? 'bg-red-500/10 border border-red-500/30 text-red-300'
              : 'bg-slate-800/80 border border-slate-700/60 text-slate-200'
        }`}
      >
        {message.isLoading ? (
          <div className="flex items-center gap-2 py-0.5">
            <span className="text-slate-400 text-xs">Qwen 2.5-3B thinking</span>
            <div className="flex gap-1">
              {[0, 150, 300].map((delay) => (
                <div
                  key={delay}
                  className="w-1.5 h-1.5 rounded-full bg-cyan-500/70 animate-bounce"
                  style={{ animationDelay: `${delay}ms` }}
                />
              ))}
            </div>
          </div>
        ) : message.error ? (
          <span>{message.error}</span>
        ) : (
          <span className="whitespace-pre-wrap">{message.content}</span>
        )}
      </div>

      {/* Metadata row (only for assistant messages) */}
      {!isUser && !message.isLoading && !message.error && (
        <div className="flex flex-col gap-1 pl-1">
          <div className="flex items-center gap-2 flex-wrap">
            {message.confidence_score != null && (
              <ConfidenceChip score={message.confidence_score} chunksUsed={message.rag_chunks_used} />
            )}
            {message.inference_time_ms != null && (
              <span className="mono text-[10px] text-slate-600">
                {Math.round(message.inference_time_ms)}ms
              </span>
            )}
          </div>
          {message.sources && <SourcesPill sources={message.sources} />}
        </div>
      )}

      {/* Timestamp */}
      <span className="mono text-[9px] text-slate-700 px-1">
        {new Date(message.timestamp).toLocaleTimeString()}
      </span>
    </div>
  )
}

// ─── SuggestedQuestions ───────────────────────────────────────────────────────

function SuggestedQuestions({ onSelect }: { onSelect: (q: string) => void }) {
  return (
    <div className="flex flex-col gap-2 py-4 px-1">
      <p className="text-[10px] text-slate-600 uppercase tracking-widest font-medium text-center mb-1">
        Suggested questions
      </p>
      {SUGGESTED_QUESTIONS.map((q) => (
        <button
          key={q}
          onClick={() => onSelect(q)}
          className="text-left text-xs text-slate-400 px-3 py-2 rounded-lg border
                     border-slate-800 bg-slate-900/60 hover:border-cyan-500/30
                     hover:text-slate-200 hover:bg-slate-800 transition-all duration-200
                     leading-relaxed"
        >
          {q}
        </button>
      ))}
    </div>
  )
}

// ─── RagToggle ────────────────────────────────────────────────────────────────

function RagToggle({ useRag, onChange }: { useRag: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      id="rag-toggle"
      onClick={() => onChange(!useRag)}
      title={useRag ? 'RAG grounding ON — answers come from your manuals' : 'RAG grounding OFF — general LLM mode'}
      className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-[10px] font-medium
                  mono border transition-all duration-200 ${
        useRag
          ? 'border-cyan-500/40 bg-cyan-500/10 text-cyan-400'
          : 'border-slate-700 bg-slate-800 text-slate-500 hover:border-slate-600'
      }`}
    >
      <span>⬢</span>
      <span>RAG {useRag ? 'ON' : 'OFF'}</span>
    </button>
  )
}

// ─── AiChatPanel ──────────────────────────────────────────────────────────────

export function AiChatPanel() {
  const [messages,   setMessages]   = useState<AiChatMessage[]>([])
  const [input,      setInput]      = useState('')
  const [isLoading,  setIsLoading]  = useState(false)
  const [useRag,     setUseRag]     = useState(true)
  const bottomRef  = useRef<HTMLDivElement>(null)
  const inputRef   = useRef<HTMLTextAreaElement>(null)
  const abortRef   = useRef<AbortController | null>(null)

  // Auto-scroll on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = useCallback(async (text: string) => {
    const query = text.trim()
    if (!query || isLoading) return

    // Optimistically add user message
    const userMsg: AiChatMessage = {
      id:        `u-${Date.now()}`,
      role:      'user',
      content:   query,
      timestamp: new Date().toISOString(),
    }

    // Placeholder for assistant
    const assistantId = `a-${Date.now()}`
    const assistantPlaceholder: AiChatMessage = {
      id:        assistantId,
      role:      'assistant',
      content:   '',
      timestamp: new Date().toISOString(),
      isLoading: true,
    }

    setMessages((prev) => [...prev, userMsg, assistantPlaceholder])
    setInput('')
    setIsLoading(true)

    // Build conversation context to send
    const history: ChatMessage[] = messages
      .slice(-MAX_HISTORY)
      .filter((m) => !m.isLoading && !m.error)
      .map((m) => ({ role: m.role, content: m.content }))

    history.push({ role: 'user', content: query })

    abortRef.current?.abort()
    abortRef.current = new AbortController()

    try {
      const res = await fetch(CHAT_ENDPOINT, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          messages:    history,
          use_rag:     useRag,
          stream:      false,
          top_k:       5,
          temperature: 0.15,
        }),
        signal: abortRef.current.signal,
      })

      if (!res.ok) {
        const err = await res.text()
        throw new Error(`HTTP ${res.status}: ${err}`)
      }

      const data: ChatResponse = await res.json()

      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? {
                ...m,
                content:          data.answer,
                confidence_score: data.confidence_score,
                sources:          data.sources,
                inference_time_ms: data.inference_time_ms,
                rag_chunks_used:  data.rag_chunks_used,
                isLoading:        false,
              }
            : m
        )
      )
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      const errMsg = err instanceof Error ? err.message : 'Unknown error'

      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? { ...m, isLoading: false, error: `Error: ${errMsg}` }
            : m
        )
      )
    } finally {
      setIsLoading(false)
      setTimeout(() => inputRef.current?.focus(), 100)
    }
  }, [messages, isLoading, useRag])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void sendMessage(input)
    }
  }

  const clearChat = () => {
    abortRef.current?.abort()
    setMessages([])
    setIsLoading(false)
    setTimeout(() => inputRef.current?.focus(), 100)
  }

  return (
    <div className="flex flex-col h-full gap-0">
      {/* Panel header */}
      <div className="flex items-center justify-between mb-4 flex-shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-500 to-violet-600
                          flex items-center justify-center text-sm shadow-lg shadow-cyan-500/20">
            💬
          </div>
          <div>
            <h2 className="text-sm font-semibold text-slate-100">AI Chat</h2>
            <p className="text-[10px] text-slate-500">
              Qwen 2.5-3B · Ask anything about your systems
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <RagToggle useRag={useRag} onChange={setUseRag} />
          {messages.length > 0 && (
            <button
              id="clear-chat"
              onClick={clearChat}
              className="text-[10px] text-slate-600 hover:text-slate-400 transition-colors
                         px-2 py-1 rounded border border-transparent hover:border-slate-700"
            >
              Clear
            </button>
          )}
        </div>
      </div>

      {/* Messages area */}
      <div className="flex-1 overflow-y-auto space-y-3 pr-0.5 pb-2">
        {messages.length === 0 ? (
          <SuggestedQuestions onSelect={(q) => void sendMessage(q)} />
        ) : (
          messages.map((msg) => <ChatBubble key={msg.id} message={msg} />)
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input bar */}
      <div className="flex-shrink-0 pt-3 border-t border-slate-800">
        <div className="relative flex items-end gap-2">
          <textarea
            ref={inputRef}
            id="chat-input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={2}
            placeholder="Ask about sensors, maintenance procedures, anomalies…"
            disabled={isLoading}
            className="flex-1 resize-none rounded-xl bg-slate-800/80 border border-slate-700
                       text-sm text-slate-200 placeholder-slate-600 px-3.5 py-2.5 pr-12
                       focus:outline-none focus:border-cyan-500/50 focus:ring-1 focus:ring-cyan-500/20
                       disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-200
                       leading-relaxed"
          />
          <button
            id="chat-send"
            onClick={() => void sendMessage(input)}
            disabled={isLoading || !input.trim()}
            className="absolute right-2 bottom-2 w-8 h-8 rounded-lg flex items-center justify-center
                       bg-gradient-to-br from-cyan-500 to-blue-600 text-white text-sm
                       disabled:opacity-30 disabled:cursor-not-allowed
                       hover:from-cyan-400 hover:to-blue-500 transition-all duration-200
                       shadow-lg shadow-cyan-900/40 active:scale-95"
          >
            {isLoading ? (
              <div className="w-3.5 h-3.5 rounded-full border-2 border-white/30 border-t-white animate-spin" />
            ) : (
              <span>↑</span>
            )}
          </button>
        </div>
        <p className="text-[10px] text-slate-700 mt-1.5 text-center">
          ↵ Send · Shift+↵ New line · {useRag ? 'Grounded in your manuals' : 'General LLM mode'}
        </p>
      </div>
    </div>
  )
}
