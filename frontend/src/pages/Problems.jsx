import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../services/api'
import { tufLookup } from '../tufLookup'

const DIFFICULTY_ORDER = { EASY: 0, MEDIUM: 1, HARD: 2 }

function nearestSundayFromNow() {
  const d = new Date()
  d.setDate(d.getDate() + 7)
  const day = d.getDay()
  if (day !== 0) {
    const toNext = 7 - day
    const toPrev = day
    d.setDate(d.getDate() + (toNext <= toPrev ? toNext : -toPrev))
  }
  const y  = d.getFullYear()
  const m  = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}


const SORT_OPTIONS = [
  { value: 'date-desc',      label: 'Newest First' },
  { value: 'date-asc',       label: 'Oldest First' },
  { value: 'difficulty',     label: 'Difficulty' },
  { value: 'confidence-asc', label: 'Confidence ↑' },
  { value: 'confidence-desc',label: 'Confidence ↓' },
  { value: 'number',         label: 'LC Number' },
]

export default function Problems() {
  const [problems, setProblems]     = useState([])
  const [search, setSearch]         = useState('')
  const [diffFilter, setDiff]       = useState('')
  const [topicFilter, setTopic]     = useState('')
  const [patternFilter, setPattern] = useState('')
  const [sortBy, setSortBy]         = useState('date-desc')
  const [expanded, setExpanded]     = useState(null)
  const [similar, setSimilar]       = useState({})
  const [syncing, setSyncing]       = useState(false)
  const [syncMsg, setSyncMsg]       = useState('')
  const [page, setPage]             = useState(1)
  const PAGE_SIZE = 15
  const navigate = useNavigate()

  const lcUsername = localStorage.getItem('lc_username')
  const [token, setToken]               = useState(localStorage.getItem('lc_session_token') || '')
  const [showTokenInput, setShowTokenInput] = useState(false)
  const [tokenDraft, setTokenDraft]     = useState('')

  function saveToken() {
    localStorage.setItem('lc_session_token', tokenDraft)
    setToken(tokenDraft)
    setShowTokenInput(false)
    setTokenDraft('')
  }

  function clearToken() {
    localStorage.removeItem('lc_session_token')
    setToken('')
  }

  function loadProblems() {
    return api.problems.getAll().then(data => { setProblems(data); return data })
  }

  function syncFromLC() {
    if (!lcUsername) return
    setSyncing(true)
    setSyncMsg('')
    api.leetcode.sync(lcUsername, token)
      .then(result => {
        const parts = []
        if (result.imported > 0) parts.push(`${result.imported} new`)
        if (result.updated  > 0) parts.push(`${result.updated} updated`)
        setSyncMsg(parts.length ? parts.join(', ') : 'Already up to date')
        loadProblems()
      })
      .catch(() => setSyncMsg('Sync failed — check your username or session token'))
      .finally(() => setSyncing(false))
  }

  useEffect(() => {
    loadProblems().then(data => {
      // Auto-sync if DB is empty and username is saved
      if ((data?.length ?? 0) === 0 && lcUsername) syncFromLC()
    })
  }, [])


  function toggleExpand(id) {
    setExpanded(prev => prev === id ? null : id)
    if (!similar[id]) {
      api.problems.similar(id).then(data => setSimilar(prev => ({ ...prev, [id]: data })))
    }
  }

  // Enrich each problem with tuf-mapped topic/pattern when DB values are null
  const enriched = problems.map(p => {
    const tuf     = tufLookup(p.leetcodeUrl)
    const topic   = p.topic   || tuf?.topic   || null
    const pattern = p.pattern || tuf?.pattern || null
    return { ...p, topic, pattern }
  })

  const topics   = [...new Set(enriched.map(p => p.topic).filter(Boolean))].sort()
  const patterns = [...new Set(enriched.map(p => p.pattern).filter(Boolean))].sort()

  const sortFn = (a, b) => {
    switch (sortBy) {
      case 'date-desc':       return (b.solvedDate ?? '').localeCompare(a.solvedDate ?? '')
      case 'date-asc':        return (a.solvedDate ?? '').localeCompare(b.solvedDate ?? '')
      case 'difficulty':      return DIFFICULTY_ORDER[a.difficulty] - DIFFICULTY_ORDER[b.difficulty]
      case 'confidence-asc':  return (a.confidence ?? 0) - (b.confidence ?? 0)
      case 'confidence-desc': return (b.confidence ?? 0) - (a.confidence ?? 0)
      case 'number':          return (a.leetcodeNumber ?? 9999) - (b.leetcodeNumber ?? 9999)
      default:                return 0
    }
  }

  const q = search.toLowerCase()
  const filtered = enriched
    .filter(p => !diffFilter    || p.difficulty === diffFilter)
    .filter(p => !topicFilter   || p.topic === topicFilter)
    .filter(p => !patternFilter || p.pattern === patternFilter)
    .filter(p => !q || p.title.toLowerCase().includes(q) ||
                       (p.pattern || '').toLowerCase().includes(q) ||
                       (p.topic   || '').toLowerCase().includes(q) ||
                       String(p.leetcodeNumber ?? '').includes(q))
    .sort(sortFn)

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage   = Math.min(page, totalPages)
  const paginated  = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE)

  function goToPage(p) { setPage(Math.max(1, Math.min(p, totalPages))); setExpanded(null) }

  // Reset to page 1 when filters change
  function resetPage(fn) { return (...args) => { fn(...args); setPage(1) } }

  return (
    <div className="page">
      <div className="page-header">
        <h2>Problems ({filtered.length})</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          {syncMsg && <span style={{ fontSize: '12px', color: '#21897E' }}>{syncMsg}</span>}
          {showTokenInput ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-start' }}>
              <input
                type="password"
                placeholder="LEETCODE_SESSION cookie"
                value={tokenDraft}
                onChange={e => setTokenDraft(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && saveToken()}
                autoFocus
                style={{ fontSize: '13px', padding: '4px 8px', width: 300 }}
              />
              <div style={{ display: 'flex', gap: 6 }}>
                <button className="btn btn-primary" style={{ padding: '4px 10px', fontSize: '13px' }} onClick={saveToken}>Save</button>
                <button className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: '13px' }} onClick={() => setShowTokenInput(false)}>Cancel</button>
              </div>
            </div>
          ) : (
            <>
              {token
                ? <button className="btn btn-ghost" style={{ fontSize: '12px', color: '#21897E' }} onClick={clearToken}>
                    Token ✓ ✕
                  </button>
                : <button className="btn btn-ghost" style={{ fontSize: '12px' }} onClick={() => { setShowTokenInput(true); setTokenDraft(token) }}>Set Token</button>
              }
              {lcUsername
                ? <button className="btn btn-primary" onClick={syncFromLC} disabled={syncing}>
                    {syncing ? 'Syncing…' : '↻ Sync from LeetCode'}
                  </button>
                : <button className="btn btn-ghost" onClick={() => navigate('/')} title="Set username on Dashboard first">
                    Set LC Username
                  </button>
              }
            </>
          )}
        </div>
      </div>

      {problems.length === 0 && !syncing && !lcUsername && (
        <div className="card" style={{ marginBottom: 20, textAlign: 'center', padding: 32 }}>
          <div style={{ color: '#7A869A', marginBottom: 12 }}>No problems yet.</div>
          <button className="btn btn-primary" onClick={() => navigate('/')}>Connect LeetCode</button>
        </div>
      )}

      {syncing && (
        <div style={{ color: '#7A869A', fontSize: '13px', marginBottom: 16, paddingLeft: 4 }}>
          Fetching your solved problems from LeetCode…
        </div>
      )}

      <div className="filter-bar">
        <input className="filter-search" placeholder="Search title, topic, pattern, LC #…" value={search} onChange={e => { setSearch(e.target.value); setPage(1) }} />

        <div className="filter-pills">
          {[['', 'All'], ['EASY', 'Easy'], ['MEDIUM', 'Medium'], ['HARD', 'Hard']].map(([val, label]) => (
            <button
              key={val || 'all'}
              className={`filter-pill${diffFilter === val ? ` active${val ? ` ${val.toLowerCase()}` : ''}` : ''}`}
              onClick={() => { setDiff(val); setPage(1) }}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="filter-selects">
          <div className="labeled-select">
            <span className="labeled-select-label">Topic</span>
            <select value={topicFilter} onChange={e => { setTopic(e.target.value); setPage(1) }}>
              <option value="">All</option>
              {topics.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div className="labeled-select">
            <span className="labeled-select-label">Pattern</span>
            <select value={patternFilter} onChange={e => { setPattern(e.target.value); setPage(1) }}>
              <option value="">All</option>
              {patterns.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          </div>
          <div className="labeled-select">
            <span className="labeled-select-label">Sort</span>
            <select value={sortBy} onChange={e => { setSortBy(e.target.value); setPage(1) }}>
              {SORT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
        </div>

        {(search || diffFilter || topicFilter || patternFilter) && (
          <button className="filter-clear" onClick={() => { setSearch(''); setDiff(''); setTopic(''); setPattern(''); setPage(1) }}>
            Clear filters
          </button>
        )}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {filtered.length === 0 && problems.length > 0 ? (
          <div className="empty">No problems match your filters</div>
        ) : filtered.length === 0 ? null : (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Title</th>
                <th>Difficulty</th>
                <th>Topic</th>
                <th>Pattern</th>
                <th>Confidence</th>
                <th>Date</th>
              </tr>
            </thead>
            <tbody>
              {paginated.map(p => {
                const { topic, pattern } = p
                return (
                <>
                  <tr key={p.id} style={{ cursor: 'pointer', borderLeft: (p.confidence > 0 && p.confidence <= 2) ? '3px solid #EF4444' : '3px solid transparent' }} onClick={() => toggleExpand(p.id)}>
                    <td style={{ color: '#7A869A' }}>{p.leetcodeNumber ?? '—'}</td>
                    <td>
                      {p.leetcodeUrl
                        ? <a href={p.leetcodeUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} style={{ color: '#387ED1' }}>{p.title}</a>
                        : p.title}
                    </td>
                    <td><span className={`badge badge-${p.difficulty?.toLowerCase()}`}>{p.difficulty}</span></td>
                    <td>{topic   ? <span className="tag">{topic}</span>   : '—'}</td>
                    <td>{pattern ? <span className="tag">{pattern}</span> : '—'}</td>
                    <td onClick={e => e.stopPropagation()}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <div className="confidence-dots" title="Click to rate; click same dot again to reset">
                          {[1,2,3,4,5].map(n => (
                            <div
                              key={n}
                              className={`dot ${(p.confidence ?? 0) >= n ? 'filled' : ''}`}
                              style={{ cursor: 'pointer' }}
                              onClick={() => {
                                const next = (p.confidence ?? 0) === n ? null : n
                                const dueDate = (next !== null && next <= 2)
                                  ? (p.dueDate || nearestSundayFromNow())
                                  : null
                                const updated = { ...p, confidence: next, dueDate }
                                api.problems.update(p.id, updated).then(() =>
                                  setProblems(ps => ps.map(x => x.id === p.id ? updated : x))
                                )
                              }}
                            />
                          ))}
                        </div>
                        {(p.confidence ?? 0) > 0 && (
                          <span
                            title="Reset confidence"
                            style={{ cursor: 'pointer', color: '#7A869A', fontSize: '11px', lineHeight: 1 }}
                            onClick={() => {
                              const updated = { ...p, confidence: null, dueDate: null }
                              api.problems.update(p.id, updated).then(() =>
                                setProblems(ps => ps.map(x => x.id === p.id ? updated : x))
                              )
                            }}
                          >✕</span>
                        )}
                      </div>
                    </td>
                    <td style={{ color: '#7A869A', fontSize: '12px' }}>{p.solvedDate ?? '—'}</td>
                  </tr>
                  {expanded === p.id && (
                    <tr key={`${p.id}-detail`}>
                      <td colSpan={7} style={{ background: '#F8F9FA', padding: '16px 20px' }}>
                        {p.notes && <div style={{ marginBottom: 12 }}><span style={{ color: '#7A869A', fontSize: '11px' }}>NOTES</span><p style={{ marginTop: 4, fontSize: '13px', lineHeight: 1.6 }}>{p.notes}</p></div>}
                        {similar[p.id]?.length > 0 && (
                          <div>
                            <span style={{ color: '#7A869A', fontSize: '11px' }}>SIMILAR PATTERN ({p.pattern})</span>
                            <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                              {similar[p.id].map(s => (
                                <a key={s.id} href={s.leetcodeUrl} target="_blank" rel="noreferrer" className="tag" style={{ cursor: 'pointer', textDecoration: 'none' }}>{s.title}</a>
                              ))}
                            </div>
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              )})}
            </tbody>
          </table>
        )}
      </div>

      {totalPages > 1 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, marginTop: 20 }}>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => goToPage(1)} disabled={safePage === 1}>«</button>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => goToPage(safePage - 1)} disabled={safePage === 1}>‹</button>

          {Array.from({ length: totalPages }, (_, i) => i + 1)
            .filter(n => n === 1 || n === totalPages || Math.abs(n - safePage) <= 2)
            .reduce((acc, n, i, arr) => {
              if (i > 0 && n - arr[i - 1] > 1) acc.push('…')
              acc.push(n)
              return acc
            }, [])
            .map((n, i) => n === '…'
              ? <span key={`ellipsis-${i}`} style={{ color: '#7A869A', padding: '0 4px' }}>…</span>
              : <button key={n} className="btn" onClick={() => goToPage(n)}
                  style={{ padding: '4px 10px', background: n === safePage ? '#387ED1' : '#F8F9FA', color: n === safePage ? '#fff' : '#172B4D', border: '1px solid #E8E8E8' }}>
                  {n}
                </button>
            )}

          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => goToPage(safePage + 1)} disabled={safePage === totalPages}>›</button>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => goToPage(totalPages)} disabled={safePage === totalPages}>»</button>

          <span style={{ color: '#7A869A', fontSize: '12px', marginLeft: 8 }}>
            {(safePage - 1) * PAGE_SIZE + 1}–{Math.min(safePage * PAGE_SIZE, filtered.length)} of {filtered.length}
          </span>
        </div>
      )}
    </div>
  )
}
