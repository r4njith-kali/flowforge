import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { DashboardLayout } from './components/DashboardLayout'
import { CreateJobPage } from './pages/CreateJobPage'
import { JobDetailPage } from './pages/JobDetailPage'
import { JobsPage } from './pages/JobsPage'
import { OverviewPage } from './pages/OverviewPage'
import { AuthProvider } from './auth/AuthProvider'
import { AuthCallbackPage, RequireAuth, RequireRole } from './auth/AuthRoutes'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<AuthCallbackPage />} path="/auth/callback" />
          <Route
            element={
              <RequireAuth>
                <DashboardLayout />
              </RequireAuth>
            }
            path="/"
          >
            <Route element={<OverviewPage />} index />
            <Route element={<JobsPage />} path="jobs" />
            <Route
              element={
                <RequireRole roles={['ADMIN', 'OPERATOR']}>
                  <CreateJobPage />
                </RequireRole>
              }
              path="jobs/new"
            />
            <Route element={<JobDetailPage />} path="jobs/:id" />
            <Route element={<OverviewPage />} path="*" />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
