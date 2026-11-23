import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { api, type BusinessData, type BranchData } from '../api/client';

export interface UserState {
  email: string;
  fullName: string;
  role: string;
  shopId: number;
  shopName: string;
  category: string;
  subscriptionActive: boolean;
  subscriptionDaysRemaining: number;
  businesses?: BusinessData[];
  branches?: BranchData[];
  activeShopId?: number;
}

interface AuthContextType {
  user: UserState | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  activeShopId: number | null;
  activeBranch: BranchData | null;
  businesses: BusinessData[];
  branches: BranchData[];
  switchShop: (shopId: number) => void;
  refreshBusinesses: () => Promise<void>;
  login: (credentials: { email?: string; password?: string; phone?: string; pin?: string; deviceFingerprint?: string; deviceRegistrationId?: string }) => Promise<void>;
  register: (data: any) => Promise<void>;
  setAuthData: (res: any) => void;
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
  const [businesses, setBusinesses] = useState<BusinessData[]>(() => {
    const saved = localStorage.getItem('queueless_businesses');
    return saved ? JSON.parse(saved) : [];
  });
  const [branches, setBranches] = useState<BranchData[]>(() => {
    const saved = localStorage.getItem('queueless_branches');
    return saved ? JSON.parse(saved) : [];
  });
  const [activeShopId, setActiveShopId] = useState<number | null>(() => {
    const savedId = localStorage.getItem('queueless_active_shop_id');
    if (savedId) {
      const parsed = parseInt(savedId, 10);
      if (!isNaN(parsed)) return parsed;
    }
    const savedUser = localStorage.getItem('queueless_user');
    if (savedUser) {
      try {
        const u = JSON.parse(savedUser);
        return u.shopId || null;
      } catch {
        return null;
      }
    }
    return null;
  });
  const [isLoading, setIsLoading] = useState(false);

  const activeBranch = branches.find((b) => b.id === activeShopId) || null;

  const refreshBusinesses = useCallback(async () => {
    if (!localStorage.getItem('queueless_token')) return;
    try {
      if (user?.role === 'ROLE_STAFF') {
        const branchList = await api.getBranches();
        setBranches(branchList);
        localStorage.setItem('queueless_branches', JSON.stringify(branchList));
      } else {
        const [bizList, branchList] = await Promise.all([
          api.getBusinesses(),
          api.getBranches(),
        ]);
        setBusinesses(bizList);
        setBranches(branchList);
        localStorage.setItem('queueless_businesses', JSON.stringify(bizList));
        localStorage.setItem('queueless_branches', JSON.stringify(branchList));
      }
    } catch (err) {
      console.error('Failed to refresh businesses/branches', err);
    }
  }, [user?.role]);

  const switchShop = useCallback((shopId: number) => {
    setActiveShopId(shopId);
    localStorage.setItem('queueless_active_shop_id', shopId.toString());

    // Sync active branch theme and name with user state
    setBranches((prevBranches) => {
      const target = prevBranches.find((b) => b.id === shopId);
      if (target) {
        setUser((prevUser) => {
          if (!prevUser) return null;
          const updated: UserState = {
            ...prevUser,
            shopId: target.id,
            shopName: target.name,
            category: target.category || prevUser.category,
            activeShopId: target.id,
          };
          localStorage.setItem('queueless_user', JSON.stringify(updated));
          return updated;
        });
      }
      return prevBranches;
    });
  }, []);

  const handleAuthSuccess = useCallback((res: any) => {
    setToken(res.token);

    const resBranches: BranchData[] = res.branches || [];
    const resBusinesses: BusinessData[] = res.businesses || [];
    const initialShopId = res.shopId || (resBranches[0] ? resBranches[0].id : null);

    const userData: UserState = {
      email: res.email,
      fullName: res.fullName,
      role: res.role,
      shopId: initialShopId,
      shopName: res.shopName,
      category: res.category,
      subscriptionActive: res.subscriptionActive,
      subscriptionDaysRemaining: res.subscriptionDaysRemaining,
      businesses: resBusinesses,
      branches: resBranches,
      activeShopId: initialShopId,
    };

    setUser(userData);
    setBranches(resBranches);
    setBusinesses(resBusinesses);
    setActiveShopId(initialShopId);

    localStorage.setItem('queueless_token', res.token);
    localStorage.setItem('queueless_user', JSON.stringify(userData));
    localStorage.setItem('queueless_branches', JSON.stringify(resBranches));
    localStorage.setItem('queueless_businesses', JSON.stringify(resBusinesses));
    if (initialShopId) {
      localStorage.setItem('queueless_active_shop_id', initialShopId.toString());
    }
  }, []);

  const login = async (credentials: { email?: string; password?: string; phone?: string; pin?: string; deviceFingerprint?: string; deviceRegistrationId?: string }) => {
    setIsLoading(true);
    try {
      const res = await api.login(credentials);
      handleAuthSuccess(res);
    } finally {
      setIsLoading(false);
    }
  };

  const register = async (data: any) => {
    setIsLoading(true);
    try {
      const res = await api.register(data);
      handleAuthSuccess(res);
    } finally {
      setIsLoading(false);
    }
  };

  const setAuthData = useCallback((res: any) => {
    handleAuthSuccess(res);
  }, [handleAuthSuccess]);

  const logout = () => {
    api.logout().catch(() => {});
    setToken(null);
    setUser(null);
    setBranches([]);
    setBusinesses([]);
    setActiveShopId(null);
    localStorage.removeItem('queueless_token');
    localStorage.removeItem('queueless_user');
    localStorage.removeItem('queueless_branches');
    localStorage.removeItem('queueless_businesses');
    localStorage.removeItem('queueless_active_shop_id');
  };

  const updateShopInfo = (name: string, category: string) => {
    if (user) {
      const updated = { ...user, shopName: name, category };
      setUser(updated);
      localStorage.setItem('queueless_user', JSON.stringify(updated));
    }
  };

  // Handle unauthorized session expiration broadcast from API client
  useEffect(() => {
    const handleUnauthorized = () => {
      // Clear authentication credentials but preserve persistent device enrollment
      setToken(null);
      setUser(null);
      setBranches([]);
      setBusinesses([]);
      setActiveShopId(null);
      localStorage.removeItem('queueless_token');
      localStorage.removeItem('queueless_user');
      localStorage.removeItem('queueless_branches');
      localStorage.removeItem('queueless_businesses');
      localStorage.removeItem('queueless_active_shop_id');
    };

    window.addEventListener('queueless:unauthorized', handleUnauthorized);
    return () => {
      window.removeEventListener('queueless:unauthorized', handleUnauthorized);
    };
  }, []);

  // If user is authenticated on initial load, ensure branches/businesses are loaded
  useEffect(() => {
    if (token && branches.length === 0) {
      refreshBusinesses();
    }
  }, [token, branches.length, refreshBusinesses]);

  // If activeShopId is not in authorized branches and branches exist, smoothly switch to first authorized branch
  useEffect(() => {
    if (branches.length > 0 && activeShopId) {
      const hasAccess = branches.some((b) => b.id === activeShopId);
      if (!hasAccess) {
        switchShop(branches[0].id);
      }
    }
  }, [branches, activeShopId, switchShop]);

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        isAuthenticated: !!token && !!user,
        isLoading,
        activeShopId,
        activeBranch,
        businesses,
        branches,
        switchShop,
        refreshBusinesses,
        login,
        register,
        setAuthData,
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
