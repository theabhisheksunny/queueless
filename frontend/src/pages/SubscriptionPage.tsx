import React, { useState, useEffect } from 'react';
import { api, type SubscriptionStatusData } from '../api/client';
import { useLanguage } from '../context/LanguageContext';
import { Navbar } from '../components/Navbar';
import { QRCodeSVG } from 'qrcode.react';
import {
  CheckCircle2,
  AlertTriangle,
  Copy,
  Check,
  QrCode,
  Send,
} from 'lucide-react';

export const SubscriptionPage: React.FC = () => {
  const { t } = useLanguage();

  const [subData, setSubData] = useState<SubscriptionStatusData | null>(null);
  const [selectedPlan, setSelectedPlan] = useState<'MONTHLY' | 'YEARLY'>('MONTHLY');
  const [utrNumber, setUtrNumber] = useState('');
  const [copied, setCopied] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSubscription();
  }, []);

  const fetchSubscription = async () => {
    try {
      const data = await api.getSubscriptionStatus();
      setSubData(data);
      if (data.pendingPaymentReference) {
        setUtrNumber(data.pendingPaymentReference);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch subscription info');
    } finally {
      setIsLoading(false);
    }
  };

  const handleCopyUpi = () => {
    if (subData?.configuredUpiId) {
      navigator.clipboard.writeText(subData.configuredUpiId);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleSubmitUtr = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!utrNumber.trim()) {
      setError('Please enter your 12-digit UPI Reference Number (UTR)');
      return;
    }

    setIsSubmitting(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await api.submitSubscriptionPayment({
        planType: selectedPlan,
        paymentReference: utrNumber.trim(),
      });
      setSubData(updated);
      setMessage(t('utrSubmittedNotice'));
    } catch (err: any) {
      setError(err.message || 'Failed to submit payment reference');
    } finally {
      setIsSubmitting(false);
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center">
        <p className="text-slate-500 font-semibold">Loading subscription info...</p>
      </div>
    );
  }

  const currentPrice =
    selectedPlan === 'YEARLY'
      ? subData?.yearlyPrice || 501
      : subData?.monthlyPrice || 51;

  const upiId = subData?.configuredUpiId || 'queueless@upi';
  const payeeName = subData?.configuredUpiName || 'QueueLess Token System';

  // Dynamic UPI Payment Deep-Link
  const upiDeepLink = `upi://pay?pa=${encodeURIComponent(upiId)}&pn=${encodeURIComponent(
    payeeName
  )}&am=${currentPrice}&cu=INR&tn=QueueLess+Subscription`;

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Navbar />

      <main className="flex-1 max-w-4xl w-full mx-auto px-4 sm:px-6 py-6 md:py-8 space-y-6">
        {/* Title */}
        <div>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-slate-900">
            {t('subscription')}
          </h1>
          <p className="text-sm text-slate-500 font-medium mt-1">
            Simple, honest pricing. Direct UPI payment with zero hidden charges.
          </p>
        </div>

        {/* Current Subscription Status Card */}
        <div
          className={`p-6 rounded-3xl border shadow-sm flex flex-col sm:flex-row sm:items-center justify-between gap-4 ${
            subData?.active
              ? 'bg-emerald-50/70 border-emerald-200'
              : 'bg-rose-50/70 border-rose-200'
          }`}
        >
          <div className="flex items-start gap-3.5">
            <div
              className={`w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0 ${
                subData?.active
                  ? 'bg-emerald-100 text-emerald-700'
                  : 'bg-rose-100 text-rose-700'
              }`}
            >
              {subData?.active ? (
                <CheckCircle2 className="w-6 h-6" />
              ) : (
                <AlertTriangle className="w-6 h-6" />
              )}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-lg font-bold text-slate-900">
                  {subData?.active ? t('subActive') : t('subExpired')}
                </h3>
                {subData?.planType && (
                  <span className="text-xs uppercase font-extrabold px-2.5 py-0.5 rounded-full bg-white border border-slate-200 text-slate-700">
                    {subData.planType} Plan
                  </span>
                )}
              </div>
              <p className="text-sm text-slate-600 font-medium mt-1">
                {subData?.active ? (
                  <>
                    Valid until <span className="font-bold">{subData.endDate}</span> (
                    {subData.daysRemaining} {t('days')} remaining)
                  </>
                ) : (
                  'Please renew your plan via UPI to continue uninterrupted queue operations.'
                )}
              </p>
            </div>
          </div>
        </div>

        {/* Plan Selection Tabs */}
        <div>
          <h2 className="text-lg font-extrabold text-slate-900 mb-3">
            Choose Your Subscription Plan
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {/* Monthly Plan */}
            <div
              onClick={() => setSelectedPlan('MONTHLY')}
              className={`p-6 rounded-3xl border-2 cursor-pointer transition-all ${
                selectedPlan === 'MONTHLY'
                  ? 'bg-white border-brand-600 shadow-md ring-4 ring-brand-500/10'
                  : 'bg-white border-slate-200 hover:border-slate-300'
              }`}
            >
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-bold uppercase tracking-wider text-slate-600">
                  {t('planMonthlyTitle')}
                </span>
                <span className="w-5 h-5 rounded-full border-2 flex items-center justify-center border-brand-600">
                  {selectedPlan === 'MONTHLY' && (
                    <span className="w-2.5 h-2.5 rounded-full bg-brand-600" />
                  )}
                </span>
              </div>
              <div className="flex items-baseline gap-1 mt-2">
                <span className="text-3xl sm:text-4xl font-black text-slate-900">
                  ₹{subData?.monthlyPrice || 51}
                </span>
                <span className="text-sm font-medium text-slate-500">
                  / {t('planMonthlyDuration')}
                </span>
              </div>
              <p className="text-xs text-slate-500 font-medium mt-3">
                Full unlimited tokens, real-time QR join, live dashboard & customer tracking.
              </p>
            </div>

            {/* Yearly Plan */}
            <div
              onClick={() => setSelectedPlan('YEARLY')}
              className={`p-6 rounded-3xl border-2 cursor-pointer transition-all relative overflow-hidden ${
                selectedPlan === 'YEARLY'
                  ? 'bg-white border-brand-600 shadow-md ring-4 ring-brand-500/10'
                  : 'bg-white border-slate-200 hover:border-slate-300'
              }`}
            >
              <div className="absolute top-0 right-0 bg-brand-600 text-white text-[10px] font-black uppercase px-3 py-1 rounded-bl-xl tracking-wider">
                Best Value
              </div>
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-bold uppercase tracking-wider text-slate-600">
                  {t('planYearlyTitle')}
                </span>
                <span className="w-5 h-5 rounded-full border-2 flex items-center justify-center border-brand-600">
                  {selectedPlan === 'YEARLY' && (
                    <span className="w-2.5 h-2.5 rounded-full bg-brand-600" />
                  )}
                </span>
              </div>
              <div className="flex items-baseline gap-1 mt-2">
                <span className="text-3xl sm:text-4xl font-black text-slate-900">
                  ₹{subData?.yearlyPrice || 501}
                </span>
                <span className="text-sm font-medium text-slate-500">
                  / {t('planYearlyDuration')}
                </span>
              </div>
              <p className="text-xs text-slate-500 font-medium mt-3">
                Full year access (saves ₹111 compared to 12 monthly renewals).
              </p>
            </div>
          </div>
        </div>

        {/* UPI Payment Instructions & QR Card */}
        <div className="bg-white rounded-3xl border border-slate-200/80 shadow-sm p-6 sm:p-8">
          <div className="flex items-center gap-2 mb-6">
            <div className="w-8 h-8 rounded-xl bg-brand-100 text-brand-700 flex items-center justify-center">
              <QrCode className="w-4 h-4" />
            </div>
            <h2 className="text-xl font-extrabold text-slate-900">
              {t('payViaUpi')} (₹{currentPrice})
            </h2>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8 items-center">
            {/* Left Column: QR Code & UPI Copy */}
            <div className="flex flex-col items-center p-6 rounded-3xl bg-slate-50 border border-slate-200/80 text-center">
              <div className="p-4 bg-white rounded-2xl shadow-sm border border-slate-200/80 mb-4 inline-block">
                <QRCodeSVG value={upiDeepLink} size={180} level="H" includeMargin />
              </div>

              <p className="text-xs font-semibold text-slate-500 mb-2">
                Scan with GPay, PhonePe, Paytm, or BHIM
              </p>

              {/* UPI ID Pill & Copy Button */}
              <div className="w-full max-w-xs flex items-center justify-between p-2.5 rounded-2xl bg-white border border-slate-200 shadow-sm">
                <span className="text-xs sm:text-sm font-mono font-bold text-slate-800 truncate px-1">
                  {upiId}
                </span>
                <button
                  type="button"
                  onClick={handleCopyUpi}
                  className="px-3 py-1.5 rounded-xl bg-brand-50 hover:bg-brand-100 text-brand-700 font-semibold text-xs flex items-center gap-1 transition-colors flex-shrink-0"
                >
                  {copied ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-600" />
                      <span>{t('copied')}</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>{t('copyUpiId')}</span>
                    </>
                  )}
                </button>
              </div>
            </div>

            {/* Right Column: Step-by-Step Instructions & UTR Form */}
            <div className="space-y-4">
              <h3 className="text-sm font-bold text-slate-800 uppercase tracking-wider">
                {t('upiInstructionsHeading')}
              </h3>

              <ol className="space-y-2.5 text-xs sm:text-sm text-slate-600 font-medium">
                <li className="flex items-start gap-2">
                  <span className="w-5 h-5 rounded-full bg-brand-100 text-brand-700 font-bold flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
                    1
                  </span>
                  <span>{t('step1')}</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="w-5 h-5 rounded-full bg-brand-100 text-brand-700 font-bold flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
                    2
                  </span>
                  <span>
                    Scan QR or pay exact amount <strong className="text-slate-900">₹{currentPrice}</strong>.
                  </span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="w-5 h-5 rounded-full bg-brand-100 text-brand-700 font-bold flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
                    3
                  </span>
                  <span>{t('step3')}</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="w-5 h-5 rounded-full bg-brand-100 text-brand-700 font-bold flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
                    4
                  </span>
                  <span>{t('step4')}</span>
                </li>
              </ol>

              {/* UTR Form */}
              <form onSubmit={handleSubmitUtr} className="pt-2 space-y-3">
                {error && (
                  <div className="p-3 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-xs font-semibold">
                    {error}
                  </div>
                )}
                {message && (
                  <div className="p-3 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs font-semibold">
                    {message}
                  </div>
                )}

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                    {t('enterUtrLabel')}
                  </label>
                  <input
                    type="text"
                    required
                    value={utrNumber}
                    onChange={(e) => setUtrNumber(e.target.value)}
                    placeholder="e.g. 431289012345"
                    className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-mono text-sm font-semibold transition-all"
                  />
                </div>

                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="w-full py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md shadow-brand-500/25 flex items-center justify-center gap-2 transition-all disabled:opacity-50"
                >
                  <Send className="w-4 h-4" />
                  <span>{isSubmitting ? 'Submitting...' : t('submitUtrBtn')}</span>
                </button>
              </form>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};
