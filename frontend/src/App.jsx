import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AppShell } from './components/shell/AppShell';
import { EngineStatusProvider } from './context/EngineStatusContext';
import { ActiveSchemaProvider } from './context/ActiveSchemaContext';
import { WorkbenchProvider } from './context/WorkbenchContext';
import { WorkbenchPage } from './pages/WorkbenchPage';
import { SchemaSourcePage } from './pages/SchemaSourcePage';
import { AnalysisPage } from './pages/AnalysisPage';
import { OptimizationPage } from './pages/OptimizationPage';
import { SchemaAdvisorPage } from './pages/SchemaAdvisorPage';
import { HistoryPage } from './pages/HistoryPage';
import { NotFoundPage } from './pages/NotFoundPage';

function App() {
  return (
    <EngineStatusProvider>
      <ActiveSchemaProvider>
        <WorkbenchProvider>
          <BrowserRouter>
            <Routes>
              <Route element={<AppShell />}>
                <Route index element={<WorkbenchPage />} />
                <Route path="schema-source" element={<SchemaSourcePage />} />
                <Route path="analysis" element={<AnalysisPage />} />
                <Route path="optimization" element={<OptimizationPage />} />
                <Route path="schema" element={<SchemaAdvisorPage />} />
                <Route path="history" element={<HistoryPage />} />
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Routes>
          </BrowserRouter>
        </WorkbenchProvider>
      </ActiveSchemaProvider>
    </EngineStatusProvider>
  );
}

export default App;
