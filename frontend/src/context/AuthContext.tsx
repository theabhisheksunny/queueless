import React, { createContext, useContext, useState } from 'react';
import { api } from '../api/client';

export interface UserState {
  email: string;
  fullName: string;
  role: string;
  shopId: number;
  shopName: string;
  category: string;
  subscriptionActive: boolean;
  subscriptionDaysRemaining: number;
}

interface AuthContextType {
  user: UserState | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (credentials: { email?: string; password?: string; phone?: string; pin?: string; deviceFingerprint?: string }) => Promise<void>;
  register: (data: any) => Promise<void>;
  logout: () => void;
  updateShopInfo: (name: string, category: string) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('queueless_token'));
  const [user, setUser] = useState<UserState | null>(() => {
    const saved = localStorage.getItem('queueless_user');
    return saved ? JSON.parse(saved) : null;
  });
  const [isLoading, setIsLoading] = useState(false);

  const login = async (credentials: { email?: string; password?: string; phone?: string; pin?: string; deviceFingerprint?: string }) => {
    setIsLoading(true);
    try {
      const res = await api.login(credentials);
      setToken(res.token);
      const userData: UserState = {
        email: res.email,
        fullName: res.fullName,
        role: res.role,
        shopId: res.shopId,
        shopName: res.shopName,
        category: res.category,
        subscriptionActive: res.subscriptionActive,
        subscriptionDaysRemaining: res.subscriptionDaysRemaining,
      };
      setUser(userData);
      localStorage.setItem('queueless_token', res.token);
      localStorage.setItem('queueless_user', JSON.stringify(userData));
    } finally {
      setIsLoading(false);
    }
  };

  const register = async (data: any) => {
    setIsLoading(true);
    try {
      const res = await api.register(data);
      setToken(res.token);
      const userData: UserState = {
        email: res.email,
        fullName: res.fullName,
        role: res.role,
        shopId: res.shopId,
        shopName: res.shopName,
        category: res.category,
        subscriptionActive: res.subscriptionActive,
        subscriptionDaysRemaining: res.subscriptionDaysRemaining,
      };
      setUser(userData);
      localStorage.setItem('queueless_token', res.token);
      localStorage.setItem('queueless_user', JSON.stringify(userData));
    } finally {
      setIsLoading(false);
    }
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    localStorage.removeItem('queueless_token');
    localStorage.removeItem('queueless_user');
  };

  const updateShopInfo = (name: string, category: string) => {
    if (user) {
      const updated = { ...user, shopName: name, category };
      setUser(updated);
      localStorage.setItem('queueless_user', JSON.stringify(updated));
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        isAuthenticated: !!token && !!user,
        isLoading,
        login,
        register,
        logout,
        updateShopInfo,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
