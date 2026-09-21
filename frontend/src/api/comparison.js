import { mockDelay } from './client';
import { mockCompare } from '../mock/fixtures';

export async function compareQueries(payload) {
  await mockDelay(500);
  return mockCompare(payload);
}
