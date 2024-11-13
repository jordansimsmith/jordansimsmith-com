import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createTheme, MantineProvider } from '@mantine/core';
import { App } from './App.jsx';
import '@mantine/core/styles.css';
import './index.css';

const theme = createTheme({
  headings: {
    fontWeight: 300,
  },
});

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <MantineProvider theme={theme}>
      <App />
    </MantineProvider>
  </StrictMode>,
);
