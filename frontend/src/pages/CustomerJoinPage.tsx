import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useLanguage } from '../context/LanguageContext';
import { QrCode, User, Phone, ArrowRight, AlertCircle, Globe } from 'lucide-react';

export const CustomerJoinPage: React.FC = () => {
  const { publicQueueId } = useParams<{ publicQueueId: string }>();
  const navigate = useNavigate();
  const { language, toggleLanguage, t } = useLanguage();

  const [customerName, setCustomerName] = useState('');
  const [customerPhone, setCustomerPhone] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!publicQueueId) return;
    if (!customerName.trim()) {
      setError(t('customerNameLabel') + ' is required');
      return;
    }

    setIsSubmitting(true);
    setError(null);
    try {
      const res = await api.joinQueue(publicQueueId, {
        customerName: customerName.trim(),
        customerPhone: customerPhone.trim() || undefined,
      });

      // Save token info to local storage so user can recover if refreshed
      localStorage.setItem('queueless_customer_token', res.trackingToken);
      navigate(`/track/${res.trackingToken}`);
    } catch (err: any) {
      setError(err.message || 'Failed to join queue. The queue may be closed.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-50/50 via-slate-50 to-slate-100 flex flex-col justify-between p-4 sm:p-6 relative selection:bg-brand-500 selection:text-white">
      {/* Top Bar with Language Toggle */}
      <div className="max-w-md w-full mx-auto flex items-center justify-between py-2">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-brand-600 text-white flex items-center justify-center shadow-md shadow-brand-500/20">
            <QrCode className="w-4 h-4" />
          </div>
          <span className="font-extrabold text-slate-900 tracking-tight text-lg">QueueLess</span>
        </div>

        <button
          onClick={toggleLanguage}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-2xl bg-white border border-slate-200 text-slate-700 text-xs font-semibold shadow-sm hover:bg-slate-50 transition-colors"
        >
          <Globe className="w-3.5 h-3.5 text-brand-600" />
          <span>{language === 'en' ? 'हिंदी' : 'English'}</span>
        </button>
      </div>

      {/* Main Join Card */}
      <div className="max-w-md w-full mx-auto my-auto py-6">
        <div className="bg-white rounded-3xl shadow-xl shadow-brand-950/5 border border-slate-200/80 overflow-hidden">
          {/* Card Header Banner */}
          <div className="p-6 sm:p-8 bg-gradient-to-tr from-brand-700 via-brand-600 to-brand-500 text-white text-center">
            <span className="text-xs uppercase tracking-widest font-bold px-3 py-1 rounded-full bg-white/20 backdrop-blur-md inline-block mb-3">
              Digital Token Queue
            </span>
            <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight">
              {t('joinHeading')}
            </h1>
            <p className="text-sm font-medium text-brand-100 mt-1 max-w-xs mx-auto">
              {t('joinSubheading')}
            </p>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="p-6 sm:p-8 space-y-5">
            {error && (
              <div className="p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-medium flex items-start gap-2.5">
                <AlertCircle className="w-5 h-5 flex-shrink-0 text-rose-500 mt-0.5" />
                <span>{error}</span>
              </div>
            )}

            <div>
              <label className="block text-sm font-bold text-slate-700 mb-1.5">
                {t('customerNameLabel')} <span className="text-rose-500">*</span>
              </label>
              <div className="relative">
                <User className="w-5 h-5 text-slate-400 absolute left-4 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  autoFocus
                  required
                  value={customerName}
                  onChange={(e) => setCustomerName(e.target.value)}
                  placeholder={t('enterNamePlaceholder')}
                  className="w-full pl-12 pr-4 py-3.5 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 text-base font-semibold placeholder:font-normal placeholder:text-slate-400 transition-all"
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-bold text-slate-700 mb-1.5">
                {t('customerPhoneLabel')}
              </label>
              <div className="relative">
                <Phone className="w-5 h-5 text-slate-400 absolute left-4 top-1/2 -translate-y-1/2" />
                <input
                  type="tel"
                  value={customerPhone}
                  onChange={(e) => setCustomerPhone(e.target.value)}
                  placeholder={t('enterPhonePlaceholder')}
                  className="w-full pl-12 pr-4 py-3.5 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 text-base font-semibold placeholder:font-normal placeholder:text-slate-400 transition-all"
                />
              </div>
              <p className="text-xs text-slate-400 mt-1 pl-1">
                Optional: Used only for token updates. No spam or account needed.
              </p>
            </div>

            <button
              type="submit"
              disabled={isSubmitting}
              className="w-full py-4 px-6 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-lg shadow-xl shadow-brand-500/30 flex items-center justify-center gap-2 transition-all active:scale-[0.99] disabled:opacity-50"
            >
              <span>{isSubmitting ? 'Issuing Token...' : t('getMyToken')}</span>
              <ArrowRight className="w-5 h-5" />
            </button>
          </form>

          {/* Privacy Note */}
          <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 text-center">
            <p className="text-xs text-slate-500 font-medium">
              No app download or account required • 100% Free digital token
            </p>
          </div>
        </div>
      </div>

      {/* Footer */}
      <footer className="text-center py-4 text-xs font-semibold text-slate-400">
        QueueLess — Simple QR-Based Digital Token Management
      </footer>
    </div>
  );
};
