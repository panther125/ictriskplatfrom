import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './layout/AppLayout.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Views from './pages/Views.jsx';
import ViewCreate from './pages/ViewCreate.jsx';
import Features from './pages/Features.jsx';
import FeatureCreate from './pages/FeatureCreate.jsx';
import StrategyManage from './pages/StrategyManage.jsx';
import StrategyVersions from './pages/StrategyVersions.jsx';
import StrategyEditor from './pages/StrategyEditor.jsx';
import Trial from './pages/Trial.jsx';
import CrossTree from './pages/CrossTree.jsx';
import Monitoring from './pages/Monitoring.jsx';
import Track from './pages/Track.jsx';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<AppLayout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
          <Route path="views" element={<Views />} />
          <Route path="views/new" element={<ViewCreate />} />
          <Route path="views/:viewId/edit" element={<ViewCreate />} />
          <Route path="features" element={<Features />} />
          <Route path="features/:featureId/edit" element={<FeatureCreate />} />
          <Route path="strategy" element={<StrategyManage />} />
          <Route path="strategy/:strategyId/versions" element={<StrategyVersions />} />
          <Route path="strategy/:strategyId/editor" element={<StrategyEditor />} />
          <Route path="trial" element={<Trial />} />
          <Route path="cross-tree" element={<CrossTree />} />
          <Route path="monitoring" element={<Monitoring />} />
          <Route path="track" element={<Track />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
