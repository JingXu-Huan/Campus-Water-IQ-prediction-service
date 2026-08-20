import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useAuthStore } from './store/authStore'
import AgentConsole from './pages/AgentConsole'
import Dashboard from './pages/Dashboard'
import DigitalTwin from './pages/DigitalTwin'
import Help from './pages/Help'
import Login from './pages/Login'
import Monitoring from './pages/Monitoring'
import Register from './pages/Register'
import Repair from './pages/Repair'
import Reports from './pages/Reports'

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { token } = useAuthStore()
  return token ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return <BrowserRouter><Routes>
    <Route path="/agent" element={<AgentConsole />} />
    <Route path="/login" element={<Login />} />
    <Route path="/register" element={<Register />} />
    <Route path="/dashboard" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
    <Route path="/monitoring" element={<ProtectedRoute><Monitoring /></ProtectedRoute>} />
    <Route path="/digital-twin" element={<ProtectedRoute><DigitalTwin /></ProtectedRoute>} />
    <Route path="/repair" element={<ProtectedRoute><Repair /></ProtectedRoute>} />
    <Route path="/help" element={<ProtectedRoute><Help /></ProtectedRoute>} />
    <Route path="/reports" element={<ProtectedRoute><Reports /></ProtectedRoute>} />
    <Route path="/" element={<Navigate to="/agent" replace />} />
  </Routes></BrowserRouter>
}
