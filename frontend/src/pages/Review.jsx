import { useEffect, useState } from 'react'
import DatePicker from 'react-datepicker'
import 'react-datepicker/dist/react-datepicker.css'
import { api } from '../services/api'

function parseLocalDate(str) {
  if (!str) return null
  const [y, m, d] = str.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function toISODate(date) {
  if (!date) return null
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const PAGE_SIZE = 8

export default function Review() {
  const [problems, setProblems]     = useState([])
  const [page, setPage]             = useState(0)
  const [totalElements, setTotal]   = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  function loadPage(p) {
    api.problems.review(p, PAGE_SIZE).then(data => {
      setProblems(data.content)
      setTotal(data.totalElements)
      setTotalPages(data.totalPages)
      setPage(data.page)
    })
  }

  useEffect(() => { loadPage(0) }, [])

  function removeFromReview(p) {
    api.problems.update(p.id, { ...p, confidence: null }).then(() => {
      const newTotalPages = Math.max(1, Math.ceil((totalElements - 1) / PAGE_SIZE))
      loadPage(Math.min(page, newTotalPages - 1))
    })
  }

  function handleDateChange(p, date) {
    const updated = { ...p, dueDate: toISODate(date) }
    api.problems.update(p.id, updated).then(() =>
      setProblems(ps => ps.map(x => x.id === p.id ? updated : x))
    )
  }

  if (totalElements === 0 && problems.length === 0) {
    return (
      <div className="page">
        <div className="page-header"><h2>Review Queue</h2></div>
        <div className="card empty" style={{ padding: 64 }}>
          No problems need review right now.
          <br /><br />
          <span style={{ color: '#7A869A', fontSize: '13px' }}>Problems with confidence less than 3 appear here.</span>
        </div>
      </div>
    )
  }

  const from = page * PAGE_SIZE + 1
  const to   = Math.min((page + 1) * PAGE_SIZE, totalElements)

  return (
    <div className="page">
      <div className="page-header">
        <h2>Review Queue</h2>
        <span style={{ fontSize: '13px', color: '#7A869A' }}>{from}–{to} of {totalElements}</span>
      </div>

      {problems.map(p => {
        const dueDate = parseLocalDate(p.dueDate)
        const isOverdue = dueDate && dueDate < new Date(new Date().setHours(0,0,0,0))
        return (
          <div key={p.id} className="card" style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              {p.leetcodeNumber && <span style={{ color: '#7A869A', flexShrink: 0 }}>#{p.leetcodeNumber}</span>}
              <span style={{ fontWeight: 600, flex: 1, minWidth: 0 }}>
                {p.leetcodeUrl
                  ? <a href={p.leetcodeUrl} target="_blank" rel="noreferrer" style={{ color: '#387ED1' }}>{p.title}</a>
                  : p.title}
              </span>
              <span className={`badge badge-${p.difficulty?.toLowerCase()}`} style={{ flexShrink: 0 }}>{p.difficulty}</span>

              <div className="labeled-select rdp-wrap" style={{ flexShrink: 0 }}>
                <span className="labeled-select-label" style={{ color: isOverdue ? '#EF4444' : undefined }}>
                  {isOverdue ? 'Overdue' : 'Due'}
                </span>
                <DatePicker
                  selected={dueDate}
                  onChange={date => handleDateChange(p, date)}
                  dateFormat="MMM d, yyyy"
                  placeholderText="Set date"
                  isClearable
                  customInput={
                    <input
                      readOnly
                      style={{
                        color: isOverdue ? '#EF4444' : dueDate ? '#172B4D' : '#7A869A',
                        cursor: 'pointer',
                        minWidth: 110,
                      }}
                    />
                  }
                />
              </div>

              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                {p.leetcodeUrl && (
                  <a
                    href={p.leetcodeUrl.replace(/\/?$/, '/') + 'submissions/'}
                    target="_blank"
                    rel="noreferrer"
                    className="btn btn-ghost"
                    style={{ fontSize: '12px', padding: '4px 10px' }}
                  >
                    Submissions ↗
                  </a>
                )}
                <button
                  className="btn btn-danger"
                  style={{ fontSize: '12px', padding: '4px 10px' }}
                  onClick={() => removeFromReview(p)}
                >
                  Remove
                </button>
              </div>
            </div>
          </div>
        )
      })}

      {totalPages > 1 && (
        <div className="pagination" style={{ marginTop: 20 }}>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => loadPage(0)} disabled={page === 0}>«</button>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => loadPage(page - 1)} disabled={page === 0}>‹</button>
          {Array.from({ length: totalPages }, (_, i) => i)
            .filter(n => n === 0 || n === totalPages - 1 || Math.abs(n - page) <= 2)
            .reduce((acc, n, i, arr) => { if (i > 0 && n - arr[i-1] > 1) acc.push('…'); acc.push(n); return acc }, [])
            .map((n, i) => n === '…'
              ? <span key={`e${i}`} style={{ color: '#7A869A', padding: '0 4px' }}>…</span>
              : <button key={n} className="btn" onClick={() => loadPage(n)}
                  style={{ padding: '4px 10px', background: n === page ? '#387ED1' : '#F8F9FA', color: n === page ? '#fff' : '#172B4D', border: '1px solid #E8E8E8' }}>
                  {n + 1}
                </button>
            )}
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => loadPage(page + 1)} disabled={page === totalPages - 1}>›</button>
          <button className="btn btn-ghost" style={{ padding: '4px 10px' }} onClick={() => loadPage(totalPages - 1)} disabled={page === totalPages - 1}>»</button>
        </div>
      )}
    </div>
  )
}
