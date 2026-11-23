import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  api,
  type AdminDashboardSummaryData,
  type AdminPaymentItemData,
  type AdminSubscriptionData,
  type AdminUserShopData,
  type AdminActivityLogData,
} from '../api/client';
import {
  ShieldCheck,
  LayoutDashboard,
  CreditCard,
  CalendarCheck,
  Store,
  History,
  LogOut,
  RefreshCw,
  Search,
  CheckCircle2,
  XCircle,
  Clock,
  AlertCircle,
  Menu,
  X,
  ExternalLink,
  ChevronRight,
  Eye,
  EyeOff,
  Check,
  User,
  Lock,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

type ActiveTab = 'dashboard' | 'payments' | 'subscriptions' | 'users' | 'activity';

export const SuperAdminPage: React.FC = () => {
  const { user, token, setAuthData, logout } = useAuth();
  const [identifierInput, setIdentifierInput] = useState<string>('superadmin@queueless.in');
  const [passwordInput, setPasswordInput] = useState<string>('');
  const [showPassword, setShowPassword] = useState<boolean>(false);
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(() => {
    return Boolean(token && user?.role === 'ROLE_SUPER_ADMIN');
  });
  const [activeTab, setActiveTab] = useState<ActiveTab>('dashboard');
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState<boolean>(false);

  // Global State
  const [summary, setSummary] = useState<AdminDashboardSummaryData | null>(null);
  const [payments, setPayments] = useState<AdminPaymentItemData[]>([]);
  const [subscriptions, setSubscriptions] = useState<AdminSubscriptionData[]>([]);
  const [usersShops, setUsersShops] = useState<AdminUserShopData[]>([]);
  const [activityLogs, setActivityLogs] = useState<AdminActivityLogData[]>([]);

  // Loading & Alert State
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Filters & Search
  const [paymentFilter, setPaymentFilter] = useState<'ALL' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'>('ALL');
  const [paymentSearch, setPaymentSearch] = useState<string>('');
  const [subFilter, setSubFilter] = useState<'ALL' | 'ACTIVE' | 'EXPIRED' | 'PENDING' | 'SUSPENDED'>('ALL');
  const [subSearch, setSubSearch] = useState<string>('');
  const [userSearch, setUserSearch] = useState<string>('');
  const [activityFilter, setActivityFilter] = useState<string>('ALL');

  // Modals
  const [selectedPayment, setSelectedPayment] = useState<AdminPaymentItemData | null>(null);
  const [isConfirmApproveOpen, setIsConfirmApproveOpen] = useState<boolean>(false);
  const [isConfirmRejectOpen, setIsConfirmRejectOpen] = useState<boolean>(false);
  const [rejectionReason, setRejectionReason] = useState<string>('');
  const [isActionInProgress, setIsActionInProgress] = useState<boolean>(false);

  const [selectedOwnerHistory, setSelectedOwnerHistory] = useState<{
    ownerName: string;
    phone: string;
    email?: string;
    subscriptions: AdminSubscriptionData[];
    payments: AdminPaymentItemData[];
  } | null>(null);
  const [_isLoadingOwnerHistory, setIsLoadingOwnerHistory] = useState<boolean>(false);

  // Load all Super Admin data
  const loadAllData = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const [sumData, payData, subData, userShopData, logData] = await Promise.all([
        api.getSuperAdminDashboardSummary(),
        api.getAdminPayments(undefined, 0, 50),
        api.getSuperAdminSubscriptions(undefined, 0, 50),
        api.getSuperAdminUsersAndShops(undefined, 0, 50),
        api.getSuperAdminActivityLogs(0, 50),
      ]);
      setSummary(sumData);
      setPayments(payData);
      setSubscriptions(subData);
      setUsersShops(userShopData);
      setActivityLogs(logData);
      setIsAuthenticated(true);
    } catch (err: any) {
      console.error('Super Admin fetch error:', err);
      setErrorMessage(err.message || 'Authentication failed or server error.');
      if (err.status === 403 || err.status === 401) {
        setIsAuthenticated(false);
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Initial check on mount
  useEffect(() => {
    if (token && user?.role === 'ROLE_SUPER_ADMIN') {
      loadAllData();
    }
  }, [token, user?.role, loadAllData]);

  // Handle Login
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!identifierInput.trim() || !passwordInput) return;
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const res = await api.login({ email: identifierInput.trim(), password: passwordInput });
      if (res.role !== 'ROLE_SUPER_ADMIN') {
        throw new Error('Access denied: Account does not have SUPER_ADMIN privileges.');
      }
      setAuthData(res);
      setIsAuthenticated(true);
      await loadAllData();
    } catch (err: any) {
      console.error('Super Admin sign-in error:', err);
      setErrorMessage(err.message || 'Invalid Super Administrator credentials.');
      setIsAuthenticated(false);
    } finally {
      setIsLoading(false);
    }
  };

  // Handle Logout
  const handleLogout = () => {
    logout();
    setIsAuthenticated(false);
    setPasswordInput('');
    setSummary(null);
    setPayments([]);
    setSubscriptions([]);
    setUsersShops([]);
    setActivityLogs([]);
  };

  // Clear messages after 5 seconds
  useEffect(() => {
    if (successMessage) {
      const t = setTimeout(() => setSuccessMessage(null), 5000);
      return () => clearTimeout(t);
    }
  }, [successMessage]);

  useEffect(() => {
    if (errorMessage) {
      const t = setTimeout(() => setErrorMessage(null), 8000);
      return () => clearTimeout(t);
    }
  }, [errorMessage]);

  // Handle Payment Approval
  const handleApprovePayment = async () => {
    if (!selectedPayment) return;
    setIsActionInProgress(true);
    try {
      await api.approveAdminPayment(selectedPayment.id);
      setSuccessMessage(`Payment for ₹${selectedPayment.amount} (UTR: ${selectedPayment.paymentReference}) approved successfully.`);
      setIsConfirmApproveOpen(false);
      setSelectedPayment(null);
      await loadAllData();
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to approve payment.');
    } finally {
      setIsActionInProgress(false);
    }
  };

  // Handle Payment Rejection
  const handleRejectPayment = async () => {
    if (!selectedPayment) return;
    if (!rejectionReason.trim()) {
      alert('Please provide a rejection reason.');
      return;
    }
    setIsActionInProgress(true);
    try {
      await api.rejectAdminPayment(selectedPayment.id, rejectionReason.trim());
      setSuccessMessage(`Payment with UTR ${selectedPayment.paymentReference} rejected.`);
      setIsConfirmRejectOpen(false);
      setSelectedPayment(null);
      setRejectionReason('');
      await loadAllData();
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to reject payment.');
    } finally {
      setIsActionInProgress(false);
    }
  };

  // Inspect Owner History
  const handleInspectOwner = async (ownerId: number, fallbackName: string, fallbackPhone: string) => {
    setIsLoadingOwnerHistory(true);
    try {
      const history = await api.getSuperAdminOwnerHistory(ownerId);
      setSelectedOwnerHistory({
        ownerName: history.ownerName || fallbackName,
        phone: history.phone || fallbackPhone,
        email: history.email,
        subscriptions: history.subscriptions || [],
        payments: history.payments || [],
      });
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to fetch owner history.');
    } finally {
      setIsLoadingOwnerHistory(false);
    }
  };

  // Helper: Format Payment Purpose Label
  const formatPaymentPurpose = (type: string, planType?: string, batchCount?: number) => {
    switch (type) {
      case 'SUBSCRIPTION':
        return planType === 'YEARLY' ? 'Yearly Subscription (₹501)' : 'Monthly Subscription (₹51)';
      case 'BRANCH_REGISTRATION':
        return batchCount && batchCount > 1
          ? `Additional Branches (${batchCount}x ₹99 = ₹${batchCount * 99})`
          : 'Additional Branch (₹99)';
      case 'USER_SEAT':
        return planType === 'YEARLY' ? 'Staff Seat Yearly (₹494)' : 'Staff Seat Monthly (₹47)';
      case 'CATEGORY_CHANGE':
        return 'Category Change (₹101)';
      default:
        return type || 'Payment';
    }
  };

  // Helper: Format Date
  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '—';
    try {
      const d = new Date(dateStr);
      return d.toLocaleDateString('en-IN', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  };

  // Filtered Payments
  const filteredPayments = useMemo(() => {
    return payments.filter((p) => {
      if (paymentFilter !== 'ALL' && p.status !== paymentFilter) return false;
      if (!paymentSearch.trim()) return true;
      const q = paymentSearch.toLowerCase().trim();
      const name = (p.ownerName || '').toLowerCase();
      const phone = (p.ownerPhone || '').toLowerCase();
      const utr = (p.paymentReference || '').toLowerCase();
      const shop = (p.shopName || '').toLowerCase();
      return name.includes(q) || phone.includes(q) || utr.includes(q) || shop.includes(q);
    });
  }, [payments, paymentFilter, paymentSearch]);

  // Filtered Subscriptions
  const filteredSubscriptions = useMemo(() => {
    return subscriptions.filter((s) => {
      if (subFilter !== 'ALL' && s.status !== subFilter) return false;
      if (!subSearch.trim()) return true;
      const q = subSearch.toLowerCase().trim();
      const name = (s.ownerName || '').toLowerCase();
      const phone = (s.ownerPhone || '').toLowerCase();
      const shop = (s.shopName || '').toLowerCase();
      return name.includes(q) || phone.includes(q) || shop.includes(q);
    });
  }, [subscriptions, subFilter, subSearch]);

  // Filtered Users & Shops
  const filteredUsersShops = useMemo(() => {
    if (!userSearch.trim()) return usersShops;
    const q = userSearch.toLowerCase().trim();
    return usersShops.filter((u) => {
      const name = (u.ownerName || '').toLowerCase();
      const phone = (u.phone || '').toLowerCase();
      const email = (u.email || '').toLowerCase();
      const hasShop = u.branches.some(
        (b) =>
          b.name.toLowerCase().includes(q) ||
          (b.branchName && b.branchName.toLowerCase().includes(q)) ||
          b.category.toLowerCase().includes(q)
      );
      return name.includes(q) || phone.includes(q) || email.includes(q) || hasShop;
    });
  }, [usersShops, userSearch]);

  // Filtered Activity Logs
  const filteredLogs = useMemo(() => {
    return activityLogs.filter((l) => {
      if (activityFilter === 'PAYMENTS') return l.action.includes('PAYMENT');
      if (activityFilter === 'SUBSCRIPTION') return l.action.includes('SUBSCRIPTION');
      if (activityFilter === 'CATEGORY') return l.action.includes('CATEGORY');
      return true;
    });
  }, [activityLogs, activityFilter]);

  // =========================================================================
  // Authentication Screen
  // =========================================================================
  if (!isAuthenticated) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
        <div className="w-full max-w-md bg-white rounded-3xl border border-slate-200 shadow-xl p-8">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-12 h-12 rounded-2xl bg-brand-600 text-white flex items-center justify-center shadow-md">
              <ShieldCheck className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-xl font-black text-slate-900 tracking-tight">QueueLess Super Admin</h1>
              <p className="text-xs text-slate-500 font-medium">Platform Payments & Subscription Management</p>
            </div>
          </div>

          {errorMessage && (
            <div className="mb-6 p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-xs font-semibold flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{errorMessage}</span>
            </div>
          )}

          <form onSubmit={handleLogin} className="space-y-4">
            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                Administrator Identifier
              </label>
              <div className="relative">
                <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  required
                  value={identifierInput}
                  onChange={(e) => setIdentifierInput(e.target.value)}
                  placeholder="Email or phone (e.g. superadmin@queueless.in)"
                  className="w-full pl-10 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20 outline-none text-slate-900 text-sm font-medium"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  required
                  value={passwordInput}
                  onChange={(e) => setPasswordInput(e.target.value)}
                  placeholder="Enter administrator password"
                  className="w-full pl-10 pr-10 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20 outline-none text-slate-900 text-sm font-medium"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 transition-colors"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            <button
              type="submit"
              disabled={isLoading}
              className="w-full py-3.5 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm transition-all shadow-md hover:shadow-lg disabled:opacity-50"
            >
              {isLoading ? 'Authenticating...' : 'Sign In to Super Admin'}
            </button>
          </form>

          <div className="mt-6 pt-6 border-t border-slate-100 flex items-center justify-between text-xs text-slate-500">
            <Link to="/" className="hover:text-brand-600 font-medium transition-colors">
              ← Return to Main App
            </Link>
            <Link to="/admin" className="hover:text-brand-600 font-medium transition-colors">
              Classic Admin
            </Link>
          </div>
        </div>
      </div>
    );
  }

  // =========================================================================
  // Main Super Admin Portal UI
  // =========================================================================
  return (
    <div className="min-h-screen bg-slate-50 flex flex-col md:flex-row text-slate-900">
      {/* Sidebar for Desktop */}
      <aside className="hidden md:flex flex-col w-64 bg-white border-r border-slate-200 shrink-0">
        <div className="p-6 border-b border-slate-100 flex items-center gap-3">
          <div className="w-10 h-10 rounded-2xl bg-brand-600 text-white flex items-center justify-center shadow-md">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-black text-slate-900 leading-tight">QueueLess</h2>
            <span className="text-[10px] font-bold uppercase tracking-wider text-brand-600 bg-brand-50 px-2 py-0.5 rounded-full">
              Super Admin
            </span>
          </div>
        </div>

        {/* Navigation Links */}
        <nav className="p-4 space-y-1.5 flex-1">
          <button
            onClick={() => setActiveTab('dashboard')}
            className={`w-full flex items-center gap-3 px-4 py-3 rounded-2xl font-bold text-sm transition-all ${
              activeTab === 'dashboard'
                ? 'bg-brand-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <LayoutDashboard className="w-4 h-4" />
            <span>Dashboard</span>
          </button>

          <button
            onClick={() => setActiveTab('payments')}
            className={`w-full flex items-center justify-between px-4 py-3 rounded-2xl font-bold text-sm transition-all ${
              activeTab === 'payments'
                ? 'bg-brand-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <div className="flex items-center gap-3">
              <CreditCard className="w-4 h-4" />
              <span>Payments</span>
            </div>
            {summary && summary.pendingPayments > 0 && (
              <span
                className={`text-xs px-2 py-0.5 rounded-full font-extrabold ${
                  activeTab === 'payments' ? 'bg-white text-brand-700' : 'bg-amber-100 text-amber-800'
                }`}
              >
                {summary.pendingPayments}
              </span>
            )}
          </button>

          <button
            onClick={() => setActiveTab('subscriptions')}
            className={`w-full flex items-center gap-3 px-4 py-3 rounded-2xl font-bold text-sm transition-all ${
              activeTab === 'subscriptions'
                ? 'bg-brand-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <CalendarCheck className="w-4 h-4" />
            <span>Subscriptions</span>
          </button>

          <button
            onClick={() => setActiveTab('users')}
            className={`w-full flex items-center gap-3 px-4 py-3 rounded-2xl font-bold text-sm transition-all ${
              activeTab === 'users'
                ? 'bg-brand-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <Store className="w-4 h-4" />
            <span>Users & Shops</span>
          </button>

          <button
            onClick={() => setActiveTab('activity')}
            className={`w-full flex items-center gap-3 px-4 py-3 rounded-2xl font-bold text-sm transition-all ${
              activeTab === 'activity'
                ? 'bg-brand-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <History className="w-4 h-4" />
            <span>Activity Logs</span>
          </button>
        </nav>

        {/* Footer Actions */}
        <div className="p-4 border-t border-slate-100 space-y-2">
          <Link
            to="/admin"
            className="w-full flex items-center justify-between px-4 py-2.5 rounded-xl text-xs font-semibold text-slate-500 hover:text-slate-900 hover:bg-slate-50 transition-colors"
          >
            <span>Classic Admin Settings</span>
            <ExternalLink className="w-3.5 h-3.5" />
          </Link>
          <button
            onClick={handleLogout}
            className="w-full flex items-center gap-2.5 px-4 py-2.5 rounded-xl text-xs font-bold text-rose-600 hover:bg-rose-50 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            <span>Exit Portal</span>
          </button>
        </div>
      </aside>

      {/* Mobile Top Navigation */}
      <header className="md:hidden bg-white border-b border-slate-200 px-4 py-3 flex items-center justify-between sticky top-0 z-30 shadow-sm">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-brand-600 text-white flex items-center justify-center">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <span className="font-black text-sm text-slate-900">QueueLess Super Admin</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => loadAllData()}
            disabled={isLoading}
            className="p-2 rounded-xl text-slate-500 hover:bg-slate-100"
          >
            <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
            className="p-2 rounded-xl text-slate-700 hover:bg-slate-100"
          >
            {isMobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </header>

      {/* Mobile Drawer */}
      {isMobileMenuOpen && (
        <div className="md:hidden fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-sm flex">
          <div className="w-64 bg-white h-full shadow-2xl p-4 flex flex-col">
            <div className="flex items-center justify-between pb-4 border-b border-slate-100 mb-4">
              <span className="font-black text-sm text-slate-900">Navigation</span>
              <button onClick={() => setIsMobileMenuOpen(false)} className="p-1 rounded-lg hover:bg-slate-100">
                <X className="w-5 h-5 text-slate-500" />
              </button>
            </div>
            <nav className="space-y-1.5 flex-1">
              {(['dashboard', 'payments', 'subscriptions', 'users', 'activity'] as ActiveTab[]).map((tab) => (
                <button
                  key={tab}
                  onClick={() => {
                    setActiveTab(tab);
                    setIsMobileMenuOpen(false);
                  }}
                  className={`w-full flex items-center justify-between px-4 py-3 rounded-2xl font-bold text-sm capitalize ${
                    activeTab === tab ? 'bg-brand-600 text-white' : 'text-slate-600 hover:bg-slate-100'
                  }`}
                >
                  <span>{tab === 'users' ? 'Users & Shops' : tab === 'activity' ? 'Activity Logs' : tab}</span>
                  {tab === 'payments' && summary && summary.pendingPayments > 0 && (
                    <span className="text-xs px-2 py-0.5 rounded-full bg-amber-200 text-amber-900 font-extrabold">
                      {summary.pendingPayments}
                    </span>
                  )}
                </button>
              ))}
            </nav>
            <div className="pt-4 border-t border-slate-100 space-y-2">
              <button
                onClick={handleLogout}
                className="w-full flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-bold text-rose-600 hover:bg-rose-50"
              >
                <LogOut className="w-4 h-4" />
                <span>Exit Portal</span>
              </button>
            </div>
          </div>
          <div className="flex-1" onClick={() => setIsMobileMenuOpen(false)} />
        </div>
      )}

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col min-w-0 overflow-y-auto">
        {/* Top Header */}
        <div className="hidden md:flex bg-white border-b border-slate-200 px-8 py-4 items-center justify-between sticky top-0 z-20">
          <div>
            <h1 className="text-xl font-black text-slate-900">
              {activeTab === 'users'
                ? 'Users & Physical Shops'
                : activeTab === 'activity'
                ? 'Platform Activity Logs'
                : activeTab === 'dashboard'
                ? 'Dashboard'
                : activeTab === 'payments'
                ? 'Payments'
                : 'Subscriptions'}
            </h1>
            <p className="text-xs text-slate-500 font-medium">Platform Administration & Verification Portal</p>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={() => loadAllData()}
              disabled={isLoading}
              className="flex items-center gap-2 px-3.5 py-2 rounded-2xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 text-xs font-bold transition-all shadow-sm"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
              <span>{isLoading ? 'Refreshing...' : 'Refresh Data'}</span>
            </button>
            <div className="h-4 w-px bg-slate-200" />
            <div className="text-right">
              <p className="text-xs font-bold text-slate-800">Super Administrator</p>
              <p className="text-[10px] text-emerald-600 font-semibold flex items-center gap-1 justify-end">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 inline-block animate-pulse" />
                Live Database
              </p>
            </div>
          </div>
        </div>

        {/* Toast Alerts */}
        <div className="p-4 sm:p-6 md:p-8 space-y-6">
          {successMessage && (
            <div className="p-4 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm font-semibold flex items-center gap-3 shadow-sm animate-fadeIn">
              <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0" />
              <span>{successMessage}</span>
            </div>
          )}

          {errorMessage && (
            <div className="p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-800 text-sm font-semibold flex items-center gap-3 shadow-sm">
              <XCircle className="w-5 h-5 text-rose-600 shrink-0" />
              <span>{errorMessage}</span>
            </div>
          )}

          {/* ================================================================= */}
          {/* 1. DASHBOARD VIEW */}
          {/* ================================================================= */}
          {activeTab === 'dashboard' && summary && (
            <div className="space-y-8">
              {/* 6 Essential Summary Cards */}
              <div className="grid grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
                {/* 1. Total Shop Owners */}
                <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Shop Owners</span>
                    <Store className="w-4 h-4 text-slate-400" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-slate-900">{summary.totalShopOwners}</div>
                  <p className="text-[11px] text-slate-500 font-medium mt-1">Platform verified merchants</p>
                </div>

                {/* 2. Total Registered Branches */}
                <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Registered Branches</span>
                    <Store className="w-4 h-4 text-brand-600" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-slate-900">{summary.totalRegisteredBranches}</div>
                  <p className="text-[11px] text-slate-500 font-medium mt-1">Primary & secondary outlets</p>
                </div>

                {/* 3. Active Subscriptions */}
                <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Active Subscriptions</span>
                    <CalendarCheck className="w-4 h-4 text-emerald-600" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-emerald-600">{summary.activeSubscriptions}</div>
                  <p className="text-[11px] text-slate-500 font-medium mt-1">Currently entitled accounts</p>
                </div>

                {/* 4. Expired Subscriptions */}
                <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Expired Subscriptions</span>
                    <Clock className="w-4 h-4 text-slate-400" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-slate-700">{summary.expiredSubscriptions}</div>
                  <p className="text-[11px] text-slate-500 font-medium mt-1">Awaiting renewal payment</p>
                </div>

                {/* 5. Pending Payments */}
                <div
                  onClick={() => {
                    setPaymentFilter('PENDING_APPROVAL');
                    setActiveTab('payments');
                  }}
                  className={`bg-white p-5 rounded-3xl border shadow-sm cursor-pointer transition-all hover:scale-[1.01] ${
                    summary.pendingPayments > 0 ? 'border-amber-300 ring-2 ring-amber-400/20 bg-amber-50/20' : 'border-slate-200'
                  }`}
                >
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-amber-700 uppercase tracking-wider">Pending Payments</span>
                    <AlertCircle className="w-4 h-4 text-amber-600" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-amber-600">{summary.pendingPayments}</div>
                  <p className="text-[11px] text-amber-700 font-medium mt-1">Action required (click to review)</p>
                </div>

                {/* 6. Total Approved Payments */}
                <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Approved Payments</span>
                    <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                  </div>
                  <div className="text-2xl sm:text-3xl font-black text-slate-900">{summary.totalApprovedPayments}</div>
                  <p className="text-[11px] text-slate-500 font-medium mt-1">
                    Total volume: ₹{Number(summary.totalApprovedAmount || 0).toLocaleString('en-IN')}
                  </p>
                </div>
              </div>

              {/* Latest Pending Payments Section */}
              <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
                  <div>
                    <h3 className="text-base font-bold text-slate-900">Latest Pending Payments</h3>
                    <p className="text-xs text-slate-500">Payments submitted by shop owners waiting for UTR confirmation</p>
                  </div>
                  <button
                    onClick={() => {
                      setPaymentFilter('PENDING_APPROVAL');
                      setActiveTab('payments');
                    }}
                    className="text-xs font-bold text-brand-600 hover:text-brand-700 flex items-center gap-1"
                  >
                    View All ({summary.pendingPayments}) <ChevronRight className="w-4 h-4" />
                  </button>
                </div>

                {summary.latestPendingPayments && summary.latestPendingPayments.length > 0 ? (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-wider text-slate-500 border-b border-slate-100">
                        <tr>
                          <th className="px-6 py-3">Owner</th>
                          <th className="px-6 py-3">Purpose</th>
                          <th className="px-6 py-3">Amount</th>
                          <th className="px-6 py-3">UTR Reference</th>
                          <th className="px-6 py-3">Submitted At</th>
                          <th className="px-6 py-3 text-right">Action</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {summary.latestPendingPayments.map((p) => (
                          <tr key={p.id} className="hover:bg-slate-50/60 transition-colors">
                            <td className="px-6 py-3.5">
                              <p className="font-bold text-slate-900">{p.ownerName || 'Unknown Owner'}</p>
                              <p className="text-xs text-slate-500">{p.ownerPhone || '—'}</p>
                            </td>
                            <td className="px-6 py-3.5 text-xs font-medium text-slate-700">
                              {formatPaymentPurpose(p.paymentType, p.planType, p.batchCount)}
                            </td>
                            <td className="px-6 py-3.5 font-bold text-slate-900">₹{p.amount}</td>
                            <td className="px-6 py-3.5 font-mono text-xs text-slate-700 font-semibold">{p.paymentReference}</td>
                            <td className="px-6 py-3.5 text-xs text-slate-500">{formatDate(p.createdAt)}</td>
                            <td className="px-6 py-3.5 text-right">
                              <button
                                onClick={() => setSelectedPayment(p)}
                                className="px-3 py-1.5 rounded-xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-xs shadow-sm transition-colors"
                              >
                                Review
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="p-8 text-center text-slate-400 text-sm">
                    <CheckCircle2 className="w-8 h-8 text-emerald-500 mx-auto mb-2 opacity-80" />
                    <p className="font-semibold text-slate-600">All caught up!</p>
                    <p className="text-xs text-slate-400">Zero pending payments awaiting review.</p>
                  </div>
                )}
              </div>

              {/* Two Column Section: Recently Approved Payments & Recently Registered Shop Owners */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Recently Approved Payments */}
                <div className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6">
                  <div className="flex items-center justify-between mb-4">
                    <h3 className="text-base font-bold text-slate-900">Recently Approved Payments</h3>
                    <Check className="w-4 h-4 text-emerald-600" />
                  </div>
                  {summary.recentlyApprovedPayments && summary.recentlyApprovedPayments.length > 0 ? (
                    <div className="space-y-3">
                      {summary.recentlyApprovedPayments.map((p) => (
                        <div key={p.id} className="p-3.5 rounded-2xl bg-slate-50 border border-slate-100 flex items-center justify-between">
                          <div>
                            <p className="text-sm font-bold text-slate-900">{p.ownerName || 'Verified Merchant'}</p>
                            <p className="text-xs text-slate-500">
                              {formatPaymentPurpose(p.paymentType, p.planType)} • UTR: {p.paymentReference}
                            </p>
                          </div>
                          <div className="text-right">
                            <span className="font-extrabold text-sm text-emerald-600">₹{p.amount}</span>
                            <p className="text-[10px] text-slate-400">{formatDate(p.reviewedAt || p.createdAt)}</p>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-xs text-slate-400 text-center py-6">No approved payment history yet.</p>
                  )}
                </div>

                {/* Recently Registered Shop Owners */}
                <div className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6">
                  <div className="flex items-center justify-between mb-4">
                    <h3 className="text-base font-bold text-slate-900">Recently Registered Shop Owners</h3>
                    <Store className="w-4 h-4 text-brand-600" />
                  </div>
                  {summary.recentlyRegisteredOwners && summary.recentlyRegisteredOwners.length > 0 ? (
                    <div className="space-y-3">
                      {summary.recentlyRegisteredOwners.map((o) => (
                        <div key={o.ownerId} className="p-3.5 rounded-2xl bg-slate-50 border border-slate-100 flex items-center justify-between">
                          <div>
                            <p className="text-sm font-bold text-slate-900">{o.ownerName}</p>
                            <p className="text-xs text-slate-500">
                              {o.phone} • {o.branchCount} {o.branchCount === 1 ? 'branch' : 'branches'}
                            </p>
                          </div>
                          <div className="text-right">
                            <span
                              className={`text-[10px] font-extrabold px-2 py-0.5 rounded-full ${
                                o.subscriptionStatus === 'ACTIVE'
                                  ? 'bg-emerald-100 text-emerald-800'
                                  : 'bg-slate-200 text-slate-700'
                              }`}
                            >
                              {o.subscriptionStatus}
                            </span>
                            <p className="text-[10px] text-slate-400 mt-1">{formatDate(o.registeredAt)}</p>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-xs text-slate-400 text-center py-6">No registered owners found.</p>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* ================================================================= */}
          {/* 2. PAYMENTS PAGE (THE MOST IMPORTANT SCREEN) */}
          {/* ================================================================= */}
          {activeTab === 'payments' && (
            <div className="space-y-6">
              {/* Filter Controls & Search */}
              <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm flex flex-col sm:flex-row gap-4 items-center justify-between">
                {/* Search */}
                <div className="relative w-full sm:w-80">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={paymentSearch}
                    onChange={(e) => setPaymentSearch(e.target.value)}
                    placeholder="Search owner, phone, or UTR..."
                    className="w-full pl-10 pr-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-xs text-slate-900"
                  />
                  {paymentSearch && (
                    <button onClick={() => setPaymentSearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400">
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>

                {/* Filter Tabs */}
                <div className="flex items-center gap-1.5 w-full sm:w-auto overflow-x-auto pb-1 sm:pb-0">
                  {(['ALL', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED'] as const).map((status) => (
                    <button
                      key={status}
                      onClick={() => setPaymentFilter(status)}
                      className={`px-3.5 py-2 rounded-2xl font-bold text-xs transition-all whitespace-nowrap ${
                        paymentFilter === status
                          ? 'bg-slate-900 text-white shadow-sm'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                      }`}
                    >
                      {status === 'ALL'
                        ? 'All'
                        : status === 'PENDING_APPROVAL'
                        ? 'Pending'
                        : status === 'APPROVED'
                        ? 'Approved'
                        : 'Rejected'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Payments Table */}
              <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
                  <h3 className="text-sm font-bold text-slate-900">
                    Payment Transactions ({filteredPayments.length})
                  </h3>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-wider text-slate-500 border-b border-slate-100">
                      <tr>
                        <th className="px-6 py-3">Owner Name</th>
                        <th className="px-6 py-3">Mobile</th>
                        <th className="px-6 py-3">Purpose</th>
                        <th className="px-6 py-3">Amount</th>
                        <th className="px-6 py-3">UTR Reference</th>
                        <th className="px-6 py-3">Submission Date</th>
                        <th className="px-6 py-3">Status</th>
                        <th className="px-6 py-3 text-right">Action</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredPayments.map((p) => (
                        <tr key={p.id} className="hover:bg-slate-50/70 transition-colors">
                          <td className="px-6 py-3.5 font-bold text-slate-900">{p.ownerName || '—'}</td>
                          <td className="px-6 py-3.5 text-xs text-slate-600 font-medium">{p.ownerPhone || '—'}</td>
                          <td className="px-6 py-3.5">
                            <span className="text-xs font-semibold text-slate-800">
                              {formatPaymentPurpose(p.paymentType, p.planType, p.batchCount)}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 font-extrabold text-slate-900">₹{p.amount}</td>
                          <td className="px-6 py-3.5 font-mono text-xs font-bold text-slate-700">{p.paymentReference}</td>
                          <td className="px-6 py-3.5 text-xs text-slate-500">{formatDate(p.createdAt)}</td>
                          <td className="px-6 py-3.5">
                            <span
                              className={`text-[11px] font-extrabold px-2.5 py-1 rounded-full ${
                                p.status === 'APPROVED'
                                  ? 'bg-emerald-100 text-emerald-800'
                                  : p.status === 'REJECTED'
                                  ? 'bg-rose-100 text-rose-800'
                                  : 'bg-amber-100 text-amber-800 animate-pulse'
                              }`}
                            >
                              {p.status === 'PENDING_APPROVAL' ? 'Pending' : p.status}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 text-right">
                            <button
                              onClick={() => setSelectedPayment(p)}
                              className="px-3.5 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-100 text-slate-700 font-bold text-xs transition-colors shadow-sm inline-flex items-center gap-1.5"
                            >
                              <Eye className="w-3.5 h-3.5" />
                              <span>View Details</span>
                            </button>
                          </td>
                        </tr>
                      ))}
                      {filteredPayments.length === 0 && (
                        <tr>
                          <td colSpan={8} className="px-6 py-12 text-center text-slate-400 text-sm">
                            No payment transactions match the selected filters.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* ================================================================= */}
          {/* 3. SUBSCRIPTIONS PAGE */}
          {/* ================================================================= */}
          {activeTab === 'subscriptions' && (
            <div className="space-y-6">
              {/* Filter Controls & Search */}
              <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm flex flex-col sm:flex-row gap-4 items-center justify-between">
                <div className="relative w-full sm:w-80">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={subSearch}
                    onChange={(e) => setSubSearch(e.target.value)}
                    placeholder="Search owner or shop..."
                    className="w-full pl-10 pr-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-xs text-slate-900"
                  />
                  {subSearch && (
                    <button onClick={() => setSubSearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400">
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>

                <div className="flex items-center gap-1.5 w-full sm:w-auto overflow-x-auto pb-1 sm:pb-0">
                  {(['ALL', 'ACTIVE', 'EXPIRED', 'PENDING', 'SUSPENDED'] as const).map((status) => (
                    <button
                      key={status}
                      onClick={() => setSubFilter(status)}
                      className={`px-3.5 py-2 rounded-2xl font-bold text-xs transition-all whitespace-nowrap ${
                        subFilter === status
                          ? 'bg-slate-900 text-white shadow-sm'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                      }`}
                    >
                      {status === 'ALL' ? 'All' : status}
                    </button>
                  ))}
                </div>
              </div>

              {/* Subscriptions Table */}
              <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
                  <h3 className="text-sm font-bold text-slate-900">
                    Subscriptions ({filteredSubscriptions.length})
                  </h3>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-wider text-slate-500 border-b border-slate-100">
                      <tr>
                        <th className="px-6 py-3">Owner Name</th>
                        <th className="px-6 py-3">Shop / Outlet</th>
                        <th className="px-6 py-3">Plan</th>
                        <th className="px-6 py-3">Amount</th>
                        <th className="px-6 py-3">Start Date</th>
                        <th className="px-6 py-3">Expiry Date</th>
                        <th className="px-6 py-3">Status</th>
                        <th className="px-6 py-3 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredSubscriptions.map((s) => (
                        <tr key={s.id} className="hover:bg-slate-50/70 transition-colors">
                          <td className="px-6 py-3.5">
                            <p className="font-bold text-slate-900">{s.ownerName || '—'}</p>
                            <p className="text-xs text-slate-500">{s.ownerPhone || '—'}</p>
                          </td>
                          <td className="px-6 py-3.5 font-medium text-slate-800">{s.shopName || 'Primary Shop'}</td>
                          <td className="px-6 py-3.5">
                            <span className="font-bold text-xs text-brand-700 bg-brand-50 px-2.5 py-1 rounded-xl">
                              {s.plan === 'YEARLY' ? 'Yearly (₹501)' : s.plan === 'MONTHLY' ? 'Monthly (₹51)' : s.plan}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 font-bold text-slate-900">₹{s.amount}</td>
                          <td className="px-6 py-3.5 text-xs text-slate-600">{s.startDate || '—'}</td>
                          <td className="px-6 py-3.5">
                            <p className="text-xs font-semibold text-slate-900">{s.endDate || '—'}</p>
                            {s.daysRemaining !== undefined && (
                              <p className="text-[10px] text-slate-400">
                                {s.daysRemaining > 0 ? `${s.daysRemaining} days left` : 'Expired'}
                              </p>
                            )}
                          </td>
                          <td className="px-6 py-3.5">
                            <span
                              className={`text-[11px] font-extrabold px-2.5 py-1 rounded-full ${
                                s.status === 'ACTIVE'
                                  ? 'bg-emerald-100 text-emerald-800'
                                  : 'bg-slate-200 text-slate-700'
                              }`}
                            >
                              {s.status}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 text-right">
                            <button
                              onClick={() => s.ownerId && handleInspectOwner(s.ownerId, s.ownerName || 'Owner', s.ownerPhone || '')}
                              disabled={!s.ownerId}
                              className="px-3.5 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-100 text-slate-700 font-bold text-xs transition-colors shadow-sm"
                            >
                              Inspect History
                            </button>
                          </td>
                        </tr>
                      ))}
                      {filteredSubscriptions.length === 0 && (
                        <tr>
                          <td colSpan={8} className="px-6 py-12 text-center text-slate-400 text-sm">
                            No subscription records found.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* ================================================================= */}
          {/* 4. USERS & SHOPS PAGE */}
          {/* ================================================================= */}
          {activeTab === 'users' && (
            <div className="space-y-6">
              {/* Search Bar */}
              <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm flex items-center justify-between">
                <div className="relative w-full max-w-md">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={userSearch}
                    onChange={(e) => setUserSearch(e.target.value)}
                    placeholder="Search by owner name, phone, email, or shop/branch name..."
                    className="w-full pl-10 pr-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-xs text-slate-900"
                  />
                  {userSearch && (
                    <button onClick={() => setUserSearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400">
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>
                <span className="text-xs font-bold text-slate-500 hidden sm:inline">
                  {filteredUsersShops.length} Registered Owners
                </span>
              </div>

              {/* Owner Cards with Associated Businesses & Branches */}
              <div className="space-y-4">
                {filteredUsersShops.map((u) => (
                  <div key={u.ownerId} className="bg-white p-6 rounded-3xl border border-slate-200 shadow-sm space-y-4">
                    {/* Owner Header */}
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-3 border-b border-slate-100">
                      <div>
                        <div className="flex items-center gap-2.5">
                          <h4 className="text-base font-black text-slate-900">{u.ownerName}</h4>
                          <span
                            className={`text-[10px] font-extrabold px-2 py-0.5 rounded-full ${
                              u.subscriptionStatus === 'ACTIVE'
                                ? 'bg-emerald-100 text-emerald-800'
                                : 'bg-slate-100 text-slate-600'
                            }`}
                          >
                            Sub: {u.subscriptionStatus} ({u.subscriptionPlan})
                          </span>
                        </div>
                        <p className="text-xs text-slate-500 mt-0.5">
                          Mobile: <span className="font-semibold text-slate-800">{u.phone}</span>
                          {u.email && (
                            <>
                              {' '}
                              • Email: <span className="text-slate-600">{u.email}</span>
                            </>
                          )}
                          {' '}
                          • Registered: {formatDate(u.registeredAt)}
                        </p>
                      </div>

                      <button
                        onClick={() => handleInspectOwner(u.ownerId, u.ownerName, u.phone)}
                        className="px-3.5 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-50 text-slate-700 font-bold text-xs transition-colors self-start sm:self-auto"
                      >
                        Inspect History
                      </button>
                    </div>

                    {/* Associated Branches */}
                    <div>
                      <p className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2">
                        Physical Branches ({u.branches.length})
                      </p>
                      {u.branches.length > 0 ? (
                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                          {u.branches.map((b) => (
                            <div key={b.id} className="p-3.5 rounded-2xl bg-slate-50 border border-slate-100 space-y-1">
                              <div className="flex items-center justify-between">
                                <span className="font-bold text-sm text-slate-900">{b.name}</span>
                                {b.primary ? (
                                  <span className="text-[10px] font-extrabold px-2 py-0.5 rounded-full bg-brand-100 text-brand-800">
                                    Primary
                                  </span>
                                ) : (
                                  <span className="text-[10px] font-extrabold px-2 py-0.5 rounded-full bg-slate-200 text-slate-700">
                                    Branch
                                  </span>
                                )}
                              </div>
                              <p className="text-xs text-slate-600">Category: {b.category}</p>
                              {b.branchName && (
                                <p className="text-xs text-slate-500">Location: {b.branchName}</p>
                              )}
                              {b.publicQueueId && (
                                <p className="text-[10px] font-mono text-slate-400">Queue: {b.publicQueueId}</p>
                              )}
                            </div>
                          ))}
                        </div>
                      ) : (
                        <p className="text-xs text-slate-400 italic">No physical branches registered under this owner.</p>
                      )}
                    </div>
                  </div>
                ))}

                {filteredUsersShops.length === 0 && (
                  <div className="p-12 text-center text-slate-400 bg-white rounded-3xl border border-slate-200">
                    No shop owners found matching &quot;{userSearch}&quot;.
                  </div>
                )}
              </div>
            </div>
          )}

          {/* ================================================================= */}
          {/* 5. ACTIVITY LOGS PAGE */}
          {/* ================================================================= */}
          {activeTab === 'activity' && (
            <div className="space-y-6">
              {/* Filter Tabs */}
              <div className="bg-white p-5 rounded-3xl border border-slate-200 shadow-sm flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-slate-500 uppercase tracking-wider mr-2">Filter Action:</span>
                  {(['ALL', 'PAYMENTS', 'SUBSCRIPTION', 'CATEGORY'] as const).map((filter) => (
                    <button
                      key={filter}
                      onClick={() => setActivityFilter(filter)}
                      className={`px-3.5 py-1.5 rounded-2xl font-bold text-xs transition-all ${
                        activityFilter === filter
                          ? 'bg-slate-900 text-white shadow-sm'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                      }`}
                    >
                      {filter}
                    </button>
                  ))}
                </div>
                <span className="text-xs font-bold text-slate-400">Last 50 Actions</span>
              </div>

              {/* Logs Table */}
              <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-wider text-slate-500 border-b border-slate-100">
                      <tr>
                        <th className="px-6 py-3">Action</th>
                        <th className="px-6 py-3">Target</th>
                        <th className="px-6 py-3">Details / Result</th>
                        <th className="px-6 py-3">Actor</th>
                        <th className="px-6 py-3 text-right">Timestamp</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredLogs.map((log) => (
                        <tr key={log.id} className="hover:bg-slate-50/70 transition-colors">
                          <td className="px-6 py-3.5">
                            <span
                              className={`text-[11px] font-extrabold px-2.5 py-1 rounded-full ${
                                log.action.includes('APPROVE')
                                  ? 'bg-emerald-100 text-emerald-800'
                                  : log.action.includes('REJECT')
                                  ? 'bg-rose-100 text-rose-800'
                                  : 'bg-slate-100 text-slate-800'
                              }`}
                            >
                              {log.action}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 text-xs font-semibold text-slate-700">
                            {log.targetType} #{log.targetId}
                          </td>
                          <td className="px-6 py-3.5 text-xs text-slate-600 max-w-md break-words font-medium">
                            {log.details || '—'}
                          </td>
                          <td className="px-6 py-3.5 text-xs text-slate-500 font-mono">{log.actor}</td>
                          <td className="px-6 py-3.5 text-xs text-slate-400 text-right">{formatDate(log.createdAt)}</td>
                        </tr>
                      ))}
                      {filteredLogs.length === 0 && (
                        <tr>
                          <td colSpan={5} className="px-6 py-12 text-center text-slate-400 text-sm">
                            No activity logs found for this filter.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      </main>

      {/* ===================================================================== */}
      {/* MODAL 1: PAYMENT DETAILS VIEW & ACTIONS */}
      {/* ===================================================================== */}
      {selectedPayment && (
        <div className="fixed inset-0 z-50 bg-slate-900/50 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
          <div className="bg-white rounded-3xl border border-slate-200 shadow-2xl max-w-lg w-full p-6 sm:p-8 space-y-6 my-8">
            {/* Header */}
            <div className="flex items-center justify-between pb-4 border-b border-slate-100">
              <div>
                <h3 className="text-lg font-black text-slate-900">Payment Verification Details</h3>
                <p className="text-xs text-slate-500">Record #{selectedPayment.id}</p>
              </div>
              <button
                onClick={() => setSelectedPayment(null)}
                className="p-1.5 rounded-xl hover:bg-slate-100 text-slate-400 hover:text-slate-700 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Content Fields */}
            <div className="space-y-4 text-xs">
              <div className="p-4 rounded-2xl bg-slate-50 border border-slate-100 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Owner Name</span>
                  <span className="font-bold text-slate-900 text-sm">{selectedPayment.ownerName || '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Mobile Number</span>
                  <span className="font-semibold text-slate-800">{selectedPayment.ownerPhone || '—'}</span>
                </div>
                {selectedPayment.shopName && (
                  <div className="flex items-center justify-between">
                    <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Associated Shop</span>
                    <span className="font-semibold text-slate-800">{selectedPayment.shopName}</span>
                  </div>
                )}
              </div>

              <div className="p-4 rounded-2xl bg-slate-50 border border-slate-100 space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Purpose</span>
                  <span className="font-bold text-brand-700 bg-brand-50 px-2.5 py-0.5 rounded-full">
                    {formatPaymentPurpose(selectedPayment.paymentType, selectedPayment.planType, selectedPayment.batchCount)}
                  </span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Expected Amount</span>
                  <span className="font-black text-slate-900 text-base">₹{selectedPayment.amount}</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Submitted UTR</span>
                  <span className="font-mono font-extrabold text-sm text-slate-900 bg-white px-2 py-0.5 rounded border border-slate-200">
                    {selectedPayment.paymentReference}
                  </span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Submitted At</span>
                  <span className="text-slate-600 font-medium">{formatDate(selectedPayment.createdAt)}</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500 font-bold uppercase tracking-wider text-[10px]">Status</span>
                  <span
                    className={`font-extrabold px-2.5 py-0.5 rounded-full text-[10px] ${
                      selectedPayment.status === 'APPROVED'
                        ? 'bg-emerald-100 text-emerald-800'
                        : selectedPayment.status === 'REJECTED'
                        ? 'bg-rose-100 text-rose-800'
                        : 'bg-amber-100 text-amber-800'
                    }`}
                  >
                    {selectedPayment.status}
                  </span>
                </div>

                {selectedPayment.rejectionReason && (
                  <div className="pt-2 border-t border-slate-200">
                    <span className="text-rose-600 font-bold block mb-1">Rejection Reason:</span>
                    <p className="text-rose-700 font-medium bg-rose-50 p-2.5 rounded-xl">{selectedPayment.rejectionReason}</p>
                  </div>
                )}
              </div>

              {/* Manual Verification Warning Banner */}
              <div className="p-3.5 bg-amber-50 border border-amber-200 rounded-2xl flex items-start gap-2.5">
                <AlertCircle className="w-4 h-4 text-amber-700 shrink-0 mt-0.5" />
                <p className="text-[11px] text-amber-800 leading-relaxed font-medium">
                  <strong>Manual Bank Verification Required:</strong> User-submitted UTR references and transaction slips are self-reported. Super Administrators must manually verify that funds have been credited to the platform bank account before approving.
                </p>
              </div>
            </div>

            {/* Actions for Pending Payments */}
            {selectedPayment.status === 'PENDING_APPROVAL' ? (
              <div className="pt-4 border-t border-slate-100 flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setIsConfirmApproveOpen(true)}
                  className="flex-1 py-3 rounded-2xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-xs shadow-md transition-colors flex items-center justify-center gap-1.5"
                >
                  <Check className="w-4 h-4" />
                  <span>Manual Verify & Approve</span>
                </button>

                <button
                  type="button"
                  onClick={() => setIsConfirmRejectOpen(true)}
                  className="flex-1 py-3 rounded-2xl bg-rose-50 hover:bg-rose-100 text-rose-700 font-bold text-xs border border-rose-200 transition-colors flex items-center justify-center gap-1.5"
                >
                  <X className="w-4 h-4" />
                  <span>Reject Payment</span>
                </button>
              </div>
            ) : (
              <div className="pt-2 text-center text-xs text-slate-400">
                Payment has already been marked as {selectedPayment.status.toLowerCase()}.
              </div>
            )}
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* MODAL 2: CONFIRM APPROVAL DIALOG */}
      {/* ===================================================================== */}
      {isConfirmApproveOpen && selectedPayment && (
        <div className="fixed inset-0 z-[100] bg-slate-900/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-white rounded-3xl border border-slate-200 shadow-2xl max-w-md w-full p-6 space-y-5">
            <div className="w-12 h-12 rounded-2xl bg-emerald-100 text-emerald-600 flex items-center justify-center mx-auto">
              <CheckCircle2 className="w-6 h-6" />
            </div>

            <div className="text-center space-y-2">
              <h4 className="text-base font-black text-slate-900">Confirm Manual Verification & Approval</h4>
              <p className="text-xs text-slate-500">
                Are you sure you have manually verified the bank credit of{' '}
                <strong className="text-slate-900">₹{selectedPayment.amount}</strong> with UTR{' '}
                <strong className="font-mono text-slate-900">{selectedPayment.paymentReference}</strong> for merchant{' '}
                <strong className="text-slate-900">{selectedPayment.ownerName}</strong>?
              </p>
              <div className="p-3 bg-amber-50 border border-amber-200 rounded-2xl text-left text-[11px] text-amber-900 font-medium">
                ⚠️ Approving will immediately activate the merchant's {formatPaymentPurpose(selectedPayment.paymentType, selectedPayment.planType, selectedPayment.batchCount)}. This transaction is permanently recorded in audit logs.
              </div>
            </div>

            <div className="flex items-center gap-3 pt-2">
              <button
                type="button"
                disabled={isActionInProgress}
                onClick={() => setIsConfirmApproveOpen(false)}
                className="flex-1 py-2.5 rounded-2xl border border-slate-200 hover:bg-slate-50 text-slate-700 font-bold text-xs"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={isActionInProgress}
                onClick={handleApprovePayment}
                className="flex-1 py-2.5 rounded-2xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-xs shadow-md disabled:opacity-50"
              >
                {isActionInProgress ? 'Approving...' : 'Confirm Manual Verification & Approve'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* MODAL 3: CONFIRM REJECTION DIALOG (REQUIRES REASON) */}
      {/* ===================================================================== */}
      {isConfirmRejectOpen && selectedPayment && (
        <div className="fixed inset-0 z-[100] bg-slate-900/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-white rounded-3xl border border-slate-200 shadow-2xl max-w-md w-full p-6 space-y-4">
            <div className="w-12 h-12 rounded-2xl bg-rose-100 text-rose-600 flex items-center justify-center mx-auto">
              <XCircle className="w-6 h-6" />
            </div>

            <div className="text-center space-y-1.5">
              <h4 className="text-base font-black text-slate-900">Reject Payment Verification</h4>
              <p className="text-xs text-slate-500">
                A clear rejection reason is required so the shop owner understands why the verification failed and can correct or resubmit their payment reference.
              </p>
            </div>

            <div>
              <label className="block text-[11px] font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                Rejection Reason (Required)
              </label>
              <textarea
                rows={3}
                required
                value={rejectionReason}
                onChange={(e) => setRejectionReason(e.target.value)}
                placeholder="e.g. UTR not found in bank statement, amount mismatch, invalid reference..."
                className="w-full p-3 rounded-2xl border border-slate-200 focus:border-rose-500 focus:ring-1 focus:ring-rose-500 outline-none text-xs text-slate-900"
              />
            </div>

            <div className="flex items-center gap-3 pt-2">
              <button
                type="button"
                disabled={isActionInProgress}
                onClick={() => {
                  setIsConfirmRejectOpen(false);
                  setRejectionReason('');
                }}
                className="flex-1 py-2.5 rounded-2xl border border-slate-200 hover:bg-slate-50 text-slate-700 font-bold text-xs"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={isActionInProgress || !rejectionReason.trim()}
                onClick={handleRejectPayment}
                className="flex-1 py-2.5 rounded-2xl bg-rose-600 hover:bg-rose-700 text-white font-bold text-xs shadow-md disabled:opacity-50"
              >
                {isActionInProgress ? 'Rejecting...' : 'Confirm Rejection'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* MODAL 4: OWNER HISTORY & SUBSCRIPTION INSPECTION */}
      {/* ===================================================================== */}
      {selectedOwnerHistory && (
        <div className="fixed inset-0 z-50 bg-slate-900/50 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
          <div className="bg-white rounded-3xl border border-slate-200 shadow-2xl max-w-2xl w-full p-6 sm:p-8 space-y-6 my-8">
            <div className="flex items-center justify-between pb-4 border-b border-slate-100">
              <div>
                <h3 className="text-base font-black text-slate-900">Owner Subscription & Payment History</h3>
                <p className="text-xs text-slate-500">
                  {selectedOwnerHistory.ownerName} • {selectedOwnerHistory.phone}
                </p>
              </div>
              <button
                onClick={() => setSelectedOwnerHistory(null)}
                className="p-1.5 rounded-xl hover:bg-slate-100 text-slate-400 hover:text-slate-700"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Subscriptions History */}
            <div className="space-y-2">
              <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700">
                Subscriptions ({selectedOwnerHistory.subscriptions.length})
              </h4>
              <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
                {selectedOwnerHistory.subscriptions.map((s) => (
                  <div key={s.id} className="p-3 rounded-2xl bg-slate-50 border border-slate-100 flex items-center justify-between text-xs">
                    <div>
                      <span className="font-bold text-slate-900">{s.plan}</span>
                      <p className="text-slate-500 text-[11px]">
                        {s.startDate} to {s.endDate}
                      </p>
                    </div>
                    <div className="text-right">
                      <span
                        className={`text-[10px] font-extrabold px-2 py-0.5 rounded-full ${
                          s.status === 'ACTIVE' ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-700'
                        }`}
                      >
                        {s.status}
                      </span>
                      <p className="font-bold text-slate-800 mt-1">₹{s.amount}</p>
                    </div>
                  </div>
                ))}
                {selectedOwnerHistory.subscriptions.length === 0 && (
                  <p className="text-xs text-slate-400 italic">No subscription records.</p>
                )}
              </div>
            </div>

            {/* Payments History */}
            <div className="space-y-2">
              <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700">
                Payment Transactions ({selectedOwnerHistory.payments.length})
              </h4>
              <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
                {selectedOwnerHistory.payments.map((p) => (
                  <div key={p.id} className="p-3 rounded-2xl bg-slate-50 border border-slate-100 flex items-center justify-between text-xs">
                    <div>
                      <p className="font-bold text-slate-900">{formatPaymentPurpose(p.paymentType, p.planType)}</p>
                      <p className="font-mono text-[11px] text-slate-500">UTR: {p.paymentReference}</p>
                      <p className="text-[10px] text-slate-400">{formatDate(p.createdAt)}</p>
                    </div>
                    <div className="text-right">
                      <span
                        className={`text-[10px] font-extrabold px-2 py-0.5 rounded-full ${
                          p.status === 'APPROVED'
                            ? 'bg-emerald-100 text-emerald-800'
                            : p.status === 'REJECTED'
                            ? 'bg-rose-100 text-rose-800'
                            : 'bg-amber-100 text-amber-800'
                        }`}
                      >
                        {p.status}
                      </span>
                      <p className="font-bold text-slate-900 mt-1">₹{p.amount}</p>
                    </div>
                  </div>
                ))}
                {selectedOwnerHistory.payments.length === 0 && (
                  <p className="text-xs text-slate-400 italic">No payment transactions on record.</p>
                )}
              </div>
            </div>

            <div className="pt-2 text-right">
              <button
                onClick={() => setSelectedOwnerHistory(null)}
                className="px-5 py-2.5 rounded-2xl bg-slate-900 text-white font-bold text-xs"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default SuperAdminPage;
