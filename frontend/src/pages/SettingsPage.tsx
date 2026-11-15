import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { api, type CategoryChangeStatusData } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { Navbar } from '../components/Navbar';
import {
  Store,
  Clock,
  Save,
  CheckCircle2,
  Trash2,
  AlertTriangle,
  Lock,
  Sparkles,
  QrCode,
  Copy,
  Check,
  X,
} from 'lucide-react';

const CATEGORIES = [
  'Barber Shop',
  'Salon & Spa',
  'Clinic & Doctor',
  'Small Hospital',
  'Restaurant / Cafe',
  'Repair Shop',
  'Automobile Service',
  'Other Local Business',
];

export const SettingsPage: React.FC = () => {
  const { updateShopInfo, logout } = useAuth();
  const { t } = useLanguage();
  const navigate = useNavigate();

  const [shopName, setShopName] = useState('');
  const [category, setCategory] = useState(CATEGORIES[0]);
  const [avgServiceDurationMinutes, setAvgServiceDurationMinutes] = useState(15);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Category Change Request state (₹101)
  const [catChangeStatus, setCatChangeStatus] = useState<CategoryChangeStatusData | null>(null);
  const [showCatModal, setShowCatModal] = useState(false);
  const [requestedCategory, setRequestedCategory] = useState('');
  const [utrNumber, setUtrNumber] = useState('');
  const [isSubmittingCatChange, setIsSubmittingCatChange] = useState(false);
  const [catModalError, setCatModalError] = useState<string | null>(null);
  const [copiedUpi, setCopiedUpi] = useState(false);

  useEffect(() => {
    fetchSettings();
  }, []);

  const fetchSettings = async () => {
    try {
      const [shopData, catData] = await Promise.all([
        api.getShopSettings(),
        api.getCategoryChangeStatus().catch(() => null),
      ]);
      setShopName(shopData.name);
      setCategory(shopData.category);
      setAvgServiceDurationMinutes(shopData.avgServiceDurationMinutes);
      if (catData) {
        setCatChangeStatus(catData);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load shop settings');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!shopName.trim()) {
      setError('Shop name cannot be blank');
      return;
    }

    setIsSaving(true);
    setError(null);
    setMessage(null);
    try {
      // Category is permanent and locked; ordinary updates only update name and service duration
      const updated = await api.updateShopSettings({
        name: shopName.trim(),
        avgServiceDurationMinutes,
      });
      updateShopInfo(updated.name, updated.category);
      setMessage(t('settingsUpdated'));
    } catch (err: any) {
      setError(err.message || 'Failed to save settings');
    } finally {
      setIsSaving(false);
    }
  };

  const handleCopyUpi = () => {
    const upi = catChangeStatus?.upiId || 'queueless@upi';
    navigator.clipboard.writeText(upi);
    setCopiedUpi(true);
    setTimeout(() => setCopiedUpi(false), 2000);
  };

  const handleSubmitCategoryChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setCatModalError(null);

    const targetCat = requestedCategory || CATEGORIES.find((c) => c !== category) || CATEGORIES[1];
    if (!utrNumber.trim()) {
      setCatModalError('Please enter a valid 12-digit UPI Reference (UTR) number');
      return;
    }

    setIsSubmittingCatChange(true);
    try {
      // 1. Initiate or check active request
      let reqId = catChangeStatus?.requestId;
      if (!reqId || catChangeStatus?.status === 'APPROVED' || catChangeStatus?.status === 'REJECTED') {
        const initRes = await api.initiateCategoryChange(targetCat);
        reqId = initRes.requestId;
      }

      if (!reqId) {
        throw new Error('Could not initiate category change request.');
      }

      // 2. Submit payment reference
      const updatedStatus = await api.submitCategoryChangePayment({
        requestId: reqId,
        paymentReference: utrNumber.trim(),
      });

      setCatChangeStatus(updatedStatus);
      setShowCatModal(false);
      setUtrNumber('');
      setMessage(t('categoryPendingNotice'));
    } catch (err: any) {
      setCatModalError(err.message || 'Failed to submit category change request');
    } finally {
      setIsSubmittingCatChange(false);
    }
  };

  const handleDeleteAccount = async () => {
    setIsDeleting(true);
    setError(null);
    try {
      await api.deleteAccount();
      logout();
      navigate('/login');
    } catch (err: any) {
      setError(err.message || 'Failed to delete account');
      setShowDeleteConfirm(false);
    } finally {
      setIsDeleting(false);
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center">
        <p className="text-slate-500 font-semibold">Loading settings...</p>
      </div>
    );
  }

  const availableCategories = CATEGORIES.filter((c) => c.toLowerCase() !== category.toLowerCase());

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Navbar />

      <main className="flex-1 max-w-2xl w-full mx-auto px-4 sm:px-6 py-6 md:py-8 space-y-6">
        <div>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-slate-900">
            {t('settings')}
          </h1>
          <p className="text-sm text-slate-500 font-medium mt-1">
            Configure your shop profile and default service duration.
          </p>
        </div>

        <div className="bg-white rounded-3xl border border-slate-200/80 shadow-sm p-6 sm:p-8">
          {error && (
            <div className="mb-4 p-3.5 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-medium">
              {error}
            </div>
          )}

          {message && (
            <div className="mb-4 p-3.5 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm font-semibold flex items-center gap-2">
              <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0" />
              <span>{message}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                {t('shopName')} <span className="text-rose-500">*</span>
              </label>
              <div className="relative">
                <Store className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  required
                  value={shopName}
                  onChange={(e) => setShopName(e.target.value)}
                  className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold transition-all"
                />
              </div>
            </div>

            {/* Permanent & Locked Business Category */}
            <div className="p-4 rounded-2xl bg-slate-50 border border-slate-200/80 space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <span className="block text-xs font-bold uppercase tracking-wider text-slate-500 mb-0.5">
                    {t('category')}
                  </span>
                  <p className="text-base font-extrabold text-slate-900">{category}</p>
                </div>
                <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-slate-200/80 text-slate-700 text-xs font-bold">
                  <Lock className="w-3.5 h-3.5 text-slate-500" />
                  <span>{t('categoryLocked')}</span>
                </div>
              </div>

              <p className="text-xs text-slate-500 leading-relaxed font-medium">
                {t('categoryLockNotice')}
              </p>

              {/* Status banner if request exists */}
              {catChangeStatus && catChangeStatus.status && (
                <div
                  className={`p-3 rounded-xl border text-xs font-semibold flex items-center justify-between ${
                    catChangeStatus.status === 'APPROVED'
                      ? 'bg-emerald-50 border-emerald-200 text-emerald-800'
                      : catChangeStatus.status === 'REJECTED'
                      ? 'bg-rose-50 border-rose-200 text-rose-800'
                      : 'bg-amber-50 border-amber-200 text-amber-800'
                  }`}
                >
                  <div>
                    <span className="font-bold">
                      {catChangeStatus.status === 'APPROVED'
                        ? t('statusApproved')
                        : catChangeStatus.status === 'REJECTED'
                        ? t('statusRejected')
                        : catChangeStatus.status === 'PENDING_VERIFICATION'
                        ? t('statusPendingVerification')
                        : 'Payment Pending'}
                      :
                    </span>{' '}
                    <span>
                      {catChangeStatus.requestedCategory}
                      {catChangeStatus.rejectionReason ? ` — ${catChangeStatus.rejectionReason}` : ''}
                      {catChangeStatus.paymentReference ? ` (UTR: ${catChangeStatus.paymentReference})` : ''}
                    </span>
                  </div>
                </div>
              )}

              {/* Small Action trigger */}
              <div className="pt-1">
                <button
                  type="button"
                  onClick={() => {
                    setRequestedCategory(availableCategories[0] || '');
                    setShowCatModal(true);
                  }}
                  className="inline-flex items-center gap-1.5 text-xs font-bold text-brand-600 hover:text-brand-700 hover:underline"
                >
                  <Sparkles className="w-3.5 h-3.5 text-brand-500" />
                  <span>{t('requestCategoryChange')}</span>
                </button>
              </div>
            </div>

            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                {t('avgServiceTime')} ({t('minutes')})
              </label>
              <div className="relative">
                <Clock className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type="number"
                  min="1"
                  max="180"
                  required
                  value={avgServiceDurationMinutes}
                  onChange={(e) => setAvgServiceDurationMinutes(Number(e.target.value))}
                  className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold transition-all"
                />
              </div>
              <p className="text-xs text-slate-400 mt-1 pl-1">
                Used to estimate customer waiting times: (Customers Ahead × Avg. Service Time).
              </p>
            </div>

            <div className="pt-2">
              <button
                type="submit"
                disabled={isSaving}
                className="w-full py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md shadow-brand-500/25 flex items-center justify-center gap-2 transition-all disabled:opacity-50"
              >
                <Save className="w-4 h-4" />
                <span>{isSaving ? 'Saving...' : t('saveSettings')}</span>
              </button>
            </div>
          </form>
        </div>

        {/* Modal: Request Business Category Change — ₹101 */}
        {showCatModal && (
          <div className="fixed inset-0 z-50 bg-slate-900/50 backdrop-blur-sm flex items-center justify-center p-4">
            <div className="bg-white rounded-3xl max-w-lg w-full p-6 sm:p-8 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto space-y-5 animate-in fade-in zoom-in-95 duration-200">
              <div className="flex items-center justify-between pb-3 border-b border-slate-100">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
                    <Sparkles className="w-4 h-4" />
                  </div>
                  <h2 className="text-lg font-black text-slate-900">
                    {t('requestCategoryChangeTitle')}
                  </h2>
                </div>
                <button
                  type="button"
                  onClick={() => setShowCatModal(false)}
                  className="p-1 rounded-xl text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {catModalError && (
                <div className="p-3.5 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-xs font-semibold">
                  {catModalError}
                </div>
              )}

              <form onSubmit={handleSubmitCategoryChange} className="space-y-4">
                {/* 1. Current category */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-500 mb-1">
                    {t('currentRegisteredCategory')}
                  </label>
                  <div className="px-4 py-2.5 rounded-xl bg-slate-100 text-slate-700 text-sm font-bold flex items-center justify-between">
                    <span>{category}</span>
                    <span className="text-xs font-semibold text-slate-500">{t('categoryLocked')}</span>
                  </div>
                </div>

                {/* 2. Requested new category */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    {t('newCategory')} <span className="text-rose-500">*</span>
                  </label>
                  <select
                    required
                    value={requestedCategory || availableCategories[0]}
                    onChange={(e) => setRequestedCategory(e.target.value)}
                    className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold bg-white transition-all text-sm"
                  >
                    {availableCategories.map((cat) => (
                      <option key={cat} value={cat}>
                        {cat}
                      </option>
                    ))}
                  </select>
                </div>

                {/* 3. Modification Fee ₹101 */}
                <div className="p-4 rounded-2xl bg-brand-50/70 border border-brand-100 flex items-center justify-between">
                  <div>
                    <span className="block text-xs font-bold uppercase tracking-wider text-brand-800">
                      {t('changeFee')}
                    </span>
                    <span className="text-xs text-brand-600 font-medium">Non-refundable review fee</span>
                  </div>
                  <span className="text-2xl font-black text-brand-700">₹101</span>
                </div>

                {/* 4. Platform UPI ID & Dynamic QR Code */}
                <div className="p-4 rounded-2xl bg-slate-50 border border-slate-200/80 space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <span className="block text-[11px] font-bold uppercase tracking-wider text-slate-500">
                        Platform UPI ID
                      </span>
                      <span className="text-sm font-mono font-bold text-slate-800">
                        {catChangeStatus?.upiId || 'queueless@upi'}
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={handleCopyUpi}
                      className="px-3 py-1.5 rounded-xl bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 text-xs font-bold flex items-center gap-1.5 transition-colors shadow-sm"
                    >
                      {copiedUpi ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                      <span>{copiedUpi ? t('copied') : t('copyUpiId')}</span>
                    </button>
                  </div>

                  <div className="flex justify-center py-2">
                    <div className="p-3 bg-white rounded-2xl border border-slate-200 shadow-sm flex flex-col items-center">
                      <QRCodeSVG
                        value={
                          catChangeStatus?.qrPayload ||
                          `upi://pay?pa=${catChangeStatus?.upiId || 'queueless@upi'}&pn=QueueLess&am=101.00&cu=INR&tn=CategoryChange`
                        }
                        size={130}
                        level="M"
                      />
                      <span className="text-[10px] font-bold text-slate-400 mt-1.5 flex items-center gap-1">
                        <QrCode className="w-3 h-3" /> Scan to Pay ₹101
                      </span>
                    </div>
                  </div>
                </div>

                {/* 5. UTR / Payment Reference Input */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    {t('paymentReferenceUtr')} <span className="text-rose-500">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    value={utrNumber}
                    onChange={(e) => setUtrNumber(e.target.value)}
                    placeholder="e.g. 432198765432"
                    className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-mono font-semibold transition-all text-sm"
                  />
                  <p className="text-[11px] text-slate-400 mt-1 pl-1">
                    Enter the 12-digit UPI reference ID from your payment receipt.
                  </p>
                </div>

                {/* Submit action */}
                <div className="pt-2 flex gap-3">
                  <button
                    type="submit"
                    disabled={isSubmittingCatChange || !utrNumber.trim()}
                    className="flex-1 py-3 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md shadow-brand-500/25 flex items-center justify-center gap-2 transition-all disabled:opacity-50"
                  >
                    <span>{isSubmittingCatChange ? 'Submitting...' : t('submitPaymentRef')}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setShowCatModal(false)}
                    className="px-4 py-3 rounded-2xl bg-slate-100 hover:bg-slate-200 text-slate-700 font-bold text-sm transition-colors"
                  >
                    Cancel
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* Danger Zone: Account Deletion */}
        <div className="bg-white p-6 sm:p-8 rounded-3xl border border-rose-200/80 shadow-sm space-y-4">
          <div className="flex items-center gap-2 text-rose-700">
            <AlertTriangle className="w-5 h-5 shrink-0" />
            <h2 className="text-base font-bold uppercase tracking-wider">{t('deleteAccount')}</h2>
          </div>
          <p className="text-xs text-slate-600 leading-relaxed font-medium">
            {t('deleteAccountWarning')}
          </p>

          {!showDeleteConfirm ? (
            <button
              type="button"
              onClick={() => setShowDeleteConfirm(true)}
              className="px-4 py-2.5 rounded-2xl bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 font-bold text-xs flex items-center gap-2 transition-colors"
            >
              <Trash2 className="w-4 h-4" />
              <span>{t('deleteAccount')}</span>
            </button>
          ) : (
            <div className="p-4 rounded-2xl bg-rose-50 border border-rose-300 space-y-3">
              <p className="text-xs text-rose-800 font-bold">
                {t('confirmDeleteAccount')}
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={isDeleting}
                  onClick={handleDeleteAccount}
                  className="px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-700 text-white font-bold text-xs transition-colors disabled:opacity-50"
                >
                  {isDeleting ? 'Deleting...' : 'Yes, Delete Permanently'}
                </button>
                <button
                  type="button"
                  disabled={isDeleting}
                  onClick={() => setShowDeleteConfirm(false)}
                  className="px-4 py-2 rounded-xl bg-white border border-slate-300 hover:bg-slate-50 text-slate-700 font-bold text-xs transition-colors"
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
};
