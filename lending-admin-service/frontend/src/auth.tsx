import { createContext, ReactNode, useCallback, useContext, useEffect, useState } from 'react';
import { clearAuth, get, getAuth, setAuth, setUnauthorizedHandler } from './api';
import type { Me } from './types';

interface AuthState {
  me: Me | null;
  checking: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthState>({ me: null, checking: true, login: async () => {}, logout: () => {} });
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(true);

  const logout = useCallback(() => { clearAuth(); setMe(null); }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
    if (!getAuth()) { setChecking(false); return; }
    get<Me>('/me').then(setMe).catch(() => clearAuth()).finally(() => setChecking(false));
  }, [logout]);

  const login = useCallback(async (username: string, password: string) => {
    setAuth(username, password);
    try {
      setMe(await get<Me>('/me'));
    } catch (e) {
      clearAuth();
      throw new Error('Wrong username or password');
    }
  }, []);

  return <Ctx.Provider value={{ me, checking, login, logout }}>{children}</Ctx.Provider>;
}
