import axios from 'axios'

const BASE = '/api'

axios.interceptors.request.use(config => {
  const userId = localStorage.getItem('lc_username')
  if (userId) config.headers['X-User-Id'] = userId
  return config
})

export const api = {
  features: {
    get: () => axios.get(`${BASE}/features`).then(r => r.data),
  },
  problems: {
    getAll: (params) => axios.get(`${BASE}/problems`, { params }).then(r => r.data),
    update: (id, data) => axios.put(`${BASE}/problems/${id}`, data).then(r => r.data),
    review: (page = 0, size = 8) => axios.get(`${BASE}/problems/review`, { params: { page, size } }).then(r => r.data),
    similar: (id)    => axios.get(`${BASE}/problems/${id}/similar`).then(r => r.data),
  },
  stats: {
    get: () => axios.get(`${BASE}/stats`).then(r => r.data),
    heatmapData: (year) => axios.get(`${BASE}/stats/heatmap-data`, { params: { year } }).then(r => r.data),
  },
  account: {
    get: (userId) => axios.get(`${BASE}/account/${userId}`).then(r => r.data),
  },
  leaderboard: {
    getAll:  () => axios.get(`${BASE}/leaderboard`).then(r => r.data),
    refresh: () => axios.post(`${BASE}/leaderboard/refresh`).then(r => r.data),
  },
  leetcode: {
    profile: (username) => axios.get(`${BASE}/leetcode/${username}`).then(r => r.data),
    sync: (username, token) => {
      const headers = {}
      if (token) headers['X-LC-Session'] = token
      return axios.post(`${BASE}/leetcode/${username}/sync`, null, { headers }).then(r => r.data)
    },
  },
  chat: {
    // streams `delta` and `error` SSE events from the backend.
    // axios doesn't support streaming response bodies in the browser, so we use raw fetch + a tiny SSE parser.
    stream: async (message, { onDelta, onStatus, onError, signal } = {}) => {
      const userId = localStorage.getItem('lc_username')
      const res = await fetch(`${BASE}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          ...(userId ? { 'X-User-Id': userId } : {}),
        },
        body: JSON.stringify({ message }),
        signal,
      })
      if (!res.ok || !res.body) {
        const text = await res.text().catch(() => '')
        throw new Error(`chat stream failed: ${res.status} ${text}`)
      }
      const reader  = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // SSE events are separated by a blank line
        let idx
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const raw = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          let eventName = 'message'
          const dataLines = []
          for (const line of raw.split('\n')) {
            if (line.startsWith('event:'))      eventName = line.slice(6).trim()
            else if (line.startsWith('data:'))  dataLines.push(line.slice(5).trimStart())
          }
          if (!dataLines.length) continue
          let payload
          try { payload = JSON.parse(dataLines.join('\n')) } catch { continue }
          if (eventName === 'delta' && payload?.text) onDelta?.(payload.text)
          else if (eventName === 'status') onStatus?.(payload?.status || '')
          else if (eventName === 'error') onError?.(payload?.text || 'unknown error')
        }
      }
    },
    clearSession: () => axios.delete(`${BASE}/chat/session`),
  },
}
