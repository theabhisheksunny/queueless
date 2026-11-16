import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api, type PublicQueueInfoData } from '../api/client';
import { useLanguage } from '../context/LanguageContext';
import { QrCode, User, Phone, ArrowRight, AlertCircle, Globe, Clock, Users } from 'lucide-react';
import { getTheme } from '../theme/theme';

export const CustomerJoinPage: React.FC = () => {
  const { publicQueueId } = useParams<{ publicQueueId: string }>();
  const navigate = useNavigate();
  const { language, toggleLanguage, t } = useLanguage();

  const [queueInfo, setQueueInfo] = useState<PublicQueueInfoData | null>(null);
  const [customerName, setCustomerName] = useState('');
  const [customerPhone, setCustomerPhone] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!publicQueueId) return;
    api.getPublicQueueInfo(publicQueueId)
      .then((data) => setQueueInfo(data))
      .catch(() => {
        // Fallback gracefully without blocking join form
      });
  }, [publicQueueId]);

  const theme = getTheme(queueInfo?.category);
  const ThemeIcon = theme.icon;

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
    <div
      data-theme={theme.id}
      className={`min-h-screen ${theme.styles.pageBgGradient} flex flex-col justify-between p-4 sm:p-6 relative selection:bg-brand-500 selection:text-white transition-colors duration-300`}
    >
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
        <div className={`bg-white rounded-3xl shadow-xl shadow-brand-950/5 border ${theme.styles.cardBorder} overflow-hidden transition-all`}>
          {/* Card Header Banner */}
          <div className={`p-6 sm:p-8 ${theme.styles.bannerGradient} text-center relative overflow-hidden`}>
            <div className="flex items-center justify-center gap-1.5 mb-2">
              <span className={`text-[11px] uppercase tracking-wider font-extrabold px-3 py-1 rounded-full border inline-flex items-center gap-1.5 ${theme.styles.bannerTag}`}>
                <ThemeIcon className="w-3 h-3" />
                <span>{queueInfo?.category || theme.categoryLabel}</span>
              </span>
            </div>

            <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight">
              {queueInfo?.shopName || t('joinHeading')}
            </h1>
            <p className={`text-sm font-medium ${theme.styles.bannerSubtext} mt-1 max-w-xs mx-auto`}>
              {queueInfo?.shopName ? t('joinHeading') : t('joinSubheading')}
            </p>

            {/* Live Queue Overview Badge if available */}
            {queueInfo && (
              <div className="mt-4 pt-3 border-t border-white/15 flex items-center justify-around text-xs font-semibold">
                <div className="flex items-center gap-1.5">
                  <span className={`w-2 h-2 rounded-full ${queueInfo.queueOpen ? 'bg-emerald-400 animate-pulse' : 'bg-rose-400'}`} />
                  <span>{queueInfo.queueOpen ? 'Queue Open' : 'Queue Closed'}</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <Users className="w-3.5 h-3.5 opacity-80" />
                  <span>{queueInfo.totalWaiting} waiting</span>
                </div>
                {queueInfo.currentlyServingToken && queueInfo.currentlyServingToken !== '-' && (
                  <div className="flex items-center gap-1.5">
                    <Clock className="w-3.5 h-3.5 opacity-80" />
                    <span>Serving #{queueInfo.currentlyServingToken}</span>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="p-6 sm:p-8 space-y-5">
            {queueInfo && !queueInfo.queueOpen && (
              <div className="p-4 rounded-2xl bg-amber-50 border border-amber-200 text-amber-900 text-xs sm:text-sm font-semibold flex items-start gap-2.5">
                <AlertCircle className="w-5 h-5 flex-shrink-0 text-amber-600 mt-0.5" />
                <span>The shop owner has paused registrations. You can check back shortly or ask the staff.</span>
              </div>
            )}

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
                  className={`w-full pl-12 pr-4 py-3.5 rounded-2xl border border-slate-200 ${theme.styles.focusRing} outline-none text-slate-900 text-base font-semibold placeholder:font-normal placeholder:text-slate-400 transition-all`}
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
                  className={`w-full pl-12 pr-4 py-3.5 rounded-2xl border border-slate-200 ${theme.styles.focusRing} outline-none text-slate-900 text-base font-semibold placeholder:font-normal placeholder:text-slate-400 transition-all`}
                />
              </div>
              <p className="text-xs text-slate-400 mt-1 pl-1">
                Optional: Used only for token updates. No spam or account needed.
              </p>
            </div>

            <button
              type="submit"
              disabled={isSubmitting}
              className={`w-full py-4 px-6 rounded-2xl ${theme.styles.primaryButton} flex items-center justify-center gap-2 transition-all disabled:opacity-50 disabled:cursor-not-allowed`}
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
