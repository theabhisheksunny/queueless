import React, { useState } from 'react';
import { api, type AdminShopItemData, type AdminCategoryChangeItemData } from '../api/client';
import { useLanguage } from '../context/LanguageContext';
import { ShieldCheck, Key, CheckCircle2, Check, X, Tag } from 'lucide-react';

export const AdminPage: React.FC = () => {
  const { t } = useLanguage();

  const [adminKey, setAdminKey] = useState(
    () => localStorage.getItem('queueless_admin_key') || 'admin-secret-queueless-2026'
  );
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [upiId, setUpiId] = useState('');
  const [upiName, setUpiName] = useState('');
  const [monthlyPrice, setMonthlyPrice] = useState(51);
  const [yearlyPrice, setYearlyPrice] = useState(501);
  const [shops, setShops] = useState<AdminShopItemData[]>([]);
  const [catRequests, setCatRequests] = useState<AdminCategoryChangeItemData[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleVerifyKey = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!adminKey.trim()) return;

    setIsLoading(true);
    setError(null);
    try {
      const config = await api.getAdminConfig(adminKey.trim());
      setUpiId(config.upiId || '');
      setUpiName(config.upiName || '');
      setMonthlyPrice(config.monthlyPrice || 51);
      setYearlyPrice(config.yearlyPrice || 501);

      const [shopsList, catReqs] = await Promise.all([
        api.getAdminShops(adminKey.trim()),
        api.getAdminCategoryChangeRequests(adminKey.trim()).catch(() => []),
      ]);
      setShops(shopsList);
      setCatRequests(catReqs);

      setIsAuthenticated(true);
      localStorage.setItem('queueless_admin_key', adminKey.trim());
    } catch (err: any) {
      setError(err.message || 'Invalid Admin Key');
      setIsAuthenticated(false);
    } finally {
      setIsLoading(false);
    }
  };

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setMessage(null);
    try {
      await api.updateAdminSettings(adminKey, {
        upiId: upiId.trim(),
        upiName: upiName.trim(),
        monthlyPrice,
        yearlyPrice,
      });
      setMessage('Subscription UPI configuration updated successfully.');
    } catch (err: any) {
      setError(err.message || 'Failed to update configuration');
    }
  };

  const handleActivateShop = async (shopId: number, planType: 'MONTHLY' | 'YEARLY', customDays: number) => {
    try {
      await api.activateAdminShop(adminKey, {
        shopId,
        planType,
        customDays,
      });
      const updated = await api.getAdminShops(adminKey);
      setShops(updated);
      setMessage(`Shop subscription extended by ${customDays} days!`);
    } catch (err: any) {
      alert(err.message || 'Failed to activate shop');
    }
  };

  const handleApproveCatRequest = async (id: number) => {
    try {
      await api.approveAdminCategoryChange(adminKey, id);
      const [updatedReqs, updatedShops] = await Promise.all([
        api.getAdminCategoryChangeRequests(adminKey),
        api.getAdminShops(adminKey),
      ]);
      setCatRequests(updatedReqs);
      setShops(updatedShops);
      setMessage('Category change request approved successfully! Shop category updated.');
    } catch (err: any) {
      alert(err.message || 'Failed to approve category change request');
    }
  };

  const handleRejectCatRequest = async (id: number) => {
    const reason = window.prompt(
      t('rejectionReasonPrompt'),
      'Payment verification failed or invalid UTR reference.'
    );
    if (reason === null) return;

    try {
      await api.rejectAdminCategoryChange(adminKey, id, reason.trim() || 'Payment verification failed');
      const updatedReqs = await api.getAdminCategoryChangeRequests(adminKey);
      setCatRequests(updatedReqs);
      setMessage('Category change request rejected.');
    } catch (err: any) {
      alert(err.message || 'Failed to reject category change request');
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col p-4 sm:p-6 md:p-8">
      <div className="max-w-4xl w-full mx-auto space-y-6">
        {/* Header */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-2xl bg-slate-900 text-white flex items-center justify-center shadow-md">
            <ShieldCheck className="w-5 h-5 text-brand-400" />
          </div>
          <div>
            <h1 className="text-2xl font-black text-slate-900">{t('adminTitle')}</h1>
            <p className="text-xs text-slate-500 font-medium">
              Minimal facility to configure subscription UPI ID, manage shop access & verify category changes
            </p>
          </div>
        </div>

        {/* Step 1: Admin Key Entry if not verified */}
        {!isAuthenticated ? (
          <div className="bg-white p-6 sm:p-8 rounded-3xl border border-slate-200/80 shadow-sm max-w-md mx-auto">
            <h2 className="text-lg font-bold text-slate-900 mb-2">Admin Authentication</h2>
            <p className="text-xs text-slate-500 mb-6">
              Enter your backend administration key to access controls.
            </p>

            {error && (
              <div className="mb-4 p-3 rounded-2xl bg-rose-50 text-rose-700 text-xs font-semibold border border-rose-200">
                {error}
              </div>
            )}

            <form onSubmit={handleVerifyKey} className="space-y-4">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                  {t('adminKeyLabel')}
                </label>
                <div className="relative">
                  <Key className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="password"
                    required
                    value={adminKey}
                    onChange={(e) => setAdminKey(e.target.value)}
                    placeholder={t('adminKeyPlaceholder')}
                    className="w-full pl-10 pr-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-slate-900 font-mono text-sm"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={isLoading}
                className="w-full py-3 rounded-2xl bg-slate-900 hover:bg-slate-800 text-white font-bold text-sm transition-colors shadow-md disabled:opacity-50"
              >
                {isLoading ? 'Verifying...' : t('adminVerifyBtn')}
              </button>
            </form>
          </div>
        ) : (
          <div className="space-y-6">
            {message && (
              <div className="p-4 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm font-semibold flex items-center gap-2">
                <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0" />
                <span>{message}</span>
              </div>
            )}

            {/* UPI Settings Configuration */}
            <div className="bg-white p-6 sm:p-8 rounded-3xl border border-slate-200/80 shadow-sm">
              <h2 className="text-lg font-extrabold text-slate-900 mb-1">
                {t('configUpiHeading')}
              </h2>
              <p className="text-xs text-slate-500 mb-6">
                Owners will see this UPI ID and merchant name for direct payments and QR codes.
              </p>

              <form onSubmit={handleSaveConfig} className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    Subscription UPI ID
                  </label>
                  <input
                    type="text"
                    required
                    value={upiId}
                    onChange={(e) => setUpiId(e.target.value)}
                    placeholder="e.g. queueless@upi"
                    className="w-full px-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-slate-900 text-sm font-semibold font-mono"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    {t('merchantName')}
                  </label>
                  <input
                    type="text"
                    required
                    value={upiName}
                    onChange={(e) => setUpiName(e.target.value)}
                    placeholder="e.g. QueueLess Platform"
                    className="w-full px-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-slate-900 text-sm font-semibold"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    Monthly Plan Fee (₹)
                  </label>
                  <input
                    type="number"
                    value={monthlyPrice}
                    onChange={(e) => setMonthlyPrice(Number(e.target.value))}
                    className="w-full px-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-slate-900 text-sm font-semibold"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1">
                    Yearly Plan Fee (₹)
                  </label>
                  <input
                    type="number"
                    value={yearlyPrice}
                    onChange={(e) => setYearlyPrice(Number(e.target.value))}
                    className="w-full px-4 py-2.5 rounded-2xl border border-slate-200 focus:border-brand-500 outline-none text-slate-900 text-sm font-semibold"
                  />
                </div>

                <div className="sm:col-span-2 pt-2">
                  <button
                    type="submit"
                    className="py-3 px-6 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md transition-colors"
                  >
                    {t('updateConfigBtn')}
                  </button>
                </div>
              </form>
            </div>

            {/* Category Change Requests Section (₹101) */}
            <div className="bg-white rounded-3xl border border-slate-200/80 shadow-sm overflow-hidden">
              <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-extrabold text-slate-900 flex items-center gap-2">
                    <Tag className="w-5 h-5 text-brand-600" />
                    <span>{t('categoryRequestsAdminHeading')}</span>
                  </h2>
                  <p className="text-xs text-slate-500 font-medium">
                    Verify ₹101 UPI payment references and approve permanent category updates
                  </p>
                </div>
                <span className="text-xs font-bold px-3 py-1 rounded-full bg-slate-100 text-slate-700">
                  {catRequests.length} Requests
                </span>
              </div>

              {catRequests.length === 0 ? (
                <div className="p-8 text-center text-slate-400 text-xs font-medium">
                  No category change requests submitted yet.
                </div>
              ) : (
                <div className="divide-y divide-slate-100">
                  {catRequests.map((req) => (
                    <div
                      key={req.id}
                      className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/60 transition-colors"
                    >
                      <div className="space-y-1">
                        <div className="flex items-center gap-2">
                          <h3 className="text-base font-bold text-slate-900">
                            {req.shopName} <span className="text-slate-400 font-mono text-xs">(#{req.shopId})</span>
                          </h3>
                          <span
                            className={`text-xs px-2.5 py-0.5 rounded-full font-bold ${
                              req.status === 'APPROVED'
                                ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                                : req.status === 'REJECTED'
                                ? 'bg-rose-50 text-rose-700 border border-rose-200'
                                : 'bg-amber-50 text-amber-800 border border-amber-200'
                            }`}
                          >
                            {req.status}
                          </span>
                        </div>

                        <div className="text-xs text-slate-600 font-medium flex items-center gap-2">
                          <span className="line-through text-slate-400">{req.oldCategory}</span>
                          <span>➔</span>
                          <span className="font-bold text-brand-700">{req.requestedCategory}</span>
                          <span className="text-slate-400">• Fee: ₹{req.amount}</span>
                        </div>

                        <div className="text-xs text-slate-500 font-medium space-x-3">
                          <span>Owner: {req.ownerName} ({req.ownerEmail})</span>
                          {req.paymentReference && (
                            <span className="font-mono text-slate-700 font-bold">
                              • UTR: {req.paymentReference}
                            </span>
                          )}
                        </div>

                        {req.rejectionReason && (
                          <div className="text-xs text-rose-600 font-medium">
                            Reason: {req.rejectionReason}
                          </div>
                        )}
                      </div>

                      {/* Action buttons if not already approved or rejected */}
                      {req.status !== 'APPROVED' && req.status !== 'REJECTED' && (
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => handleApproveCatRequest(req.id)}
                            className="py-2 px-3 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-xs shadow-sm flex items-center gap-1 transition-colors"
                          >
                            <Check className="w-3.5 h-3.5" />
                            <span>{t('approveBtn')}</span>
                          </button>
                          <button
                            onClick={() => handleRejectCatRequest(req.id)}
                            className="py-2 px-3 rounded-xl bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 font-bold text-xs flex items-center gap-1 transition-colors"
                          >
                            <X className="w-3.5 h-3.5" />
                            <span>{t('rejectBtn')}</span>
                          </button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Shop Subscriptions Table */}
            <div className="bg-white rounded-3xl border border-slate-200/80 shadow-sm overflow-hidden">
              <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-extrabold text-slate-900">
                    {t('manageShopsHeading')}
                  </h2>
                  <p className="text-xs text-slate-500 font-medium">
                    View submitted UPI UTRs and activate subscriptions manually
                  </p>
                </div>
                <span className="text-xs font-bold px-3 py-1 rounded-full bg-slate-100 text-slate-700">
                  {shops.length} Shops Registered
                </span>
              </div>

              <div className="divide-y divide-slate-100">
                {shops.map((shop) => (
                  <div
                    key={shop.shopId}
                    className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/60 transition-colors"
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="text-base font-bold text-slate-900">{shop.shopName}</h3>
                        <span className="text-xs px-2 py-0.5 rounded-md bg-slate-100 text-slate-600 font-medium">
                          {shop.category}
                        </span>
                        <span
                          className={`text-xs px-2 py-0.5 rounded-full font-bold ${
                            shop.active
                              ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                              : 'bg-rose-50 text-rose-700 border border-rose-200'
                          }`}
                        >
                          {shop.active ? 'ACTIVE' : 'EXPIRED'}
                        </span>
                      </div>

                      <div className="text-xs text-slate-500 font-medium mt-1 space-x-3">
                        <span>Owner: {shop.ownerName} ({shop.ownerEmail})</span>
                        {shop.subscriptionEndDate && (
                          <span>• Expiry: {shop.subscriptionEndDate}</span>
                        )}
                      </div>

                      {shop.pendingPaymentReference && (
                        <div className="mt-2 inline-flex items-center gap-1.5 px-3 py-1 rounded-xl bg-amber-50 border border-amber-200 text-amber-900 text-xs font-mono font-bold">
                          <span>Submitted UTR:</span>
                          <span className="text-brand-700 font-black">
                            {shop.pendingPaymentReference}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Activation Buttons */}
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handleActivateShop(shop.shopId, 'MONTHLY', 30)}
                        className="py-2 px-3 rounded-xl bg-slate-100 hover:bg-emerald-50 text-slate-700 hover:text-emerald-700 border border-slate-200 hover:border-emerald-300 font-bold text-xs transition-colors"
                      >
                        {t('activateMonthBtn')}
                      </button>
                      <button
                        onClick={() => handleActivateShop(shop.shopId, 'YEARLY', 365)}
                        className="py-2 px-3 rounded-xl bg-brand-50 hover:bg-brand-100 text-brand-700 border border-brand-200 font-bold text-xs transition-colors"
                      >
                        {t('activateYearBtn')}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
