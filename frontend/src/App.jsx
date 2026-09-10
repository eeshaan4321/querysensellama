import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AppShell } from './components/shell/AppShell';
import { EngineStatusProvider } from './context/EngineStatusContext';
import { WorkbenchProvider } from './context/WorkbenchContext';
import { WorkbenchPage } from './pages/WorkbenchPage';
import { AnalysisPage } from './pages/AnalysisPage';
import { OptimizationPage } from './pages/OptimizationPage';
import { SchemaAdvisorPage } from './pages/SchemaAdvisorPage';
import { HistoryPage } from './pages/HistoryPage';
import { AboutPage } from './pages/AboutPage';

function App() {
  return (
    <EngineStatusProvider>
      <WorkbenchProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<WorkbenchPage />} />
              <Route path="analysis" element={<AnalysisPage />} />
              <Route path="optimization" element={<OptimizationPage />} />
              <Route path="schema" element={<SchemaAdvisorPage />} />
              <Route path="history" element={<HistoryPage />} />
              <Route path="about" element={<AboutPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </WorkbenchProvider>
    </EngineStatusProvider>
  );
}

export default App;
