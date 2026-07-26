import { config } from '../common/config.js';

if (!config.serviceToken) {
  process.env.SERVICE_TOKEN = 'test-service-token';
}
