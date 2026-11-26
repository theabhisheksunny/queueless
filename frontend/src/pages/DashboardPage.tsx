import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { api, type QueueDashboardData, type CustomerSummary, type SubscriptionStatusData } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { Navbar } from '../components/Navbar';
import { StatusBadge } from '../components/StatusBadge';
import { AddCustomerModal } from '../components/AddCustomerModal';
import { EditCustomerModal } from '../components/EditCustomerModal';
import { QrModal } from '../components/QrModal';
import { ConfirmModal } from '../components/ConfirmModal';
import { QRCodeSVG } from 'qrcode.react';
import { getTheme } from '../theme/theme';
import {
  UserPlus,
  QrCode,
  Volume2,
  Play,
  Square,
  Users,
  Clock,
  Sparkles,
  Phone,
  CheckCircle2,
  XCircle,
  SkipForward,
  RotateCcw,
  Edit2,
  Trash2,
  Radio,
  AlertTriangle,
  AlertCircle,
  Copy,
  Check,
  Send,
} from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const { user, activeShopId, activeBranch, branches, refreshBusinesses } = useAuth();
  const { t } = useLanguage();

  const [dashboard, setDashboard] = useState<QueueDashboardData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [filterTab, setFilterTab] = useState<'active' | 'waiting' | 'serving' | 'history'>('active');
  const [sseConnected, setSseConnected] = useState(false);

  const [isSubscriptionExpired, setIsSubscriptionExpired] = useState(false);
  const [subData, setSubData] = useState<SubscriptionStatusData | null>(null);
  const [selectedPlan, setSelectedPlan] = useState<'MONTHLY' | 'YEARLY'>('MONTHLY');
  const [utrNumber, setUtrNumber] = useState('');
  const [copied, setCopied] = useState(false);
  const [isSubmittingPayment, setIsSubmittingPayment] = useState(false);
  const [paymentSuccessMsg, setPaymentSuccessMsg] = useState<string | null>(null);
  const [paymentError, setPaymentError] = useState<string | null>(null);

  // Branch Registration (₹99) state
  const [branchUtr, setBranchUtr] = useState('');
  const [isBulkBranchPay, setIsBulkBranchPay] = useState(false);
  const [isSubmittingBranchPayment, setIsSubmittingBranchPayment] = useState(false);
  const [branchPaymentSuccess, setBranchPaymentSuccess] = useState<string | null>(null);
  const [branchPaymentError, setBranchPaymentError] = useState<string | null>(null);
  const [copiedBranchUpi, setCopiedBranchUpi] = useState(false);

  // Modals state
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isQrModalOpen, setIsQrModalOpen] = useState(false);
  const [editingCustomer, setEditingCustomer] = useState<CustomerSummary | null>(null);
  const [confirmCloseOpen, setConfirmCloseOpen] = useState(false);
  const [confirmRemoveId, setConfirmRemoveId] = useState<number | null>(null);

  const fetchDashboard = useCallback(async () => {
    try {
      const data = await api.getDashboard(activeShopId ?? undefined);
      setDashboard(data);
      setError(null);
      setIsSubscriptionExpired(false);
    } catch (err: any) {
      if (
        err.status === 402 ||
        err.message?.includes('subscription') ||
        err.message?.includes('trial') ||
        err.message?.includes('Payment Required')
      ) {
        setIsSubscriptionExpired(true);
        try {
          const sub = await api.getSubscriptionStatus(activeShopId ?? undefined);
          setSubData(sub);
          if (sub.pendingPaymentReference) {
            setUtrNumber(sub.pendingPaymentReference);
          }
        } catch {}
      }
      setError(err.message || 'Failed to load queue dashboard');
    } finally {
      setIsLoading(false);
    }
  }, [activeShopId]);

  const handleCopyUpi = () => {
    if (subData?.configuredUpiId) {
      navigator.clipboard.writeText(subData.configuredUpiId);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleSubmitPayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!utrNumber.trim()) {
      setPaymentError('Please enter your 12-digit UPI Reference Number (UTR)');
      return;
    }
    setIsSubmittingPayment(true);
    setPaymentError(null);
    setPaymentSuccessMsg(null);
    try {
      const updated = await api.submitSubscriptionPayment({
        planType: selectedPlan,
        paymentReference: utrNumber.trim(),
      });
      setSubData(updated);
      setPaymentSuccessMsg('Payment reference submitted successfully! Platform admin will verify and activate your subscription shortly.');
    } catch (err: any) {
      setPaymentError(err.message || 'Failed to submit payment reference');
    } finally {
      setIsSubmittingPayment(false);
    }
  };

  const pendingBranches = useMemo(() => {
    return branches.filter((b) => b.registrationStatus === 'PAYMENT_PENDING');
  }, [branches]);

  const handleBranchRegistrationPayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeShopId) return;
    if (!branchUtr.trim() || branchUtr.trim().length < 6) {
      setBranchPaymentError('Please enter a valid 12-digit UPI Reference Number / UTR.');
      return;
    }
    setIsSubmittingBranchPayment(true);
    setBranchPaymentError(null);
    setBranchPaymentSuccess(null);
    try {
      if (isBulkBranchPay && pendingBranches.length > 1) {
        await api.submitBulkBranchPayment({
          branchIds: pendingBranches.map((b) => b.id),
          paymentReference: branchUtr.trim(),
          paymentUpiId: 'absunny@ybl',
        });
        setBranchPaymentSuccess(`Bulk payment of ₹${pendingBranches.length * 99} submitted for ${pendingBranches.length} branches! Admin approval pending.`);
      } else {
        await api.submitBranchPayment(activeShopId, {
          paymentReference: branchUtr.trim(),
          paymentUpiId: 'absunny@ybl',
        });
        setBranchPaymentSuccess('₹99 branch registration payment submitted! Admin will verify and activate your branch shortly.');
      }
      await refreshBusinesses();
      await fetchDashboard();
    } catch (err: any) {
      setBranchPaymentError(err.message || 'Failed to submit branch registration payment.');
    } finally {
      setIsSubmittingBranchPayment(false);
    }
  };

  // Fetch initial dashboard and setup Server-Sent Events (SSE) via header-authenticated fetch stream
  useEffect(() => {
    fetchDashboard();

    let disconnect: (() => void) | null = null;
    let isCancelled = false;

    async function initSse() {
      try {
        const teardown = await api.connectOwnerStream(
          activeShopId ?? undefined,
          (updatedData: QueueDashboardData) => {
            if (!isCancelled) setDashboard(updatedData);
          },
          () => {
            if (!isCancelled) setSseConnected(true);
          },
          () => {
            if (!isCancelled) setSseConnected(false);
          }
        );
        if (isCancelled) {
          teardown();
        } else {
          disconnect = teardown;
        }
      } catch {
        if (!isCancelled) setSseConnected(false);
      }
    }

    initSse();

    return () => {
      isCancelled = true;
      if (disconnect) {
        disconnect();
      }
    };
  }, [fetchDashboard, activeShopId]);

  // Actions
  const handleStartQueue = async () => {
    setActionLoading(true);
    try {
      const updated = await api.startQueue(activeShopId ?? undefined);
      setDashboard(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to start queue');
    } finally {
      setActionLoading(false);
    }
  };

  const handleCloseQueue = async () => {
    setActionLoading(true);
    try {
      const updated = await api.closeQueue(activeShopId ?? undefined);
      setDashboard(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to close queue');
    } finally {
      setActionLoading(false);
    }
  };

  const handleCallNext = async () => {
    if (!dashboard || dashboard.waitingCustomers === 0) {
      alert(t('noWaitingToCall'));
      return;
    }
    setActionLoading(true);
    try {
      const updated = await api.callNextCustomer(activeShopId ?? undefined);
      setDashboard(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to call next customer');
    } finally {
      setActionLoading(false);
    }
  };

  const handleUpdateStatus = async (customerId: number, status: string) => {
    try {
      const updated = await api.updateCustomerStatus(customerId, status, activeShopId ?? undefined);
      setDashboard(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to update customer status');
    }
  };

  const handleManualAddSubmit = async (data: { customerName: string; customerPhone?: string }) => {
    const updated = await api.manualAddCustomer(data, activeShopId ?? undefined);
    setDashboard(updated);
  };

  const handleEditSubmit = async (customerId: number, data: { customerName: string; customerPhone?: string }) => {
    const updated = await api.editCustomer(customerId, data, activeShopId ?? undefined);
    setDashboard(updated);
    setEditingCustomer(null);
  };

  const handleRemoveConfirm = async () => {
    if (confirmRemoveId == null) return;
    try {
      const updated = await api.removeCustomer(confirmRemoveId, activeShopId ?? undefined);
      setDashboard(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to remove customer');
    } finally {
      setConfirmRemoveId(null);
    }
  };

  // Authoritative currently serving customers derived directly from latest state
  const servingCustomers = useMemo(() => {
    return (dashboard?.customers || []).filter((c) => c.status === 'SERVING');
  }, [dashboard?.customers]);

  // Filter customers by selected tab
  const filteredCustomers = (dashboard?.customers || []).filter((c) => {
    if (filterTab === 'active') {
      return ['WAITING', 'CALLED', 'SERVING', 'SKIPPED'].includes(c.status);
    }
    if (filterTab === 'waiting') {
      return c.status === 'WAITING';
    }
    if (filterTab === 'serving') {
      return c.status === 'SERVING';
    }
    if (filterTab === 'history') {
      return ['COMPLETED', 'SKIPPED', 'NO_SHOW', 'LEFT', 'CANCELLED'].includes(c.status);
    }
    return true;
  });

  const formatTime = (isoString?: string) => {
    if (!isoString) return '-';
    try {
      const d = new Date(isoString);
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return isoString;
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4">
        <div className="w-12 h-12 rounded-2xl bg-brand-600 animate-spin flex items-center justify-center text-white shadow-lg shadow-brand-500/25 mb-4">
          <QrCode className="w-6 h-6 animate-pulse" />
        </div>
        <p className="text-slate-600 font-semibold text-base">Loading QueueLess Dashboard...</p>
      </div>
    );
  }

  if (isSubscriptionExpired) {
    const currentPrice = selectedPlan === 'YEARLY' ? subData?.yearlyPrice || 501 : subData?.monthlyPrice || 51;
    const upiId = subData?.configuredUpiId || 'absunny@ybl';
    const payeeName = subData?.configuredUpiName || 'QueueLess Token System';
    const upiDeepLink = `upi://pay?pa=${encodeURIComponent(upiId)}&pn=${encodeURIComponent(
      payeeName
    )}&am=${currentPrice}&cu=INR&tn=QueueLess+Subscription`;

    return (
      <div className="min-h-screen bg-slate-50 flex flex-col">
        <Navbar />
        <main className="flex-1 max-w-4xl w-full mx-auto px-4 sm:px-6 py-6 md:py-8 space-y-6">
          {/* Expiry Warning Card */}
          <div className="p-6 rounded-3xl bg-rose-50 border border-rose-200 shadow-sm flex items-start gap-4">
            <div className="w-12 h-12 rounded-2xl bg-rose-100 text-rose-700 flex items-center justify-center shrink-0">
              <AlertTriangle className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-xl sm:text-2xl font-extrabold text-slate-900">
                {t('trialExpiredBarrierTitle')}
              </h1>
              <p className="text-sm text-slate-600 font-medium mt-1">
                {t('trialExpiredBarrierMsg')}
              </p>
              {subData?.endDate && (
                <p className="text-xs text-rose-600 font-bold mt-2">
                  Trial Expiration Date: {subData.endDate}
                </p>
              )}
            </div>
          </div>

          {/* Pricing Options */}
          <div className="bg-white p-6 sm:p-8 rounded-3xl border border-slate-200/80 shadow-sm space-y-6">
            <div>
              <h2 className="text-lg font-bold text-slate-900 mb-3">Choose Subscription Plan</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div
                  onClick={() => setSelectedPlan('MONTHLY')}
                  className={`p-5 rounded-2xl border-2 cursor-pointer transition-all ${
                    selectedPlan === 'MONTHLY'
                      ? 'bg-brand-50/30 border-brand-600 ring-4 ring-brand-500/10'
                      : 'bg-white border-slate-200 hover:border-slate-300'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs font-bold uppercase tracking-wider text-slate-600">Monthly Plan</span>
                    <span className="w-4 h-4 rounded-full border-2 border-brand-600 flex items-center justify-center">
                      {selectedPlan === 'MONTHLY' && <span className="w-2 h-2 rounded-full bg-brand-600" />}
                    </span>
                  </div>
                  <div className="text-3xl font-extrabold text-slate-900">
                    ₹{subData?.monthlyPrice || 51}
                    <span className="text-sm font-medium text-slate-500"> / 30 days</span>
                  </div>
                  <p className="text-xs text-slate-500 mt-1 font-medium">Ideal for small local shops</p>
                </div>

                <div
                  onClick={() => setSelectedPlan('YEARLY')}
                  className={`p-5 rounded-2xl border-2 cursor-pointer transition-all ${
                    selectedPlan === 'YEARLY'
                      ? 'bg-brand-50/30 border-brand-600 ring-4 ring-brand-500/10'
                      : 'bg-white border-slate-200 hover:border-slate-300'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs font-bold uppercase tracking-wider text-slate-600">Yearly Plan</span>
                    <span className="w-4 h-4 rounded-full border-2 border-brand-600 flex items-center justify-center">
                      {selectedPlan === 'YEARLY' && <span className="w-2 h-2 rounded-full bg-brand-600" />}
                    </span>
                  </div>
                  <div className="text-3xl font-extrabold text-slate-900">
                    ₹{subData?.yearlyPrice || 501}
                    <span className="text-sm font-medium text-slate-500"> / 365 days</span>
                  </div>
                  <p className="text-xs text-slate-500 mt-1 font-medium">Best value for active businesses</p>
                </div>
              </div>
            </div>

            {/* UPI QR & Payment */}
            <div className="p-6 rounded-2xl bg-slate-50 border border-slate-200/80 flex flex-col sm:flex-row items-center gap-6">
              <div className="p-3 bg-white rounded-2xl border border-slate-200 shadow-sm shrink-0">
                <QRCodeSVG value={upiDeepLink} size={150} level="M" />
              </div>
              <div className="flex-1 space-y-3 text-center sm:text-left">
                <div>
                  <span className="text-xs font-bold uppercase text-slate-500">Direct Payee UPI ID</span>
                  <div className="flex items-center justify-center sm:justify-start gap-2 mt-0.5">
                    <code className="text-base font-bold text-slate-900 bg-white px-2.5 py-1 rounded-xl border border-slate-200">
                      {upiId}
                    </code>
                    <button
                      type="button"
                      onClick={handleCopyUpi}
                      className="p-1.5 rounded-xl bg-white border border-slate-200 hover:bg-slate-100 text-slate-700 transition-colors"
                      title="Copy UPI ID"
                    >
                      {copied ? <Check className="w-4 h-4 text-emerald-600" /> : <Copy className="w-4 h-4" />}
                    </button>
                  </div>
                  <p className="text-xs text-slate-500 mt-1 font-medium">{payeeName}</p>
                </div>
                <p className="text-xs text-slate-600">
                  Scan QR code with Google Pay, PhonePe, Paytm, or BHIM. After paying ₹{currentPrice}, enter the 12-digit UPI Reference Number (UTR) below.
                </p>
              </div>
            </div>

            {/* UTR Form */}
            <form onSubmit={handleSubmitPayment} className="space-y-4">
              {paymentError && (
                <div className="p-3.5 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-medium">
                  {paymentError}
                </div>
              )}
              {paymentSuccessMsg && (
                <div className="p-3.5 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm font-medium flex items-center gap-2">
                  <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0" />
                  <span>{paymentSuccessMsg}</span>
                </div>
              )}
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                  12-Digit UPI Reference Number (UTR) <span className="text-rose-500">*</span>
                </label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    required
                    value={utrNumber}
                    onChange={(e) => setUtrNumber(e.target.value)}
                    placeholder="e.g. 432109876543"
                    className="flex-1 px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-mono font-bold tracking-wider transition-all"
                  />
                  <button
                    type="submit"
                    disabled={isSubmittingPayment}
                    className="px-6 py-3 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md shadow-brand-500/20 flex items-center gap-2 transition-all disabled:opacity-50 shrink-0"
                  >
                    <Send className="w-4 h-4" />
                    <span>{isSubmittingPayment ? 'Submitting...' : 'Submit Payment'}</span>
                  </button>
                </div>
              </div>
            </form>
          </div>
        </main>
      </div>
    );
  }

  const currentCategory = dashboard?.category || user?.category;
  const theme = getTheme(currentCategory);
  const ThemeIcon = theme.icon;

  return (
    <div
      data-theme={theme.id}
      className={`min-h-screen ${theme.styles.pageBgGradient} flex flex-col transition-colors duration-300`}
    >
      <Navbar
        onShowQr={() => setIsQrModalOpen(true)}
        publicQueueId={dashboard?.publicQueueId}
        category={currentCategory}
      />

      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 py-6 md:py-8 space-y-6">
        {/* Top Shop Banner & Live Indicator */}
        {error && (
          <div className="p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-semibold">
            {error}
          </div>
        )}

        {/* Branch Registration Required Barrier (₹99 One-time) */}
        {activeBranch?.registrationStatus === 'PAYMENT_PENDING' && (
          <div className="bg-white p-6 sm:p-8 rounded-3xl border-2 border-amber-300 shadow-md space-y-6">
            <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-4">
              <div className="flex items-start gap-3.5">
                <div className="w-12 h-12 rounded-2xl bg-amber-100 text-amber-700 flex items-center justify-center shrink-0">
                  <AlertCircle className="w-6 h-6" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold uppercase tracking-wider px-2.5 py-0.5 rounded-full bg-amber-100 text-amber-800">
                      Registration Required
                    </span>
                    <span className="text-xs text-slate-400 font-semibold">One-time ₹99 fee</span>
                  </div>
                  <h2 className="text-xl sm:text-2xl font-black text-slate-900 mt-1">
                    Activate {activeBranch.branchName || activeBranch.name}
                  </h2>
                  <p className="text-sm text-slate-600 mt-1">
                    Every additional physical branch requires a one-time ₹99 registration payment.
                    Once approved, this branch remains registered for lifetime and operates under your main subscription.
                  </p>
                </div>
              </div>
            </div>

            {branchPaymentSuccess && (
              <div className="p-4 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2">
                <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0" />
                <span>{branchPaymentSuccess}</span>
              </div>
            )}

            {branchPaymentError && (
              <div className="p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-800 text-sm flex items-center gap-2">
                <AlertCircle className="w-5 h-5 text-rose-600 shrink-0" />
                <span>{branchPaymentError}</span>
              </div>
            )}

            {activeBranch.pendingPaymentReference && !branchPaymentSuccess && (
              <div className="p-4 rounded-2xl bg-blue-50 border border-blue-200 text-blue-900 text-sm flex items-center gap-2">
                <Clock className="w-5 h-5 text-blue-600 shrink-0" />
                <div>
                  <p className="font-semibold">Payment Under Verification</p>
                  <p className="text-xs text-blue-700 mt-0.5">
                    UTR: <span className="font-mono font-bold">{activeBranch.pendingPaymentReference}</span>. Admin approval pending.
                  </p>
                </div>
              </div>
            )}

            {/* Optional Bulk Selection when multiple branches pending */}
            {pendingBranches.length > 1 && (
              <div className="p-4 rounded-2xl bg-amber-50/80 border border-amber-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div>
                  <span className="font-bold text-slate-900 text-xs uppercase tracking-wider block">
                    Bulk Branch Registration Available
                  </span>
                  <span className="text-xs text-slate-600">
                    You have {pendingBranches.length} branches pending registration. You can pay for all branches at once using a single UTR.
                  </span>
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setIsBulkBranchPay(false)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all ${
                      !isBulkBranchPay
                        ? 'bg-amber-600 text-white shadow-sm'
                        : 'bg-white text-slate-700 border border-slate-200'
                    }`}
                  >
                    Current Branch (₹99)
                  </button>
                  <button
                    type="button"
                    onClick={() => setIsBulkBranchPay(true)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all ${
                      isBulkBranchPay
                        ? 'bg-amber-600 text-white shadow-sm'
                        : 'bg-white text-slate-700 border border-slate-200'
                    }`}
                  >
                    All {pendingBranches.length} Branches (₹{pendingBranches.length * 99})
                  </button>
                </div>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 pt-2">
              <div className="p-6 rounded-2xl bg-slate-50 border border-slate-200/80 flex flex-col items-center justify-center text-center">
                <div className="p-3 bg-white rounded-2xl shadow-sm border border-slate-100">
                  <QRCodeSVG
                    value={`upi://pay?pa=absunny@ybl&pn=QueueLess+Token+System&am=${
                      isBulkBranchPay && pendingBranches.length > 1 ? pendingBranches.length * 99 : 99
                    }&cu=INR&tn=QueueLess+Branch+Registration`}
                    size={180}
                    level="H"
                  />
                </div>
                <div className="mt-4 flex items-center gap-2 px-3 py-1.5 bg-white rounded-xl border border-slate-200 text-xs font-mono">
                  <span className="font-semibold text-slate-800">absunny@ybl</span>
                  <button
                    type="button"
                    onClick={() => {
                      navigator.clipboard.writeText('absunny@ybl');
                      setCopiedBranchUpi(true);
                      setTimeout(() => setCopiedBranchUpi(false), 2000);
                    }}
                    className="p-1 hover:text-brand-600 transition-colors"
                  >
                    {copiedBranchUpi ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                  </button>
                </div>
                <span className="text-xs text-slate-500 mt-1">
                  Scan & pay ₹{isBulkBranchPay && pendingBranches.length > 1 ? pendingBranches.length * 99 : 99} via any UPI app
                </span>
              </div>

              <form onSubmit={handleBranchRegistrationPayment} className="flex flex-col justify-between space-y-4">
                <div>
                  <h3 className="text-sm font-bold text-slate-800 uppercase tracking-wider mb-1">
                    Step 2: Submit 12-Digit UPI Reference (UTR)
                  </h3>
                  <p className="text-xs text-slate-500 mb-3">
                    After completing the payment of ₹{isBulkBranchPay && pendingBranches.length > 1 ? pendingBranches.length * 99 : 99} on your UPI app, enter the transaction UTR number below.
                  </p>

                  <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                    UTR Reference Number
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. 432198765432"
                    value={branchUtr}
                    onChange={(e) => setBranchUtr(e.target.value)}
                    className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm font-mono"
                    required
                  />
                </div>

                <button
                  type="submit"
                  disabled={isSubmittingBranchPayment}
                  className="w-full py-3.5 px-5 rounded-2xl bg-amber-600 hover:bg-amber-700 text-white font-bold text-sm shadow-md shadow-amber-600/20 disabled:opacity-50 transition-colors flex items-center justify-center gap-2"
                >
                  <Send className="w-4 h-4" />
                  <span>
                    {isSubmittingBranchPayment
                      ? 'Submitting...'
                      : isBulkBranchPay && pendingBranches.length > 1
                      ? `Submit ₹${pendingBranches.length * 99} Bulk Payment`
                      : 'Submit ₹99 Branch Payment'}
                  </span>
                </button>
              </form>
            </div>
          </div>
        )}
        <div className={`flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white p-5 sm:p-6 rounded-3xl border ${theme.styles.cardBorder} shadow-sm`}>
          <div>
            <div className="flex items-center gap-2">
              <span className={`text-xs uppercase tracking-wider font-extrabold px-2.5 py-0.5 rounded-full border inline-flex items-center gap-1.5 ${theme.styles.badge}`}>
                <ThemeIcon className="w-3.5 h-3.5" />
                <span>{currentCategory || theme.categoryLabel}</span>
              </span>
              {(dashboard?.businessType || user?.businessType) && (
                <span className="text-xs font-semibold px-2.5 py-0.5 rounded-full border border-slate-200 bg-slate-50 text-slate-700">
                  {dashboard?.businessType || user?.businessType}
                </span>
              )}
              <div className="flex items-center gap-1.5 text-xs font-semibold px-2.5 py-0.5 rounded-full bg-slate-100 text-slate-600">
                {sseConnected ? (
                  <>
                    <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                    <span className="text-emerald-700">Live SSE</span>
                  </>
                ) : (
                  <>
                    <span className="w-2 h-2 rounded-full bg-slate-400" />
                    <span>Connecting...</span>
                  </>
                )}
              </div>
            </div>
            <h1 className="text-2xl sm:text-3xl font-extrabold text-slate-900 mt-1">
              {dashboard?.shopName || user?.shopName}
            </h1>
          </div>

          {/* Queue Open/Close Toggle Control */}
          <div className="flex items-center gap-3">
            {dashboard?.queueOpen ? (
              <button
                onClick={() => setConfirmCloseOpen(true)}
                disabled={actionLoading}
                className="inline-flex items-center gap-2 px-4 py-2.5 rounded-2xl bg-slate-100 hover:bg-rose-50 text-slate-700 hover:text-rose-700 font-semibold text-sm border border-slate-200 transition-colors"
              >
                <Square className="w-4 h-4 text-rose-500 fill-rose-500" />
                <span>{t('closeQueue')}</span>
              </button>
            ) : (
              <button
                onClick={handleStartQueue}
                disabled={actionLoading}
                className="inline-flex items-center gap-2 px-5 py-2.5 rounded-2xl bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-md shadow-emerald-600/20 transition-all"
              >
                <Play className="w-4 h-4 fill-white" />
                <span>{t('startQueue')}</span>
              </button>
            )}
          </div>
        </div>

        {/* 4 Summary Stat Cards */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
          {/* Card 1: Queue Status */}
          <div className="bg-white p-5 rounded-3xl border border-slate-200/80 shadow-sm flex flex-col justify-between">
            <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
              {t('queueStatus')}
            </span>
            <div className="mt-3 flex items-center justify-between">
              <span
                className={`text-xl sm:text-2xl font-black ${
                  dashboard?.queueOpen ? 'text-emerald-600' : 'text-slate-400'
                }`}
              >
                {dashboard?.queueOpen ? t('open') : t('closed')}
              </span>
              <div
                className={`w-10 h-10 rounded-2xl flex items-center justify-center ${
                  dashboard?.queueOpen
                    ? 'bg-emerald-50 text-emerald-600'
                    : 'bg-slate-100 text-slate-400'
                }`}
              >
                <Radio className="w-5 h-5" />
              </div>
            </div>
          </div>

          {/* Card 2: Currently Serving */}
          <div className="bg-white p-5 rounded-3xl border border-slate-200/80 shadow-sm flex flex-col justify-between" data-testid="currently-serving-card">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
                {t('currentlyServing')}
              </span>
              {servingCustomers.length > 0 && (
                <span
                  data-testid="serving-badge"
                  className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-black bg-emerald-100 text-emerald-800 border border-emerald-200 tracking-wider"
                >
                  SERVING
                </span>
              )}
            </div>
            <div className="mt-3 flex items-center justify-between">
              <div className="flex-1 min-w-0 pr-2">
                {servingCustomers.length === 0 ? (
                  <>
                    <span className="text-xl sm:text-2xl font-black text-slate-400" data-testid="serving-token">-</span>
                    <p className="text-xs text-slate-400 font-medium truncate" data-testid="serving-name">
                      {t('noneServing')}
                    </p>
                  </>
                ) : servingCustomers.length === 1 ? (
                  <>
                    <span className="text-xl sm:text-2xl font-black text-emerald-600" data-testid="serving-token">
                      #{servingCustomers[0].tokenNumber}
                    </span>
                    <p className="text-xs text-slate-700 font-bold truncate" data-testid="serving-name">
                      {servingCustomers[0].customerName}
                    </p>
                  </>
                ) : (
                  <>
                    <div className="flex items-center gap-1.5 flex-wrap" data-testid="serving-token">
                      {servingCustomers.map((c) => (
                        <span
                          key={c.id}
                          className="text-base sm:text-lg font-black text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded-lg border border-emerald-200"
                        >
                          #{c.tokenNumber}
                        </span>
                      ))}
                    </div>
                    <p className="text-xs text-slate-700 font-bold truncate mt-1" data-testid="serving-name">
                      {servingCustomers.map((c) => c.customerName).join(', ')}
                    </p>
                  </>
                )}
              </div>
              <div className="w-10 h-10 rounded-2xl bg-emerald-50 text-emerald-600 flex items-center justify-center shrink-0">
                <Sparkles className="w-5 h-5" />
              </div>
            </div>
          </div>

          {/* Card 3: Waiting Customers */}
          <div className="bg-white p-5 rounded-3xl border border-slate-200/80 shadow-sm flex flex-col justify-between">
            <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
              {t('waitingCustomers')}
            </span>
            <div className="mt-3 flex items-center justify-between">
              <span className="text-xl sm:text-2xl font-black text-amber-600">
                {dashboard?.waitingCustomers || 0}
              </span>
              <div className="w-10 h-10 rounded-2xl bg-amber-50 text-amber-600 flex items-center justify-center">
                <Clock className="w-5 h-5" />
              </div>
            </div>
          </div>

          {/* Card 4: Total Today */}
          <div className="bg-white p-5 rounded-3xl border border-slate-200/80 shadow-sm flex flex-col justify-between">
            <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
              {t('totalToday')}
            </span>
            <div className="mt-3 flex items-center justify-between">
              <span className="text-xl sm:text-2xl font-black text-slate-800">
                {dashboard?.totalCustomers || 0}
              </span>
              <div className="w-10 h-10 rounded-2xl bg-slate-100 text-slate-600 flex items-center justify-center">
                <Users className="w-5 h-5" />
              </div>
            </div>
          </div>
        </div>

        {/* Primary Action Buttons */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {/* Add Customer Manual */}
          <button
            onClick={() => setIsAddModalOpen(true)}
            className={`py-4 px-5 rounded-2xl ${theme.styles.primaryButton} text-base flex items-center justify-center gap-2.5 transition-all`}
          >
            <UserPlus className="w-5 h-5" />
            <span>{t('addCustomer')}</span>
          </button>

          {/* Show QR Code */}
          <button
            onClick={() => setIsQrModalOpen(true)}
            disabled={!dashboard?.publicQueueId}
            className={`py-4 px-5 rounded-2xl ${theme.styles.secondaryButton} text-base shadow-sm flex items-center justify-center gap-2.5 transition-all active:scale-[0.98] disabled:opacity-50`}
          >
            <QrCode className="w-5 h-5" />
            <span>{t('showQrCode')}</span>
          </button>

          {/* Call Next Customer */}
          <button
            onClick={handleCallNext}
            disabled={actionLoading || !dashboard?.waitingCustomers}
            className={`py-4 px-5 rounded-2xl ${theme.styles.accentButton} text-base flex items-center justify-center gap-2.5 transition-all active:scale-[0.98] disabled:opacity-40 disabled:cursor-not-allowed`}
          >
            <Volume2 className="w-5 h-5" />
            <span>{t('callNext')}</span>
          </button>
        </div>

        {/* Customer List Header & Filter Tabs */}
        <div className={`bg-white rounded-3xl border ${theme.styles.cardBorder} shadow-sm overflow-hidden`}>
          <div className="p-4 sm:p-6 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <h2 className="text-xl font-extrabold text-slate-900">
                Customer Queue Register
              </h2>
              <p className="text-xs text-slate-500 font-medium mt-0.5">
                Sequential tokens 001, 002... Real-time automatic updates
              </p>
            </div>

            {/* Filter Tabs */}
            <div className="flex p-1 rounded-2xl bg-slate-100 text-xs sm:text-sm font-semibold overflow-x-auto">
              <button
                onClick={() => setFilterTab('active')}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap transition-all ${
                  filterTab === 'active'
                    ? 'bg-white text-slate-900 shadow-sm'
                    : 'text-slate-500 hover:text-slate-900'
                }`}
              >
                {t('tabAll')}
              </button>
              <button
                onClick={() => setFilterTab('waiting')}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap transition-all ${
                  filterTab === 'waiting'
                    ? 'bg-white text-slate-900 shadow-sm'
                    : 'text-slate-500 hover:text-slate-900'
                }`}
              >
                {t('tabWaiting')} ({dashboard?.waitingCustomers || 0})
              </button>
              <button
                onClick={() => setFilterTab('serving')}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap transition-all ${
                  filterTab === 'serving'
                    ? 'bg-white text-slate-900 shadow-sm'
                    : 'text-slate-500 hover:text-slate-900'
                }`}
              >
                {t('tabServing')} ({servingCustomers.length})
              </button>
              <button
                onClick={() => setFilterTab('history')}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap transition-all ${
                  filterTab === 'history'
                    ? 'bg-white text-slate-900 shadow-sm'
                    : 'text-slate-500 hover:text-slate-900'
                }`}
              >
                {t('tabCompleted')}
              </button>
            </div>
          </div>

          {/* Customer Cards List */}
          {filteredCustomers.length === 0 ? (
            <div className="py-16 px-4 text-center">
              <div className={`w-14 h-14 rounded-3xl ${theme.styles.iconBg} mx-auto flex items-center justify-center mb-3`}>
                <ThemeIcon className="w-7 h-7" />
              </div>
              <h3 className="text-base font-bold text-slate-800">No customers in this view</h3>
              <p className="text-sm text-slate-500 max-w-sm mx-auto mt-1">
                Add customers manually with the button above or share your QR code for customers to join automatically!
              </p>
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {filteredCustomers.map((cust) => {
                const isCurrentServing = cust.status === 'SERVING';
                const isCalled = cust.status === 'CALLED';

                return (
                  <div
                    key={cust.id}
                    data-testid="customer-row"
                    data-customer-name={cust.customerName}
                    className={`p-4 sm:p-5 flex flex-col sm:flex-row sm:items-center justify-between gap-4 transition-colors ${
                      isCurrentServing
                        ? 'bg-emerald-50/40'
                        : isCalled
                        ? `${theme.styles.cardHighlight}`
                        : 'hover:bg-slate-50/70'
                    }`}
                  >
                    {/* Customer Info & Token */}
                    <div className="flex items-center gap-4">
                      {/* Large Bold Token Badge */}
                      <div
                        className={`w-14 h-14 rounded-2xl flex flex-col items-center justify-center font-black text-lg shadow-sm ${
                          isCurrentServing
                            ? 'bg-emerald-600 text-white shadow-emerald-500/20'
                            : isCalled
                            ? `${theme.styles.calledHighlight}`
                            : `${theme.styles.tokenHeroCard}`
                        }`}
                      >
                        <span className="text-[10px] uppercase font-bold tracking-tight opacity-75">
                          {t('token')}
                        </span>
                        <span>#{cust.tokenNumber}</span>
                      </div>

                      {/* Name, Phone & Time */}
                      <div>
                        <div className="flex items-center gap-2">
                          <h4 className="text-base sm:text-lg font-bold text-slate-900">
                            {cust.customerName}
                          </h4>
                          <StatusBadge status={cust.status} category={currentCategory} size="sm" />
                        </div>
                        <div className="flex items-center gap-3 text-xs text-slate-500 font-medium mt-1">
                          {cust.customerPhone && (
                            <span className="flex items-center gap-1">
                              <Phone className="w-3.5 h-3.5 text-slate-400" />
                              <span>{cust.customerPhone}</span>
                            </span>
                          )}
                          <span className="flex items-center gap-1">
                            <Clock className="w-3.5 h-3.5 text-slate-400" />
                            <span>{formatTime(cust.joinedAt)}</span>
                          </span>
                          <span className="text-slate-400">
                            {cust.addedBy === 'QR_CODE' ? '• QR Joined' : '• Manual Entry'}
                          </span>
                        </div>
                      </div>
                    </div>

                    {/* Contextual Action Buttons */}
                    <div className="flex items-center flex-wrap gap-2 sm:self-center">
                      {/* WAITING actions */}
                      {cust.status === 'WAITING' && (
                        <>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'CALLED')}
                            className="px-3.5 py-2 rounded-xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-xs sm:text-sm shadow-sm transition-colors"
                          >
                            {t('btnCall')}
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'SERVING')}
                            className="px-3.5 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-xs sm:text-sm shadow-sm transition-colors"
                          >
                            {t('btnServe')}
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'SKIPPED')}
                            className="px-3 py-2 rounded-xl bg-slate-100 hover:bg-orange-50 text-slate-700 hover:text-orange-700 font-semibold text-xs transition-colors"
                            title={t('btnSkip')}
                          >
                            <SkipForward className="w-4 h-4" />
                          </button>
                        </>
                      )}

                      {/* CALLED actions */}
                      {cust.status === 'CALLED' && (
                        <>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'SERVING')}
                            className="px-3.5 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-xs sm:text-sm shadow-sm transition-colors"
                          >
                            {t('btnServe')}
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'CALLED')}
                            className="px-3 py-2 rounded-xl bg-brand-100 text-brand-700 hover:bg-brand-200 font-semibold text-xs transition-colors"
                            title={t('btnRecall')}
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'NO_SHOW')}
                            className="px-3 py-2 rounded-xl bg-slate-100 hover:bg-rose-50 text-slate-700 hover:text-rose-700 font-semibold text-xs transition-colors"
                            title={t('btnNoShow')}
                          >
                            <XCircle className="w-4 h-4" />
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(cust.id, 'SKIPPED')}
                            className="px-3 py-2 rounded-xl bg-slate-100 hover:bg-orange-50 text-slate-700 hover:text-orange-700 font-semibold text-xs transition-colors"
                            title={t('btnSkip')}
                          >
                            <SkipForward className="w-4 h-4" />
                          </button>
                        </>
                      )}

                      {/* SERVING actions */}
                      {cust.status === 'SERVING' && (
                        <button
                          onClick={() => handleUpdateStatus(cust.id, 'COMPLETED')}
                          className="px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-xs sm:text-sm shadow-md shadow-emerald-600/20 flex items-center gap-1.5 transition-all"
                        >
                          <CheckCircle2 className="w-4 h-4" />
                          <span>{t('btnComplete')}</span>
                        </button>
                      )}

                      {/* SKIPPED actions */}
                      {cust.status === 'SKIPPED' && (
                        <button
                          onClick={() => handleUpdateStatus(cust.id, 'WAITING')}
                          className="px-3 py-2 rounded-xl bg-amber-50 hover:bg-amber-100 text-amber-800 font-semibold text-xs flex items-center gap-1 transition-colors"
                        >
                          <RotateCcw className="w-3.5 h-3.5" />
                          <span>Re-queue</span>
                        </button>
                      )}

                      {/* Edit Button */}
                      <button
                        onClick={() => setEditingCustomer(cust)}
                        className="p-2 rounded-xl text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-colors"
                        title={t('btnEdit')}
                      >
                        <Edit2 className="w-4 h-4" />
                      </button>

                      {/* Remove Button */}
                      {['WAITING', 'CALLED', 'SKIPPED'].includes(cust.status) && (
                        <button
                          onClick={() => setConfirmRemoveId(cust.id)}
                          className="p-2 rounded-xl text-slate-400 hover:text-rose-600 hover:bg-rose-50 transition-colors"
                          title={t('btnRemove')}
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </main>

      {/* Modals */}
      <AddCustomerModal
        isOpen={isAddModalOpen}
        category={currentCategory}
        onClose={() => setIsAddModalOpen(false)}
        onSubmit={handleManualAddSubmit}
      />

      <EditCustomerModal
        isOpen={!!editingCustomer}
        customer={editingCustomer}
        category={currentCategory}
        onClose={() => setEditingCustomer(null)}
        onSubmit={handleEditSubmit}
      />

      <QrModal
        isOpen={isQrModalOpen}
        onClose={() => setIsQrModalOpen(false)}
        shopName={dashboard?.shopName || user?.shopName || 'Shop Queue'}
        category={currentCategory || 'Other Local Business'}
        publicQueueId={dashboard?.publicQueueId || ''}
        publicMerchantCode={dashboard?.publicMerchantCode}
        canonicalJoinUrl={dashboard?.canonicalJoinUrl}
      />

      <ConfirmModal
        isOpen={confirmCloseOpen}
        title={t('closeQueue')}
        message={t('confirmCloseQueue')}
        confirmText="Close Queue"
        confirmVariant="danger"
        onClose={() => setConfirmCloseOpen(false)}
        onConfirm={handleCloseQueue}
      />

      <ConfirmModal
        isOpen={confirmRemoveId !== null}
        title="Remove Customer"
        message="Are you sure you want to remove this customer from the active queue?"
        confirmText="Remove"
        confirmVariant="danger"
        onClose={() => setConfirmRemoveId(null)}
        onConfirm={handleRemoveConfirm}
      />
    </div>
  );
};
