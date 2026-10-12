import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { DashboardLayout } from './components/DashboardLayout'
import { CreateJobPage } from './pages/CreateJobPage'
import { JobDetailPage } from './pages/JobDetailPage'
import { JobsPage } from './pages/JobsPage'
import { OverviewPage } from './pages/OverviewPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<DashboardLayout />} path="/">
          <Route element={<OverviewPage />} index />
          <Route element={<JobsPage />} path="jobs" />
          <Route element={<CreateJobPage />} path="jobs/new" />
          <Route element={<JobDetailPage />} path="jobs/:id" />
          <Route element={<OverviewPage />} path="*" />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
