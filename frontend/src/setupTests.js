import '@testing-library/jest-dom';

window.matchMedia ??= () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} });
