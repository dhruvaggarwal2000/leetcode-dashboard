import { useEffect, useState } from 'react'
import { api } from '../services/api'

function SortIcon({ sortKey, field, sortDir }) {
  if (sortKey !== field) return <span style={{ color: '#ccc', marginLeft: 4 }}>↕</span>
  return <span style={{ marginLeft: 4 }}>{sortDir === 'asc' ? '↑' : '↓'}</span>
}

export default function Leaderboard() {
  const [accounts, setAccounts]   = useState([])
  const [loading, setLoading]     = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [sortKey, setSortKey]     = useState('ranking')
  const [sortDir, setSortDir]     = useState('asc')
  const currentUser = localStorage.getItem('lc_username')

  useEffect(() => {
    api.leaderboard.getAll().then(data => {
      setAccounts(data)
      setLoading(false)
    })
  }, [])

  function handleSort(key) {
    if (key === sortKey) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    } else {
      setSortKey(key)
      setSortDir(key === 'ranking' ? 'asc' : 'desc')
    }
  }

  function handleRefresh() {
    setRefreshing(true)
    api.leaderboard.refresh()
      .then(data => { setAccounts(data); setRefreshing(false) })
      .catch(() => setRefreshing(false))
  }

  const sorted = [...accounts].sort((a, b) => {
    const av = a[sortKey] ?? 0
    const bv = b[sortKey] ?? 0
    const cmp = av < bv ? -1 : av > bv ? 1 : 0
    return sortDir === 'asc' ? cmp : -cmp
  })

  function thStyle(field) {
    return { cursor: 'pointer', userSelect: 'none', color: sortKey === field ? '#387ED1' : undefined }
  }

  if (loading) return <div className="page"><div className="card">Loading...</div></div>

  return (
    <div className="page">
      <div className="page-header">
        <h2>Leaderboard</h2>
        <button
          className="btn btn-primary"
          onClick={handleRefresh}
          disabled={refreshing}
          style={{ fontSize: '13px', padding: '6px 14px' }}
        >
          {refreshing ? 'Refreshing…' : 'Refresh Accounts'}
        </button>
      </div>

      {accounts.length === 0 ? (
        <div className="card empty" style={{ padding: 64 }}>
          No accounts found. Sync a LeetCode profile to get started.
        </div>
      ) : (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Username</th>
                <th onClick={() => handleSort('totalSolved')} style={thStyle('totalSolved')}>
                  Total <SortIcon sortKey={sortKey} field="totalSolved" sortDir={sortDir} />
                </th>
                <th style={{ color: 'var(--easy)' }}>Easy</th>
                <th style={{ color: 'var(--medium)' }}>Medium</th>
                <th style={{ color: 'var(--hard)' }}>Hard</th>
                <th onClick={() => handleSort('ranking')} style={thStyle('ranking')}>
                  LC Rank <SortIcon sortKey={sortKey} field="ranking" sortDir={sortDir} />
                </th>
                <th onClick={() => handleSort('acceptanceRate')} style={thStyle('acceptanceRate')}>
                  Acceptance <SortIcon sortKey={sortKey} field="acceptanceRate" sortDir={sortDir} />
                </th>
                <th>Last Synced</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((a, i) => {
                const isCurrentUser = a.userId === currentUser
                return (
                  <tr
                    key={a.userId}
                    style={isCurrentUser ? { background: '#EBF2FB', fontWeight: 600 } : undefined}
                  >
                    <td style={{ color: '#7A869A' }}>{i + 1}</td>
                    <td>
                      <a
                        href={`${import.meta.env.VITE_LEETCODE_BASE_URL}/${a.userId}`}
                        target="_blank"
                        rel="noreferrer"
                        style={{ color: '#387ED1' }}
                      >
                        {a.userId}
                      </a>
                      {isCurrentUser && (
                        <span style={{ marginLeft: 6, fontSize: '11px', color: '#387ED1' }}>you</span>
                      )}
                    </td>
                    <td style={{ fontWeight: 600 }}>{a.totalSolved}</td>
                    <td style={{ color: 'var(--easy)' }}>{a.easySolved}</td>
                    <td style={{ color: 'var(--medium)' }}>{a.mediumSolved}</td>
                    <td style={{ color: 'var(--hard)' }}>{a.hardSolved}</td>
                    <td>{a.ranking ? a.ranking.toLocaleString() : '—'}</td>
                    <td>{a.acceptanceRate ? `${a.acceptanceRate.toFixed(1)}%` : '—'}</td>
                    <td style={{ color: '#7A869A', fontSize: '12px' }}>
                      {a.lastSyncedAt ? new Date(a.lastSyncedAt).toLocaleDateString() : '—'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
