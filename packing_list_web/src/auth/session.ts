const SESSION_KEY = 'packing_list_auth';

export interface Session {
  username: string;
  token: string;
}

export function getSession(): Session | null {
  const stored = localStorage.getItem(SESSION_KEY);
  if (!stored) {
    return null;
  }
  try {
    return JSON.parse(stored) as Session;
  } catch {
    return null;
  }
}

export function setSession(username: string, password: string): void {
  const token = btoa(`${username}:${password}`);
  const session: Session = { username, token };
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY);
}
