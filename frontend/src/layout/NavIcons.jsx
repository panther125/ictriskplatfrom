import React from 'react';

const s = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' };

export const IconDashboard = () => (
  <svg {...s}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
);

export const IconView = () => (
  <svg {...s}><rect x="3" y="4" width="18" height="16" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="9" y1="4" x2="9" y2="20"/></svg>
);

export const IconFeature = () => (
  <svg {...s}><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
);

export const IconStrategy = () => (
  <svg {...s}>
    <rect x="9" y="2" width="6" height="4" rx="1"/>
    <rect x="2" y="18" width="6" height="4" rx="1"/>
    <rect x="16" y="18" width="6" height="4" rx="1"/>
    <path d="M12 6v4"/>
    <path d="M5 18v-2h14v2"/>
    <path d="M12 10v6"/>
  </svg>
);

export const IconTrial = () => (
  <svg {...s}>
    <rect x="4" y="2" width="16" height="20" rx="2"/>
    <line x1="8" y1="6" x2="16" y2="6"/>
    <line x1="8" y1="10" x2="16" y2="10"/>
    <line x1="8" y1="14" x2="12" y2="14"/>
    <circle cx="14" cy="17" r="1.5" fill="currentColor" stroke="none"/>
  </svg>
);

export const IconMonitor = () => (
  <svg {...s}>
    <rect x="2" y="3" width="20" height="14" rx="2"/>
    <polyline points="6 12 10 8 14 13 18 9"/>
    <line x1="8" y1="21" x2="16" y2="21"/>
    <line x1="12" y1="17" x2="12" y2="21"/>
  </svg>
);

export const IconLog = () => (
  <svg {...s}>
    <path d="M12 8V4l8 8-8 8v-4"/>
    <path d="M4 12h16"/>
  </svg>
);

export const IconDatasource = () => (
  <svg {...s}>
    <ellipse cx="12" cy="5" rx="9" ry="3"/>
    <path d="M21 12c0 1.66-4.03 3-9 3s-9-1.34-9-3"/>
    <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/>
  </svg>
);

export const IconAudit = () => (
  <svg {...s}>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    <polyline points="9 12 11 14 15 10"/>
  </svg>
);

export const IconSearch = () => (
  <svg {...s}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
);
