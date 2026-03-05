import { useState } from 'react'
import Dashboard from './pages/Dashboard'
import StockSearch from './pages/StockSearch'
import OrderForm from './pages/OrderForm'
import TradeHistory from './pages/TradeHistory'
import Strategy from './pages/Strategy'
import Admin from './pages/Admin'
import './App.css'

const TABS = [
  { id: 'dashboard', label: '대시보드', icon: '📊' },
  { id: 'search', label: '종목검색', icon: '🔍' },
  { id: 'order', label: '주문', icon: '💹' },
  { id: 'history', label: '매매내역', icon: '📋' },
  { id: 'strategy', label: '전략', icon: '⚡' },
]

function App() {
  const [tab, setTab] = useState('dashboard')
  const [adminOpen, setAdminOpen] = useState(false)
  const [liveAdminConfig, setLiveAdminConfig] = useState(null)

  return (
    <div className="app">
      <header className="app-header">
        <h1>Axiom Automated Trade</h1>
        <button className="admin-btn" onClick={() => setAdminOpen(true)}>⚙️</button>
      </header>
      {adminOpen && (
        <Admin
          onClose={() => setAdminOpen(false)}
          onConfigUpdated={setLiveAdminConfig}
        />
      )}

      <main className="app-content">
        {tab === 'dashboard' && <Dashboard />}
        {tab === 'search' && <StockSearch />}
        {tab === 'order' && <OrderForm />}
        {tab === 'history' && <TradeHistory />}
        {tab === 'strategy' && <Strategy liveAdminConfig={liveAdminConfig} />}
      </main>

      <nav className="bottom-nav">
        {TABS.map(t => (
          <button
            key={t.id}
            className={`nav-btn ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            <span className="nav-icon">{t.icon}</span>
            <span className="nav-label">{t.label}</span>
          </button>
        ))}
      </nav>
    </div>
  )
}

export default App
