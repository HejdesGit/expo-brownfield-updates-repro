import { registerRootComponent } from 'expo';

import App from './App';

// Publishing with EXPO_PUBLIC_BREAK=1 produces an update whose entry module throws
// before registerRootComponent runs ("update B" in the README).
if (process.env.EXPO_PUBLIC_BREAK === '1') {
  throw new Error('Intentional module-scope error in a broken update');
}

registerRootComponent(App);
