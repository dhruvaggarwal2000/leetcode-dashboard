import { useEffect, useRef, useState } from 'react'
import { api } from '../services/api'

const CELL = 13
const GAP  = 3
const STEP = CELL + GAP
const DAY_LABEL_WIDTH = 28
const MONTHS   = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
const DAY_NAMES = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat']

function buildGrid(year, data) {
  const jan1   = new Date(year, 0, 1)
  const dec31  = new Date(year, 11, 31)

  const gridStart = new Date(jan1)
  gridStart.setDate(gridStart.getDate() - gridStart.getDay())

  const gridEnd = new Date(dec31)
  gridEnd.setDate(gridEnd.getDate() + (6 - gridEnd.getDay()))

  const weeks = []
  const cur = new Date(gridStart)
  while (cur <= gridEnd) {
    const week = []
    for (let d = 0; d < 7; d++) {
      const iso    = `${cur.getFullYear()}-${String(cur.getMonth()+1).padStart(2,'0')}-${String(cur.getDate()).padStart(2,'0')}`
      const inYear = cur.getFullYear() === year
      week.push({ iso, date: new Date(cur), inYear, value: inYear ? (data[iso] ?? null) : null })
      cur.setDate(cur.getDate() + 1)
    }
    weeks.push(week)
  }

  const monthLabels = []
  weeks.forEach((week, wi) => {
    const first = week.find(d => d.value !== null || d.date.getFullYear() === year)
    if (!first || first.date.getFullYear() !== year) return
    const m = first.date.getMonth()
    if (!monthLabels.length || monthLabels[monthLabels.length - 1].m !== m)
      monthLabels.push({ label: MONTHS[m], wi, m })
  })

  return { weeks, monthLabels }
}

function lcColor(v) {
  if (!v) return '#EBEDF0'
  if (v <= 2) return '#9BE9A8'
  if (v <= 5) return '#40C463'
  if (v <= 9) return '#30A14E'
  return '#216E39'
}

function activityColor(v) {
  if (!v || (v.new === 0 && v.repeated === 0)) return '#EBEDF0'
  if (v.new > 0) return '#40C463'
  return '#EF4444'
}

function HeatmapGrid({ year, data, colorFn, tooltipFn, legendCells }) {
  const [tooltip, setTooltip] = useState(null)
  const { weeks, monthLabels } = buildGrid(year, data)
  const svgW = DAY_LABEL_WIDTH + weeks.length * STEP
  const svgH = 20 + 7 * STEP

  return (
    <div style={{ overflowX: 'auto', position: 'relative' }}>
      <svg width={svgW} height={svgH} style={{ display: 'block' }}>
        {[1, 3, 5].map(d => (
          <text key={d} x={0} y={20 + d * STEP + CELL - 2} fontSize={10} fill="#7A869A">
            {DAY_NAMES[d]}
          </text>
        ))}
        {monthLabels.map(({ label, wi }) => (
          <text key={label} x={DAY_LABEL_WIDTH + wi * STEP} y={11} fontSize={11} fill="#7A869A">
            {label}
          </text>
        ))}
        {weeks.map((week, wi) =>
          week.map(day =>
            day.inYear ? (
              <rect
                key={day.iso}
                x={DAY_LABEL_WIDTH + wi * STEP}
                y={20 + day.date.getDay() * STEP}
                width={CELL}
                height={CELL}
                rx={2}
                fill={colorFn(day.value)}
                onMouseEnter={e => setTooltip({ text: tooltipFn(day.iso, day.value), x: e.clientX, y: e.clientY })}
                onMouseLeave={() => setTooltip(null)}
                style={{ cursor: 'default' }}
              />
            ) : null
          )
        )}
      </svg>

      <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 8, justifyContent: 'flex-end' }}>
        {legendCells}
      </div>

      {tooltip && (
        <div style={{
          position: 'fixed',
          left: tooltip.x + 14,
          top: tooltip.y - 36,
          background: '#172B4D',
          color: '#fff',
          fontSize: 11,
          padding: '5px 9px',
          borderRadius: 4,
          pointerEvents: 'none',
          zIndex: 9999,
          whiteSpace: 'nowrap',
        }}>
          {tooltip.text}
        </div>
      )}
    </div>
  )
}

const lcLegend = (
  <>
    <span style={{ fontSize: 11, color: '#7A869A', marginRight: 2 }}>Less</span>
    {[0, 1, 3, 6, 10].map(v => (
      <div key={v} style={{ width: CELL, height: CELL, borderRadius: 2, background: lcColor(v) }} />
    ))}
    <span style={{ fontSize: 11, color: '#7A869A', marginLeft: 2 }}>More</span>
  </>
)

