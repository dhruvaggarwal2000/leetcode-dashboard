import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { api } from '../services/api'

export default function Chat() {
  const [messages, setMessages]   = useState([])
  const [input, setInput]         = useState('')
  const [streaming, setStreaming] = useState(false)
  const [error, setError]         = useState(null)
  const scrollerRef = useRef(null)
  const abortRef    = useRef(null)

  useEffect(() => {
    const el = scrollerRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, streaming])

  async function handleSubmit(e) {
    e.preventDefault()
    const text = input.trim()
    if (!text || streaming) return
    if (!localStorage.getItem('lc_username')) {
      setError('Set a LeetCode username on the Dashboard first.')
      return
    }
    setError(null)
    setInput('')
    setMessages(prev => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }])
    setStreaming(true)
    const controller = new AbortController()
    abortRef.current = controller
    try {
      await api.chat.stream(text, {
        signal: controller.signal,
        onDelta: (chunk) => {
          setMessages(prev => {
            const next = prev.slice()
            const last = next[next.length - 1]
            if (last && last.role === 'assistant') {
              next[next.length - 1] = { ...last, content: last.content + chunk }
            }
            return next
          })
        },
        onError: (msg) => setError(msg),
      })
    } catch (err) {
      if (err.name !== 'AbortError') setError(err.message || String(err))
    } finally {
      setStreaming(false)
      abortRef.current = null
    }
  }

  async function handleNewConversation() {
    if (streaming) return
    try { await api.chat.clearSession() } catch {}
    setMessages([])
    setError(null)
  }

  function handleStop() {
    abortRef.current?.abort()
  }

  return (
    <div className="page chat-page">
      <div className="chat-header">
        <div>
          <h2 className="chat-title">Practice Coach</h2>
          <p className="chat-subtitle">Talk through problems, patterns, and trade-offs.</p>
        </div>
        <button
          className="btn btn-ghost"
          onClick={handleNewConversation}
          disabled={streaming || messages.length === 0}
        >
          New conversation
        </button>
      </div>

      <div className="chat-container card">
        <div className="chat-messages" ref={scrollerRef}>
          {messages.length === 0 && (
            <div className="chat-empty">
              <p>Try: <em>"I'm stuck on Two Sum, what pattern should I be thinking about?"</em></p>
            </div>
          )}
          {messages.map((m, i) => {
            const isLast      = i === messages.length - 1
            const isStreaming = streaming && isLast && m.role === 'assistant'
            const isThinking  = isStreaming && m.content === ''
            return (
              <div key={i} className={`chat-message chat-message-${m.role}`}>
                <div className="chat-message-role">{m.role === 'user' ? 'You' : 'Claude'}</div>
                <div className="chat-message-content">
                  {isThinking ? (
                    <span className="chat-thinking">
                      Thinking<span className="chat-thinking-dots"><span/><span/><span/></span>
                    </span>
                  ) : m.role === 'assistant' ? (
                    <>
                      <div className="chat-markdown">
                        <ReactMarkdown>{m.content}</ReactMarkdown>
                      </div>
                      {isStreaming && <span className="chat-cursor" />}
                    </>
                  ) : (
                    m.content
                  )}
                </div>
              </div>
            )
          })}
          {error && <div className="chat-error">⚠ {error}</div>}
        </div>

        <form className="chat-input-row" onSubmit={handleSubmit}>
          <textarea
            className="chat-input"
            placeholder="Ask anything — patterns, walkthroughs, hints..."
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                handleSubmit(e)
              }
            }}
            rows={2}
            disabled={streaming}
          />
          {streaming ? (
            <button type="button" className="btn btn-ghost" onClick={handleStop}>Stop</button>
          ) : (
            <button type="submit" className="btn btn-primary" disabled={!input.trim()}>Send</button>
          )}
        </form>
      </div>
    </div>
  )
}
