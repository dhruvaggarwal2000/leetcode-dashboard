import { useEffect, useState } from 'react'
import { Routes, Route, NavLink, Navigate } from 'react-router-dom'
import Dashboard from './pages/Dashboard'
import Problems from './pages/Problems'
import Review from './pages/Review'
import Heatmap from './pages/Heatmap'
import Leaderboard from './pages/Leaderboard'
import Chat from './pages/Chat'
import { api } from './services/api'

export default function App() {
  const [chatEnabled, setChatEnabled] = useState(false)

  useEffect(() => {
    api.features.get()
      .then(f => setChatEnabled(!!f?.chatEnabled))
      .catch(() => setChatEnabled(false))
  }, [])

  return (
    <>
      <nav className="nav">
        <span className="nav-logo">LC Dashboard</span>
        <div className="nav-links">
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/problems">Problems</NavLink>
          <NavLink to="/review">Review</NavLink>
          <NavLink to="/heatmap">Heatmap</NavLink>
          <NavLink to="/leaderboard">Leaderboard</NavLink>
          {chatEnabled && <NavLink to="/chat">Chat</NavLink>}
        </div>
      </nav>
      <Routes>
        <Route path="/"            element={<Dashboard />} />
        <Route path="/problems"    element={<Problems />} />
        <Route path="/review"      element={<Review />} />
        <Route path="/heatmap"     element={<Heatmap />} />
        <Route path="/leaderboard" element={<Leaderboard />} />
        <Route path="/chat"        element={chatEnabled ? <Chat /> : <Navigate to="/" replace />} />
      </Routes>
    </>
  )
}