const activityLegend = (
  <>
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <div style={{ width: CELL, height: CELL, borderRadius: 2, background: '#EBEDF0' }} />
      <span style={{ fontSize: 11, color: '#7A869A' }}>None</span>
      <div style={{ width: CELL, height: CELL, borderRadius: 2, background: '#40C463', marginLeft: 8 }} />
      <span style={{ fontSize: 11, color: '#7A869A' }}>New</span>
      <div style={{ width: CELL, height: CELL, borderRadius: 2, background: '#EF4444', marginLeft: 8 }} />
      <span style={{ fontSize: 11, color: '#7A869A' }}>Repeated only</span>
    </div>
  </>
)

export default function Heatmap() {
  const [year, setYear]             = useState(new Date().getFullYear())
  const [lcData, setLcData]         = useState({})
  const [actData, setActData]       = useState({})
  const [loading, setLoading]       = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const cache = useRef({})

  useEffect(() => {
    if (cache.current[year]) {
      const { lc, act } = cache.current[year]
      setLcData(lc)
      setActData(act)
      setLoading(false)
      return
    }
    setLoading(true)
    setRefreshing(false)
    api.stats.heatmapData(year)
      .then(({ lcActivity = {}, problemActivity = {} }) => {
        cache.current[year] = { lc: lcActivity, act: problemActivity }
        setLcData(lcActivity)
        setActData(problemActivity)
      })
      .catch(() => { setLcData({}); setActData({}) })
      .finally(() => setLoading(false))
  }, [year, refreshKey])

  function refreshCurrentYear() {
    const currentYear = new Date().getFullYear()
    delete cache.current[currentYear]
    setRefreshing(true)
    setYear(currentYear)          // navigate to current year if not already there
    setRefreshKey(k => k + 1)    // force useEffect to re-run even if year didn't change
  }

  const lcTotal  = Object.values(lcData).reduce((s, v) => s + v, 0)
  const actDays  = Object.keys(actData).length

  const yearControls = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <button className="btn btn-ghost" style={{ padding: '4px 12px' }} onClick={() => setYear(y => y - 1)}>‹</button>
      <span style={{ fontSize: 15, fontWeight: 600, color: '#172B4D', minWidth: 44, textAlign: 'center' }}>{year}</span>
      <button className="btn btn-ghost" style={{ padding: '4px 12px' }} onClick={() => setYear(y => y + 1)} disabled={year >= new Date().getFullYear()}>›</button>
    </div>
  )

  return (
    <div className="page" style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>Heatmap</h2>
        <button className="btn btn-ghost" onClick={refreshCurrentYear} disabled={refreshing}
          title="Refresh current year" style={{ fontSize: 18, padding: '4px 10px' }}>
          {refreshing ? '…' : '↻'}
        </button>
      </div>

      <div className="card" style={{ padding: '24px 28px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <div>
            <div className="chart-title" style={{ margin: 0 }}>LeetCode Submissions</div>
            {!loading && (
              <div style={{ fontSize: 12, color: '#7A869A', marginTop: 4 }}>
                {lcTotal} total submission{lcTotal !== 1 ? 's' : ''} in {year}
              </div>
            )}
          </div>
          {yearControls}
        </div>
        {loading
          ? <div className="empty" style={{ padding: '40px 0' }}>Loading…</div>
          : <HeatmapGrid
              year={year}
              data={lcData}
              colorFn={lcColor}
              tooltipFn={(iso, v) => v ? `${v} submission${v !== 1 ? 's' : ''} on ${iso}` : `No submissions on ${iso}`}
              legendCells={lcLegend}
            />
        }
      </div>

      <div className="card" style={{ padding: '24px 28px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <div>
            <div className="chart-title" style={{ margin: 0 }}>Problem Activity</div>
            {!loading && (
              <div style={{ fontSize: 12, color: '#7A869A', marginTop: 4 }}>
                {actDays} active day{actDays !== 1 ? 's' : ''} in {year}
              </div>
            )}
          </div>
          {yearControls}
        </div>
        {loading
          ? <div className="empty" style={{ padding: '40px 0' }}>Loading…</div>
          : <HeatmapGrid
              year={year}
              data={actData}
              colorFn={activityColor}
              tooltipFn={(iso, v) => {
                if (!v || (v.new === 0 && v.repeated === 0)) return `No activity on ${iso}`
                const parts = []
                if (v.new > 0)      parts.push(`${v.new} new`)
                if (v.repeated > 0) parts.push(`${v.repeated} repeated`)
                return `${parts.join(', ')} on ${iso}`
              }}
              legendCells={activityLegend}
            />
        }
      </div>

    </div>
  )
}
