export const RESOURCE_LEVEL_INFO = {
  LOW: {
    label: 'Low',
    spec: '~1 vCPU, 512MB RAM',
    description: 'Constrained hardware. Favors index scans over sort buffers and caps result sets aggressively to keep memory use minimal.',
  },
  MEDIUM: {
    label: 'Medium',
    spec: '~2 vCPU, 2GB RAM',
    description: 'Typical app server. Balances speed and memory — allows moderate sort buffers and tolerates small temporary tables.',
  },
  HIGH: {
    label: 'High',
    spec: '~4+ vCPU, 8GB+ RAM',
    description: 'Dedicated or high-throughput hardware. Optimizes for latency over memory — precomputes aggregates, allows larger sort buffers.',
  },
};
