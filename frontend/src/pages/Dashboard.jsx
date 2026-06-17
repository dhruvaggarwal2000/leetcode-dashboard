import { useEffect, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts'
import { api } from '../services/api'

const COLORS = ['#387ED1', '#21897E', '#F97316', '#8B5CF6', '#EF4444', '#06B6D4', '#84CC16']

export default function Dashboard() {
  const [account, setAccount]     = useState(null)   // from Account DB table
  const [stats, setStats]         = useState(null)   // from Problem table (charts, needsReview)
  const [lcUser, setLcUser]       = useState('')
  const [lcInput, setLcInput]     = useState('')
  const [lcLoading, setLcLoading] = useState(false)
  const [syncing, setSyncing]     = useState(false)
  const [syncResult, setSyncResult] = useState(null)

  useEffect(() => {
    api.stats.get().then(setStats)
    const saved = localStorage.getItem('lc_username')
    if (saved) {
      setLcUser(saved)
      api.account.get(saved).then(setAccount).catch(() => {})
    }
  }, [])

  function fetchAndSaveProfile(username) {
    setLcLoading(true)
    // hits LC API, saves to Account table, then reloads from DB
    api.leetcode.profile(username)
      .then(() => api.account.get(username))
      .then(acc => { setAccount(acc); setLcUser(username) })
      .catch(() => {})
      .finally(() => setLcLoading(false))
  }

  function handleLcSubmit(e) {
    e.preventDefault()
    const username = lcInput.trim()
    if (!username) return
    localStorage.setItem('lc_username', username)
    fetchAndSaveProfile(username)
    api.stats.get().then(setStats)
  }

  function handleSync() {
    setSyncing(true)
    setSyncResult(null)
    const token = localStorage.getItem('lc_session_token')
    api.leetcode.sync(lcUser, token)
      .then(result => {
        setSyncResult(result)
        api.stats.get().then(setStats)
        api.account.get(lcUser).then(setAccount).catch(() => {})
      })
      .finally(() => setSyncing(false))
  }

  // Charts use Problem table aggregates
  const topicData = stats
    ? Object.entries(stats.byTopic)
        .map(([name, count]) => ({ name, count }))
        .sort((a, b) => b.count - a.count)
    : []

  const patternData = stats
    ? Object.entries(stats.byPattern)
        .map(([name, value]) => ({ name, value }))
        .sort((a, b) => b.value - a.value)
    : []

  // Stat cards always from Problem DB so they're consistent with the charts
  const totalSolved  = stats?.totalSolved  ?? '—'
  const easySolved   = stats?.easySolved   ?? '—'
  const mediumSolved = stats?.mediumSolved ?? '—'
  const hardSolved   = stats?.hardSolved   ?? '—'

  const diffData = [
    { name: 'Easy',   value: easySolved,   color: '#21897E' },
    { name: 'Medium', value: mediumSolved, color: '#F97316' },
    { name: 'Hard',   value: hardSolved,   color: '#EF4444' },
  ]

  const tooltipStyle = { background: '#fff', border: '1px solid #E8E8E8', borderRadius: 4, fontSize: 12, color: '#172B4D' }

  return (
    <div className="page">
      <div className="stat-grid">
        <div className="stat-card"><div className="value total">{totalSolved}</div><div className="label">Total Solved</div></div>
        <div className="stat-card"><div className="value easy">{easySolved}</div><div className="label">Easy</div></div>
        <div className="stat-card"><div className="value medium">{mediumSolved}</div><div className="label">Medium</div></div>
        <div className="stat-card"><div className="value hard">{hardSolved}</div><div className="label">Hard</div></div>
        <div className="stat-card"><div className="value review">{stats?.needsReview ?? '—'}</div><div className="label">Needs Review</div></div>
        <div className="stat-card"><div className="value" style={{ color: '#21897E' }}>{account?.acceptanceRate > 0 ? account.acceptanceRate.toFixed(1) + '%' : '—'}</div><div className="label">Acceptance Rate</div></div>
      </div>

      <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="charts-grid">
            <div className="card">
              <div className="chart-title">Problems by Topic</div>
              {topicData.length > 0 ? (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={topicData} margin={{ top: 0, right: 0, left: -20, bottom: 40 }}>
                    <XAxis dataKey="name" tick={{ fill: '#7A869A', fontSize: 11 }} angle={-35} textAnchor="end" />
                    <YAxis tick={{ fill: '#7A869A', fontSize: 11 }} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Bar dataKey="count" fill="#387ED1" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              ) : <div className="empty">No data yet</div>}
            </div>

            <div className="card">
              <div className="chart-title">By Difficulty</div>
              {diffData.some(d => d.value > 0) ? (
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie data={diffData} dataKey="value" cx="50%" cy="50%" outerRadius={80} label={({ name, value }) => `${name}: ${value}`}>
                      {diffData.map((entry, i) => <Cell key={i} fill={entry.color} />)}
                    </Pie>
                    <Tooltip contentStyle={tooltipStyle} />
                  </PieChart>
                </ResponsiveContainer>
              ) : <div className="empty">No data yet</div>}
            </div>

            <div className="card" style={{ gridColumn: '1 / -1' }}>
              <div className="chart-title">Problems by Pattern</div>
              {patternData.length > 0 ? (
                <ResponsiveContainer width="100%" height={300}>
                  <BarChart data={patternData} margin={{ top: 0, right: 0, left: -20, bottom: 100 }}>
                    <XAxis dataKey="name" tick={{ fill: '#7A869A', fontSize: 11 }} angle={-45} textAnchor="end" interval={0} />
                    <YAxis tick={{ fill: '#7A869A', fontSize: 11 }} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Bar dataKey="value" radius={[4, 4, 0, 0]}>
                      {patternData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              ) : <div className="empty">No data yet</div>}
            </div>
          </div>
        </div>

        <div className="card lc-sidebar">
          <div className="chart-title" style={{ marginBottom: 16 }}>LeetCode Profile</div>
          {account ? (
            <>
              <div className="lc-profile">
                <div className="lc-stat"><div className="v total">{account.totalSolved}</div><div className="l">Total Solved</div></div>
                <div className="lc-stat"><div className="v easy">{account.easySolved}</div><div className="l">Easy</div></div>
                <div className="lc-stat"><div className="v medium">{account.mediumSolved}</div><div className="l">Medium</div></div>
                <div className="lc-stat"><div className="v hard">{account.hardSolved}</div><div className="l">Hard</div></div>
                <div className="lc-stat"><div className="v" style={{ color: '#387ED1' }}>#{account.ranking?.toLocaleString()}</div><div className="l">Global Rank</div></div>
              </div>
              {account.lastSyncedAt && (
                <div style={{ fontSize: '11px', color: '#7A869A', marginTop: 8 }}>
                  Updated {new Date(account.lastSyncedAt).toLocaleDateString()}
                </div>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 16 }}>
                <button className="btn btn-primary" onClick={handleSync} disabled={syncing} style={{ width: '100%' }}>
                  {syncing ? 'Syncing…' : 'Sync Solved Problems'}
                </button>
                <button className="btn btn-ghost" style={{ width: '100%' }} onClick={() => fetchAndSaveProfile(lcUser)} disabled={lcLoading}>
                  {lcLoading ? 'Refreshing…' : 'Refresh Profile'}
                </button>
                <button className="btn btn-ghost" style={{ width: '100%' }} onClick={() => {
                  setAccount(null); setLcUser(''); setStats(null)
                  localStorage.removeItem('lc_username')
                  localStorage.removeItem('lc_session_token')
                  setSyncResult(null)
                }}>Change User</button>
              </div>
              {syncResult && (
                <div style={{ marginTop: 12, fontSize: '12px', color: '#21897E', textAlign: 'center' }}>
                  {syncResult.imported > 0 && `${syncResult.imported} new imported. `}
                  {syncResult.updated > 0 && `${syncResult.updated} updated.`}
                  {syncResult.imported === 0 && syncResult.updated === 0 && 'Already up to date.'}
                </div>
              )}
            </>
          ) : (
            <form onSubmit={handleLcSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <input value={lcInput} onChange={e => setLcInput(e.target.value)} placeholder="LeetCode username" required minLength={1} style={{ width: '100%' }} />
              <button className="btn btn-primary" type="submit" disabled={lcLoading} style={{ width: '100%' }}>
                {lcLoading ? 'Loading…' : 'Fetch Profile'}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
